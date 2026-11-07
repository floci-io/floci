package io.floci.aws.services.apigateway;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testutil.ExecuteApiRequestSigner;
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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A custom-domain route must resolve its resource owner before request account initialization.
 * The distinct caller signs the original host/path. This tests routing and authentication only,
 * not cross-account IAM policy evaluation.
 */
@QuarkusTest
class ApiGatewayCustomDomainOwnerRoutingTest {

    private static final String OWNER = "111122223333";
    private static final String CALLER = "444455556666";
    private static final String REGION = "us-east-1";
    private static final String STAGE = "prod";
    private static final String PATH = "/status";
    private static final String BODY = "{\"value\":\"original\"}";

    @Inject
    IamService iamService;

    @Inject
    ApiGatewayService apiGatewayService;

    private String apiId;
    private String domain;
    private String user;
    private AccessKey callerKey;
    private boolean domainCreated;

    @BeforeEach
    void createOwnedApiAndDistinctCaller() {
        String suffix = UUID.randomUUID().toString();
        user = "domain-route-" + suffix;
        domain = "route-" + suffix + ".example.test";
        callerKey = RequestScopes.callAs(CALLER, REGION, () -> {
            iamService.createUser(user, "/");
            return iamService.createAccessKey(user);
        });
        assertEquals(CALLER, iamService.resolveAccountId(callerKey.getAccessKeyId()).orElseThrow());

        apiId = management(OWNER).body("{\"name\":\"owned-domain-route\"}")
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = management(OWNER).get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = management(OWNER).body("{\"pathPart\":\"status\"}")
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST";
        management(OWNER).body("{\"authorizationType\":\"AWS_IAM\"}")
                .put(method).then().statusCode(201);
        management(OWNER).body("{\"responseParameters\":{}}")
                .put(method + "/responses/200").then().statusCode(201);
        management(OWNER).body("""
                {"type":"MOCK","requestTemplates":{"application/json":"{\\"statusCode\\":200}"}}
                """).put(method + "/integration").then().statusCode(201);
        management(OWNER).body("""
                {"selectionPattern":"","responseTemplates":{
                  "application/json":"{\\"reached\\":\\"account-owned-api\\"}"}}
                """).put(method + "/integration/responses/200").then().statusCode(201);
        String deployment = management(OWNER).body("{\"description\":\"owner-route\"}")
                .post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        management(OWNER).body(Map.of("stageName", STAGE, "deploymentId", deployment))
                .post("/restapis/" + apiId + "/stages").then().statusCode(201);
        management(OWNER).body(Map.of(
                "domainName", domain, "endpointConfiguration", Map.of("types", List.of("REGIONAL"))))
                .post("/domainnames").then().statusCode(201);
        domainCreated = true;
        management(OWNER).body(Map.of("restApiId", apiId, "stage", STAGE))
                .post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);

        management(OWNER).get("/domainnames/" + domain + "/basepathmappings")
                .then().statusCode(200).body("item[0].basePath", equalTo("(none)"))
                .body("item[0].restApiId", equalTo(apiId)).body("item[0].stage", equalTo(STAGE));
        management(CALLER).get("/restapis/" + apiId).then().statusCode(404);
        management(CALLER).get("/domainnames/" + domain).then().statusCode(404);
    }

    @Test
    void nonDefaultOwnerRoutesBeforeAccountContextAndKeepsTheOriginalSignature() throws Exception {
        String virtualHost = apiId + ".execute-api." + REGION + ".amazonaws.com";
        String virtualPath = "/" + STAGE + PATH;
        // This control proves the API, caller credential and signature work before testing domain lookup.
        given().contentType(ContentType.JSON).header("Host", virtualHost)
                .headers(sign(virtualPath, virtualHost)).body(BODY).post(virtualPath)
                .then().statusCode(200).body("reached", equalTo("account-owned-api"));

        Map<String, String> originalSignature = sign(PATH, domain);
        given().contentType(ContentType.JSON).header("Host", domain)
                .headers(originalSignature).body(BODY).post(PATH)
                .then().statusCode(200).body("reached", equalTo("account-owned-api"));

        given().contentType(ContentType.JSON).header("Host", domain)
                .headers(originalSignature).body("{\"value\":\"tampered\"}").post(PATH)
                .then().statusCode(403).header("x-amzn-ErrorType", "InvalidSignatureException");
        given().contentType(ContentType.JSON).header("Host", domain)
                .headers(sign("/different", domain)).body(BODY).post(PATH)
                .then().statusCode(403).header("x-amzn-ErrorType", "InvalidSignatureException");
        given().contentType(ContentType.JSON).header("Host", domain)
                .headers(sign(PATH, "other.example.test")).body(BODY).post(PATH)
                .then().statusCode(403).header("x-amzn-ErrorType", "InvalidSignatureException");
        given().contentType(ContentType.JSON).header("Host", domain)
                .headers(sign("/execute-api/" + apiId + "/" + STAGE + PATH, domain)).body(BODY).post(PATH)
                .then().statusCode(403).header("x-amzn-ErrorType", "InvalidSignatureException");
        management(CALLER).get("/restapis/" + apiId).then().statusCode(404);
    }

    @ParameterizedTest
    @CsvSource({
            "444455556666,us-east-1,false", "111122223333,us-west-2,false",
            "444455556666,us-east-1,true", "111122223333,us-west-2,true"
    })
    void mappedRestApiDoesNotBecomeAmbiguousWhenItsIdExistsElsewhere(
            String otherAccount, String otherRegion, boolean legacyMapping)
            throws Exception {
        if (legacyMapping) {
            RequestScopes.runAs(OWNER, REGION, () ->
                    apiGatewayService.getBasePathMapping(REGION, domain, "(none)").setApiType(null));
        }
        RequestScopes.runAs(otherAccount, otherRegion, () -> apiGatewayService.createRestApi(
                otherRegion, Map.of("name", "foreign-collision", "tags", Map.of("floci:override-id", apiId))));
        try {
            given().contentType(ContentType.JSON).header("Host", domain)
                    .headers(sign(PATH, domain)).body(BODY).post(PATH)
                    .then().statusCode(200).body("reached", equalTo("account-owned-api"));
            given().post("/execute-api/" + apiId + "/" + STAGE + PATH).then().statusCode(409);
        } finally {
            RequestScopes.runAs(otherAccount, otherRegion,
                    () -> apiGatewayService.deleteRestApi(otherRegion, apiId));
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1", "us-west-2"})
    void mappedHttpApiCannotBeClaimedByAForeignRestApiWithTheSameId(String httpRegion) throws Exception {
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            byte[] response = "{\"reached\":\"mapped-http-api\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        target.start();
        String httpDomain = "http-" + domain;
        String httpApi = null;
        boolean httpDomainCreated = false;
        boolean collisionCreated = false;
        try {
            httpApi = management(OWNER, httpRegion).body(Map.of("name", "mapped-http", "protocolType", "HTTP"))
                    .post("/v2/apis").then().statusCode(201).extract().path("apiId");
            String integration = management(OWNER, httpRegion).body(Map.of(
                    "integrationType", "HTTP_PROXY", "integrationMethod", "ANY",
                    "integrationUri", "http://127.0.0.1:" + target.getAddress().getPort()))
                    .post("/v2/apis/" + httpApi + "/integrations").then().statusCode(201)
                    .extract().path("integrationId");
            management(OWNER, httpRegion).body(
                    Map.of("routeKey", "GET /status", "target", "integrations/" + integration))
                    .post("/v2/apis/" + httpApi + "/routes").then().statusCode(201);
            management(OWNER, httpRegion).body(Map.of("stageName", STAGE, "autoDeploy", true))
                    .post("/v2/apis/" + httpApi + "/stages").then().statusCode(201);
            management(OWNER, httpRegion).body(Map.of("domainName", httpDomain,
                    "domainNameConfigurations", List.of(Map.of("endpointType", "REGIONAL"))))
                    .post("/v2/domainnames").then().statusCode(201);
            httpDomainCreated = true;
            management(OWNER, httpRegion).body(Map.of("apiId", httpApi, "stage", STAGE))
                    .post("/v2/domainnames/" + httpDomain + "/apimappings").then().statusCode(201);
            management(CALLER).body(Map.of("name", "foreign-rest",
                    "tags", Map.of("floci:override-id", httpApi)))
                    .post("/restapis").then().statusCode(201);
            collisionCreated = true;

            given().header("Host", httpDomain).get(PATH)
                    .then().statusCode(200).body("reached", equalTo("mapped-http-api"));
            management(CALLER, httpRegion).get("/v2/apis/" + httpApi).then().statusCode(404);
        } finally {
            target.stop(0);
            if (collisionCreated) {
                management(CALLER).delete("/restapis/" + httpApi).then().statusCode(202);
            }
            if (httpDomainCreated) {
                management(OWNER, httpRegion).delete("/v2/domainnames/" + httpDomain).then().statusCode(204);
            }
            if (httpApi != null) {
                management(OWNER, httpRegion).delete("/v2/apis/" + httpApi).then().statusCode(204);
            }
        }
    }

    private Map<String, String> sign(String path, String host) throws Exception {
        return ExecuteApiRequestSigner.signedHeaders(
                "POST", path, Map.of(), host, BODY.getBytes(StandardCharsets.UTF_8),
                callerKey.getAccessKeyId(), callerKey.getSecretAccessKey(), REGION, Instant.now());
    }

    private static RequestSpecification management(String account) {
        return management(account, REGION);
    }

    private static RequestSpecification management(String account, String region) {
        // Management account selection follows the existing public API tests; data-plane calls use real HMACs.
        return given().contentType(ContentType.JSON).header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + account + "/20261008/" + region + "/apigateway/aws4_request");
    }

    @AfterEach
    void removeOnlyThisTestGraph() {
        List<Executable> cleanup = new ArrayList<>();
        if (domainCreated) {
            cleanup.add(() -> management(OWNER).delete("/domainnames/" + domain).then().statusCode(202));
        }
        if (apiId != null) {
            cleanup.add(() -> management(OWNER).delete("/restapis/" + apiId).then().statusCode(202));
        }
        if (callerKey != null) {
            cleanup.add(() -> RequestScopes.runAs(CALLER, REGION,
                    () -> iamService.deleteAccessKey(user, callerKey.getAccessKeyId())));
        }
        if (user != null) {
            cleanup.add(() -> RequestScopes.runAs(CALLER, REGION, () -> iamService.deleteUser(user)));
        }
        assertAll("owned test resource cleanup", cleanup);
    }
}
