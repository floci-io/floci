package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class S3XmlKeyEscapingIntegrationTest {

    @Test
    void aKeyWithACarriageReturnIsListedUnchanged() {
        String bucket = "s3-xml-key-cr-" + UUID.randomUUID();
        String key = "line\rbreak.txt";
        try (S3Client client = S3TestClients.pathStyle()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("x"));
                try {
                    ListObjectsV2Response response = client.listObjectsV2(request -> request.bucket(bucket));
                    List<String> keys = response.contents().stream().map(S3Object::key).toList();
                    assertEquals(List.of(key), keys);
                } finally {
                    client.deleteObject(request -> request.bucket(bucket).key(key));
                }
            } finally {
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }
}
