package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A template whose Lambda code is {@code {S3Bucket: hot-reload, S3Key: /host/path}} uses the
 * Lambda service's bind-mount hot-reload convention. There is no S3 object behind that pair, so
 * the provisioner must hand it to LambdaService unchanged instead of probing S3 and failing the
 * resource (or, with allow-stub-lambda-code, quietly deploying the stub handler).
 */
class CloudFormationLambdaHotReloadCodeTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STACK_NAME = "test-stack";
    private static final String FUNCTION_NAME = "hot-fn";
    private static final String HOST_PATH = "/home/dev/project/dist";

    private final ObjectMapper mapper = new ObjectMapper();
    private S3Service s3Service;
    private LambdaService lambdaService;
    private CfnResourceDispatcher provisioner;

    @BeforeEach
    void setUp() {
        s3Service = mock(S3Service.class);
        lambdaService = mock(LambdaService.class);
        when(lambdaService.createFunction(anyString(), anyMap()))
                .thenAnswer(inv -> hotReloadFunction(HOST_PATH));
        when(lambdaService.updateFunctionConfiguration(anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> hotReloadFunction(HOST_PATH));
        when(lambdaService.updateFunctionCode(anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> hotReloadFunction((String) ((Map<?, ?>) inv.getArgument(2)).get("S3Key")));
        provisioner = CfnProvisionerFixture.builder()
                .s3(s3Service)
                .lambda(lambdaService)
                .objectMapper(mapper)
                .build();
    }

    @Test
    void hotReloadCodeIsPassedThroughWithoutProbingS3() {
        StackResource result = provision(HOST_PATH, null, Map.of());

        assertEquals("CREATE_COMPLETE", result.getStatus());
        verify(s3Service, never()).headObject(anyString(), anyString());
        verify(s3Service, never()).getObject(anyString(), anyString());

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambdaService).createFunction(eq(REGION), request.capture());
        assertEquals(Map.of("S3Bucket", "hot-reload", "S3Key", HOST_PATH), request.getValue().get("Code"));
        assertEquals("hot-reload:" + HOST_PATH, result.getAttributes().get("FlociLambdaCodeIdentity"));
    }

    @Test
    void redeployingTheSameHostPathDoesNotTouchTheCode() {
        when(lambdaService.getFunction(REGION, FUNCTION_NAME)).thenReturn(hotReloadFunction(HOST_PATH));

        StackResource result = provision(HOST_PATH, FUNCTION_NAME,
                Map.of("FlociLambdaCodeIdentity", "hot-reload:" + HOST_PATH));

        assertEquals(FUNCTION_NAME, result.getPhysicalId());
        verify(lambdaService, never()).createFunction(anyString(), anyMap());
        verify(lambdaService, never()).updateFunctionCode(anyString(), anyString(), anyMap());
        verify(s3Service, never()).headObject(anyString(), anyString());
    }

    @Test
    void anAdoptedHotReloadFunctionWithoutARecordedIdentityIsComparedByHostPath() {
        when(lambdaService.getFunction(REGION, FUNCTION_NAME)).thenReturn(hotReloadFunction(HOST_PATH));

        provision(HOST_PATH, FUNCTION_NAME, Map.of());

        verify(lambdaService, never()).updateFunctionCode(anyString(), anyString(), anyMap());
    }

    @Test
    void aRespelledHostPathIsNotACodeChange() {
        // LambdaService stores the normalized path (#4053); the template may spell the same
        // directory with a trailing slash or a ".." segment. Neither is a new mount.
        when(lambdaService.getFunction(REGION, FUNCTION_NAME)).thenReturn(hotReloadFunction(HOST_PATH));

        StackResource result = provision("/home/dev/project/build/../dist/", FUNCTION_NAME,
                Map.of("FlociLambdaCodeIdentity", "hot-reload:" + HOST_PATH));

        verify(lambdaService, never()).updateFunctionCode(anyString(), anyString(), anyMap());
        assertEquals("hot-reload:" + HOST_PATH, result.getAttributes().get("FlociLambdaCodeIdentity"));
    }

    @Test
    void anAdoptedFunctionIsComparedAgainstTheCanonicalTemplatePath() {
        when(lambdaService.getFunction(REGION, FUNCTION_NAME)).thenReturn(hotReloadFunction(HOST_PATH));

        provision(HOST_PATH + "/", FUNCTION_NAME, Map.of());

        verify(lambdaService, never()).updateFunctionCode(anyString(), anyString(), anyMap());
    }

    @Test
    void theRawKeyStillReachesLambdaForValidation() {
        StackResource result = provision("relative/dist", null, Map.of());

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambdaService).createFunction(eq(REGION), request.capture());
        assertEquals("relative/dist", ((Map<?, ?>) request.getValue().get("Code")).get("S3Key"));
        assertEquals("CREATE_COMPLETE", result.getStatus());
    }

    @Test
    void aChangedHostPathUpdatesTheFunctionCode() {
        when(lambdaService.getFunction(REGION, FUNCTION_NAME)).thenReturn(hotReloadFunction(HOST_PATH));

        StackResource result = provision("/home/dev/other/dist", FUNCTION_NAME,
                Map.of("FlociLambdaCodeIdentity", "hot-reload:" + HOST_PATH));

        verify(lambdaService, never()).createFunction(anyString(), anyMap());
        verify(lambdaService).updateFunctionCode(eq(REGION), eq(FUNCTION_NAME),
                eq(Map.of("S3Bucket", "hot-reload", "S3Key", "/home/dev/other/dist")));
        assertEquals("hot-reload:/home/dev/other/dist", result.getAttributes().get("FlociLambdaCodeIdentity"));
    }

    private StackResource provision(String hostPath, String existingPhysicalId,
                                    Map<String, String> existingAttributes) {
        String props = """
                {
                  "FunctionName": "%s",
                  "Runtime": "nodejs20.x",
                  "Handler": "index.handler",
                  "Role": "arn:aws:iam::000000000000:role/r",
                  "Code": {"S3Bucket": "hot-reload", "S3Key": "%s"}
                }
                """.formatted(FUNCTION_NAME, hostPath);
        return provisioner.provision("Function", "AWS::Lambda::Function", props(props), engine(),
                REGION, ACCOUNT_ID, STACK_NAME, existingPhysicalId, existingAttributes);
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

    private static LambdaFunction hotReloadFunction(String hostPath) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(FUNCTION_NAME);
        fn.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:" + FUNCTION_NAME);
        fn.setPackageType("Zip");
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setRole("arn:aws:iam::" + ACCOUNT_ID + ":role/r");
        fn.setRevisionId("1");
        fn.setHotReloadHostPath(hostPath);
        return fn;
    }
}
