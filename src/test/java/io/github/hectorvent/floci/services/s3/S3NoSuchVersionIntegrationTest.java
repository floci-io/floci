package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class S3NoSuchVersionIntegrationTest {
    private static final String BUCKET = "no-such-version-names-it";
    private static final String KEY = "dir/k.txt";
    private static final String PATH = "/" + BUCKET + "/" + KEY;

    @Test
    void noSuchVersionNamesTheKeyAndTheVersion() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + BUCKET + "?versioning").then().statusCode(200);
        String gone = given().body("1").when().put(PATH).then().statusCode(200).extract().header("x-amz-version-id");
        given().body("2").when().put(PATH).then().statusCode(200);
        given().when().delete(PATH + "?versionId=" + gone).then().statusCode(204);

        for (String versionId : List.of(gone, "null")) {
            String version = "versionId=" + versionId;
            missing(given().when().get(PATH + "?" + version), KEY, versionId);
            missing(given().when().get(PATH + "?acl&" + version), KEY, versionId);
            missing(given().header("x-amz-object-attributes", "ETag").when().get(PATH + "?attributes&" + version),
                    KEY, versionId);
            missing(given().body("<Tagging><TagSet></TagSet></Tagging>").when().put(PATH + "?tagging&" + version),
                    KEY, versionId);
            missing(given().when().delete(PATH + "?tagging&" + version), KEY, versionId);
            missing(given().when().get(PATH + "?annotation&annotationName=labels&" + version), KEY, versionId);
            missing(given().when().get(PATH + "?annotation&" + version), KEY, versionId);
            missing(given().header("x-amz-copy-source", PATH + "?" + version).when().put("/" + BUCKET + "/copy"),
                    KEY, versionId);
            // GetObjectTagging names the key with its bucket.
            missing(given().when().get(PATH + "?tagging&" + version), BUCKET + "/" + KEY, versionId);
        }
    }

    private static void missing(Response response, String key, String versionId) {
        response.then().statusCode(404)
                .body("Error.Code", equalTo("NoSuchVersion"))
                .body("Error.Key", equalTo(key))
                .body("Error.VersionId", equalTo(versionId));
    }
}
