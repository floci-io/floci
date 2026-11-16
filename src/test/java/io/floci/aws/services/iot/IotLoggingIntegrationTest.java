package io.floci.aws.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class IotLoggingIntegrationTest {

    private static String auth(String account) {
        return auth(account, "us-east-1");
    }

    private static String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/" + region + "/iot/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
    }

    @Test
    void getBeforeAnySetIsNotConfigured() {
        given()
            .header("Authorization", auth("111100000101"))
        .when()
            .get("/v2LoggingOptions")
        .then()
            .statusCode(404)
            .header("x-amzn-ErrorType", equalTo("NotConfiguredException"));
    }

    @Test
    void setThenGetRoundTripsTheOptions() {
        String auth = auth("111100000102");
        String roleArn = "arn:aws:iam::111100000102:role/iot-logging";
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("""
                {"roleArn": "%s", "defaultLogLevel": "INFO", "disableAllLogs": true}
                """.formatted(roleArn))
        .when()
            .post("/v2LoggingOptions")
        .then()
            .statusCode(200)
            .body(equalTo("{}"));

        given()
            .header("Authorization", auth)
        .when()
            .get("/v2LoggingOptions")
        .then()
            .statusCode(200)
            .body("$", aMapWithSize(3))
            .body("roleArn", equalTo(roleArn))
            .body("defaultLogLevel", equalTo("INFO"))
            .body("disableAllLogs", equalTo(true));
    }

    @Test
    void initialSetupWithoutARoleIsRejected() {
        String auth = auth("111100000103");
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("""
                {"defaultLogLevel": "ERROR"}
                """)
        .when()
            .post("/v2LoggingOptions")
        .then()
            .statusCode(400)
            .header("x-amzn-ErrorType", equalTo("InvalidRequestException"));

        given()
            .header("Authorization", auth)
        .when()
            .get("/v2LoggingOptions")
        .then()
            .statusCode(404);
    }

    @Test
    void laterSetsKeepTheOptionsTheyOmit() {
        String auth = auth("111100000104");
        String roleArn = "arn:aws:iam::111100000104:role/iot-logging";
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("""
                {"roleArn": "%s", "defaultLogLevel": "DEBUG"}
                """.formatted(roleArn))
        .when()
            .post("/v2LoggingOptions")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("""
                {"disableAllLogs": true}
                """)
        .when()
            .post("/v2LoggingOptions")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", auth)
        .when()
            .get("/v2LoggingOptions")
        .then()
            .statusCode(200)
            .body("roleArn", equalTo(roleArn))
            .body("defaultLogLevel", equalTo("DEBUG"))
            .body("disableAllLogs", equalTo(true));
    }

    @Test
    void eachAccountAndRegionKeepsItsOwnOptions() {
        given()
            .header("Authorization", auth("111100000105"))
            .contentType("application/json")
            .body("""
                {"roleArn": "arn:aws:iam::111100000105:role/iot-logging", "defaultLogLevel": "INFO"}
                """)
        .when()
            .post("/v2LoggingOptions")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", auth("111100000106"))
        .when()
            .get("/v2LoggingOptions")
        .then()
            .statusCode(404);

        given()
            .header("Authorization", auth("111100000105", "eu-west-1"))
        .when()
            .get("/v2LoggingOptions")
        .then()
            .statusCode(404);
    }
}
