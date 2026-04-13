package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse;
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3control.S3ControlClient;
import software.amazon.awssdk.services.s3control.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.s3control.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.s3control.model.TagResourceRequest;
import software.amazon.awssdk.services.s3control.model.UntagResourceRequest;
import software.amazon.awssdk.services.s3control.model.S3ControlException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for S3 Control API (issue #341).
 *
 * Verifies ListTagsForResource, TagResource, and UntagResource work correctly
 * via the real AWS SDK S3ControlClient against Floci.
 *
 * Terraform AWS provider v6.x calls ListTagsForResource during bucket read-back;
 * without this API the provider marks buckets as errored even when they are created.
 */
@DisplayName("S3 Control API — ListTagsForResource / TagResource / UntagResource (#341)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ControlTest {

    private static S3Client s3;
    private static S3ControlClient s3control;

    private static final String BUCKET      = "compat-341-bucket";
    private static final String ACCOUNT_ID  = "000000000000";
    private static final String REGION_NAME = "us-east-1";

    private static String bucketArn() {
        return "arn:aws:s3:" + REGION_NAME + ":" + ACCOUNT_ID + ":bucket/" + BUCKET;
    }

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        s3control = TestFixtures.s3ControlClient();

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {}
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            try {
                s3.deleteBucketTagging(DeleteBucketTaggingRequest.builder().bucket(BUCKET).build());
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
            } catch (Exception ignored) {}
            s3.close();
        }
        if (s3control != null) {
            s3control.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("listTagsForResource: returns tags set via standard PutBucketTagging")
    void listTagsForResourceReturnsBucketTags() {
        s3.putBucketTagging(PutBucketTaggingRequest.builder()
                .bucket(BUCKET)
                .tagging(Tagging.builder()
                        .tagSet(
                                Tag.builder().key("Environment").value("dev").build(),
                                Tag.builder().key("ManagedBy").value("terraform").build())
                        .build())
                .build());

        ListTagsForResourceResponse response = s3control.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .accountId(ACCOUNT_ID)
                        .resourceArn(bucketArn())
                        .build());

        Map<String, String> tags = response.tags().stream()
                .collect(Collectors.toMap(
                        software.amazon.awssdk.services.s3control.model.Tag::key,
                        software.amazon.awssdk.services.s3control.model.Tag::value));

        assertThat(tags).containsEntry("Environment", "dev")
                        .containsEntry("ManagedBy", "terraform");
    }

    @Test
    @Order(2)
    @DisplayName("listTagsForResource: untagged bucket returns empty tag list")
    void listTagsForResourceEmptyBucket() {
        s3.deleteBucketTagging(DeleteBucketTaggingRequest.builder().bucket(BUCKET).build());

        ListTagsForResourceResponse response = s3control.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .accountId(ACCOUNT_ID)
                        .resourceArn(bucketArn())
                        .build());

        assertThat(response.tags()).isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("tagResource: tags set via S3 Control are visible through standard GetBucketTagging")
    void tagResourceVisibleThroughStandardApi() {
        s3control.tagResource(TagResourceRequest.builder()
                .accountId(ACCOUNT_ID)
                .resourceArn(bucketArn())
                .tags(
                        software.amazon.awssdk.services.s3control.model.Tag.builder()
                                .key("Team").value("platform").build(),
                        software.amazon.awssdk.services.s3control.model.Tag.builder()
                                .key("CostCenter").value("engineering").build())
                .build());

        GetBucketTaggingResponse tagging = s3.getBucketTagging(
                GetBucketTaggingRequest.builder().bucket(BUCKET).build());

        Map<String, String> tags = tagging.tagSet().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));

        assertThat(tags).containsEntry("Team", "platform")
                        .containsEntry("CostCenter", "engineering");
    }

    @Test
    @Order(4)
    @DisplayName("tagResource: replaces all existing tags (not a merge)")
    void tagResourceReplacesAllTags() {
        // tagResource replaces — only the new tags should remain
        s3control.tagResource(TagResourceRequest.builder()
                .accountId(ACCOUNT_ID)
                .resourceArn(bucketArn())
                .tags(software.amazon.awssdk.services.s3control.model.Tag.builder()
                        .key("NewOnly").value("yes").build())
                .build());

        ListTagsForResourceResponse response = s3control.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .accountId(ACCOUNT_ID)
                        .resourceArn(bucketArn())
                        .build());

        Map<String, String> tags = response.tags().stream()
                .collect(Collectors.toMap(
                        software.amazon.awssdk.services.s3control.model.Tag::key,
                        software.amazon.awssdk.services.s3control.model.Tag::value));

        assertThat(tags).containsOnlyKeys("NewOnly");
    }

    @Test
    @Order(5)
    @DisplayName("untagResource: removes specific keys, leaves others intact")
    void untagResourceRemovesSpecificKeys() {
        // Set two tags first
        s3control.tagResource(TagResourceRequest.builder()
                .accountId(ACCOUNT_ID)
                .resourceArn(bucketArn())
                .tags(
                        software.amazon.awssdk.services.s3control.model.Tag.builder()
                                .key("Keep").value("me").build(),
                        software.amazon.awssdk.services.s3control.model.Tag.builder()
                                .key("Remove").value("me").build())
                .build());

        s3control.untagResource(UntagResourceRequest.builder()
                .accountId(ACCOUNT_ID)
                .resourceArn(bucketArn())
                .tagKeys(List.of("Remove"))
                .build());

        ListTagsForResourceResponse response = s3control.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .accountId(ACCOUNT_ID)
                        .resourceArn(bucketArn())
                        .build());

        Map<String, String> tags = response.tags().stream()
                .collect(Collectors.toMap(
                        software.amazon.awssdk.services.s3control.model.Tag::key,
                        software.amazon.awssdk.services.s3control.model.Tag::value));

        assertThat(tags).containsEntry("Keep", "me")
                        .doesNotContainKey("Remove");
    }

    @Test
    @Order(6)
    @DisplayName("listTagsForResource: non-existent bucket returns 404 NoSuchBucket")
    void listTagsForResourceNonExistentBucketThrows() {
        assertThatThrownBy(() -> s3control.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .accountId(ACCOUNT_ID)
                        .resourceArn("arn:aws:s3:" + REGION_NAME + ":" + ACCOUNT_ID + ":bucket/does-not-exist-341")
                        .build()))
                .isInstanceOf(S3ControlException.class)
                .satisfies(e -> assertThat(((S3ControlException) e).statusCode()).isEqualTo(404));
    }
}
