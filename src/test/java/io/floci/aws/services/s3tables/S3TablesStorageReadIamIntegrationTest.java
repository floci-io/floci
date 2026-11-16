package io.floci.aws.services.s3tables;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.S3TablesException;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class S3TablesStorageReadIamIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void storageReadbackRequiresPermissionOnTheRequestedBucket() throws Exception {
        try (S3TablesClient admin = AwsTestClients.create(S3TablesClient.builder(), "test", Region.US_EAST_1, endpoint)) {
            String bucket = admin.createTableBucket(request -> request.name(name())).arn();
            try {
                try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-storage-");
                        S3TablesClient client = client(user)) {
                    denied(client, bucket);
                }
                String other = admin.createTableBucket(request -> request.name(name())).arn();
                try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-storage-", "read",
                        policy("Allow", bucket));
                        S3TablesClient client = client(user)) {
                    assertEquals("STANDARD", client.getTableBucketStorageClass(request -> request.tableBucketARN(bucket))
                            .storageClassConfiguration().storageClassAsString());
                    denied(client, other);
                } finally {
                    admin.deleteTableBucket(request -> request.tableBucketARN(other));
                }
                try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "s3t-storage-", "deny",
                        explicitDeny(bucket));
                        S3TablesClient client = client(user)) {
                    denied(client, bucket);
                }
            } finally {
                admin.deleteTableBucket(request -> request.tableBucketARN(bucket));
            }
        }
    }

    private static String policy(String effect, String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "%s",
                      "Action": "s3tables:GetTableBucketStorageClass",
                      "Resource": "%s"
                    }
                  ]
                }
                """.formatted(effect, bucket);
    }

    private S3TablesClient client(SignedIamUsers.User user) {
        return AwsTestClients.create(S3TablesClient.builder(), user.accessKeyId(), user.secretAccessKey(),
                Region.US_EAST_1, endpoint);
    }

    private static String explicitDeny(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": "s3tables:GetTableBucketStorageClass",
                      "Resource": "*"
                    },
                    {
                      "Effect": "Deny",
                      "Action": "s3tables:GetTableBucketStorageClass",
                      "Resource": "%s"
                    }
                  ]
                }
                """.formatted(bucket);
    }

    private static void denied(S3TablesClient client, String bucket) {
        S3TablesException error = assertThrows(S3TablesException.class,
                () -> client.getTableBucketStorageClass(request -> request.tableBucketARN(bucket)));
        assertEquals(403, error.statusCode());
        assertTrue(error.getMessage().contains("not authorized to perform: s3tables:GetTableBucketStorageClass"),
                error.getMessage());
    }

    private static String name() {
        return "storage-iam-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
