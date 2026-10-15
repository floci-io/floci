package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class S3MultipartMinimumPartSizeIntegrationTest {

    @Test
    void undersizedNonFinalPartReturnsItsDetailsAndAllowsRetry() {
        String bucket = "multipart-minimum-" + UUID.randomUUID();
        String path = "/" + bucket + "/object";
        given().put("/" + bucket).then().statusCode(200);
        String uploadId = null;
        try {
            given().body("prior").put(path).then().statusCode(200);
            uploadId = given().post(path + "?uploads").then().statusCode(200)
                    .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
            String firstETag = given().queryParam("uploadId", uploadId).queryParam("partNumber", 1).body("data")
                    .put(path).then().statusCode(200).extract().header("ETag");
            String lastETag = given().queryParam("uploadId", uploadId).queryParam("partNumber", 2).body("last")
                    .put(path).then().statusCode(200).extract().header("ETag");
            given().queryParam("uploadId", uploadId).body(completion(firstETag, lastETag)).post(path)
                    .then().statusCode(400)
                    .body("Error.Code", equalTo("EntityTooSmall"))
                    .body("Error.Message", equalTo("Your proposed upload is smaller than the minimum allowed size"))
                    .body("Error.ProposedSize", equalTo("4"))
                    .body("Error.MinSizeAllowed", equalTo("5242880"))
                    .body("Error.PartNumber", equalTo("1"))
                    .body("Error.ETag", equalTo(firstETag.substring(1, firstETag.length() - 1)));
            given().get(path).then().statusCode(200).body(equalTo("prior"));
            given().queryParam("uploadId", uploadId).get(path).then().statusCode(200)
                    .body("ListPartsResult.Part.size()", equalTo(2));

            String replacementETag = given().queryParam("uploadId", uploadId).queryParam("partNumber", 1)
                    .body(new byte[5242880]).put(path).then().statusCode(200).extract().header("ETag");
            given().queryParam("uploadId", uploadId).body(completion(replacementETag, lastETag)).post(path)
                    .then().statusCode(200);
            given().head(path).then().statusCode(200).header("Content-Length", equalTo("5242884"));
        } finally {
            if (uploadId != null) {
                given().queryParam("uploadId", uploadId).delete(path);
            }
            given().delete(path);
            given().delete("/" + bucket);
        }
    }

    private static String completion(String firstETag, String lastETag) {
        return """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>%s</ETag></Part>
                    <Part><PartNumber>2</PartNumber><ETag>%s</ETag></Part>
                </CompleteMultipartUpload>""".formatted(firstETag, lastETag);
    }
}
