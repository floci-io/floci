package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the custom authorizer routes the way the AWS SDKs do (REST-JSON under
 * {@code /authorizer}, {@code /authorizers/} and {@code /default-authorizer}) and checks the wire
 * shapes and errors AWS IoT returns.
 */
@QuarkusTest
class IotAuthorizerIntegrationTest {

    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:it-auth";
    private static final String ARN_PREFIX = "arn:aws:iot:us-east-1:000000000000:authorizer/";
    private static final String UNSIGNED = """
            {"authorizerFunctionArn": "%s", "signingDisabled": true}
            """.formatted(FUNCTION_ARN);

    private static void create(String name) {
        given().contentType("application/json").body(UNSIGNED)
        .when().post("/authorizer/" + name)
        .then().statusCode(200);
    }

    private static void delete(String name) {
        given().when().delete("/authorizer/" + name).then().statusCode(200).body(equalTo("{}"));
    }

    private static void setStatus(String name, String status) {
        given().contentType("application/json").body("{\"status\": \"" + status + "\"}")
        .when().put("/authorizer/" + name)
        .then().statusCode(200)
            .body("authorizerName", equalTo(name))
            .body("authorizerArn", equalTo(ARN_PREFIX + name));
    }

    @Test
    void createAndDescribeUseTheAwsWireShape() {
        given()
            .contentType("application/json")
            .body("""
                {"authorizerFunctionArn": "%s", "signingDisabled": true, "tags": [{"Key": "env", "Value": "it"}]}
                """.formatted(FUNCTION_ARN))
        .when()
            .post("/authorizer/it-auth-shape")
        .then()
            .statusCode(200)
            .body("size()", equalTo(2))
            .body("authorizerName", equalTo("it-auth-shape"))
            .body("authorizerArn", equalTo(ARN_PREFIX + "it-auth-shape"));

        String body = given()
        .when()
            .get("/authorizer/it-auth-shape")
        .then()
            .statusCode(200)
            .body("authorizerDescription.authorizerName", equalTo("it-auth-shape"))
            .body("authorizerDescription.authorizerArn", equalTo(ARN_PREFIX + "it-auth-shape"))
            .body("authorizerDescription.authorizerFunctionArn", equalTo(FUNCTION_ARN))
            .body("authorizerDescription.status", equalTo("INACTIVE"))
            .body("authorizerDescription.signingDisabled", equalTo(true))
            .body("authorizerDescription.enableCachingForHttp", equalTo(false))
            .body("authorizerDescription", hasKey("tokenKeyName"))
            .body("authorizerDescription.tokenKeyName", nullValue())
            .body("authorizerDescription", hasKey("tokenSigningPublicKeys"))
            .body("authorizerDescription.tokenSigningPublicKeys", nullValue())
            .body("authorizerDescription", not(hasKey("tags")))
            .extract().asString();
        assertTrue(body.matches(".*\"creationDate\":\\d\\.\\d+E9[,}].*"), body);
        JsonPath description = JsonPath.from(body);
        assertEquals(description.getString("authorizerDescription.creationDate"),
                description.getString("authorizerDescription.lastModifiedDate"));

        delete("it-auth-shape");
    }

    @Test
    void errorsCarryTheAwsTypeStatusAndMessage() {
        create("it-auth-err");

        given().contentType("application/json").body(UNSIGNED)
        .when().post("/authorizer/it-auth-err")
        .then()
            .statusCode(409)
            .header("X-Amzn-ErrorType", "ResourceAlreadyExistsException")
            .body("message", equalTo("Authorizer it-auth-err already exist for this account"))
            .body("resourceId", equalTo("it-auth-err"))
            .body("resourceArn", equalTo(ARN_PREFIX + "it-auth-err"));

        given().when().get("/authorizer/it-auth-missing")
        .then()
            .statusCode(404)
            .header("X-Amzn-ErrorType", "ResourceNotFoundException")
            .body("message", equalTo("Authorizer it-auth-missing not found"));

        given().when().get("/authorizer/{name}", "bad name!")
        .then()
            .statusCode(400)
            .header("X-Amzn-ErrorType", "InvalidRequestException")
            .body("message", equalTo("1 validation error detected: Value at 'authorizerName' failed to satisfy constraint: "
                    + "Member must satisfy regular expression pattern: [\\w=,@-]+"));

        given().contentType("application/json")
            .body("{\"authorizerFunctionArn\": \"not-an-arn\", \"signingDisabled\": true}")
        .when().post("/authorizer/it-auth-badarn")
        .then()
            .statusCode(400)
            .header("X-Amzn-ErrorType", "InvalidRequestException")
            .body("message", equalTo("Lambda function arn for authorizer it-auth-badarn is not in proper ARN syntax"));

        setStatus("it-auth-err", "ACTIVE");
        given().when().delete("/authorizer/it-auth-err")
        .then()
            .statusCode(400)
            .header("X-Amzn-ErrorType", "InvalidRequestException")
            .body("message", equalTo("Cannot delete authorizer it-auth-err in ACTIVE status. Update status to INACTIVE and delete."));

        setStatus("it-auth-err", "INACTIVE");
        delete("it-auth-err");

        given().contentType("application/json").body("{}")
        .when().post("/default-authorizer")
        .then()
            .statusCode(400)
            .header("X-Amzn-ErrorType", "InvalidRequestException")
            .body("message", equalTo("1 validation error detected: Value at 'authorizerName' failed to satisfy constraint: "
                    + "Member must not be null"));
    }

    @Test
    void listAuthorizersOrdersFiltersAndPagesOnTheSdkPath() {
        for (String name : List.of("it-list-1", "it-list-2", "it-list-3")) {
            create(name);
        }
        setStatus("it-list-2", "ACTIVE");

        given().queryParam("isAscendingOrder", true)
        .when().get("/authorizers/")
        .then().statusCode(200)
            .body("authorizers.authorizerName", contains("it-list-1", "it-list-2", "it-list-3"))
            .body("authorizers[0].size()", equalTo(2))
            .body("authorizers[0].authorizerArn", equalTo(ARN_PREFIX + "it-list-1"));
        given().queryParam("status", "INACTIVE")
        .when().get("/authorizers/")
        .then().statusCode(200)
            .body("authorizers.authorizerName", contains("it-list-3", "it-list-1"));

        List<String> paged = new ArrayList<>();
        String marker = null;
        do {
            RequestSpecification request = given().queryParam("pageSize", 1);
            if (marker != null) {
                request.queryParam("marker", marker);
            }
            JsonPath page = request
                .when().get("/authorizers/")
                .then().statusCode(200).body("authorizers", not(empty())).body("$", hasKey("nextMarker"))
                .extract().jsonPath();
            paged.addAll(page.getList("authorizers.authorizerName"));
            marker = page.getString("nextMarker");
        } while (marker != null && paged.size() < 10);
        assertEquals(List.of("it-list-3", "it-list-2", "it-list-1"), paged);
        assertNull(marker);

        given().queryParam("marker", "garbage")
        .when().get("/authorizers/")
        .then().statusCode(400)
            .header("X-Amzn-ErrorType", "InvalidRequestException")
            .body("message", equalTo("Invalid/Malformed marker passed for listAuthorizers"));

        setStatus("it-list-2", "INACTIVE");
        List.of("it-list-1", "it-list-2", "it-list-3").forEach(IotAuthorizerIntegrationTest::delete);
    }

    @Test
    void defaultAuthorizerIsSetDescribedAndCleared() {
        create("it-auth-default");

        given().contentType("application/json").body("{\"authorizerName\": \"it-auth-default\"}")
        .when().post("/default-authorizer")
        .then().statusCode(200)
            .body("size()", equalTo(2))
            .body("authorizerName", equalTo("it-auth-default"))
            .body("authorizerArn", equalTo(ARN_PREFIX + "it-auth-default"));

        given().when().get("/default-authorizer")
        .then().statusCode(200)
            .body("authorizerDescription.authorizerName", equalTo("it-auth-default"))
            .body("authorizerDescription.status", equalTo("INACTIVE"));

        given().when().delete("/authorizer/it-auth-default")
        .then().statusCode(409)
            .header("X-Amzn-ErrorType", "DeleteConflictException")
            .body("message", equalTo("Cannot delete default authorizer it-auth-default. Change default and retry delete."));

        given().when().delete("/default-authorizer").then().statusCode(200).body(equalTo("{}"));
        given().when().get("/default-authorizer")
        .then().statusCode(404)
            .header("X-Amzn-ErrorType", "ResourceNotFoundException")
            .body("message", equalTo("Default authorizer not found"));

        delete("it-auth-default");
    }

    @Test
    void tagsOnAnAuthorizerArnGoThroughTheIotTagRoutes() {
        String arn = ARN_PREFIX + "it-auth-tags";
        given().contentType("application/json")
            .body("""
                {"authorizerFunctionArn": "%s", "signingDisabled": true, "tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(FUNCTION_ARN))
        .when().post("/authorizer/it-auth-tags")
        .then().statusCode(200);

        given().contentType("application/json")
            .body("{\"resourceArn\": \"" + arn + "\", \"tags\": [{\"Key\": \"team\", \"Value\": \"a\"}]}")
        .when().post("/tags")
        .then().statusCode(200);
        given().contentType("application/json")
            .body("{\"resourceArn\": \"" + arn + "\", \"tagKeys\": [\"env\"]}")
        .when().post("/untag")
        .then().statusCode(200);

        given().queryParam("resourceArn", arn)
        .when().get("/tags")
        .then().statusCode(200)
            .body("tags.Key", contains("team"))
            .body("tags.Value", contains("a"));

        String missing = ARN_PREFIX + "it-auth-gone";
        given().queryParam("resourceArn", missing)
        .when().get("/tags")
        .then().statusCode(200).body("tags", empty());
        given().contentType("application/json")
            .body("{\"resourceArn\": \"" + missing + "\", \"tags\": [{\"Key\": \"k\", \"Value\": \"v\"}]}")
        .when().post("/tags")
        .then().statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Authorizer it-auth-gone not found"));

        delete("it-auth-tags");
    }

    @Test
    void authorizerTextOutsideTheArnResourceIsTaggedLikeAnyOtherIotArn() {
        for (String arn : List.of("arn:aws:iot:authorizer/:000000000000:x", "arn:aws:iot:us-east-1:000000000000:x")) {
            given().queryParam("resourceArn", arn)
            .when().get("/tags")
            .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Invalid resource ARN: " + arn));
            given().contentType("application/json")
                .body("{\"resourceArn\": \"" + arn + "\", \"tags\": [{\"Key\": \"k\", \"Value\": \"v\"}]}")
            .when().post("/tags")
            .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Invalid resource ARN: " + arn));
            given().contentType("application/json")
                .body("{\"resourceArn\": \"" + arn + "\", \"tagKeys\": [\"k\"]}")
            .when().post("/untag")
            .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Invalid resource ARN: " + arn));
        }

        given().contentType("application/json")
            .body("{\"resourceArn\": \"not-an-arn\", \"tagKeys\": [\"k\"]}")
        .when().post("/untag")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("Invalid resource ARN: not-an-arn"));
    }
}
