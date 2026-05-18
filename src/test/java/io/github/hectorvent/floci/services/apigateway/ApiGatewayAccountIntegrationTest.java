package io.github.hectorvent.floci.services.apigateway;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;
import io.restassured.http.ContentType;

@QuarkusTest
class ApiGatewayAccountIntegrationTest {

  @BeforeEach
  void resetAccount() {
    String removePatch = "{\"patchOperations\":[{\"op\":\"remove\",\"path\":\"/cloudwatchRoleArn\"}]}";
    try {
      given().contentType(ContentType.JSON).body(removePatch).when().patch("/account");
    } catch (Exception ignored) {
    }
  }

    @Test
    void getAccount_returnsDefaultShape() {
        given()
                .when().get("/account")
                .then()
                .statusCode(200)
                .body("apiKeyVersion", notNullValue())
          .body("features", hasItem("UsagePlans"))
            .body("throttleSettings.burstLimit", equalTo(5000))
            .body("throttleSettings.rateLimit", equalTo(10000.0f));
    }

    @Test
    void updateAccount_updatesCloudwatchRoleArn() {
        String arn = "arn:aws:iam::123456789012:role/apigAwsProxyRole";
        String patch = """
                {
                  "patchOperations": [
                    {
                      "op": "replace",
                      "path": "/cloudwatchRoleArn",
                      "value": "%s"
                    }
                  ]
                }
                """.formatted(arn);

        given()
                .contentType(ContentType.JSON)
                .body(patch)
                .when().patch("/account")
                .then()
                .statusCode(200)
                .body("cloudwatchRoleArn", equalTo(arn));

        given()
                .when().get("/account")
                .then()
                .statusCode(200)
                .body("cloudwatchRoleArn", equalTo(arn));
    }

    @Test
    void updateAccount_rejectsThrottleSettingsPatch() {
        String patch = """
                {
                  "patchOperations": [
                    {
                      "op": "replace",
                      "path": "/throttleSettings/rateLimit",
                      "value": "100"
                    }
                  ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(patch)
                .when().patch("/account")
                .then()
                .statusCode(400);
    }

    @Test
    void updateAccount_removesCloudwatchRoleArn() {
        String arn = "arn:aws:iam::123456789012:role/apigAwsProxyRole";
        String setPatch = """
                {
                  "patchOperations": [
                    {
                      "op": "replace",
                      "path": "/cloudwatchRoleArn",
                      "value": "%s"
                    }
                  ]
                }
                """.formatted(arn);

        String removePatch = """
                {
                  "patchOperations": [
                    {
                      "op": "remove",
                      "path": "/cloudwatchRoleArn"
                    }
                  ]
                }
                """;

        given().contentType(ContentType.JSON).body(setPatch)
                .when().patch("/account")
                .then().statusCode(200).body("cloudwatchRoleArn", equalTo(arn));

        given().contentType(ContentType.JSON).body(removePatch)
                .when().patch("/account")
                .then().statusCode(200).body("cloudwatchRoleArn", nullValue());
    }

    @Test
    void updateAccount_rejectsUnsupportedPatchPath() {
        String patch = """
                {
                  "patchOperations": [
                    {
                      "op": "replace",
                      "path": "/apiKeyVersion",
                      "value": "5"
                    }
                  ]
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(patch)
                .when().patch("/account")
                .then()
                .statusCode(400);
    }

                @Test
                void updateAccount_rejectsNullPatchOperation() {
              String patch = """
                {
                  "patchOperations": [null]
                }
                """;

              given()
                .contentType(ContentType.JSON)
                .body(patch)
                .when().patch("/account")
                .then()
                .statusCode(400);
                }

                @Test
                void updateAccount_rejectsNonObjectPatchOperation() {
              String patch = """
                {
                  "patchOperations": ["invalid"]
                }
                """;

              given()
                .contentType(ContentType.JSON)
                .body(patch)
                .when().patch("/account")
                .then()
                .statusCode(400);
                }

          @Test
          void updateAccount_rejectsEmptyBody() {
            given()
                .contentType(ContentType.JSON)
                .body("")
                .when().patch("/account")
                .then()
                .statusCode(400);
          }
}
