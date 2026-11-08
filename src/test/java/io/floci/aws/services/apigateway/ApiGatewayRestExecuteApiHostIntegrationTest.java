package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import io.floci.aws.services.apigatewayv2.model.Api;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.ConfiguredHostnameProfile;
import io.floci.aws.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ConfiguredHostnameProfile.class)
class ApiGatewayRestExecuteApiHostIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    ApiGatewayV2Service v2Service;

    @Inject
    ApiGatewayService restService;

    @Inject
    IamService iamService;

    @ParameterizedTest
    @CsvSource({"eu-west-1,amazonaws.com", "us-gov-west-1,amazonaws.com", "cn-north-1,amazonaws.com.cn"})
    void publicRestUrlResolvesItsOwnerWithoutExposingManagementReads(String region, String suffix) {
        String owner = "111122223333";
        String authorization = managementAccount(owner, region);
        String apiId = createOwnedApi(authorization);
        try {
            given().header("Authorization", authorization).contentType(ContentType.JSON)
                    .body("{\"stageName\":\"test\"}").post("/restapis/" + apiId + "/deployments")
                    .then().statusCode(201);
            given().header("Host", apiId + ".execute-api." + region + "." + suffix)
                    .get("/test/ping").then().statusCode(200).body("route", equalTo("ping"));
            given().header("Authorization", managementAccount("000000000000", region))
                    .get("/restapis/" + apiId).then().statusCode(404);
            given().header("Authorization", authorization).get("/restapis/" + apiId)
                    .then().statusCode(200).body("id", equalTo(apiId));
        } finally {
            RequestScopes.runAs(owner, () -> restService.deleteRestApi(region, apiId));
        }
    }

    @Test
    void signedRestCallerCanDifferFromApiOwnerAndMissingOrTamperedSignaturesStillFail() throws Exception {
        String owner = "222233334444";
        String caller = "444455556666";
        String authorization = managementAccount(owner);
        String apiId = createOwnedApi(authorization);
        String userName = "rest-caller-" + UUID.randomUUID();
        AccessKey key = RequestScopes.callAs(caller, () -> {
            iamService.createUser(userName, "/");
            return iamService.createAccessKey(userName);
        });
        try {
            String methodPath = "/restapis/" + apiId + "/resources/"
                    + resourceId(apiId, "/ping", authorization) + "/methods/GET";
            given().header("Authorization", authorization).contentType(ContentType.JSON)
                    .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/authorizationType\","
                            + "\"value\":\"AWS_IAM\"}]}").patch(methodPath).then().statusCode(200);
            given().header("Authorization", authorization).contentType(ContentType.JSON)
                    .body("{\"stageName\":\"test\"}").post("/restapis/" + apiId + "/deployments")
                    .then().statusCode(201);
            String host = apiId + ".execute-api.eu-west-1.amazonaws.com";
            String path = "/test/ping";
            given().header("Host", host).get(path).then().statusCode(403);
            Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                    "GET", path, Map.of(), host, null, key.getAccessKeyId(),
                    key.getSecretAccessKey(), "eu-west-1", Instant.now());
            given().header("Host", host).headers(signed).get(path)
                    .then().statusCode(200).body("route", equalTo("ping"))
                    .body("callerAccount", equalTo(caller))
                    .body("callerArn", equalTo("arn:aws:iam::" + caller + ":user/" + userName));
            Map<String, String> tampered = new LinkedHashMap<>(signed);
            String header = signed.get("Authorization");
            tampered.put("Authorization", header.substring(0, header.length() - 1)
                    + (header.endsWith("0") ? "1" : "0"));
            given().header("Host", host).headers(tampered).get(path).then().statusCode(403);
            given().header("Authorization", managementAccount(caller)).get("/restapis/" + apiId)
                    .then().statusCode(404);
        } finally {
            RequestScopes.runAs(caller, () -> {
                iamService.deleteAccessKey(userName, key.getAccessKeyId());
                iamService.deleteUser(userName);
            });
            RequestScopes.runAs(owner, () -> restService.deleteRestApi("eu-west-1", apiId));
        }
    }

    @Test
    void ambiguousRestIdsAcrossOwnersRefuseDataPlaneLookup() {
        String apiId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        for (String owner : new String[]{"555566667777", "666677778888"}) {
            RequestScopes.runAs(owner, () -> restService.createRestApi("eu-west-1",
                    Map.of("name", "ambiguous-rest", "tags", Map.of("floci:override-id", apiId))));
        }
        try {
            given().header("Host", apiId + ".execute-api.eu-west-1.amazonaws.com")
                    .get("/test/ping").then().statusCode(409);
        } finally {
            for (String owner : new String[]{"555566667777", "666677778888"}) {
                RequestScopes.runAs(owner, () -> restService.deleteRestApi("eu-west-1", apiId));
            }
        }
    }

    @Test
    void signedCallersRetainTheirOwnApiWhenIdsAreDuplicatedAcrossAccounts() throws Exception {
        String region = "eu-west-1";
        String apiId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String user = "duplicate-id-" + UUID.randomUUID();
        String[] owners = {"555566667777", "666677778888"};
        String unrelated = "777788889999";
        Map<String, AccessKey> keys = new LinkedHashMap<>();
        try {
            for (String account : new String[] {owners[0], owners[1], unrelated}) {
                keys.put(account, RequestScopes.callAs(account, () -> {
                    iamService.createUser(user, "/");
                    return iamService.createAccessKey(user);
                }));
            }
            for (String owner : owners) {
                ObjectNode document = JSON.createObjectNode();
                document.put("openapi", "3.0.1");
                document.putObject("info").put("title", "duplicate-owner").put("version", "1");
                ObjectNode paths = document.putObject("paths");
                addMockMethod(paths, "/ping", owner);
                document.putObject("components").putObject("securitySchemes").putObject("iam")
                        .put("type", "apiKey").put("name", "Authorization").put("in", "header")
                        .put("x-amazon-apigateway-authtype", "awsSigv4");
                ((ObjectNode) document.at("/paths/~1ping/get")).putArray("security")
                        .addObject().putArray("iam");
                RequestScopes.runAs(owner, region, () -> {
                    restService.createRestApi(region,
                            Map.of("name", "duplicate-owner", "tags", Map.of("floci:override-id", apiId)));
                    restService.putRestApi(region, apiId, "overwrite", document.toString());
                    restService.createDeployment(region, apiId, Map.of("stageName", "test"));
                });
            }
            String host = apiId + ".execute-api." + region + ".amazonaws.com";
            String path = "/test/ping";
            for (String owner : owners) {
                AccessKey key = keys.get(owner);
                Map<String, String> signed = ExecuteApiRequestSigner.signedHeaders(
                        "GET", path, Map.of(), host, null,
                        key.getAccessKeyId(), key.getSecretAccessKey(), region, Instant.now());
                given().header("Host", host).headers(signed).get(path)
                        .then().statusCode(200).body("route", equalTo(owner))
                        .body("callerAccount", equalTo(owner));
                Map<String, String> tampered = new LinkedHashMap<>(signed);
                String authorization = signed.get("Authorization");
                tampered.put("Authorization", authorization.substring(0, authorization.length() - 1)
                        + (authorization.endsWith("0") ? "1" : "0"));
                given().header("Host", host).headers(tampered).get(path).then().statusCode(403);
            }
            AccessKey otherKey = keys.get(unrelated);
            Map<String, String> otherSigned = ExecuteApiRequestSigner.signedHeaders(
                    "GET", path, Map.of(), host, null,
                    otherKey.getAccessKeyId(), otherKey.getSecretAccessKey(), region, Instant.now());
            given().header("Host", host).headers(otherSigned).get(path).then().statusCode(409);
            given().header("Host", host).get(path).then().statusCode(409);
        } finally {
            for (String owner : owners) {
                RequestScopes.runAs(owner, region, () -> {
                    if (restService.hasRestApi(region, apiId)) {
                        restService.deleteRestApi(region, apiId);
                    }
                });
            }
            for (Map.Entry<String, AccessKey> entry : keys.entrySet()) {
                RequestScopes.runAs(entry.getKey(), () -> {
                    iamService.deleteAccessKey(user, entry.getValue().getAccessKeyId());
                    iamService.deleteUser(user);
                });
            }
        }
    }

    private static String managementAccount(String account) {
        return managementAccount(account, "eu-west-1");
    }

    private static String managementAccount(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261008/" + region + "/apigateway/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String createOwnedApi(String authorization) {
        return given().header("Authorization", authorization).contentType(ContentType.JSON)
                .queryParam("mode", "import").body(spec()).post("/restapis")
                .then().statusCode(201).extract().path("id");
    }

    @Test
    void routesRestApiVirtualHostToDeployedMethods() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec())
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201);

        given()
                .when().get("/execute-api/" + apiId + "/test/ping")
                .then().statusCode(200)
                .body("route", equalTo("ping"));

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/ping")
                .then().statusCode(200)
                .body("route", equalTo("ping"));

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/deep/path")
                .then().statusCode(200)
                .body("route", equalTo("nested"));

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/@connections/demo")
                .then().statusCode(200)
                .body("route", equalTo("rest-connection"));

        given()
                .when().get("/execute-api/" + apiId + "/test/@connections/demo")
                .then().statusCode(200)
                .body("route", equalTo("rest-connection"));

        given()
                .when().get("/restapis/" + apiId + "/test/_user_request_/ping")
                .then().statusCode(200)
                .body("route", equalTo("ping"));
    }

    @Test
    void signedRestHostRequestUsesOriginalPath() throws Exception {
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec())
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId(apiId, "/ping")
                + "/methods/GET";
        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/authorizationType\","
                        + "\"value\":\"AWS_IAM\"}]}")
                .when().patch(methodPath)
                .then().statusCode(200)
                .body("authorizationType", equalTo("AWS_IAM"));
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"signed\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201);

        String host = apiId + ".execute-api.localhost.floci.io:4566";
        String path = "/signed/ping";
        given()
                .header("Host", host)
                .when().get(path)
                .then().statusCode(403);

        Map<String, String> signedHeaders = ExecuteApiRequestSigner.signedHeaders(
                "GET", path, Map.of(), host, null, "test", "test", "us-east-1", Instant.now());
        given()
                .header("Host", host)
                .headers(signedHeaders)
                .when().get(path)
                .then().statusCode(200)
                .body("route", equalTo("ping"));
    }

    @Test
    void websocketConnectionManagementStillUsesWebSocketApi() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"connection-routing\",\"protocolType\":\"WEBSOCKET\","
                        + "\"routeSelectionExpression\":\"$request.body.action\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"rest-id-collision\",\"tags\":{\"floci:override-id\":\"" + apiId + "\"}}")
                .when().post("/restapis")
                .then().statusCode(201)
                .body("id", equalTo(apiId));

        given()
                .when().get("/execute-api/" + apiId + "/test/@connections/missing")
                .then().statusCode(410);

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/@connections/missing")
                .then().statusCode(410);
    }

    @Test
    void restCustomDomainWinsOverWebSocketIdCollision() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec())
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"websocket-id-collision\",\"protocolType\":\"WEBSOCKET\","
                        + "\"routeSelectionExpression\":\"$request.body.action\","
                        + "\"tags\":{\"floci:override-id\":\"" + apiId + "\"}}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .body("apiId", equalTo(apiId));
        given()
                .contentType(ContentType.JSON)
                .body("{\"domainName\":\"collision.example.com\","
                        + "\"certificateArn\":\"arn:aws:acm:us-east-1:000000000000:certificate/demo\"}")
                .when().post("/domainnames")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"basePath\":\"(none)\",\"restApiId\":\"" + apiId + "\",\"stage\":\"test\"}")
                .when().post("/domainnames/collision.example.com/basepathmappings")
                .then().statusCode(201);

        given()
                .header("Host", "collision.example.com.regional.local")
                .when().get("/@connections/demo")
                .then().statusCode(200)
                .body("route", equalTo("rest-connection"));

        given()
                .when().get("/execute-api/" + apiId + "/test/@connections/missing")
                .then().statusCode(410);
    }

    @Test
    void websocketApiMappingKeepsConnectionManagementWithRestIdCollision() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"websocket-domain\",\"protocolType\":\"WEBSOCKET\","
                        + "\"routeSelectionExpression\":\"$request.body.action\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"domainName\":\"websocket.example.com\","
                        + "\"domainNameConfigurations\":[{\"endpointType\":\"REGIONAL\"}]}")
                .when().post("/v2/domainnames")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"apiId\":\"" + apiId + "\",\"stage\":\"test\"}")
                .when().post("/v2/domainnames/websocket.example.com/apimappings")
                .then().statusCode(201);

        given()
                .header("Host", "websocket.example.com.regional.local")
                .when().get("/@connections/missing")
                .then().statusCode(410);

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"rest-id-collision\",\"tags\":{\"floci:override-id\":\"" + apiId + "\"}}")
                .when().post("/restapis")
                .then().statusCode(201)
                .body("id", equalTo(apiId));
        given()
                .header("Host", "websocket.example.com.regional.local")
                .when().get("/@connections/missing")
                .then().statusCode(410);
    }

    @Test
    void directWebSocketConnectionRoutingFindsNonDefaultAndDuplicateOwners() {
        String authorization = "AWS4-HMAC-SHA256 Credential=000000000001/20260929/us-east-1/apigateway/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
        String apiId = given()
                .header("Authorization", authorization)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"other-account-websocket\",\"protocolType\":\"WEBSOCKET\","
                        + "\"routeSelectionExpression\":\"$request.body.action\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
        given()
                .when().get("/execute-api/" + apiId + "/test/@connections/missing")
                .then().statusCode(410);

        Api firstAccountApi = RequestScopes.callAs("000000000001",
                () -> v2Service.getApi("us-east-1", apiId));
        RequestScopes.runAs("000000000002", () -> v2Service.putApi("us-east-1", firstAccountApi));
        try {
            given()
                    .when().get("/execute-api/" + apiId + "/test/@connections/missing")
                    .then().statusCode(410);
        } finally {
            RequestScopes.runAs("000000000002", () -> v2Service.deleteApi("us-east-1", apiId));
            RequestScopes.runAs("000000000001", () -> v2Service.deleteApi("us-east-1", apiId));
        }
    }

    private static String resourceId(String apiId, String path) throws Exception {
        return resourceId(apiId, path, null);
    }

    private static String resourceId(String apiId, String path, String authorization) throws Exception {
        String resources = given()
                .headers(authorization == null ? Map.of() : Map.of("Authorization", authorization))
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().asString();
        for (JsonNode resource : JSON.readTree(resources).path("item")) {
            if (path.equals(resource.path("path").asText())) {
                return resource.path("id").asText();
            }
        }
        throw new AssertionError("Imported resource was not found: " + path);
    }

    private static String spec() {
        ObjectNode root = JSON.createObjectNode();
        root.put("openapi", "3.0.1");
        root.putObject("info").put("title", "REST virtual host").put("version", "1.0");
        ObjectNode paths = root.putObject("paths");
        addMockMethod(paths, "/ping", "ping");
        addMockMethod(paths, "/deep/path", "nested");
        addMockMethod(paths, "/@connections/demo", "rest-connection");
        return root.toString();
    }

    private static void addMockMethod(ObjectNode paths, String path, String route) {
        ObjectNode operation = paths.putObject(path).putObject("get");
        operation.putObject("responses").putObject("200").put("description", "ok");
        ObjectNode integration = operation.putObject("x-amazon-apigateway-integration");
        integration.put("type", "mock");
        integration.putObject("requestTemplates").put("application/json", "{\"statusCode\":200}");
        integration.putObject("responses").putObject("default")
                .put("statusCode", "200")
                .putObject("responseTemplates").put("application/json", "{\"route\":\"" + route
                        + "\",\"callerAccount\":\"$context.identity.accountId\","
                        + "\"callerArn\":\"$context.identity.userArn\"}");
    }
}
