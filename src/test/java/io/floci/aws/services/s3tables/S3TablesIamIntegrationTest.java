package io.floci.aws.services.s3tables;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * S3 Tables requests are checked against the caller's policies through its generated routes: a table bucket call on
 * the bucket its path names, and a table call on the table its path (or {@code GetTable}'s query string) names by its
 * table bucket's ARN, its namespace and its name, whose ARN S3 Tables answers from the table it stores.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class S3TablesIamIntegrationTest {

    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "s3tables");

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void callerWithoutPermissionsIsRefusedBeforeATableBucketIsCreated() throws Exception {
        String name = "iam-none-" + UUID.randomUUID().toString().substring(0, 8);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-")) {
            denied(send(user.signer("s3tables"), "PUT", "/buckets", "{\"name\":\"" + name + "\"}"),
                    "s3tables:CreateTableBucket");
            denied(send(user.signer("s3tables"), "GET", "/buckets", null), "s3tables:ListTableBuckets");
        }
        send(admin, "GET", "/buckets", null).then().statusCode(200).body(not(containsString(name)));
    }

    @Test
    void aBucketScopedAllowReadsThatBucketAndRefusesAnother() throws Exception {
        String allowed = createBucket();
        String other = createBucket();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "access",
                allow("\"s3tables:GetTableBucket\"", allowed))) {
            send(user.signer("s3tables"), "GET", "/buckets/" + encoded(allowed), null).then().statusCode(200)
                    .body("arn", equalTo(allowed));
            denied(send(user.signer("s3tables"), "GET", "/buckets/" + encoded(other), null),
                    "s3tables:GetTableBucket");
            denied(send(user.signer("s3tables"), "DELETE", "/buckets/" + encoded(allowed), null),
                    "s3tables:DeleteTableBucket");
        } finally {
            send(admin, "DELETE", "/buckets/" + encoded(allowed), null).then().statusCode(204);
            send(admin, "DELETE", "/buckets/" + encoded(other), null).then().statusCode(204);
        }
    }

    @Test
    void aTableScopedAllowReadsThatTableByItsPathAndRefusesAnother() throws Exception {
        String bucket = createBucket();
        String path = "/tables/" + encoded(bucket) + "/analytics/";
        send(admin, "PUT", "/namespaces/" + encoded(bucket), "{\"namespace\":[\"analytics\"]}").then().statusCode(200);
        String table = send(admin, "PUT", path, "{\"name\":\"events\",\"format\":\"ICEBERG\"}").then().statusCode(200)
                .extract().path("tableARN");
        send(admin, "PUT", path, "{\"name\":\"orders\",\"format\":\"ICEBERG\"}").then().statusCode(200);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "access",
                allow("[\"s3tables:GetTableMetadataLocation\",\"s3tables:GetTable\"]", table))) {
            send(user.signer("s3tables"), "GET", path + "events/metadata-location", null).then().statusCode(200);
            denied(send(user.signer("s3tables"), "GET", path + "orders/metadata-location", null),
                    "s3tables:GetTableMetadataLocation");
            denied(send(user.signer("s3tables"), "DELETE", path + "events", null), "s3tables:DeleteTable");
            String query = "/get-table?tableBucketARN=" + encoded(bucket) + "&namespace=analytics&name=";
            send(user.signer("s3tables"), "GET", query + "events", null).then().statusCode(200)
                    .body("tableARN", equalTo(table));
            denied(send(user.signer("s3tables"), "GET", query + "orders", null), "s3tables:GetTable");
        } finally {
            send(admin, "DELETE", path + "events", null).then().statusCode(204);
            send(admin, "DELETE", path + "orders", null).then().statusCode(204);
            send(admin, "DELETE", "/namespaces/" + encoded(bucket) + "/analytics", null).then().statusCode(204);
            send(admin, "DELETE", "/buckets/" + encoded(bucket), null).then().statusCode(204);
        }
    }

    /** Tables of one name in two namespaces have ARNs of their own, so an allow on one opens only that one. */
    @Test
    void aTableScopedAllowDoesNotOpenATableOfTheSameNameInAnotherNamespace() throws Exception {
        String bucket = createBucket();
        String analytics = createTable(bucket, "analytics", "events");
        createTable(bucket, "staging", "events");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "access",
                allow("\"s3tables:GetTableMetadataLocation\"", analytics))) {
            send(user.signer("s3tables"), "GET", "/tables/" + encoded(bucket) + "/analytics/events/metadata-location",
                    null).then().statusCode(200);
            denied(send(user.signer("s3tables"), "GET", "/tables/" + encoded(bucket)
                    + "/staging/events/metadata-location", null), "s3tables:GetTableMetadataLocation");
        } finally {
            deleteBucket(bucket, "analytics", "staging");
        }
    }

    /** A {@code GetTable} naming a table by its ARN is checked on that table and answers it, whatever else it gives. */
    @Test
    void getTableByArnServesTheTableTheArnNames() throws Exception {
        String bucket = createBucket();
        String events = createTable(bucket, "analytics", "events");
        String orders = createTable(bucket, "analytics", "orders");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "access",
                allow("\"s3tables:GetTable\"", events))) {
            send(user.signer("s3tables"), "GET", "/get-table?tableArn=" + encoded(events) + "&tableBucketARN="
                    + encoded(bucket) + "&namespace=analytics&name=orders", null).then().statusCode(200)
                    .body("tableARN", equalTo(events)).body("name", equalTo("events"));
            denied(send(user.signer("s3tables"), "GET", "/get-table?tableArn=" + encoded(orders), null),
                    "s3tables:GetTable");
        } finally {
            deleteBucket(bucket, "analytics");
        }
    }

    /** A double-encoded table ARN names no table to the handler either, so a deny on the table holds. */
    @Test
    void aDoubleEncodedTableArnReadsNoTableADenyCovers() throws Exception {
        String bucket = createBucket();
        String orders = createTable(bucket, "analytics", "orders");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "\"s3tables:GetTable\",\"Resource\":\"*\"},{\"Effect\":\"Deny\",\"Action\":"
                + "\"s3tables:GetTable\",\"Resource\":\"" + orders + "\"}]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "access", policy)) {
            denied(send(user.signer("s3tables"), "GET", "/get-table?tableArn=" + encoded(orders), null),
                    "s3tables:GetTable");
            send(user.signer("s3tables"), "GET", "/get-table?tableArn=" + encoded(encoded(orders)), null).then()
                    .statusCode(not(200)).body(not(containsString(orders)));
        } finally {
            deleteBucket(bucket, "analytics");
        }
    }

    /** Tags a {@code CreateTable} sends are checked as {@code s3tables:TagResource} on the table it makes. */
    @Test
    void aTaggedCreateTableNeedsTagResourceOnTheTable() throws Exception {
        String bucket = createBucket();
        String path = "/tables/" + encoded(bucket) + "/analytics/";
        send(admin, "PUT", "/namespaces/" + encoded(bucket), "{\"namespace\":[\"analytics\"]}").then().statusCode(200);
        String tagged = "{\"name\":\"events\",\"format\":\"ICEBERG\",\"tags\":{\"team\":\"data\"}}";
        String onBucket = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "[\"s3tables:CreateTable\",\"s3tables:TagResource\"],\"Resource\":\"" + bucket + "\"}]}";
        String onTables = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "\"s3tables:CreateTable\",\"Resource\":\"" + bucket + "\"},{\"Effect\":\"Allow\",\"Action\":"
                + "\"s3tables:TagResource\",\"Resource\":\"" + bucket + "/table/*\"}]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "create", onBucket)) {
            denied(send(user.signer("s3tables"), "PUT", path, tagged), "s3tables:TagResource");
        }
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "create", onTables)) {
            send(user.signer("s3tables"), "PUT", path, tagged).then().statusCode(200);
        } finally {
            deleteBucket(bucket, "analytics");
        }
    }

    private String createTable(String bucket, String namespace, String name) throws Exception {
        send(admin, "PUT", "/namespaces/" + encoded(bucket), "{\"namespace\":[\"" + namespace + "\"]}");
        return send(admin, "PUT", "/tables/" + encoded(bucket) + "/" + namespace + "/",
                "{\"name\":\"" + name + "\",\"format\":\"ICEBERG\"}").then().statusCode(200)
                .extract().path("tableARN");
    }

    /** Deletes the bucket's tables in the namespaces given, the namespaces and the bucket. */
    private void deleteBucket(String bucket, String... namespaces) throws Exception {
        for (String namespace : namespaces) {
            List<String> tables = send(admin, "GET", "/tables/" + encoded(bucket) + "?namespace=" + namespace, null)
                    .then().statusCode(200).extract().path("tables.name");
            for (String table : tables) {
                send(admin, "DELETE", "/tables/" + encoded(bucket) + "/" + namespace + "/" + table, null).then()
                        .statusCode(204);
            }
            send(admin, "DELETE", "/namespaces/" + encoded(bucket) + "/" + namespace, null).then().statusCode(204);
        }
        send(admin, "DELETE", "/buckets/" + encoded(bucket), null).then().statusCode(204);
    }

    private static String allow(String actions, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":" + actions
                + ",\"Resource\":\"" + resource + "\"}]}";
    }

    private String createBucket() throws Exception {
        String name = "iam-" + UUID.randomUUID().toString().substring(0, 8);
        return send(admin, "PUT", "/buckets", "{\"name\":\"" + name + "\"}").then().statusCode(200)
                .extract().path("arn");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Response send(AwsRequestSigner signer, String method, String path, String body) throws Exception {
        URI uri = endpoint.resolve(path);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        RequestSpecification request = given().urlEncodingEnabled(false)
                .headers(signer.headersFor(method, uri, Map.of(), bytes));
        if (body != null) {
            request = request.contentType("application/json").body(bytes);
        }
        return request.request(method, uri);
    }

    private static void denied(Response response, String action) {
        response.then().statusCode(403).body(containsString("not authorized to perform: " + action));
    }
}
