package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Regression test for intentius/choudoufu#306: real S3 Control TagResource
 * is a merge/upsert - it adds or updates only the tags named in the
 * request, leaving every other existing tag on the resource untouched. It
 * is the resource-tagging counterpart to UntagResource, which already reads
 * the current tag set, mutates it, and writes it back rather than replacing
 * it outright.
 *
 * <p>The handler used to call straight into {@code S3Service.putBucketTagging}
 * with only the tags carried in the TagResource request body.
 * putBucketTagging correctly implements the CLASSIC S3 PutBucketTagging
 * API's genuinely-replace-the-whole-set semantics, so calling it with a
 * partial tag map silently deleted every tag not named in that one request.
 *
 * <p>This is exactly the shape terraform-provider-aws (v6.58+) relies on:
 * an update that changes one tag key sends ONLY that key to TagResource,
 * trusting the API to leave the rest alone. Against the old handler here,
 * any previously-set tag not part of the current delta - choudoufu's own
 * tofu-estate/tofu-address ownership markers among them - was wiped out by
 * the very next incremental tag update, even though nothing asked for it to
 * be removed. choudoufu's own diagnosis first suspected its config-synthesis
 * marker-injection seam (internal/live/stamp) or its apply-time re-plan;
 * both turned out to be innocent - the same drop reproduces under vanilla
 * Terraform with a real, persisted state file and no choudoufu involved at
 * all, which places the defect on this side of the wire.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ControlTagResourceMergeTest {

    private static final String BUCKET = "tagresource-merge-bucket";
    private static final String ACCOUNT = "000000000000";
    private static final String ARN = "arn:aws:s3:::" + BUCKET;

    @Test
    @Order(1)
    @DisplayName("setup: create bucket and tag it via the classic S3 tagging API")
    void setupBucketWithClassicTags() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        String tagBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Tagging><TagSet>" +
                "<Tag><Key>Owner</Key><Value>Anton</Value></Tag>" +
                "</TagSet></Tagging>";

        given().contentType("application/xml").body(tagBody)
                .when().put("/" + BUCKET + "?tagging")
                .then().statusCode(204);
    }

    @Test
    @Order(2)
    @DisplayName("out-of-band write: an ownership marker landing via S3 Control TagResource, as live-import's tags-only apply does")
    void stampMarkerViaTagResource() {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Tagging xmlns=\"http://awss3control.amazonaws.com/doc/2018-08-20/\">" +
                "<Tags>" +
                "<Tag><Key>tofu-estate</Key><Value>tagseed-repro</Value></Tag>" +
                "<Tag><Key>tofu-address</Key><Value>aws_s3_bucket.b</Value></Tag>" +
                "</Tags></Tagging>";

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/v20180820/tags/" + ARN)
        .then()
            .statusCode(204);

        // The classic tag set from Order 1 (Owner) MUST have survived: this
        // was an upsert of two new keys, not a replacement of the whole set.
        given().when().get("/" + BUCKET + "?tagging")
            .then().statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(containsString("<Key>tofu-estate</Key>"))
            .body(containsString("<Key>tofu-address</Key>"));
    }

    @Test
    @Order(3)
    @DisplayName("TagResource#306: an incremental tag update via TagResource must not drop unrelated, already-set tags")
    void tagResourceMergesRatherThanReplaces() {
        // A single new tag arrives - the shape terraform-provider-aws sends
        // for an ordinary "one key changed" apply, exactly as it does for a
        // stamped aws_s3_bucket whose own config declares a tags argument.
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Tagging xmlns=\"http://awss3control.amazonaws.com/doc/2018-08-20/\">" +
                "<Tags>" +
                "<Tag><Key>Team</Key><Value>Ops</Value></Tag>" +
                "</Tags></Tagging>";

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/v20180820/tags/" + ARN)
        .then()
            .statusCode(204);

        // Every tag set before this call - via the classic API in Order 1
        // and via TagResource itself in Order 2 - must still be there. Read
        // back through BOTH the classic S3 API and S3 Control's own
        // ListTagsForResource, since a defect that only replaced one of the
        // two underlying stores would pass a check that used just one.
        given().when().get("/" + BUCKET + "?tagging")
            .then().statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(containsString("<Key>tofu-estate</Key>"))
            .body(containsString("<Key>tofu-address</Key>"))
            .body(containsString("<Key>Team</Key>"));

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/" + ARN)
        .then()
            .statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(containsString("<Key>tofu-estate</Key>"))
            .body(containsString("<Key>tofu-address</Key>"))
            .body(containsString("<Key>Team</Key>"));
    }
}
