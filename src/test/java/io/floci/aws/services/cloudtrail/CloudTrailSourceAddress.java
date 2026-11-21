package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;

/** Writes an object through a trail with a forwarded address, and reads back the address its record names. */
final class CloudTrailSourceAddress {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudTrailSourceAddress() {
    }

    static String recordedFor(CloudTrailLogWriter writer, String forwardedFor) throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "ct-src-" + suffix;
        String logs = "ct-src-logs-" + suffix;
        String trail = "ct-src-" + suffix;
        given().when().put("/" + bucket).then().statusCode(200);
        given().when().put("/" + logs).then().statusCode(200);
        cloudTrail("CreateTrail", "{\"Name\":\"" + trail + "\",\"S3BucketName\":\"" + logs + "\"}");
        cloudTrail("PutEventSelectors", "{\"TrailName\":\"" + trail + "\",\"EventSelectors\":[{\"ReadWriteType\":"
                + "\"All\",\"IncludeManagementEvents\":false,\"DataResources\":[{\"Type\":\"AWS::S3::Object\","
                + "\"Values\":[\"arn:aws:s3:::" + bucket + "/\"]}]}]}");
        cloudTrail("StartLogging", "{\"Name\":\"" + trail + "\"}");
        given().header("X-Forwarded-For", forwardedFor).body("x")
                .when().put("/" + bucket + "/forwarded.txt").then().statusCode(200);
        writer.flushNow();
        List<String> keys = given().queryParam("list-type", "2").when().get("/" + logs)
                .then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
        for (String key : keys) {
            byte[] gz = given().when().get("/" + logs + "/" + key).then().statusCode(200).extract().asByteArray();
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                for (JsonNode record : MAPPER.readTree(in.readAllBytes()).path("Records")) {
                    if ("forwarded.txt".equals(record.path("requestParameters").path("key").asText())) {
                        return record.path("sourceIPAddress").asText();
                    }
                }
            }
        }
        throw new AssertionError("no record of the forwarded write in " + keys);
    }

    private static void cloudTrail(String action, String body) {
        given().header("X-Amz-Target", "CloudTrail_20131101." + action).contentType("application/x-amz-json-1.1")
                .body(body).when().post("/").then().statusCode(200);
    }
}
