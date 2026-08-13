package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Pins the CreateBucket tagging path the terraform/tofu AWS provider actually uses.
 *
 * <p>The provider (hashicorp/aws 6.58.0, aws-sdk-go-v2 s3 1.106.2) applies an
 * {@code aws_s3_bucket}'s tags map by putting them inside the CreateBucket body's
 * {@code CreateBucketConfiguration/Tags}. It issues no PutBucketTagging on create,
 * so a CreateBucket handler that parses the body for LocationConstraint only drops
 * the tags outright: the apply succeeds, the bucket lands with {@code tags = {}} in
 * state, and every later plan proposes the same tag additions again.
 *
 * <p>The request bodies below are copied verbatim from a TF_LOG=TRACE capture of
 * {@code terraform apply} against Floci, so a future change to the CreateBucket
 * body parsing has to keep this exact shape working.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3CreateBucketTaggingIntegrationTest {

    private static final String TAGGED_BUCKET = "create-tags-provider-bucket";
    private static final String UNTAGGED_BUCKET = "create-tags-plain-bucket";
    private static final String REGIONAL_BUCKET = "create-tags-regional-bucket";

    // Verbatim provider request body: no LocationConstraint, tags only.
    private static final String PROVIDER_CREATE_BODY = "<CreateBucketConfiguration "
            + "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<Tags>"
            + "<Tag><Key>Owner</Key><Value>choudoufu</Value></Tag>"
            + "<Tag><Key>Purpose</Key><Value>regression</Value></Tag>"
            + "</Tags>"
            + "</CreateBucketConfiguration>";

    // A non-default region drives the LocationConstraint branch; tags must survive it.
    private static final String REGIONAL_CREATE_BODY = "<CreateBucketConfiguration "
            + "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<LocationConstraint>eu-west-1</LocationConstraint>"
            + "<Tags>"
            + "<Tag><Key>Owner</Key><Value>choudoufu</Value></Tag>"
            + "</Tags>"
            + "</CreateBucketConfiguration>";

    @Test
    @Order(1)
    void createBucketWithProviderTagsRoundTripsThroughGetBucketTagging() {
        given()
            .contentType("application/xml")
            .body(PROVIDER_CREATE_BODY)
        .when()
            .put("/" + TAGGED_BUCKET)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + TAGGED_BUCKET + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(containsString("<Value>choudoufu</Value>"))
            .body(containsString("<Key>Purpose</Key>"))
            .body(containsString("<Value>regression</Value>"));
    }

    @Test
    @Order(2)
    void createTimeTagsAreReplaceableByPutBucketTagging() {
        given()
            .body("""
                    <Tagging xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <TagSet>
                            <Tag><Key>Owner</Key><Value>rewritten</Value></Tag>
                        </TagSet>
                    </Tagging>
                    """)
        .when()
            .put("/" + TAGGED_BUCKET + "?tagging")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + TAGGED_BUCKET + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Value>rewritten</Value>"))
            .body(not(containsString("<Key>Purpose</Key>")));
    }

    @Test
    @Order(3)
    void createBucketWithLocationConstraintKeepsBothRegionAndTags() {
        given()
            .contentType("application/xml")
            .body(REGIONAL_CREATE_BODY)
        .when()
            .put("/" + REGIONAL_BUCKET)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + REGIONAL_BUCKET + "?location")
        .then()
            .statusCode(200)
            .body(containsString("eu-west-1"));

        given()
        .when()
            .get("/" + REGIONAL_BUCKET + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(containsString("<Value>choudoufu</Value>"));
    }

    @Test
    @Order(4)
    void createBucketWithoutTagsStaysUntagged() {
        given()
        .when()
            .put("/" + UNTAGGED_BUCKET)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + UNTAGGED_BUCKET + "?tagging")
        .then()
            .statusCode(200)
            .body(containsString("<TagSet></TagSet>"));
    }

    @Test
    @Order(5)
    void cleanUp() {
        for (String bucket : new String[] {TAGGED_BUCKET, REGIONAL_BUCKET, UNTAGGED_BUCKET}) {
            given()
            .when()
                .delete("/" + bucket)
            .then()
                .statusCode(204);
        }
    }
}
