package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.nullValue;

/** Create, Get, Update, Delete and List for a code signing configuration. */
@QuarkusTest
class LambdaCodeSigningConfigLifecycleIntegrationTest {

    private static final String BASE = "/2020-04-22/code-signing-configs";
    private static final String PROFILE =
            "arn:aws:signer:us-east-1:000000000000:/signing-profiles/prof/AbCdEfGhIj";

    private static String create(String body) {
        return given().contentType("application/json").body(body)
            .when().post(BASE)
            .then().statusCode(201)
            .extract().path("CodeSigningConfig.CodeSigningConfigArn");
    }

    @Test
    void createReportsTheModelsIdAndArnShapeAndDefaultsThePolicy() {
        given()
            .contentType("application/json")
            .body("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]},"
                    + "\"Description\":\"release artifacts\"}")
        .when()
            .post(BASE)
        .then()
            .statusCode(201)
            .body("CodeSigningConfig.CodeSigningConfigId", matchesRegex("csc-[a-z0-9]{17}"))
            .body("CodeSigningConfig.CodeSigningConfigArn",
                    matchesRegex("arn:aws:lambda:us-east-1:\\d{12}:code-signing-config:csc-[a-z0-9]{17}"))
            .body("CodeSigningConfig.Description", is("release artifacts"))
            .body("CodeSigningConfig.AllowedPublishers.SigningProfileVersionArns", contains(PROFILE))
            // The model documents Warn as the default when the request names no policy.
            .body("CodeSigningConfig.CodeSigningPolicies.UntrustedArtifactOnDeployment", is("Warn"))
            .body("CodeSigningConfig.LastModified",
                    matchesRegex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    void getReadsBackWhatCreateStored() {
        String arn = create("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]},"
                + "\"CodeSigningPolicies\":{\"UntrustedArtifactOnDeployment\":\"Enforce\"}}");

        given().when().get(BASE + "/" + arn).then()
            .statusCode(200)
            .body("CodeSigningConfig.CodeSigningConfigArn", is(arn))
            .body("CodeSigningConfig.CodeSigningPolicies.UntrustedArtifactOnDeployment", is("Enforce"));
    }

    @Test
    void updateAppliesOnlyTheMembersTheRequestNames() {
        String arn = create("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]},"
                + "\"Description\":\"first\"}");

        // Description alone. AllowedPublishers is omitted and must survive.
        given().contentType("application/json").body("{\"Description\":\"second\"}")
            .when().put(BASE + "/" + arn)
            .then().statusCode(200)
            .body("CodeSigningConfig.Description", is("second"))
            .body("CodeSigningConfig.AllowedPublishers.SigningProfileVersionArns", contains(PROFILE));
    }

    @Test
    void aRejectedUpdateChangesNothing() {
        String arn = create("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]},"
                + "\"Description\":\"before\"}");

        // Valid Description, invalid AllowedPublishers. The description must not survive the 400.
        given().contentType("application/json")
            .body("{\"Description\":\"after\",\"AllowedPublishers\":{\"SigningProfileVersionArns\":[]}}")
            .when().put(BASE + "/" + arn)
            .then().statusCode(400)
            .body("__type", is("InvalidParameterValueException"));

        given().when().get(BASE + "/" + arn).then()
            .statusCode(200)
            .body("CodeSigningConfig.Description", is("before"));

        // The same shape with the policy as the rejected member.
        given().contentType("application/json")
            .body("{\"Description\":\"after\","
                    + "\"CodeSigningPolicies\":{\"UntrustedArtifactOnDeployment\":\"Ignore\"}}")
            .when().put(BASE + "/" + arn)
            .then().statusCode(400);

        given().when().get(BASE + "/" + arn).then()
            .statusCode(200)
            .body("CodeSigningConfig.Description", is("before"));
    }

    @Test
    void listReturnsTheConfigsAndDeleteRemovesOne() {
        String arn = create("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]}}");

        given().when().get(BASE).then().statusCode(200)
            .body("CodeSigningConfigs.CodeSigningConfigArn", hasItem(arn));

        given().when().delete(BASE + "/" + arn).then().statusCode(204);
        given().when().get(BASE + "/" + arn).then().statusCode(404)
            .body("__type", is("ResourceNotFoundException"));
        given().when().delete(BASE + "/" + arn).then().statusCode(404);
    }

    @Test
    void anUnknownConfigIsNotFoundAndAKnownOneListsNoFunctions() {
        String arn = create("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]}}");

        // A config now exists, so the functions route reports an empty attachment list rather than
        // the 404 it had to give when no config could exist at all.
        given().when().get(BASE + "/" + arn + "/functions").then()
            .statusCode(200)
            .body("FunctionArns", is(List.of()))
            .body("NextMarker", nullValue());

        String missing = "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-abcdefghij1234567";
        given().when().get(BASE + "/" + missing + "/functions").then().statusCode(404);
    }

    @Test
    void allowedPublishersIsRequiredAndBounded() {
        given().contentType("application/json").body("{\"Description\":\"no publishers\"}")
            .when().post(BASE).then().statusCode(400)
            .body("__type", is("InvalidParameterValueException"));

        given().contentType("application/json")
            .body("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[]}}")
            .when().post(BASE).then().statusCode(400);

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            many.append(i > 0 ? "," : "").append('"').append(PROFILE).append('"');
        }
        given().contentType("application/json")
            .body("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[" + many + "]}}")
            .when().post(BASE).then().statusCode(400);
    }

    @Test
    void anUnmodelledPolicyValueIsRejected() {
        given().contentType("application/json")
            .body("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + PROFILE + "\"]},"
                    + "\"CodeSigningPolicies\":{\"UntrustedArtifactOnDeployment\":\"Ignore\"}}")
            .when().post(BASE).then().statusCode(400)
            .body("__type", is("InvalidParameterValueException"));
    }
}
