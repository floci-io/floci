package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static io.restassured.RestAssured.given;

/** Reads the records a trail delivered to its S3 bucket, through the S3 API, as a client of the trail would. */
final class CloudTrailLogFiles {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudTrailLogFiles() {
    }

    /** Every record of every log file in {@code bucket}, in key order, across every page of the listing. */
    static List<JsonNode> records(String bucket) throws IOException {
        List<JsonNode> records = new ArrayList<>();
        for (String key : keys(bucket)) {
            byte[] gz = given().when().get("/" + bucket + "/" + key).then().statusCode(200).extract().asByteArray();
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                MAPPER.readTree(in).path("Records").forEach(records::add);
            }
        }
        return records;
    }

    private static List<String> keys(String bucket) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            RequestSpecification listing = given().queryParam("list-type", "2");
            if (token != null) {
                listing = listing.queryParam("continuation-token", token);
            }
            XmlPath page = listing.when().get("/" + bucket).then().statusCode(200).extract().xmlPath();
            keys.addAll(page.getList("ListBucketResult.Contents.Key"));
            token = "true".equals(page.getString("ListBucketResult.IsTruncated"))
                    ? page.getString("ListBucketResult.NextContinuationToken") : null;
        } while (token != null);
        return keys;
    }
}
