package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class S3InvalidVersionIdIntegrationTest {
    private static final String BUCKET = "invalid-version-id-it";
    private static final String KEY = "dir/k.txt";
    private static final String PATH = "/" + BUCKET + "/" + KEY;

    @Test
    void aVersionIdFlociDidNotMintIsRefused() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + BUCKET + "?versioning").then().statusCode(200);
        given().body("1").when().put(PATH).then().statusCode(200);

        for (String key : List.of(KEY, "missing")) {
            String path = "/" + BUCKET + "/" + key;
            for (String versionId : List.of("bogus", "")) {
                String version = "versionId=" + versionId;
                refused(given().when().get(path + "?" + version), versionId);
                refused(given().when().delete(path + "?" + version), versionId);
                refused(given().when().get(path + "?acl&" + version), versionId);
                refused(given().header("x-amz-acl", "private").when().put(path + "?acl&" + version), versionId);
                refused(given().header("x-amz-object-attributes", "ETag").when().get(path + "?attributes&" + version),
                        versionId);
                refused(given().when().get(path + "?tagging&" + version), versionId);
                refused(given().body("<Tagging><TagSet></TagSet></Tagging>").when().put(path + "?tagging&" + version),
                        versionId);
                refused(given().when().delete(path + "?tagging&" + version), versionId);
                // S3 checks the version id before the bucket's Object Lock configuration.
                refused(given().when().get(path + "?retention&" + version), versionId);
                refused(given().body("<Retention><Mode>GOVERNANCE</Mode>"
                                + "<RetainUntilDate>2030-01-01T00:00:00Z</RetainUntilDate></Retention>")
                        .when().put(path + "?retention&" + version), versionId);
                refused(given().when().get(path + "?legal-hold&" + version), versionId);
                refused(given().body("<LegalHold><Status>OFF</Status></LegalHold>")
                        .when().put(path + "?legal-hold&" + version), versionId);
                refused(given().body("<RestoreRequest><Days>1</Days></RestoreRequest>")
                        .when().post(path + "?restore&" + version), versionId);
                refused(given().when().get(path + "?annotation&annotationName=labels&" + version), versionId);
                refused(given().when().get(path + "?annotation&" + version), versionId);
                refused(given().body("x").when().put(path + "?annotation&annotationName=labels&" + version),
                        versionId);
                refused(given().when().delete(path + "?annotation&annotationName=labels&" + version), versionId);
                given().when().head(path + "?" + version).then().statusCode(400);
            }
        }
        // The refused DeleteObject left the object current.
        given().when().get(PATH).then().statusCode(200).body(equalTo("1"));
        given().when().get("/" + BUCKET + "-missing/" + KEY + "?versionId=bogus").then().statusCode(404)
                .body("Error.Code", equalTo("NoSuchBucket"));
    }

    private static void refused(Response response, String versionId) {
        response.then().statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"))
                .body("Error.Message", equalTo(versionId.isEmpty()
                        ? "Version id cannot be the empty string" : "Invalid version id specified"))
                .body("Error.ArgumentName", equalTo("versionId"))
                .body("Error.ArgumentValue", equalTo(versionId));
    }
}
