package io.floci.aws.testing;

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
public final class CloudTrailLogFiles {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudTrailLogFiles() {
    }

    /** Every record of every log file in {@code bucket}, in key order, across every page of the listing. */
    public static List<JsonNode> records(String bucket) throws IOException {
        return records(bucket, null);
    }

    /**
     * As {@link #records(String)}, with each S3 request signed with {@code authorization} when it is not
     * null, as a test under IAM enforcement needs.
     */
    public static List<JsonNode> records(String bucket, String authorization) throws IOException {
        List<JsonNode> records = new ArrayList<>();
        for (String key : keys(bucket, authorization)) {
            if (!key.endsWith(".json.gz")) {
                continue;
            }
            byte[] gz = request(authorization).when().get("/" + bucket + "/" + key)
                    .then().statusCode(200).extract().asByteArray();
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                MAPPER.readTree(in).path("Records").forEach(records::add);
            }
        }
        return records;
    }

    private static List<String> keys(String bucket, String authorization) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            RequestSpecification listing = request(authorization).queryParam("list-type", "2");
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

    private static RequestSpecification request(String authorization) {
        RequestSpecification request = given();
        return authorization == null ? request : request.header("Authorization", authorization);
    }
}
