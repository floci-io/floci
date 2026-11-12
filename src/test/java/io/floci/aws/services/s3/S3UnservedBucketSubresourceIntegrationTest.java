package io.floci.aws.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * A bucket request naming a sub-resource runs that sub-resource's operation in AWS, never the bucket's own. One
 * Floci does not serve used to fall through to the bucket's operation: {@code DELETE ?metadataTable} deleted the
 * bucket, {@code GET ?abac} listed it and {@code PUT ?abac} created it. The buckets here stay empty, since an empty
 * bucket is the one a fall-through DeleteBucket removes.
 */
@QuarkusTest
class S3UnservedBucketSubresourceIntegrationTest {

    private static final List<String> METHODS = List.of("GET", "PUT", "DELETE", "POST", "HEAD");

    @Test
    void aSubresourceFlociDoesNotServeIsNotImplementedAndLeavesTheBucketAlone() {
        String bucket = "unserved-subresource-bucket";
        given().when().put("/" + bucket).then().statusCode(200);
        String[][] requests = {
            {"DELETE", "metadataConfiguration"}, {"DELETE", "metadataTable"},
            {"GET", "metadataConfiguration"}, {"GET", "metadataTable"}, {"GET", "abac"}, {"GET", "session"},
            {"PUT", "abac"}, {"PUT", "metadataJournalTable"}, {"PUT", "metadataInventoryTable"},
            {"PUT", "metadataAnnotationTable"}, {"POST", "metadataConfiguration"}, {"POST", "metadataTable"},
        };
        for (String[] request : requests) {
            given().queryParam(request[1], "")
            .when()
                .request(request[0], "/" + bucket)
            .then()
                .statusCode(501)
                .body(containsString("<Code>NotImplemented</Code>"));
        }
        given().when().head("/" + bucket).then().statusCode(200);
    }

    @Test
    void aSubresourceSentWithAMethodAwsDefinesNoneForIsNotAllowed() {
        String bucket = "misdirected-subresource-bucket";
        given().when().put("/" + bucket).then().statusCode(200);
        String[][] requests = {
            {"DELETE", "versioning"}, {"DELETE", "acl"}, {"DELETE", "uploads"},
            {"PUT", "location"}, {"PUT", "uploads"}, {"GET", "delete"}, {"POST", "versioning"},
        };
        for (String[] request : requests) {
            given().queryParam(request[1], "")
            .when()
                .request(request[0], "/" + bucket)
            .then()
                .statusCode(405)
                .body(containsString("<Code>MethodNotAllowed</Code>"));
        }
        // AWS defines no sub-resource for HEAD, so none runs HeadBucket; a HEAD answer carries no body.
        given().queryParam("acl", "").when().head("/" + bucket).then().statusCode(405);
        given().when().head("/" + bucket).then().statusCode(200);
    }

    @Test
    void anEscapedAmpersandInAValueNamesNoSubresource() {
        // ?prefix=reports%26abac is one parameter whose value holds "&abac", not an ?abac sub-resource.
        String bucket = "escaped-ampersand-bucket";
        given().when().put("/" + bucket).then().statusCode(200);
        given().queryParam("prefix", "reports&abac")
        .when()
            .get("/" + bucket)
        .then()
            .statusCode(200)
            .body(containsString("<ListBucketResult"), containsString("<Prefix>reports&amp;abac</Prefix>"));
    }

    @Test
    void aPutNamingASubresourceNeverCreatesTheBucket() {
        String bucket = "never-created-by-a-subresource";
        given().queryParam("abac", "").when().put("/" + bucket).then().statusCode(501);
        given().queryParam("location", "").when().put("/" + bucket).then().statusCode(405);
        given().when().head("/" + bucket).then().statusCode(404);
    }

    @Test
    void noSubresourceOfTheAwsModelRunsAsTheBucketsOwnOperation() {
        String bucket = "every-subresource-bucket";
        String missing = "every-subresource-missing";
        given().when().put("/" + bucket).then().statusCode(200);
        for (String name : S3BucketSubresources.vendored().names()) {
            for (String method : METHODS) {
                String response = given().queryParam(name, "").when().request(method, "/" + bucket)
                        .then().extract().asString();
                given().when().head("/" + bucket).then().statusCode(200);
                if ("GET".equals(method)) {
                    assertThat(method + " ?" + name, response,
                            not(containsString("<ListBucketResult")));
                }
            }
            given().queryParam(name, "").when().put("/" + missing);
            given().when().head("/" + missing).then().statusCode(404);
        }
    }
}
