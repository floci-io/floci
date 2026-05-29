package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** REST tests for {@code /v2/vpclinks} CRUD + Integration {@code connectionId} round-trip. */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2VpcLinkRestTest {

    private static String vpcLinkId;
    private static String apiId;
    private static String integrationId;

    @BeforeAll
    static void configureRestAssured() {
        // Register encoder for application/x-amz-json-1.1 so the JSON 1.1 round-trip
        // below can post a raw body string.
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createVpcLink() {
        vpcLinkId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"my-vpc-link",
                         "subnetIds":["subnet-aaaa1111","subnet-bbbb2222"],
                         "securityGroupIds":["sg-cccc3333"],
                         "tags":{"Env":"test"}}
                        """)
                .when().post("/v2/vpclinks")
                .then()
                .statusCode(201)
                .body("vpcLinkId", notNullValue())
                .body("name", equalTo("my-vpc-link"))
                .body("vpcLinkStatus", equalTo("AVAILABLE"))
                .body("vpcLinkVersion", equalTo("V2"))
                .body("subnetIds", hasItems("subnet-aaaa1111", "subnet-bbbb2222"))
                .body("securityGroupIds", hasItem("sg-cccc3333"))
                .body("tags.Env", equalTo("test"))
                .extract().path("vpcLinkId");
    }

    @Test
    @Order(2)
    void getVpcLink() {
        given()
                .when().get("/v2/vpclinks/" + vpcLinkId)
                .then()
                .statusCode(200)
                .body("vpcLinkId", equalTo(vpcLinkId))
                .body("name", equalTo("my-vpc-link"));
    }

    @Test
    @Order(3)
    void listVpcLinksIncludesCreated() {
        given()
                .when().get("/v2/vpclinks")
                .then()
                .statusCode(200)
                .body("items.vpcLinkId", hasItem(vpcLinkId));
    }

    @Test
    @Order(4)
    void getNonexistentVpcLinkReturns404() {
        given()
                .when().get("/v2/vpclinks/does-not-exist")
                .then()
                .statusCode(404);
    }

    @Test
    @Order(10)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"vpc-link-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");
    }

    @Test
    @Order(11)
    void createIntegrationWithVpcLinkEchoesConnectionFields() {
        // VPC_LINK + connectionId pointing at our VpcLink + listener-ARN integrationUri:
        // CreateIntegration must persist + echo connectionType, connectionId, integrationUri.
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY",
                         "integrationUri":"arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/my-lb/abcdef0123456789/0011223344556677",
                         "connectionType":"VPC_LINK",
                         "connectionId":"%s",
                         "payloadFormatVersion":"1.0",
                         "integrationMethod":"ANY"}
                        """.formatted(vpcLinkId))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .body("integrationType", equalTo("HTTP_PROXY"))
                .body("connectionType", equalTo("VPC_LINK"))
                .body("connectionId", equalTo(vpcLinkId))
                .body("integrationUri", containsString("listener/app/my-lb"))
                .extract().path("integrationId");
    }

    @Test
    @Order(12)
    void getIntegrationStillEchoesConnectionId() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200)
                .body("connectionType", equalTo("VPC_LINK"))
                .body("connectionId", equalTo(vpcLinkId));
    }

    @Test
    @Order(13)
    void updateIntegrationPreservesConnectionId() {
        // PATCH unrelated field; connectionType + connectionId must survive merge-patch.
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationMethod":"GET"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200)
                .body("integrationMethod", equalTo("GET"))
                .body("connectionType", equalTo("VPC_LINK"))
                .body("connectionId", equalTo(vpcLinkId));
    }

    @Test
    @Order(14)
    void connectionIdRoundTripsViaJson11() {
        // Mirrors IntegrationConnectionTypeAndAuthorizerSimpleResponsesTest#connectionTypeReturnedViaJson11
        // to make sure the JSON 1.1 (X-Amz-Target) response path also emits ConnectionId.
        String amzJson = "application/x-amz-json-1.1";
        String authHeader = "AWS4-HMAC-SHA256 Credential=test/20260520/us-east-1/apigateway/aws4_request";
        String targetPrefix = "AmazonApiGatewayV2.";

        String localApiId = given()
                .contentType(amzJson)
                .header("X-Amz-Target", targetPrefix + "CreateApi")
                .header("Authorization", authHeader)
                .body("""
                        {"Name":"conn-id-json11","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then().statusCode(201)
                .extract().path("ApiId");

        String localIntegrationId = given()
                .contentType(amzJson)
                .header("X-Amz-Target", targetPrefix + "CreateIntegration")
                .header("Authorization", authHeader)
                .body("""
                        {"ApiId":"%s","IntegrationType":"HTTP_PROXY","IntegrationUri":"https://example.com","IntegrationMethod":"GET","ConnectionType":"VPC_LINK","ConnectionId":"%s","PayloadFormatVersion":"1.0"}
                        """.formatted(localApiId, vpcLinkId))
                .when().post("/")
                .then().statusCode(201)
                .body("ConnectionType", equalTo("VPC_LINK"))
                .body("ConnectionId", equalTo(vpcLinkId))
                .extract().path("IntegrationId");

        given()
                .contentType(amzJson)
                .header("X-Amz-Target", targetPrefix + "GetIntegration")
                .header("Authorization", authHeader)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s"}
                        """.formatted(localApiId, localIntegrationId))
                .when().post("/")
                .then().statusCode(200)
                .body("ConnectionId", equalTo(vpcLinkId));

        // cleanup the locally-scoped api so it doesn't leak into the next test
        given().when().delete("/v2/apis/" + localApiId).then().statusCode(anyOf(equalTo(204), equalTo(404)));
    }

    @Test
    @Order(20)
    void deleteVpcLink() {
        given()
                .when().delete("/v2/vpclinks/" + vpcLinkId)
                .then()
                .statusCode(202);
    }

    @Test
    @Order(21)
    void getDeletedVpcLinkReturns404() {
        given()
                .when().get("/v2/vpclinks/" + vpcLinkId)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(99)
    void cleanup() {
        if (integrationId != null) {
            given().when().delete("/v2/apis/" + apiId + "/integrations/" + integrationId)
                    .then().statusCode(anyOf(equalTo(204), equalTo(404)));
        }
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId)
                    .then().statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
