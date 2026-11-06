package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/** Real account, function and alias resolution; only execution of the selected function is mocked. */
@QuarkusTest
class ApiGatewayLambdaOwnerIntegrationTest {
    private static final String API_OWNER = "111122223333";
    private static final String TARGET_OWNER = "777788889999";
    private static final String CALLER = "444455556666";
    private static final String REGION = "us-east-1";
    private static final byte[] BODY = "{\"value\":\"original\"}".getBytes(StandardCharsets.UTF_8);

    @Inject
    IamService iamService;
    @Inject
    ObjectMapper mapper;
    @InjectMock
    LambdaExecutorService executor;

    private final List<Executable> cleanup = new ArrayList<>();
    private String function;
    private String apiId;
    private String method;
    private String domain;
    private AccessKey callerKey;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString();
        function = "owned-lambda-" + suffix;
        domain = suffix + ".example.test";
        callerKey = RequestScopes.callAs(CALLER, REGION, () -> {
            iamService.createUser(function, "/");
            return iamService.createAccessKey(function);
        });
        cleanup.add(() -> RequestScopes.runAs(CALLER, REGION,
                () -> iamService.deleteAccessKey(function, callerKey.getAccessKeyId())));
        cleanup.add(() -> RequestScopes.runAs(CALLER, REGION, () -> iamService.deleteUser(function)));
        Answer<InvokeResult> execution = invocation -> {
            LambdaFunction selected = invocation.getArgument(0);
            String body = mapper.writeValueAsString(Map.of(
                    "account", selected.getAccountId(), "version", selected.getVersion(),
                    "arn", selected.getFunctionArn()));
            return new InvokeResult(200, null,
                    mapper.writeValueAsBytes(Map.of("statusCode", 200, "body", body)), null, "selected-function");
        };
        when(executor.invoke(any(LambdaFunction.class), any(byte[].class), eq(InvocationType.RequestResponse),
                anyInt(), nullable(String.class), nullable(String.class))).thenAnswer(execution);
        when(executor.invoke(any(LambdaFunction.class), any(byte[].class), eq(InvocationType.RequestResponse),
                anyInt(), nullable(String.class))).thenAnswer(execution);
        createFunction(API_OWNER, REGION);
        apiId = management(API_OWNER, REGION, "apigateway").body(Map.of("name", function))
                .post("/restapis").then().statusCode(201).extract().path("id");
        cleanup.add(() -> management(API_OWNER, REGION, "apigateway")
                .delete("/restapis/" + apiId).then().statusCode(202));
        String rootId = management(API_OWNER, REGION, "apigateway").get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        method = "/restapis/" + apiId + "/resources/" + rootId + "/methods/POST";
        management(API_OWNER, REGION, "apigateway").body(Map.of("authorizationType", "AWS_IAM"))
                .put(method).then().statusCode(201);
    }

    @ParameterizedTest
    @CsvSource({"AWS_PROXY,live,us-east-1", "AWS_PROXY,1,us-east-1",
            "AWS,live,us-east-1", "AWS,1,us-east-1",
            "AWS_PROXY,live,eu-west-1", "AWS,live,eu-west-1"})
    void theIntegrationSelectsItsAccountRegionAndPublishedVersion(String type, String qualifier,
                                                                 String targetRegion) throws Exception {
        createFunction(TARGET_OWNER, targetRegion);
        integrate(type, arn(TARGET_OWNER, targetRegion) + ":" + qualifier);
        String bodyPath = type.equals("AWS") ? "body" : "";
        String response = invoke(200);
        String body = bodyPath.isEmpty() ? response : mapper.readTree(response).path(bodyPath).asText();
        assertAll(
                () -> assertEquals(TARGET_OWNER,
                        mapper.readTree(body).path("account").asText()),
                () -> assertEquals("1",
                        mapper.readTree(body).path("version").asText()),
                () -> assertTrue(
                        mapper.readTree(body).path("arn").asText().contains(":" + targetRegion + ":" + TARGET_OWNER + ":")));
    }

    @Test
    void missingTargetDoesNotFallBackToSameNamedFunctionInApiAccount() throws Exception {
        integrate("AWS_PROXY", arn(TARGET_OWNER, REGION) + ":live");
        invoke(404);
    }

    @Test
    void unqualifiedLocalNameKeepsTheApiOwner() throws Exception {
        integrate("AWS_PROXY", function + ":live");
        given().contentType(ContentType.JSON).header("Host", domain).headers(sign()).body(BODY).post("/")
                .then().statusCode(200).body("account", equalTo(API_OWNER)).body("version", equalTo("1"));
    }

    private void createFunction(String account, String region) throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("handler.py"));
            zip.write("def handler(event, context):\n    return event\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String path = "/2015-03-31/functions/" + function;
        management(account, region, "lambda").body(Map.of(
                "FunctionName", function, "Runtime", "python3.12", "Handler", "handler.handler",
                "Role", "arn:aws:iam::" + account + ":role/test",
                "Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(archive.toByteArray()))))
                .post("/2015-03-31/functions").then().statusCode(201);
        cleanup.add(() -> management(account, region, "lambda").delete(path).then().statusCode(204));
        management(account, region, "lambda").body("{}").post(path + "/versions")
                .then().statusCode(201).body("Version", equalTo("1"));
        management(account, region, "lambda").body(Map.of("Name", "live", "FunctionVersion", "1"))
                .post(path + "/aliases").then().statusCode(201);
    }

    private void integrate(String type, String target) {
        String uri = target.startsWith("arn:")
                ? "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/" + target + "/invocations"
                : target;
        management(API_OWNER, REGION, "apigateway")
                .body(Map.of("type", type, "httpMethod", "POST", "uri", uri))
                .put(method + "/integration").then().statusCode(201);
        if (type.equals("AWS")) {
            management(API_OWNER, REGION, "apigateway").body("{}")
                    .put(method + "/responses/200").then().statusCode(201);
            management(API_OWNER, REGION, "apigateway").body(Map.of("selectionPattern", ""))
                    .put(method + "/integration/responses/200").then().statusCode(201);
        }
        management(API_OWNER, REGION, "apigateway").body(Map.of("stageName", "prod"))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
        management(API_OWNER, REGION, "apigateway").body(Map.of(
                "domainName", domain, "endpointConfiguration", Map.of("types", List.of("REGIONAL"))))
                .post("/domainnames").then().statusCode(201);
        cleanup.add(() -> management(API_OWNER, REGION, "apigateway")
                .delete("/domainnames/" + domain).then().statusCode(202));
        management(API_OWNER, REGION, "apigateway").body(Map.of("restApiId", apiId, "stage", "prod"))
                .post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);
    }

    private String invoke(int expectedStatus) throws Exception {
        return given().contentType(ContentType.JSON).header("Host", domain).headers(sign()).body(BODY).post("/")
                .then().statusCode(expectedStatus).extract().asString();
    }

    private Map<String, String> sign() throws Exception {
        return ExecuteApiRequestSigner.signedHeaders("POST", "/", Map.of(), domain, BODY,
                callerKey.getAccessKeyId(), callerKey.getSecretAccessKey(), REGION, Instant.now());
    }

    private String arn(String account, String region) {
        return "arn:aws:lambda:" + region + ":" + account + ":function:" + function;
    }

    private static RequestSpecification management(String account, String region, String service) {
        return given().contentType(ContentType.JSON).header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + account + "/20261012/" + region + "/" + service + "/aws4_request");
    }

    @AfterEach
    void removeOwnedResources() {
        assertAll("remove only this test graph", cleanup);
    }
}
