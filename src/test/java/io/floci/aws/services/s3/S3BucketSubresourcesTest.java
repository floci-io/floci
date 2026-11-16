package io.floci.aws.services.s3;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3BucketSubresourcesTest {

    @Test
    void theVendoredModelGivesEachBucketSubresourceItsOperationsByMethod() {
        S3BucketSubresources subresources = S3BucketSubresources.vendored();

        assertEquals(List.of("DeleteBucketMetadataTableConfiguration"),
                subresources.operations("DELETE", "metadataTable"));
        assertEquals(List.of("CreateBucketMetadataTableConfiguration"),
                subresources.operations("POST", "metadataTable"));
        assertEquals(List.of("GetBucketLifecycle", "GetBucketLifecycleConfiguration"),
                subresources.operations("GET", "lifecycle"));
        assertEquals(List.of(), subresources.operations("DELETE", "versioning"));
        assertEquals(List.of(), subresources.operations("GET", "no-such-subresource"));
        assertTrue(subresources.names().containsAll(List.of("abac", "acl", "delete", "metadataConfiguration",
                "session", "uploads", "versions")));
    }

    @Test
    void aParameterWithAValueSelectsAVariantNotASubresource() {
        // ListObjectsV2 is /{Bucket}?list-type=2: the bucket listing, which runs on the bucket itself.
        assertFalse(S3BucketSubresources.vendored().names().contains("list-type"));
    }

    @Test
    void onlyABucketRouteNamesABucketSubresource() throws IOException {
        S3BucketSubresources subresources = parse("""
                {"models": {"s3": {"operations": {
                  "GetBucketAcl": {"method": "GET", "requestUri": "/{Bucket}?acl"},
                  "GetObjectAcl": {"method": "GET", "requestUri": "/{Bucket}/{Key+}?acl"},
                  "PutObjectRetention": {"method": "PUT", "requestUri": "/{Bucket}/{Key+}?retention"},
                  "ListObjects": {"method": "GET", "requestUri": "/{Bucket}"}
                }}}}""");

        assertEquals(List.of("acl"), subresources.names());
        assertEquals(List.of("GetBucketAcl"), subresources.operations("GET", "acl"));
    }

    @Test
    void aTableWithoutS3OrWithoutAUsableRouteFailsToLoad() {
        assertThrows(IllegalStateException.class, () -> parse("{\"models\": {}}"));
        assertThrows(IllegalStateException.class, () -> parse("""
                {"models": {"s3": {"operations": {"ListObjects": {"method": "GET", "requestUri": "/{Bucket}"}}}}}"""));
        assertThrows(IllegalStateException.class, () -> parse("""
                {"models": {"s3": {"operations": {"GetBucketAcl": {"requestUri": "/{Bucket}?acl"}}}}}"""));
    }

    private static S3BucketSubresources parse(String json) throws IOException {
        return S3BucketSubresources.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
