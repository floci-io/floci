package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessingConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Processor;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessorParameter;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.BufferingHints;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The messages, the enum orders and the storage behavior asserted here were probed
 * against real AWS (us-west-2, 2026-09-10). Two of them are deliberate absences:
 * NumberOfRetries is not range-checked, and the function a LambdaArn names is not
 * required to exist.
 */
class FirehoseProcessingConfigurationTest {

    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:transform";
    private static final String DESTINATION_ID = "destinationId-000000000001";
    private static final String BUFFER_PAIR_MESSAGE = "Both BufferSizeInMBs and BufferIntervalInSeconds"
            + " are required to configure buffering for lambda processor.";

    private FirehoseService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        Map<String, AccountAwareStorageBackend<?>> backends = new HashMap<>();
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> backends.computeIfAbsent(
                        invocation.getArgument(0) + "/" + invocation.getArgument(1),
                        k -> AccountAwareStorageBackend.inMemory("000000000000")));
        EmulatorConfig.FirehoseServiceConfig firehoseCfg = mock(EmulatorConfig.FirehoseServiceConfig.class);
        when(firehoseCfg.enabled()).thenReturn(true);
        when(firehoseCfg.tickIntervalSeconds()).thenReturn(10L);
        when(firehoseCfg.flushRecordCount()).thenReturn(0);
        EmulatorConfig.ServicesConfig servicesCfg = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesCfg.firehose()).thenReturn(firehoseCfg);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(servicesCfg);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        service = new FirehoseService(storageFactory, mock(S3Service.class),
                Mockito.mock(KinesisService.class), regionResolver, new MutableClock(), config,
                Mockito.mock(FirehoseParquetConverter.class),
                Mockito.mock(FirehoseLambdaTransformer.class));
    }

    private static ProcessorParameter parameter(String name, String value) {
        ProcessorParameter parameter = new ProcessorParameter();
        parameter.setParameterName(name);
        parameter.setParameterValue(value);
        return parameter;
    }

    private static ProcessingConfiguration processing(String type, ProcessorParameter... parameters) {
        Processor processor = new Processor();
        processor.setType(type);
        processor.setParameters(new ArrayList<>(List.of(parameters)));
        ProcessingConfiguration processing = new ProcessingConfiguration();
        processing.setEnabled(true);
        processing.setProcessors(new ArrayList<>(List.of(processor)));
        return processing;
    }

    private static ProcessingConfiguration lambdaProcessing(ProcessorParameter... parameters) {
        return processing("Lambda", parameters);
    }

    private static Processor lambdaProcessor(ProcessorParameter... parameters) {
        Processor processor = new Processor();
        processor.setType("Lambda");
        processor.setParameters(new ArrayList<>(List.of(parameters)));
        return processor;
    }

    private static BufferingHints hints(int sizeInMBs, int intervalInSeconds) {
        BufferingHints hints = new BufferingHints();
        hints.setSizeInMBs(sizeInMBs);
        hints.setIntervalInSeconds(intervalInSeconds);
        return hints;
    }

    private static S3Destination destination(Consumer<S3Destination> customizer) {
        S3Destination s3 = new S3Destination();
        s3.setRoleArn(ROLE_ARN);
        s3.setBucketArn("arn:aws:s3:::results");
        customizer.accept(s3);
        return s3;
    }

    private String currentVersion(String name) {
        return service.describeDeliveryStream(name).getVersionId();
    }

    private List<String> storedParameterNames(String name) {
        List<ProcessorParameter> parameters = service.describeDeliveryStream(name).s3Destination()
                .getProcessingConfiguration().getProcessors().get(0).getParameters();
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.getParameterName()));
        return names;
    }

    @Test
    void aLambdaProcessorWithoutALambdaArnIsRejected() {
        AwsException error = assertThrows(AwsException.class, () -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(
                        lambdaProcessing(parameter("NumberOfRetries", "2"))))));

        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals("LambdaArn is required when Lambda processor is used.", error.getMessage());
    }

    // Probed: AWS rejects a function name that is not one Lambda would accept, so the
    // shape has to be Lambda's rather than merely arn-looking.
    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-arn",
            "arn:aws:lambda:us-east-1:000000000000:function:bad name",
            "arn:aws:lambda:us-east-1:000000000000:function:name/with/slashes",
            "arn:aws:lambda:us-east-1:000000000000:function:bad?name",
            "arn:aws:lambda:us-east-1:000000000000:function:bad@name",
            "arn:aws:lambda:us-east-1:000000000000:function:bad[name",
    })
    void aMalformedLambdaArnIsRejected(String lambdaArn) {
        AwsException error = assertThrows(AwsException.class, () -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(
                        lambdaProcessing(parameter("LambdaArn", lambdaArn))))));

        assertEquals("Invalid lambda ARN.", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:lambda:us-east-1:000000000000:function:transform",
            "arn:aws:lambda:us-east-1:000000000000:function:transform:$LATEST",
            "arn:aws:lambda:us-east-1:000000000000:function:transform:prod",
    })
    void aQualifiedOrUnqualifiedFunctionArnIsAccepted(String lambdaArn) {
        assertDoesNotThrow(() -> service.createDeliveryStream("stream-" + lambdaArn.hashCode(),
                destination(s3 -> s3.setProcessingConfiguration(
                        lambdaProcessing(parameter("LambdaArn", lambdaArn))))));
    }

    // A function that does not exist is still accepted: unlike a conversion schema's
    // Glue table, AWS resolves nothing at configuration time.
    @Test
    void aWellFormedArnToAnyFunctionIsAccepted() {
        assertDoesNotThrow(() -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(
                        lambdaProcessing(parameter("LambdaArn",
                                "arn:aws:lambda:us-east-1:000000000000:function:never-created"))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BufferSizeInMBs", "BufferIntervalInSeconds"})
    void oneBufferingParameterWithoutTheOtherIsRejected(String parameterName) {
        AwsException error = assertThrows(AwsException.class, () -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(lambdaProcessing(
                        parameter("LambdaArn", LAMBDA_ARN), parameter(parameterName, "2"))))));

        assertEquals(BUFFER_PAIR_MESSAGE, error.getMessage());
    }

    @Test
    void bothBufferingParametersTogetherAreAccepted() {
        assertDoesNotThrow(() -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(lambdaProcessing(
                        parameter("LambdaArn", LAMBDA_ARN),
                        parameter("BufferSizeInMBs", "2"),
                        parameter("BufferIntervalInSeconds", "70"))))));
    }

    // Deliberate: real AWS accepts both, despite the documented 1 to 8.
    @ParameterizedTest
    @ValueSource(strings = {"0", "9"})
    void numberOfRetriesIsNotRangeChecked(String retries) {
        assertDoesNotThrow(() -> service.createDeliveryStream("stream-" + retries,
                destination(s3 -> s3.setProcessingConfiguration(lambdaProcessing(
                        parameter("LambdaArn", LAMBDA_ARN), parameter("NumberOfRetries", retries))))));
    }

    // The LambdaArn rule belongs to the Lambda processor, not to every processor.
    @Test
    void anotherProcessorTypeIsNotSubjectToTheLambdaRules() {
        assertDoesNotThrow(() -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(
                        processing("AppendDelimiterToRecord", parameter("Delimiter", "\\n"))))));
    }

    @Test
    void storedParametersGainTheDefaultsAndAFixedOrder() {
        service.createDeliveryStream("stream", destination(s3 -> s3.setProcessingConfiguration(
                lambdaProcessing(parameter("BufferIntervalInSeconds", "70"),
                        parameter("BufferSizeInMBs", "2"),
                        parameter("LambdaArn", LAMBDA_ARN)))));

        assertEquals(List.of("LambdaArn", "NumberOfRetries", "RoleArn", "BufferSizeInMBs",
                "BufferIntervalInSeconds"), storedParameterNames("stream"));

        List<ProcessorParameter> stored = service.describeDeliveryStream("stream").s3Destination()
                .getProcessingConfiguration().getProcessors().get(0).getParameters();
        assertEquals("3", stored.get(1).getParameterValue());
        assertEquals(ROLE_ARN, stored.get(2).getParameterValue());
    }

    @Test
    void aRepeatedParameterNameIsRejected() {
        AwsException error = assertThrows(AwsException.class, () -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(lambdaProcessing(
                        parameter("LambdaArn", LAMBDA_ARN), parameter("LambdaArn", "not-an-arn"))))));

        assertEquals("Duplicate ProcessorParameter passed to ProcessingConfiguration.", error.getMessage());
    }

    // The duplicate check runs first: the processor is missing its LambdaArn as well,
    // and AWS still reports the duplicate.
    @Test
    void aRepeatedParameterIsReportedBeforeTheMissingLambdaArn() {
        AwsException error = assertThrows(AwsException.class, () -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(lambdaProcessing(
                        parameter("NumberOfRetries", "2"), parameter("NumberOfRetries", "3"))))));

        assertEquals("Duplicate ProcessorParameter passed to ProcessingConfiguration.", error.getMessage());
    }

    @Test
    void moreThanOneLambdaProcessorIsRejected() {
        Processor first = new Processor();
        first.setType("Lambda");
        first.setParameters(List.of(parameter("LambdaArn", LAMBDA_ARN)));
        Processor second = new Processor();
        second.setType("Lambda");
        second.setParameters(List.of(parameter("LambdaArn", LAMBDA_ARN)));
        ProcessingConfiguration processing = new ProcessingConfiguration();
        processing.setEnabled(true);
        processing.setProcessors(List.of(first, second));

        AwsException error = assertThrows(AwsException.class, () -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(processing))));

        assertEquals("Cannot have more than 1 Lambda processor.", error.getMessage());
    }

    // A Lambda processor alongside another type is fine.
    @Test
    void twoProcessorsOfDifferentTypesAreAccepted() {
        Processor lambda = new Processor();
        lambda.setType("Lambda");
        lambda.setParameters(List.of(parameter("LambdaArn", LAMBDA_ARN)));
        Processor delimiter = new Processor();
        delimiter.setType("AppendDelimiterToRecord");
        delimiter.setParameters(List.of(parameter("Delimiter", "\\n")));
        ProcessingConfiguration processing = new ProcessingConfiguration();
        processing.setEnabled(true);
        processing.setProcessors(List.of(lambda, delimiter));

        assertDoesNotThrow(() -> service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(processing))));
    }

    // Settled when written, so reading cannot change what a later update sees: the
    // injected RoleArn must be the same whether or not a Describe happened in between.
    @Test
    void describeDoesNotDecideWhatALaterUpdateSees() {
        service.createDeliveryStream("read-first", destination(s3 -> s3.setProcessingConfiguration(
                lambdaProcessing(parameter("LambdaArn", LAMBDA_ARN)))));
        service.createDeliveryStream("no-read", destination(s3 -> s3.setProcessingConfiguration(
                lambdaProcessing(parameter("LambdaArn", LAMBDA_ARN)))));

        service.describeDeliveryStream("read-first");

        String otherRole = "arn:aws:iam::000000000000:role/other";
        for (String name : List.of("read-first", "no-read")) {
            service.updateDestination(name, currentVersion(name), DESTINATION_ID,
                    destination(s3 -> s3.setRoleArn(otherRole)));
        }

        assertEquals(storedRoleParameter("read-first"), storedRoleParameter("no-read"));
        assertEquals(ROLE_ARN, storedRoleParameter("no-read"));
    }

    private String storedRoleParameter(String name) {
        for (ProcessorParameter parameter : service.describeDeliveryStream(name).s3Destination()
                .getProcessingConfiguration().getProcessors().get(0).getParameters()) {
            if ("RoleArn".equals(parameter.getParameterName())) {
                return parameter.getParameterValue();
            }
        }
        return null;
    }

    // The opposite of the conversion block, which merges member-wise.
    @Test
    void anUpdateReplacesTheWholeBlockRatherThanMergingIt() {
        service.createDeliveryStream("stream", destination(s3 -> s3.setProcessingConfiguration(
                lambdaProcessing(parameter("LambdaArn", LAMBDA_ARN)))));

        ProcessingConfiguration disabled = new ProcessingConfiguration();
        disabled.setEnabled(false);
        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID,
                destination(s3 -> s3.setProcessingConfiguration(disabled)));

        ProcessingConfiguration stored = service.describeDeliveryStream("stream").s3Destination()
                .getProcessingConfiguration();
        assertEquals(false, stored.getEnabled());
        assertTrue(stored.getProcessors() == null || stored.getProcessors().isEmpty(),
                "processors were " + stored.getProcessors());
    }

    @Test
    void anUpdateThatDoesNotMentionTheBlockPreservesIt() {
        service.createDeliveryStream("stream", destination(s3 -> s3.setProcessingConfiguration(
                lambdaProcessing(parameter("LambdaArn", LAMBDA_ARN)))));

        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID,
                destination(s3 -> s3.setPrefix("changed/")));

        assertEquals(List.of("LambdaArn", "NumberOfRetries", "RoleArn", "BufferSizeInMBs",
                "BufferIntervalInSeconds"), storedParameterNames("stream"));
    }

    /**
     * Probed 2026-09-11: DescribeDeliveryStream echoes an omitted Enabled back as false
     * here, where the conversion block echoes it as true, so the two cannot share a rule.
     */
    @Test
    void anOmittedEnabledIsStoredAsDisabled() {
        ProcessingConfiguration processing = new ProcessingConfiguration();
        processing.setProcessors(List.of(lambdaProcessor(parameter("LambdaArn", LAMBDA_ARN))));
        service.createDeliveryStream("stream",
                destination(s3 -> s3.setProcessingConfiguration(processing)));

        S3Destination stored = service.describeDeliveryStream("stream").s3Destination();
        assertEquals(false, stored.getProcessingConfiguration().getEnabled());
        assertFalse(stored.isProcessingEnabled());
    }

    /**
     * The two buffer parameters are the Lambda processor's own defaults, not the
     * destination's BufferingHints: probed, a destination buffering 5 MiB over 300s still
     * echoes 1 and 60 on the processor.
     */
    @Test
    void aProcessorWithoutBufferParametersGainsTheLambdaDefaults() {
        service.createDeliveryStream("stream", destination(s3 -> {
            s3.setBufferingHints(hints(5, 300));
            s3.setProcessingConfiguration(lambdaProcessing(parameter("LambdaArn", LAMBDA_ARN)));
        }));

        List<ProcessorParameter> stored = service.describeDeliveryStream("stream").s3Destination()
                .getProcessingConfiguration().getProcessors().get(0).getParameters();
        assertEquals("1", parameterValue(stored, "BufferSizeInMBs"));
        assertEquals("60", parameterValue(stored, "BufferIntervalInSeconds"));
    }

    private static String parameterValue(List<ProcessorParameter> parameters, String name) {
        for (ProcessorParameter parameter : parameters) {
            if (name.equals(parameter.getParameterName())) {
                return parameter.getParameterValue();
            }
        }
        return null;
    }

    // Validation runs against the update's own content, since the update replaces the
    // block: a stored LambdaArn does not satisfy an update that omits one.
    @Test
    void anUpdateIsValidatedAgainstItsOwnContent() {
        service.createDeliveryStream("stream", destination(s3 -> s3.setProcessingConfiguration(
                lambdaProcessing(parameter("LambdaArn", LAMBDA_ARN)))));

        AwsException error = assertThrows(AwsException.class, () -> service.updateDestination("stream",
                currentVersion("stream"), DESTINATION_ID, destination(s3 -> s3.setProcessingConfiguration(
                        lambdaProcessing(parameter("NumberOfRetries", "7"))))));

        assertEquals("LambdaArn is required when Lambda processor is used.", error.getMessage());
    }
}
