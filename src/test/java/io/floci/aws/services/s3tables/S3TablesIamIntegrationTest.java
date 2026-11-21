package io.floci.aws.services.s3tables;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testing.SignedRestRequests;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.S3TablesException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.SignedRestRequests.denied;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A resource-tag condition reads the tags S3 Tables holds on the bucket or the table a request names. */
    @Test
    void aResourceTagConditionReadsTheTagsOfTheBucketAndTheTable() throws Exception {
        String tagged = send(admin, "PUT", "/buckets", "{\"name\":\"iam-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"tags\":{\"team\":\"data\"}}").then().statusCode(200).extract().path("arn");
        String untagged = createBucket();
        String path = "/tables/" + encoded(tagged) + "/analytics/";
        send(admin, "PUT", "/namespaces/" + encoded(tagged), "{\"namespace\":[\"analytics\"]}").then().statusCode(200);
        send(admin, "PUT", path, "{\"name\":\"events\",\"format\":\"ICEBERG\",\"tags\":{\"team\":\"data\"}}")
                .then().statusCode(200);
        send(admin, "PUT", path, "{\"name\":\"orders\",\"format\":\"ICEBERG\"}").then().statusCode(200);
        String onTeamData = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "[\"s3tables:GetTableBucket\",\"s3tables:GetTable\"],\"Resource\":\"*\",\"Condition\":"
                + "{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-iam-", "tags", onTeamData)) {
            send(user.signer("s3tables"), "GET", "/buckets/" + encoded(tagged), null).then().statusCode(200);
            denied(send(user.signer("s3tables"), "GET", "/buckets/" + encoded(untagged), null),
                    "s3tables:GetTableBucket");
            String query = "/get-table?tableBucketARN=" + encoded(tagged) + "&namespace=analytics&name=";
            send(user.signer("s3tables"), "GET", query + "events", null).then().statusCode(200);
            denied(send(user.signer("s3tables"), "GET", query + "orders", null), "s3tables:GetTable");
        } finally {
            deleteBucket(tagged, "analytics");
            send(admin, "DELETE", "/buckets/" + encoded(untagged), null).then().statusCode(204);
        }
    }

    @Test
    void encryptionReadbackRequiresPermissionOnTheRequestedBucket() throws Exception {
        String bucket = createBucket();
        try {
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-enc-");
                    S3TablesClient client = encryptionClient(user)) {
                encryptionDenied(client, bucket);
            }
            String other = createBucket();
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-enc-", "read",
                    allow("\"s3tables:GetTableBucketEncryption\"", bucket));
                    S3TablesClient client = encryptionClient(user)) {
                assertEquals("AES256", client.getTableBucketEncryption(request -> request.tableBucketARN(bucket))
                        .encryptionConfiguration().sseAlgorithmAsString());
                encryptionDenied(client, other);
            } finally {
                send(admin, "DELETE", "/buckets/" + encoded(other), null).then().statusCode(204);
            }
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Action": "s3tables:GetTableBucketEncryption",
                          "Resource": "*"
                        },
                        {
                          "Effect": "Deny",
                          "Action": "s3tables:GetTableBucketEncryption",
                          "Resource": "%s"
                        }
                      ]
                    }
                    """.formatted(bucket);
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-enc-", "deny", policy);
                    S3TablesClient client = encryptionClient(user)) {
                encryptionDenied(client, bucket);
            }
        } finally {
            send(admin, "DELETE", "/buckets/" + encoded(bucket), null).then().statusCode(204);
        }
    }

    private S3TablesClient encryptionClient(SignedIamUsers.User user) {
        return AwsTestClients.create(S3TablesClient.builder(), user.accessKeyId(), user.secretAccessKey(),
                Region.US_EAST_1, endpoint);
    }

    private static void encryptionDenied(S3TablesClient client, String bucket) {
        S3TablesException error = assertThrows(S3TablesException.class,
                () -> client.getTableBucketEncryption(request -> request.tableBucketARN(bucket)));
        assertEquals(403, error.statusCode());
        assertTrue(error.getMessage().contains("not authorized to perform: s3tables:GetTableBucketEncryption"),
                error.getMessage());
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
        return SignedRestRequests.send(endpoint, signer, method, path, body, "application/json");
    }
}
