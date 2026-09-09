package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.BufferingHints;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.DataFormatConversionConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Deserializer;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.HiveJsonSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.InputFormatConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OpenXJsonSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OrcSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.OutputFormatConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ParquetSerDe;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.SchemaConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Serializer;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every error message and the precedence between them was captured from real
 * AWS (probe 2026-09-07, us-west-2), covering both CreateDeliveryStream and
 * UpdateDestination. See okinaka/floci discussion 118 for the probe record.
 */
class FirehoseDataFormatConversionTest {

    private static final String COMPRESSION_MESSAGE = "The S3 destination's compression format must be set"
            + " to UNCOMPRESSED when data format conversion is enabled. To enable compression within the"
            + " converted output format, set the appropriate serialization option in the output format configuration.";
    private static final String BUFFER_MESSAGE =
            "BufferingHints.SizeInMBs must be at least 64 when data format conversion is enabled.";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/firehose-role";
    private static final String DESTINATION_ID = "destinationId-000000000001";

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
        service = new FirehoseService(storageFactory, Mockito.mock(S3Service.class),
                Mockito.mock(KinesisService.class), regionResolver, new MutableClock(), config);
    }

    private static SchemaConfiguration schema() {
        SchemaConfiguration schema = new SchemaConfiguration();
        schema.setRoleArn(ROLE_ARN);
        schema.setDatabaseName("db");
        schema.setTableName("table");
        schema.setRegion("us-east-1");
        return schema;
    }

    private static InputFormatConfiguration openXInput() {
        Deserializer deserializer = new Deserializer();
        deserializer.setOpenXJsonSerDe(new OpenXJsonSerDe());
        InputFormatConfiguration input = new InputFormatConfiguration();
        input.setDeserializer(deserializer);
        return input;
    }

    private static OutputFormatConfiguration parquetOutput() {
        Serializer serializer = new Serializer();
        serializer.setParquetSerDe(new ParquetSerDe());
        OutputFormatConfiguration output = new OutputFormatConfiguration();
        output.setSerializer(serializer);
        return output;
    }

    private static DataFormatConversionConfiguration fullConversion() {
        DataFormatConversionConfiguration conversion = new DataFormatConversionConfiguration();
        conversion.setEnabled(true);
        conversion.setSchemaConfiguration(schema());
        conversion.setInputFormatConfiguration(openXInput());
        conversion.setOutputFormatConfiguration(parquetOutput());
        return conversion;
    }

    private static S3Destination destination(Consumer<S3Destination> customizer) {
        S3Destination s3 = new S3Destination();
        s3.setRoleArn(ROLE_ARN);
        s3.setBucketArn("arn:aws:s3:::results");
        BufferingHints hints = new BufferingHints();
        hints.setSizeInMBs(64);
        hints.setIntervalInSeconds(60);
        s3.setBufferingHints(hints);
        s3.setCompressionFormat("UNCOMPRESSED");
        s3.setDataFormatConversionConfiguration(fullConversion());
        customizer.accept(s3);
        return s3;
    }

    private AwsException assertCreateRejected(Consumer<S3Destination> customizer) {
        AwsException error = assertThrows(AwsException.class,
                () -> service.createDeliveryStream("stream", destination(customizer)));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        return error;
    }

    private static BufferingHints hints(int sizeInMBs) {
        BufferingHints hints = new BufferingHints();
        hints.setSizeInMBs(sizeInMBs);
        hints.setIntervalInSeconds(60);
        return hints;
    }

    // ── create: message and precedence matrix ────────────────────────────────

    @Test
    void createAcceptsAFullyValidConversionConfiguration() {
        assertDoesNotThrow(() -> service.createDeliveryStream("stream", destination(s3 -> {})));
    }

    @Test
    void compressionCheckComesBeforeEveryOtherConversionCheck() {
        AwsException error = assertCreateRejected(s3 -> {
            s3.setCompressionFormat("GZIP");
            s3.setBufferingHints(hints(5));
            s3.getDataFormatConversionConfiguration().setSchemaConfiguration(null);
        });
        assertEquals(COMPRESSION_MESSAGE, error.getMessage());
    }

    @Test
    void bufferSizeCheckComesBeforeTheMissingMemberChecks() {
        AwsException error = assertCreateRejected(s3 -> {
            s3.setBufferingHints(hints(5));
            s3.getDataFormatConversionConfiguration().setSchemaConfiguration(null);
        });
        assertEquals(BUFFER_MESSAGE, error.getMessage());
    }

    @Test
    void bufferSizeBelow64IsRejectedOnlyWhenExplicit() {
        assertEquals(BUFFER_MESSAGE,
                assertCreateRejected(s3 -> s3.setBufferingHints(hints(63))).getMessage());
        // Probe: a destination with no BufferingHints at all passes the floor check.
        assertDoesNotThrow(() -> service.createDeliveryStream("no-hints",
                destination(s3 -> s3.setBufferingHints(null))));
    }

    /** Probed: AWS stores 128 MiB, not the ordinary 5 MiB, when a converting stream omits hints. */
    @Test
    void omittedHintsGetTheConversionSizeDefault() {
        service.createDeliveryStream("converting", destination(s3 -> s3.setBufferingHints(null)));

        BufferingHints stored = service.describeDeliveryStream("converting")
                .s3Destination().getBufferingHints();
        assertEquals(128, stored.getSizeInMBs());
        assertEquals(300, stored.getIntervalInSeconds());
    }

    @Test
    void omittedHintsKeepTheOrdinaryDefaultWithoutConversion() {
        service.createDeliveryStream("plain", destination(s3 -> {
            s3.setBufferingHints(null);
            s3.setDataFormatConversionConfiguration(null);
        }));

        BufferingHints stored = service.describeDeliveryStream("plain")
                .s3Destination().getBufferingHints();
        assertEquals(5, stored.getSizeInMBs());
        assertEquals(300, stored.getIntervalInSeconds());
    }

    /**
     * Regression: storing 5 MiB for a converting stream made every later update fail
     * the floor check, since the merged state then carried an explicit 5.
     */
    @Test
    void anUnrelatedUpdateSucceedsOnAStreamCreatedWithoutHints() {
        service.createDeliveryStream("converting", destination(s3 -> s3.setBufferingHints(null)));

        assertDoesNotThrow(() -> service.updateDestination("converting", currentVersion("converting"),
                DESTINATION_ID, update(u -> u.setPrefix("changed/"))));

        S3Destination stored = service.describeDeliveryStream("converting").s3Destination();
        assertEquals("changed/", stored.getPrefix());
        assertEquals(128, stored.getBufferingHints().getSizeInMBs());
    }

    @Test
    void missingMembersAreReportedInInputOutputSchemaOrder() {
        DataFormatConversionConfiguration onlyEnabled = new DataFormatConversionConfiguration();
        onlyEnabled.setEnabled(true);
        assertEquals("InputFormatConfiguration must not be null",
                assertCreateRejected(s3 -> s3.setDataFormatConversionConfiguration(onlyEnabled)).getMessage());

        assertEquals("OutputFormatConfiguration must not be null",
                assertCreateRejected(s3 -> {
                    s3.getDataFormatConversionConfiguration().setOutputFormatConfiguration(null);
                    s3.getDataFormatConversionConfiguration().setSchemaConfiguration(null);
                }).getMessage());

        assertEquals("SchemaConfiguration must not be null",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration().setSchemaConfiguration(null))
                        .getMessage());
    }

    /** An empty Deserializer or Serializer object reports the same error as an absent one. */
    @Test
    void emptyDeserializerAndSerializerReportTheNullMessage() {
        assertEquals("Deserializer must not be null",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration()
                        .getInputFormatConfiguration().setDeserializer(new Deserializer())).getMessage());
        assertEquals("Serializer must not be null",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration()
                        .getOutputFormatConfiguration().setSerializer(new Serializer())).getMessage());
    }

    @Test
    void specifyingBothSerdesOfAUnionIsRejected() {
        assertEquals("More than one deserializer specified. Only one may be chosen.",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration()
                        .getInputFormatConfiguration().getDeserializer().setHiveJsonSerDe(new HiveJsonSerDe()))
                        .getMessage());
        assertEquals("More than one serializer specified. Only one may be chosen.",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration()
                        .getOutputFormatConfiguration().getSerializer().setOrcSerDe(new OrcSerDe()))
                        .getMessage());
    }

    /** Floci deviation: real AWS accepts OrcSerDe, but DuckDB cannot write ORC. */
    @Test
    void orcSerDeIsRejectedExplicitly() {
        AwsException error = assertCreateRejected(s3 -> {
            Serializer serializer = new Serializer();
            serializer.setOrcSerDe(new OrcSerDe());
            s3.getDataFormatConversionConfiguration().getOutputFormatConfiguration().setSerializer(serializer);
        });
        assertEquals("OrcSerDe is not supported. Floci supports only ParquetSerDe for data format conversion.",
                error.getMessage());
    }

    @Test
    void schemaMembersAreRequiredWithTheAwsCasedMessages() {
        assertEquals("SchemaConfiguration.RoleArn must not be null or empty",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration()
                        .getSchemaConfiguration().setRoleArn(null)).getMessage());
        assertEquals("SchemaConfiguration.DatabaseName must not be null or empty",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration()
                        .getSchemaConfiguration().setDatabaseName("")).getMessage());
        assertEquals("SchemaConfiguration.TableName must not be null or empty",
                assertCreateRejected(s3 -> s3.getDataFormatConversionConfiguration()
                        .getSchemaConfiguration().setTableName(null)).getMessage());
    }

    @Test
    void omittedEnabledIsTreatedAsEnabled() {
        AwsException error = assertCreateRejected(s3 -> {
            s3.getDataFormatConversionConfiguration().setEnabled(null);
            s3.getDataFormatConversionConfiguration().setSchemaConfiguration(null);
        });
        assertEquals("SchemaConfiguration must not be null", error.getMessage());
    }

    @Test
    void disabledConversionSkipsEveryCheckButIsStored() {
        service.createDeliveryStream("disabled", destination(s3 -> {
            s3.setCompressionFormat("GZIP");
            s3.setBufferingHints(hints(5));
            s3.getDataFormatConversionConfiguration().setEnabled(false);
        }));

        DataFormatConversionConfiguration stored = service.describeDeliveryStream("disabled")
                .s3Destination().getDataFormatConversionConfiguration();
        assertEquals(false, stored.getEnabled());
        assertNotNull(stored.getSchemaConfiguration());
    }

    // ── describe: null-honest echo ───────────────────────────────────────────

    @Test
    void describeEchoesTheConfigurationWithoutMaterializingDefaults() {
        service.createDeliveryStream("stream", destination(s3 -> {}));

        S3Destination described = service.describeDeliveryStream("stream").s3Destination();
        DataFormatConversionConfiguration conversion = described.getDataFormatConversionConfiguration();
        assertEquals(true, conversion.getEnabled());
        assertNull(conversion.getSchemaConfiguration().getCatalogId());
        assertNull(conversion.getSchemaConfiguration().getVersionId());
        ParquetSerDe parquet = conversion.getOutputFormatConfiguration().getSerializer().getParquetSerDe();
        assertNotNull(parquet);
        assertNull(parquet.getCompression());
        assertNull(parquet.getBlockSizeBytes());
    }

    /**
     * A disabled ORC configuration is stored rather than rejected, so Describe has to
     * echo its members: modeling OrcSerDe as a bare marker used to drop them silently.
     */
    @Test
    void disabledOrcConfigurationKeepsItsSerDeMembers() {
        OrcSerDe orc = new OrcSerDe();
        orc.setStripeSizeBytes(67108864L);
        orc.setCompression("SNAPPY");
        orc.setFormatVersion("V0_12");
        service.createDeliveryStream("orc", destination(s3 -> {
            Serializer serializer = new Serializer();
            serializer.setOrcSerDe(orc);
            s3.getDataFormatConversionConfiguration().getOutputFormatConfiguration()
                    .setSerializer(serializer);
            s3.getDataFormatConversionConfiguration().setEnabled(false);
        }));

        OrcSerDe stored = service.describeDeliveryStream("orc").s3Destination()
                .getDataFormatConversionConfiguration().getOutputFormatConfiguration()
                .getSerializer().getOrcSerDe();
        assertEquals(67108864L, stored.getStripeSizeBytes());
        assertEquals("SNAPPY", stored.getCompression());
        assertEquals("V0_12", stored.getFormatVersion());
        assertNull(stored.getRowIndexStride());
    }

    /**
     * Every member AWS defines on a destination is PascalCase, so a camelCase key is
     * proof that a helper leaked into the wire shape: an {@code is...} accessor with
     * no backing field of that name becomes a Jackson property unless it is ignored.
     */
    @Test
    void destinationRendersOnlyAwsDefinedMembers() throws Exception {
        service.createDeliveryStream("stream", destination(s3 -> {}));
        DeliveryStreamDescription.Destination destination =
                service.describeDeliveryStream("stream").getDestinations().get(0);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        for (Object rendered : List.of(destination.getExtendedS3DestinationDescription(),
                destination.getS3DestinationDescription())) {
            com.fasterxml.jackson.databind.JsonNode json = mapper.valueToTree(rendered);
            json.fieldNames().forEachRemaining(name -> assertTrue(Character.isUpperCase(name.charAt(0)),
                    "unexpected non-AWS member rendered: " + name + " in " + json));
        }
    }

    /** The plain S3DestinationDescription mirror has no conversion member in AWS's model. */
    @Test
    void conversionIsExtendedOnlyAndAbsentFromTheStandardView() {
        service.createDeliveryStream("stream", destination(s3 -> {}));

        DeliveryStreamDescription.Destination dest =
                service.describeDeliveryStream("stream").getDestinations().get(0);
        assertNotNull(dest.getExtendedS3DestinationDescription().getDataFormatConversionConfiguration());
        assertNull(dest.getS3DestinationDescription().getDataFormatConversionConfiguration());
    }

    // ── update: validation on the merged state, member-wise merge ────────────

    private String currentVersion(String name) {
        return service.describeDeliveryStream(name).getVersionId();
    }

    private static S3Destination update(Consumer<S3Destination> customizer) {
        S3Destination update = new S3Destination();
        customizer.accept(update);
        return update;
    }

    @Test
    void updateAppliesTheSameChecksAgainstTheMergedState() {
        service.createDeliveryStream("stream", destination(s3 -> {}));

        AwsException gzip = assertThrows(AwsException.class, () -> service.updateDestination("stream",
                currentVersion("stream"), DESTINATION_ID, update(u -> u.setCompressionFormat("GZIP"))));
        assertEquals(COMPRESSION_MESSAGE, gzip.getMessage());

        AwsException buffer = assertThrows(AwsException.class, () -> service.updateDestination("stream",
                currentVersion("stream"), DESTINATION_ID, update(u -> u.setBufferingHints(hints(5)))));
        assertEquals(BUFFER_MESSAGE, buffer.getMessage());
    }

    /** Probed: AWS rejects this too, since a plain stream stores the 5 MiB default. */
    @Test
    void enablingConversionOnAPlainStreamIsRejectedForItsStoredBufferSize() {
        service.createDeliveryStream("plain", destination(s3 -> {
            s3.setBufferingHints(null);
            s3.setDataFormatConversionConfiguration(null);
        }));

        AwsException error = assertThrows(AwsException.class, () -> service.updateDestination("plain",
                currentVersion("plain"), DESTINATION_ID,
                update(u -> u.setDataFormatConversionConfiguration(fullConversion()))));

        assertEquals(BUFFER_MESSAGE, error.getMessage());
    }

    @Test
    void disablingWithOnlyEnabledPreservesTheStoredMembers() {
        service.createDeliveryStream("stream", destination(s3 -> {}));
        String versionBefore = currentVersion("stream");

        DataFormatConversionConfiguration disable = new DataFormatConversionConfiguration();
        disable.setEnabled(false);
        service.updateDestination("stream", versionBefore, DESTINATION_ID,
                update(u -> u.setDataFormatConversionConfiguration(disable)));

        DataFormatConversionConfiguration stored = service.describeDeliveryStream("stream")
                .s3Destination().getDataFormatConversionConfiguration();
        assertEquals(false, stored.getEnabled());
        assertEquals("db", stored.getSchemaConfiguration().getDatabaseName());
        assertNotNull(stored.getInputFormatConfiguration().getDeserializer().getOpenXJsonSerDe());
        assertNotNull(stored.getOutputFormatConfiguration().getSerializer().getParquetSerDe());
        assertFalse(versionBefore.equals(currentVersion("stream")));
    }

    @Test
    void reenablingAloneIsRejectedWhileGzipIsInEffectAndLeavesTheStoreUntouched() {
        service.createDeliveryStream("stream", destination(s3 -> {}));
        DataFormatConversionConfiguration disable = new DataFormatConversionConfiguration();
        disable.setEnabled(false);
        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID,
                update(u -> u.setDataFormatConversionConfiguration(disable)));
        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID,
                update(u -> u.setCompressionFormat("GZIP")));
        String versionBefore = currentVersion("stream");

        DataFormatConversionConfiguration reenable = new DataFormatConversionConfiguration();
        reenable.setEnabled(true);
        AwsException error = assertThrows(AwsException.class, () -> service.updateDestination("stream",
                versionBefore, DESTINATION_ID, update(u -> u.setDataFormatConversionConfiguration(reenable))));

        assertEquals(COMPRESSION_MESSAGE, error.getMessage());
        S3Destination stored = service.describeDeliveryStream("stream").s3Destination();
        assertEquals(versionBefore, currentVersion("stream"));
        assertEquals("GZIP", stored.getCompressionFormat());
        assertEquals(false, stored.getDataFormatConversionConfiguration().getEnabled());
    }

    /** Probed: SchemaConfiguration merges a member at a time, like its parent. */
    @Test
    void updatingOneSchemaMemberKeepsTheOtherStoredOnes() {
        service.createDeliveryStream("stream", destination(s3 -> {}));

        SchemaConfiguration schemaUpdate = new SchemaConfiguration();
        schemaUpdate.setTableName("other_table");
        DataFormatConversionConfiguration conversionUpdate = new DataFormatConversionConfiguration();
        conversionUpdate.setSchemaConfiguration(schemaUpdate);
        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID,
                update(u -> u.setDataFormatConversionConfiguration(conversionUpdate)));

        SchemaConfiguration stored = service.describeDeliveryStream("stream").s3Destination()
                .getDataFormatConversionConfiguration().getSchemaConfiguration();
        assertEquals("other_table", stored.getTableName());
        assertEquals("db", stored.getDatabaseName());
        assertEquals(ROLE_ARN, stored.getRoleArn());
        assertEquals("us-east-1", stored.getRegion());
    }

    @Test
    void reenablingTogetherWithUncompressedSucceedsOnTheStoredSchema() {
        service.createDeliveryStream("stream", destination(s3 -> {}));
        DataFormatConversionConfiguration disable = new DataFormatConversionConfiguration();
        disable.setEnabled(false);
        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID,
                update(u -> u.setDataFormatConversionConfiguration(disable)));
        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID,
                update(u -> u.setCompressionFormat("GZIP")));

        DataFormatConversionConfiguration reenable = new DataFormatConversionConfiguration();
        reenable.setEnabled(true);
        service.updateDestination("stream", currentVersion("stream"), DESTINATION_ID, update(u -> {
            u.setCompressionFormat("UNCOMPRESSED");
            u.setDataFormatConversionConfiguration(reenable);
        }));

        S3Destination stored = service.describeDeliveryStream("stream").s3Destination();
        assertTrue(stored.isDataFormatConversionEnabled());
        assertEquals("UNCOMPRESSED", stored.getCompressionFormat());
        assertEquals("db", stored.getDataFormatConversionConfiguration()
                .getSchemaConfiguration().getDatabaseName());
    }

    @Test
    void updateOntoAStreamWithoutADestinationValidatesTheUpdateItself() {
        service.createDeliveryStream("bare", (S3Destination) null, List.of());

        AwsException error = assertThrows(AwsException.class, () -> service.updateDestination("bare",
                currentVersion("bare"), DESTINATION_ID, destination(s3 -> s3.setBufferingHints(hints(5)))));
        assertEquals(BUFFER_MESSAGE, error.getMessage());
    }
}
