package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * ResourceArn parsing and check order on the SES V2 tag endpoints, as probed against AWS.
 */
@QuarkusTest
class SesTagArnV2IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";
    private static final String PREFIX = "arn:aws:ses:us-east-1:000000000000:";
    private static final String BARE_VALIDATION = "{\"__type\":\"ValidationException\",\"message\":null}";

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "invalid-arn",
        "arn:",
        "arn:aws:ses:us-east-1:000000000000",
        "arn::ses:us-east-1:000000000000:configuration-set/tagarn-any",
        "arn:aws::us-east-1:000000000000:configuration-set/tagarn-any",
        "arn:aws:ses:us-east-1::configuration-set/tagarn-any",
        "arn:aws:ses:us-east-1:000000000000:",
        "arn:aws:ses:us-east-1:000000000000:configuration-set",
        "arn:aws:ses:us-east-1:000000000000:configuration-set/",
        "arn:aws:ses:us-east-1:000000000000:configuration-set/ ",
        "arn:aws:ses:us-east-1:000000000000:configuration-set/tagarn-any:extra",
        "arn:aws:ses:us-east-1:000000000000:configuration-set/tagarn-any/extra",
        "arn:aws:ses:us-east-1:000000000000:configuration-set:tagarn-any",
        "arn:aws:ses:us-east-1:000000000000:foo/bar",
        "arn:aws:ses:us-east-1:000000000000:dedicated-ip-pool/a:b",
    })
    void malformedArn_isOneFormatErrorOnEveryOperation(String arn) {
        assertExpectedFormatError(listTags(arn));
        assertExpectedFormatError(tag(arn));
        assertExpectedFormatError(untag(arn));
    }

    @Test
    void partitionAndService_addressTheSameResource() {
        String name = createConfigurationSet("tagarn-alias", "keep", "yes");

        for (String alias : new String[] {
            "arn:aws-cn:ses:us-east-1:000000000000:configuration-set/" + name,
            "arn:bogus:ses:us-east-1:000000000000:configuration-set/" + name,
            "arn:aws:s3:us-east-1:000000000000:configuration-set/" + name,
        }) {
            listTags(alias).then().statusCode(200)
                .body("Tags", hasSize(1))
                .body("Tags[0].Key", equalTo("keep"));
        }

        tagWith("arn:aws:s3:us-east-1:000000000000:configuration-set/" + name, "via", "s3")
            .then().statusCode(200);
        listTags(PREFIX + "configuration-set/" + name).then().statusCode(200)
            .body("Tags.Key", hasItem("via"));
    }

    @Test
    void emptyRegion_readsAsAnotherRegion() {
        String name = createConfigurationSet("tagarn-noregion", "keep", "yes");
        String arn = "arn:aws:ses::000000000000:configuration-set/" + name;

        listTags(arn).then().statusCode(200).body("Tags", hasSize(0));
        tag(arn).then().statusCode(400).body("message", equalTo("Failed to tag resource"));
        untag(arn).then().statusCode(400).body("message", equalTo("Failed to untag resource"));
    }

    @Test
    void malformedAccount_isTheDifferentAccountError() {
        String arn = "arn:aws:ses:us-east-1:12345:configuration-set/tagarn-any";

        listTags(arn).then().statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Operations on a resource created in a different account is not allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b", "bad name", "badé",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidConfigurationSetName_isMessagelessValidation(String name) {
        String arn = PREFIX + "configuration-set/" + name;

        listTags(arn).then().statusCode(400).body(equalTo(BARE_VALIDATION));
        tag(arn).then().statusCode(400).body(equalTo(BARE_VALIDATION));
        untag(arn).then().statusCode(400).body(equalTo(BARE_VALIDATION));
    }

    @Test
    void differentAccount_winsOverInvalidName() {
        listTags("arn:aws:ses:us-east-1:111111111111:configuration-set/bad name").then().statusCode(400)
            .body("message", equalTo("Operations on a resource created in a different account is not allowed"));
    }

    @Test
    void missingResource_winsOverRegionMismatchOnTagAndUntag() {
        String arn = "arn:aws:ses:us-west-2:000000000000:configuration-set/tagarn-nowhere";

        tag(arn).then().statusCode(404)
            .body("message", equalTo("No ConfigurationSet present with name: tagarn-nowhere"));
        untag(arn).then().statusCode(404)
            .body("message", equalTo("No ConfigurationSet present with name: tagarn-nowhere"));
    }

    @Test
    void omittedResourceArn_onTagAndUntag_isMessagelessValidation() {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Tags\": [{\"Key\": \"k\", \"Value\": \"v\"}]}")
        .when()
            .post("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(equalTo(BARE_VALIDATION));

        given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("TagKeys", "k")
        .when()
            .delete("/v2/email/tags")
        .then()
            .statusCode(400)
            .body(equalTo(BARE_VALIDATION));
    }

    @Test
    void omittedResourceArn_onList_staysAClientError() {
        // AWS answers this with a 500 InternalFailure; Floci keeps a 400 on purpose.
        given()
            .header("Authorization", AUTH_HEADER)
        .when()
            .get("/v2/email/tags")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("ResourceArn is required."));
    }

    private static void assertExpectedFormatError(Response response) {
        response.then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("ResourceArn is not the expected format"));
    }

    private static String createConfigurationSet(String prefix, String key, String value) {
        String name = prefix + "-" + System.nanoTime();
        given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ConfigurationSetName": "%s", "Tags": [{"Key": "%s", "Value": "%s"}]}
                """.formatted(name, key, value))
        .when()
            .post("/v2/email/configuration-sets")
        .then()
            .statusCode(200);
        return name;
    }

    private static Response listTags(String arn) {
        return given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
        .when()
            .get("/v2/email/tags");
    }

    private static Response tag(String arn) {
        return tagWith(arn, "probe", "1");
    }

    private static Response tagWith(String arn, String key, String value) {
        return given()
            .contentType("application/json")
            .header("Authorization", AUTH_HEADER)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "%s", "Value": "%s"}]}
                """.formatted(arn, key, value))
        .when()
            .post("/v2/email/tags");
    }

    private static Response untag(String arn) {
        return given()
            .header("Authorization", AUTH_HEADER)
            .queryParam("ResourceArn", arn)
            .queryParam("TagKeys", "probe")
        .when()
            .delete("/v2/email/tags");
    }
}
