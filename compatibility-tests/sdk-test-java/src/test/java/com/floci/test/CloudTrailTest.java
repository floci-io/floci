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
        assertThat(trail.homeRegion()).isEqualTo("us-east-1");
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
        assertThat(started.latestDeliveryTime()).isNotNull();

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
}
