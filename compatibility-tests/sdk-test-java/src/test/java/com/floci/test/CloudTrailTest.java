package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.CreateTrailResponse;
import software.amazon.awssdk.services.cloudtrail.model.DescribeTrailsResponse;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusResponse;
import software.amazon.awssdk.services.cloudtrail.model.Trail;
import software.amazon.awssdk.services.cloudtrail.model.TrailNotFoundException;
import software.amazon.awssdk.services.cloudtrail.model.UpdateTrailResponse;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("CloudTrail")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudTrailTest {

    private static CloudTrailClient cloudTrail;
    private static String trailName;
    private static String bucketName;
    private static String updatedBucketName;

    @BeforeAll
    static void setup() {
        cloudTrail = TestFixtures.cloudTrailClient();
        trailName = TestFixtures.uniqueName("ct-sdk");
        bucketName = TestFixtures.uniqueName("ct-bucket");
        updatedBucketName = TestFixtures.uniqueName("ct-bucket-updated");
    }

    @AfterAll
    static void cleanup() {
        if (cloudTrail != null) {
            try {
                cloudTrail.deleteTrail(r -> r.name(trailName));
            } catch (Exception ignored) {
            }
            cloudTrail.close();
        }
    }

    @Test
    @Order(1)
    void createTrail() {
        CreateTrailResponse response = cloudTrail.createTrail(r -> r
                .name(trailName)
                .s3BucketName(bucketName)
                .includeGlobalServiceEvents(true)
                .isMultiRegionTrail(true));

        assertThat(response.name()).isEqualTo(trailName);
        assertThat(response.s3BucketName()).isEqualTo(bucketName);
        assertThat(response.trailARN()).contains(":cloudtrail:").endsWith(":trail/" + trailName);
        assertThat(response.includeGlobalServiceEvents()).isTrue();
        assertThat(response.isMultiRegionTrail()).isTrue();
    }

    @Test
    @Order(2)
    void describeTrailsByName() {
        DescribeTrailsResponse response = cloudTrail.describeTrails(r -> r.trailNameList(trailName));

        assertThat(response.trailList()).hasSize(1);
        Trail trail = response.trailList().get(0);
        assertThat(trail.name()).isEqualTo(trailName);
        assertThat(trail.homeRegion()).isEqualTo(TestFixtures.region().id());
        assertThat(trail.s3BucketName()).isEqualTo(bucketName);
    }

    @Test
    @Order(3)
    void updateTrail() {
        UpdateTrailResponse response = cloudTrail.updateTrail(r -> r
                .name(trailName)
                .s3BucketName(updatedBucketName)
                .includeGlobalServiceEvents(false));

        assertThat(response.name()).isEqualTo(trailName);
        assertThat(response.s3BucketName()).isEqualTo(updatedBucketName);
        assertThat(response.includeGlobalServiceEvents()).isFalse();
    }

    @Test
    @Order(4)
    void startAndStopLogging() {
        cloudTrail.startLogging(r -> r.name(trailName));

        GetTrailStatusResponse started = cloudTrail.getTrailStatus(r -> r.name(trailName));
        assertThat(started.isLogging()).isTrue();
        assertThat(started.latestDeliveryTime()).isNull();

        cloudTrail.stopLogging(r -> r.name(trailName));

        GetTrailStatusResponse stopped = cloudTrail.getTrailStatus(r -> r.name(trailName));
        assertThat(stopped.isLogging()).isFalse();
    }

    @Test
    @Order(5)
    void putEventSelectorsAcceptsTrailName() {
        cloudTrail.putEventSelectors(r -> r.trailName(trailName));
    }

    @Test
    @Order(6)
    void deleteTrail() {
        cloudTrail.deleteTrail(r -> r.name(trailName));

        assertThatThrownBy(() -> cloudTrail.getTrailStatus(r -> r.name(trailName)))
                .isInstanceOf(TrailNotFoundException.class);
    }

    @Test
    @Order(7)
    void encryptionAndCloudWatchLogsRoundTrip() {
        assumeFalse(TestFixtures.isRealAws(), "Uses placeholder KMS key, log group and role ARNs");
        String key = "arn:aws:kms:us-east-1:000000000000:key/0f1e2d3c-4b5a-6978-8091-a2b3c4d5e6f7";
        String group = "arn:aws:logs:us-east-1:000000000000:log-group:/trail:*";
        String role = "arn:aws:iam::000000000000:role/trail-to-logs";
        String name = TestFixtures.uniqueName("ct-enc");
        try {
            CreateTrailResponse created = cloudTrail.createTrail(r -> r.name(name).s3BucketName(bucketName)
                    .kmsKeyId(key).cloudWatchLogsLogGroupArn(group).cloudWatchLogsRoleArn(role));
            assertThat(created.kmsKeyId()).isEqualTo(key);
            assertThat(created.cloudWatchLogsLogGroupArn()).isEqualTo(group);
            assertThat(created.cloudWatchLogsRoleArn()).isEqualTo(role);

            Trail described = cloudTrail.describeTrails(r -> r.trailNameList(name)).trailList().get(0);
            assertThat(described.kmsKeyId()).isEqualTo(key);
            assertThat(described.cloudWatchLogsLogGroupArn()).isEqualTo(group);
            assertThat(described.cloudWatchLogsRoleArn()).isEqualTo(role);

            UpdateTrailResponse updated = cloudTrail.updateTrail(r -> r.name(name).kmsKeyId(""));
            assertThat(updated.kmsKeyId()).isNull();
            assertThat(updated.cloudWatchLogsRoleArn()).isEqualTo(role);
            assertThat(cloudTrail.describeTrails(r -> r.trailNameList(name)).trailList().get(0).kmsKeyId()).isNull();
        } finally {
            cloudTrail.deleteTrail(r -> r.name(name));
        }
    }
}
