package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class S3BucketTaggingIntegrationTest {

    @Test
    void getBucketTaggingOnUntaggedBucketReturnsNoSuchTagSet() {
        String bucket = "tagging-never-tagged";
        given().when().put("/" + bucket).then().statusCode(200);

        given()
        .when()
            .get("/" + bucket + "?tagging")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchTagSet</Code>"));
    }

    @Test
    void getBucketTaggingAfterDeleteBucketTaggingReturnsNoSuchTagSet() {
        String bucket = "tagging-tags-deleted";
        given().when().put("/" + bucket).then().statusCode(200);
        given()
            .contentType("application/xml")
            .body("<Tagging><TagSet><Tag><Key>team</Key><Value>backend</Value></Tag></TagSet></Tagging>")
        .when()
            .put("/" + bucket + "?tagging")
        .then()
            .statusCode(204);
        given().when().delete("/" + bucket + "?tagging").then().statusCode(204);

        given()
        .when()
            .get("/" + bucket + "?tagging")
        .then()
            .statusCode(404)
            .body(containsString("<Code>NoSuchTagSet</Code>"));
    }
}
