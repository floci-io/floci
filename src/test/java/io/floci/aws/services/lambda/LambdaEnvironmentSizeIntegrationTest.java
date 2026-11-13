package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.Environment;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetFunctionConfigurationResponse;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionConfigurationRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class LambdaEnvironmentSizeIntegrationTest {

    private static final String ACCOUNT = "799500000001";
    private final List<String> functions = new ArrayList<>();
    private LambdaClient client;

    @Inject
    LambdaFunctionStore functionStore;

    @BeforeEach
    void openClient() {
        client = AwsTestClients.create(LambdaClient.builder().overrideConfiguration(configuration -> configuration
                .addExecutionInterceptor(new ExecutionInterceptor() {
                    @Override
                    public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
                        if (context.request() instanceof UpdateFunctionConfigurationRequest) {
                            long length = context.requestBody().orElseThrow().contentLength();
                            assertTrue(length < 5120,
                                    "Update bodies must stay below the separate 5,120-byte request limit: " + length);
                        }
                    }
                })), ACCOUNT, Region.US_EAST_1);
    }

    @AfterEach
    void cleanUp() {
        try {
            for (String name : functions) {
                client.deleteFunction(request -> request.functionName(name));
            }
        } finally {
            client.close();
        }
    }

    @ParameterizedTest
    @MethodSource("oversizedEnvironments")
    void createRejectsOversizedEnvironmentWithoutCreatingFunction(Map<String, String> variables) throws IOException {
        String name = functionName();
        CreateFunctionRequest request = functionRequest(name).environment(environment(variables)).build();

        InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                () -> client.createFunction(request));

        assertQuotaError(error);
        assertMeasuredJson(error, variables);
        assertThrows(ResourceNotFoundException.class,
                () -> client.getFunctionConfiguration(get -> get.functionName(name)));
    }

    @ParameterizedTest
    @MethodSource("oversizedEnvironments")
    void rejectedUpdatePreservesConfiguration(Map<String, String> variables) throws IOException {
        String name = createFunction(Map.of("MARKER", "original"));
        GetFunctionConfigurationResponse original = configuration(name);

        InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                () -> client.updateFunctionConfiguration(request -> request.functionName(name)
                        .description("rejected update").timeout(60).memorySize(512)
                        .environment(environment(variables))));

        assertQuotaError(error);
        assertMeasuredJson(error, variables);
        GetFunctionConfigurationResponse unchanged = configuration(name);
        assertEquals(original.environment(), unchanged.environment());
        assertEquals(original.description(), unchanged.description());
        assertEquals(original.timeout(), unchanged.timeout());
        assertEquals(original.memorySize(), unchanged.memorySize());
        assertEquals(original.revisionId(), unchanged.revisionId());
        assertEquals(original.lastModified(), unchanged.lastModified());
        assertEquals(original.lastUpdateStatus(), unchanged.lastUpdateStatus());
    }

    @ParameterizedTest
    @MethodSource("acceptedEnvironments")
    void createAndUpdateAcceptEnvironmentWithinLimit(Map<String, String> variables) throws IOException {
        String name = createFunction(variables);
        assertEnvironment(name, variables);

        client.updateFunctionConfiguration(request -> request.functionName(name)
                .environment(environment(Map.of("MARKER", "replacement"))));
        client.updateFunctionConfiguration(request -> request.functionName(name).environment(environment(variables)));

        assertEnvironment(name, variables);
    }

    @Test
    void omittedEnvironmentPreservesVariablesAndEmptyVariablesClearThem() throws IOException {
        String name = createFunction(Map.of("BIG", "x".repeat(4086)));

        client.updateFunctionConfiguration(request -> request.functionName(name).description("updated"));
        assertEquals(Map.of("BIG", "x".repeat(4086)), configuration(name).environment().variables());

        client.updateFunctionConfiguration(request -> request.functionName(name).environment(environment(Map.of())));
        assertNull(configuration(name).environment());
    }

    @Test
    void substantiallyOversizedCreateReturnsABoundedMeasuredJsonPreview() throws IOException {
        String name = functionName();
        Map<String, String> variables = Map.of("BIG", "x".repeat(1_000_000));
        CreateFunctionRequest request = functionRequest(name).environment(environment(variables)).build();

        InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class,
                () -> client.createFunction(request));

        assertQuotaError(error);
        String measured = measuredJson(error);
        assertTrue(measured.startsWith("{\"BIG\":\"" + "x".repeat(100)));
        assertTrue(measured.endsWith("..."));
        assertTrue(measured.getBytes(StandardCharsets.UTF_8).length <= 5123);
        assertThrows(ResourceNotFoundException.class,
                () -> client.getFunctionConfiguration(get -> get.functionName(name)));
    }

    @Test
    void cloudFormationUpdatesLegacyEnvironmentOnlyWhenVariablesChange() throws IOException {
        String stackName = "legacy-environment-" + UUID.randomUUID();
        String name = functionName();
        Map<String, String> legacy = Map.of("BIG", "x".repeat(5000));
        try (CloudFormationClient cloudFormation = AwsTestClients.create(
                CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            cloudFormation.createStack(request -> request.stackName(stackName)
                    .templateBody(stackTemplate(name, "original", Map.of("MARKER", "original"))));
            try {
                CfnStackWaits.awaitStatus(cloudFormation, stackName, "CREATE_COMPLETE");
                // Seed the stored state of a function created before quota enforcement existed.
                LambdaFunction stored = functionStore.getForAccount(ACCOUNT, "us-east-1", name).orElseThrow();
                stored.setEnvironment(legacy);
                functionStore.saveForAccount(ACCOUNT, "us-east-1", stored);

                updateStack(cloudFormation, stackName, stackTemplate(name, "updated", legacy));
                assertEquals("updated", configuration(name).description());
                assertEnvironment(name, legacy);
                assertThrows(InvalidParameterValueException.class, () -> client.updateFunctionConfiguration(
                        request -> request.functionName(name).environment(environment(legacy))));

                cloudFormation.updateStack(request -> request.stackName(stackName)
                        .templateBody(stackTemplate(name, "rejected", Map.of("BIG", "x".repeat(5001)))));
                CfnStackWaits.awaitStatus(cloudFormation, stackName, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals("updated", configuration(name).description());
                assertEnvironment(name, legacy);

                updateStack(cloudFormation, stackName,
                        stackTemplate(name, "reduced", Map.of("MARKER", "valid")));
                assertEnvironment(name, Map.of("MARKER", "valid"));

                updateStack(cloudFormation, stackName, stackTemplate(name, "cleared", null));
                assertNull(configuration(name).environment());
            } finally {
                cloudFormation.deleteStack(request -> request.stackName(stackName));
                CfnStackWaits.awaitStackDeleted(stackName, "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261111/us-east-1/cloudformation/aws4_request");
            }
        }
    }

    private static void updateStack(CloudFormationClient client, String name, String template) {
        client.updateStack(request -> request.stackName(name).templateBody(template));
        CfnStackWaits.awaitStatus(client, name, "UPDATE_COMPLETE");
    }

    private static String stackTemplate(String name, String description, Map<String, String> variables) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "FunctionName", name,
                "Runtime", "nodejs20.x",
                "Handler", "index.handler",
                "Role", "arn:aws:iam::" + ACCOUNT + ":role/lambda-role",
                "Code", Map.of("ZipFile", "exports.handler = async () => 1;"),
                "Description", description));
        if (variables != null) {
            properties.put("Environment", Map.of("Variables", variables));
        }
        return new ObjectMapper().valueToTree(Map.of("Resources", Map.of("Function", Map.of(
                "Type", "AWS::Lambda::Function", "Properties", properties)))).toString();
    }

    private static Stream<Map<String, String>> oversizedEnvironments() {
        return Stream.of(
                Map.of("BIG", "x".repeat(5000)),
                Map.of("BIG", "x".repeat(4087)),
                Map.of("AA", "x".repeat(2040), "BB", "x".repeat(2040)),
                Map.of("BIG", "é".repeat(2043) + "x"),
                Map.of("BIG", "x".repeat(4084) + "😀"),
                Map.of("BIG", "x".repeat(3991) + "😀" + "x".repeat(92)),
                Map.of("BIG", "\"".repeat(2043) + "x"),
                Map.of("BIG", "\n".repeat(2043) + "x"));
    }

    private static Stream<Map<String, String>> acceptedEnvironments() {
        return Stream.of(
                Map.of(),
                Map.of("BIG", "x".repeat(4085)),
                Map.of("BIG", "x".repeat(4086)),
                Map.of("AA", "x".repeat(2040), "BB", "x".repeat(2039)),
                Map.of("BIG", "é".repeat(2043)),
                Map.of("BIG", "x".repeat(4082) + "😀"),
                Map.of("BIG", "x".repeat(3991) + "😀" + "x".repeat(91)),
                Map.of("BIG", "\"".repeat(2043)),
                Map.of("BIG", "\n".repeat(2043)));
    }

    private String createFunction(Map<String, String> variables) throws IOException {
        String name = functionName();
        client.createFunction(functionRequest(name).environment(environment(variables)).build());
        functions.add(name);
        return name;
    }

    private static CreateFunctionRequest.Builder functionRequest(String name) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => 1;".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return CreateFunctionRequest.builder().functionName(name).runtime("nodejs20.x")
                .role("arn:aws:iam::" + ACCOUNT + ":role/lambda-role").handler("index.handler")
                .description("original").timeout(10).memorySize(256)
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(bytes.toByteArray())).build());
    }

    private GetFunctionConfigurationResponse configuration(String name) {
        return client.getFunctionConfiguration(request -> request.functionName(name));
    }

    private void assertEnvironment(String name, Map<String, String> variables) {
        GetFunctionConfigurationResponse configuration = configuration(name);
        if (variables.isEmpty()) {
            assertNull(configuration.environment());
        } else {
            assertEquals(variables, configuration.environment().variables());
        }
    }

    private static Environment environment(Map<String, String> variables) {
        return Environment.builder().variables(variables).build();
    }

    private static String functionName() {
        return "environment-size-" + UUID.randomUUID();
    }

    private static void assertQuotaError(InvalidParameterValueException error) {
        assertEquals(400, error.statusCode());
        assertEquals("InvalidParameterValueException", error.awsErrorDetails().errorCode());
        assertTrue(error.awsErrorDetails().errorMessage()
                .contains("environment variables you have provided exceeded the 4KB limit"));
    }

    private static void assertMeasuredJson(InvalidParameterValueException error, Map<String, String> variables) {
        assertEquals(new ObjectMapper().valueToTree(variables).toString(), measuredJson(error));
    }

    private static String measuredJson(InvalidParameterValueException error) {
        String message = error.awsErrorDetails().errorMessage();
        String marker = " String measured: ";
        assertTrue(message.contains(marker), "The quota error must include AWS's measured JSON detail");
        return message.substring(message.indexOf(marker) + marker.length());
    }
}
