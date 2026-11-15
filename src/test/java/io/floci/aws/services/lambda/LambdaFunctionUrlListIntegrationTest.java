package io.floci.aws.services.lambda;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionUrlAuthType;
import software.amazon.awssdk.services.lambda.model.FunctionUrlConfig;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.ListFunctionUrlConfigsResponse;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaFunctionUrlListIntegrationTest {

    private static final String ACCOUNT = "741681410001";
    private static final String REGION = "eu-west-2";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261112/" + REGION + "/lambda/aws4_request";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();
    private LambdaClient client;
    private String functionName;
    private String functionArn;

    @BeforeEach
    void createFunction() throws IOException {
        client = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.of(REGION), endpoint);
        functionName = "url-list-" + UUID.randomUUID().toString().substring(0, 8);
        functionArn = createFunction(client, functionName, ACCOUNT, Region.of(REGION));
    }

    @AfterEach
    void cleanUp() {
        client.close();
    }

    @Test
    void emptyListAndMissingFunctionHaveDifferentResponses() {
        ListFunctionUrlConfigsResponse empty = client.listFunctionUrlConfigs(request -> request
                .functionName(functionName));
        assertTrue(empty.functionUrlConfigs().isEmpty());
        assertNull(empty.nextMarker());
        ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                () -> client.listFunctionUrlConfigs(request -> request
                .functionName(functionName + "-missing")));
        assertEquals(404, missing.statusCode());
        assertEquals("Function not found: " + functionName + "-missing", missing.awsErrorDetails().errorMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-gov-west-1", "us-gov-east-1", "cn-north-1", "cn-northwest-1"})
    void listingIsRejectedWhereFunctionUrlsAreUnavailable(String region) throws IOException {
        try (LambdaClient regional = AwsTestClients.create(
                LambdaClient.builder(), ACCOUNT, Region.of(region), endpoint)) {
            String arn = createFunction(regional, functionName, ACCOUNT, Region.of(region));
            for (String reference : List.of(functionName, arn, ACCOUNT + ":function:" + functionName)) {
                InvalidParameterValueException unavailable = assertThrows(InvalidParameterValueException.class,
                        () -> regional.listFunctionUrlConfigs(request -> request.functionName(reference)));
                assertEquals(400, unavailable.statusCode());
            }
            assertEquals(arn, regional.getFunction(request -> request.functionName(functionName))
                    .configuration().functionArn());
        }
    }

    @Test
    void listsStoredLatestAndAliasConfigurationsThroughAllDocumentedNameForms() {
        createUrls();
        for (String reference : List.of(functionName, functionArn, ACCOUNT + ":function:" + functionName)) {
            List<FunctionUrlConfig> configs = client.listFunctionUrlConfigs(request -> request
                    .functionName(reference)).functionUrlConfigs();
            assertEquals(List.of(functionArn, functionArn + ":blue", functionArn + ":green"),
                    configs.stream().map(FunctionUrlConfig::functionArn).sorted().toList());
            FunctionUrlConfig blue = configs.stream().filter(config -> config.functionArn().endsWith(":blue"))
                    .findFirst().orElseThrow();
            assertEquals(FunctionUrlAuthType.NONE, blue.authType());
            assertEquals(List.of("https://example.invalid"), blue.cors().allowOrigins());
            assertEquals(client.getFunctionUrlConfig(request -> request
                    .functionName(functionName).qualifier("blue")).functionUrl(), blue.functionUrl());
            assertNotNull(blue.creationTime());
            assertNotNull(blue.lastModifiedTime());
        }
    }

    @Test
    void sdkPaginatorTraversesEveryConfigAndUpdatesAndDeletesAreVisible() {
        createUrls();
        List<FunctionUrlConfig> all = client.listFunctionUrlConfigsPaginator(request -> request
                        .functionName(functionName).maxItems(1)).stream()
                .flatMap(page -> page.functionUrlConfigs().stream()).toList();
        assertEquals(3, all.size());
        assertEquals(3, all.stream().map(FunctionUrlConfig::functionArn).distinct().count());
        client.updateFunctionUrlConfig(request -> request.functionName(functionName).qualifier("blue")
                .authType(FunctionUrlAuthType.AWS_IAM).cors(cors -> cors.allowOrigins("https://updated.invalid")));
        FunctionUrlConfig updated = client.listFunctionUrlConfigs(request -> request
                        .functionName(functionName).maxItems(50)).functionUrlConfigs().stream()
                .filter(config -> config.functionArn().endsWith(":blue")).findFirst().orElseThrow();
        assertEquals(FunctionUrlAuthType.AWS_IAM, updated.authType());
        assertEquals(List.of("https://updated.invalid"), updated.cors().allowOrigins());
        client.deleteFunctionUrlConfig(request -> request.functionName(functionName).qualifier("blue"));
        client.deleteAlias(request -> request.functionName(functionName).name("green"));
        ListFunctionUrlConfigsResponse remaining = client.listFunctionUrlConfigs(request -> request
                .functionName(functionName));
        assertEquals(List.of(functionArn), remaining.functionUrlConfigs().stream()
                .map(FunctionUrlConfig::functionArn).toList());
        assertNull(remaining.nextMarker());
        client.deleteFunctionUrlConfig(request -> request.functionName(functionName));
        assertTrue(client.listFunctionUrlConfigs(request -> request.functionName(functionName))
                .functionUrlConfigs().isEmpty());
    }

    @Test
    void pageCanResumeAfterThePreviouslyReturnedUrlIsDeleted() {
        createUrls();
        ListFunctionUrlConfigsResponse first = client.listFunctionUrlConfigs(request -> request
                .functionName(functionName).maxItems(1));
        assertEquals(List.of(functionArn), first.functionUrlConfigs().stream()
                .map(FunctionUrlConfig::functionArn).toList());
        assertNotNull(first.nextMarker());
        client.deleteFunctionUrlConfig(request -> request.functionName(functionName));
        ListFunctionUrlConfigsResponse remaining = client.listFunctionUrlConfigs(request -> request
                .functionName(functionName).marker(first.nextMarker()).maxItems(2));
        assertEquals(List.of(functionArn + ":blue", functionArn + ":green"),
                remaining.functionUrlConfigs().stream().map(FunctionUrlConfig::functionArn).toList());
        assertNull(remaining.nextMarker());
    }

    @Test
    void configurationsAndMarkersAreScopedToFunctionAccountAndRegion() throws IOException {
        createUrls();
        String marker = client.listFunctionUrlConfigs(request -> request
                .functionName(functionName).maxItems(1)).nextMarker();
        String otherName = functionName + "-other";
        createFunction(client, otherName, ACCOUNT, Region.of(REGION));
        assertThrows(InvalidParameterValueException.class, () -> client.listFunctionUrlConfigs(request -> request
                .functionName(otherName).marker(marker)));
        try (LambdaClient otherAccount = AwsTestClients.create(LambdaClient.builder(),
                "741681410002", Region.of(REGION), endpoint);
             LambdaClient otherRegion = AwsTestClients.create(LambdaClient.builder(),
                     ACCOUNT, Region.US_EAST_1, endpoint)) {
            for (LambdaClient other : List.of(otherAccount, otherRegion)) {
                assertThrows(ResourceNotFoundException.class, () -> other.listFunctionUrlConfigs(request -> request
                        .functionName(functionName)));
            }
            createFunction(otherAccount, functionName, "741681410002", Region.of(REGION));
            createFunction(otherRegion, functionName, ACCOUNT, Region.US_EAST_1);
            for (LambdaClient other : List.of(otherAccount, otherRegion)) {
                assertTrue(other.listFunctionUrlConfigs(request -> request.functionName(functionName))
                        .functionUrlConfigs().isEmpty());
                assertThrows(InvalidParameterValueException.class, () -> other.listFunctionUrlConfigs(request -> request
                        .functionName(functionName).marker(marker)));
            }
            ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                    () -> otherAccount.listFunctionUrlConfigs(request -> request.functionName(functionArn)));
            assertEquals("Function not found: " + functionArn, missing.awsErrorDetails().errorMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-marker", "%%%"})
    void rejectsInvalidMarkers(String marker) {
        InvalidParameterValueException invalid = assertThrows(InvalidParameterValueException.class,
                () -> client.listFunctionUrlConfigs(request -> request.functionName(functionName).marker(marker)));
        assertEquals(400, invalid.statusCode());
        assertEquals("Invalid pagination key.", invalid.awsErrorDetails().errorMessage());
    }

    @Test
    void aForeignPartitionArnCannotSelectTheCallersFunction() {
        createUrls();
        String foreignArn = functionArn.replace("arn:aws:", "arn:aws-cn:");
        ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                () -> client.listFunctionUrlConfigs(request -> request.functionName(foreignArn)));
        assertEquals("Function not found: " + foreignArn, missing.awsErrorDetails().errorMessage());
        assertEquals(3, client.listFunctionUrlConfigs(request -> request.functionName(functionArn))
                .functionUrlConfigs().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "$LATEST"})
    void rejectsQualifiersOutsideTheFunctionUrlNameShape(String qualifier) {
        LambdaException invalid = assertThrows(LambdaException.class, () -> client.listFunctionUrlConfigs(request -> request
                .functionName(functionName + ":" + qualifier)));
        assertEquals(400, invalid.statusCode());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void invalidPageSizesReturnModeledJsonErrors(int maxItems) {
        String bound = maxItems < 1 ? "greater than or equal to 1" : "less than or equal to 50";
        given().port(endpoint.getPort()).header("Authorization", AUTH).queryParam("MaxItems", maxItems)
                .get("/2021-10-31/functions/" + functionName + "/urls")
                .then().statusCode(400).body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '" + maxItems
                        + "' at 'maxItems' failed to satisfy constraint: Member must have value " + bound));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "1.5", "invalid"})
    void nonIntegerPageSizesReturnSerializationErrors(String maxItems) {
        given().port(endpoint.getPort()).header("Authorization", AUTH).queryParam("MaxItems", maxItems)
                .get("/2021-10-31/functions/" + functionName + "/urls")
                .then().statusCode(400).body("__type", equalTo("SerializationException"))
                .body("message", equalTo("'" + maxItems + "' can not be converted to Integer"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void sdkReportsPageSizeValidationErrors(int maxItems) {
        LambdaException invalid = assertThrows(LambdaException.class,
                () -> client.listFunctionUrlConfigs(request -> request.functionName(functionName).maxItems(maxItems)));
        assertEquals(400, invalid.statusCode());
        assertEquals("ValidationException", invalid.awsErrorDetails().errorCode());
    }

    @Test
    void emptyPageSizeUsesTheDefault() {
        createUrls();
        given().port(endpoint.getPort()).header("Authorization", AUTH).queryParam("MaxItems", "")
                .get("/2021-10-31/functions/" + functionName + "/urls")
                .then().statusCode(200).body("FunctionUrlConfigs.size()", equalTo(3));
    }

    private void createUrls() {
        client.createFunctionUrlConfig(request -> request.functionName(functionName).authType(FunctionUrlAuthType.AWS_IAM));
        for (String alias : List.of("blue", "green", "without-url")) {
            client.createAlias(request -> request.functionName(functionName).name(alias).functionVersion("1"));
            if (!alias.equals("without-url")) {
                client.createFunctionUrlConfig(request -> request.functionName(functionName).qualifier(alias)
                        .authType(FunctionUrlAuthType.NONE).cors(cors -> cors.allowOrigins("https://example.invalid")));
            }
        }
    }

    private String createFunction(LambdaClient target, String name, String account, Region region) throws IOException {
        return LambdaFunctionUrlFixtures.createFunction(target, name, account, region, endpoint, cleanup);
    }
}
