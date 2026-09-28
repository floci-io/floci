package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * An {@code AWS_IAM} method invoked through a custom domain. The caller signs the request it
 * actually sends, {@code Host: <domain>} and the base-path-mapped path, while the custom domain
 * filter rewrites the URI onto {@code /execute-api/...} before dispatch. Verification has to run
 * against the signed form, so the rewritten path must not leak into the canonical request.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayCustomDomainIamAuthIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final String STAGE = "prod";
    private static final String DOMAIN = "iam.example.com";
    private static final String BASE_PATH = "v1";

    private static String apiId;

    @Test
    @Order(0)
    void setup() {
        apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"custom-domain-iam-auth-test\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        createMockMethod(rootId, "iam", "AWS_IAM");

        String deploymentId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + STAGE + "\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"domainName\":\"" + DOMAIN + "\","
                        + "\"certificateArn\":\"arn:aws:acm:us-east-1:123456789012:certificate/abc\"}")
                .when().post("/domainnames")
                .then().statusCode(201)
                .body("regionalDomainName", equalTo(DOMAIN + ".regional.local"));
        given().contentType(ContentType.JSON)
                .body("{\"basePath\":\"" + BASE_PATH + "\",\"restApiId\":\"" + apiId + "\","
                        + "\"stage\":\"" + STAGE + "\"}")
                .when().post("/domainnames/" + DOMAIN + "/basepathmappings")
                .then().statusCode(201);
    }

    private static void createMockMethod(String rootId, String pathPart, String authorizationType) {
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"" + authorizationType + "\"}")
                .when().put(methodPath)
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put(methodPath + "/responses/200")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put(methodPath + "/integration")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":"
                        + "{\"application/json\":\"{\\\"reached\\\":\\\"integration\\\"}\"}}")
                .when().put(methodPath + "/integration/responses/200")
                .then().statusCode(201);
    }

    @Test
    @Order(10)
    void unsignedRequestThroughCustomDomainIsRejected() {
        given().header("Host", DOMAIN)
                .when().get(customDomainPath())
                .then().statusCode(403)
                .header("x-amzn-ErrorType", "MissingAuthenticationTokenException")
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test
    @Order(11)
    void requestSignedForTheBareDomainReachesTheIntegration() throws Exception {
        given().header("Host", DOMAIN)
                .headers(signedHeaders(customDomainPath(), DOMAIN))
                .when().get(customDomainPath())
                .then().statusCode(200)
                .body("reached", equalTo("integration"));
    }

    @Test
    @Order(12)
    void requestSignedForTheRegionalDomainReachesTheIntegration() throws Exception {
        String host = DOMAIN + ".regional.local:4566";
        given().header("Host", host)
                .headers(signedHeaders(customDomainPath(), host))
                .when().get(customDomainPath())
                .then().statusCode(200)
                .body("reached", equalTo("integration"));
    }

    @Test
    @Order(13)
    void signatureOverTheRewrittenExecuteApiPathIsRejected() throws Exception {
        String rewrittenPath = "/execute-api/" + apiId + "/" + STAGE + "/iam";
        given().header("Host", DOMAIN)
                .headers(signedHeaders(rewrittenPath, DOMAIN))
                .when().get(customDomainPath())
                .then().statusCode(403)
                .header("x-amzn-ErrorType", "InvalidSignatureException");
    }

    @Test
    @Order(14)
    void signatureForAnotherCustomDomainPathIsRejected() throws Exception {
        given().header("Host", DOMAIN)
                .headers(signedHeaders("/" + BASE_PATH + "/other", DOMAIN))
                .when().get(customDomainPath())
                .then().statusCode(403)
                .header("x-amzn-ErrorType", "InvalidSignatureException");
    }

    @Test
    @Order(999)
    void cleanup() {
        given().when().delete("/domainnames/" + DOMAIN);
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId);
        }
    }

    private static String customDomainPath() {
        return "/" + BASE_PATH + "/iam";
    }

    private static Map<String, String> signedHeaders(String path, String host) throws Exception {
        return ExecuteApiRequestSigner.signedHeaders(
                "GET", path, Map.of(), host, null, ACCESS_KEY, SECRET_KEY, REGION, Instant.now());
    }
}
