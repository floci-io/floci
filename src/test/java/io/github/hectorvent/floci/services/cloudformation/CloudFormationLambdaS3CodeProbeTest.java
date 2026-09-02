package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #2675: resolving an AWS::Lambda::Function whose Code points at S3 checked the object by
 * calling getObject and discarding the result, so the package was read in full twice per stack
 * operation, once here and once by LambdaService during CreateFunction. The check only needs to
 * know whether the object is readable, which headObject answers without reading the body.
 *
 * <p>Uses the mocked-service provisioner approach of CloudFormationLambdaLegacyNameModeTest,
 * because how many times a service is called is not observable through the HTTP integration tests.
 */
class CloudFormationLambdaS3CodeProbeTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STACK_NAME = "test-stack";
    private static final String BUCKET = "pkg";
    private static final String KEY = "fn.zip";

    private final ObjectMapper mapper = new ObjectMapper();
    private S3Service s3Service;
    private LambdaService lambdaService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        s3Service = mock(S3Service.class);
        lambdaService = mock(LambdaService.class);
        when(lambdaService.createFunction(anyString(), anyMap()))
                .thenAnswer(inv -> lambdaFunction((String) ((Map<?, ?>) inv.getArgument(1)).get("FunctionName")));
        // Built through the fixture rather than by positional nulls: AWS::Lambda::Function is
        // still served by the provisioner's own switch, so an empty registry is correct here.
        provisioner = CfnProvisionerFixture.builder()
                .s3(s3Service)
                .lambda(lambdaService)
                .objectMapper(mapper)
                .build();
    }

    @Test
    void theCodeProbeReadsMetadataOnlyAndNeverTheBody() {
        when(s3Service.headObject(BUCKET, KEY)).thenReturn(new S3Object());

        StackResource result = provision();

        assertEquals("CREATE_COMPLETE", result.getStatus());
        verify(s3Service).headObject(BUCKET, KEY);
        // The body is LambdaService's to read during CreateFunction. Reading it here too is a
        // second full copy of the package per Lambda per stack operation.
        verify(s3Service, never()).getObject(anyString(), anyString());
        verify(s3Service, never()).getObject(anyString(), anyString(), any());
    }

    @Test
    void anUnreadableObjectStillFailsTheResource() {
        // The probe changed from getObject to headObject, but both resolve the object through the
        // same metadata lookup, so the failure behaviour established in #2648 must be unchanged.
        when(s3Service.headObject(BUCKET, KEY))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        StackResource result = provision();

        assertEquals("CREATE_FAILED", result.getStatus());
        verify(lambdaService, never()).createFunction(anyString(), anyMap());
    }

    private StackResource provision() {
        String props = """
                {
                  "FunctionName": "probe-fn",
                  "Runtime": "nodejs20.x",
                  "Handler": "index.handler",
                  "Role": "arn:aws:iam::000000000000:role/r",
                  "Code": {"S3Bucket": "%s", "S3Key": "%s"}
                }
                """.formatted(BUCKET, KEY);
        return provisioner.provision("Function", "AWS::Lambda::Function", props(props), engine(),
                REGION, ACCOUNT_ID, STACK_NAME, null, Map.of());
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine(
                ACCOUNT_ID, REGION, STACK_NAME, "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static LambdaFunction lambdaFunction(String name) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(name);
        fn.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:" + name);
        return fn;
    }
}
