package io.floci.aws.services.autoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.autoscaling.model.ScalingActivity;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingActivityIdsIntegrationTest {

    private static final String ACCOUNT = "414141414141";
    private static final String OTHER_ACCOUNT = "424242424242";

    @Inject
    StorageFactory storageFactory;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private AccountAwareStorageBackend<ScalingActivity> activities;
    private String groupName;
    private List<String> groupIds;
    private String otherGroupId;
    private String otherRegionId;
    private String otherAccountId;

    @BeforeEach
    void storeActivityHistory() {
        activities = storageFactory.create("autoscaling", "autoscaling-activities.json",
                new TypeReference<Map<String, ScalingActivity>>() {});
        groupName = "activity-ids-" + UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(10);
        groupIds = List.of(
                store(ACCOUNT, Region.US_EAST_1, groupName, start.plusSeconds(2)),
                store(ACCOUNT, Region.US_EAST_1, groupName, start.plusSeconds(1)),
                store(ACCOUNT, Region.US_EAST_1, groupName, start));
        otherGroupId = store(ACCOUNT, Region.US_EAST_1, groupName + "-other", start);
        otherRegionId = store(ACCOUNT, Region.US_WEST_2, groupName, start);
        otherAccountId = store(OTHER_ACCOUNT, Region.US_EAST_1, groupName, start);
    }

    @Test
    void sdkFiltersSingleMultipleRepeatedAndUnknownIds() {
        try (AutoScalingClient client = AwsTestClients.create(
                AutoScalingClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertEquals(List.of(groupIds.get(1)), activityIds(client.describeScalingActivities(request -> request
                    .autoScalingGroupName(groupName).activityIds(groupIds.get(1)))));
            assertEquals(List.of(groupIds.get(0), groupIds.get(2)),
                    activityIds(client.describeScalingActivities(request -> request.autoScalingGroupName(groupName)
                            .activityIds(groupIds.get(2), groupIds.get(0), groupIds.get(2)))));
            assertTrue(client.describeScalingActivities(request -> request.autoScalingGroupName(groupName)
                    .activityIds(UUID.randomUUID().toString())).activities().isEmpty());
            assertEquals(List.of(groupIds.get(1)), activityIds(client.describeScalingActivities(request -> request
                    .autoScalingGroupName(groupName).activityIds(UUID.randomUUID().toString(), groupIds.get(1)))));
        }
    }

    @Test
    void omittedIdsKeepTheCompleteGroupHistoryInNewestFirstOrder() {
        try (AutoScalingClient client = AwsTestClients.create(
                AutoScalingClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertEquals(groupIds, activityIds(client.describeScalingActivities(
                    request -> request.autoScalingGroupName(groupName))));
        }
    }

    @Test
    void requestedIdsStillRespectGroupRegionAndAccountBoundaries() {
        try (AutoScalingClient client = AwsTestClients.create(
                AutoScalingClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertTrue(client.describeScalingActivities(request -> request.autoScalingGroupName(groupName)
                    .activityIds(otherGroupId, otherRegionId, otherAccountId)).activities().isEmpty());
            assertEquals(List.of(otherGroupId), activityIds(client.describeScalingActivities(request -> request
                    .activityIds(otherGroupId, otherRegionId, otherAccountId))));
        }
    }

    @Test
    void queryAcceptsSparseCanonicalMemberIndices() {
        List<String> returned = given()
                .header("Authorization", auth(ACCOUNT, "autoscaling"))
                .formParam("Action", "DescribeScalingActivities")
                .formParam("Version", "2011-01-01")
                .formParam("AutoScalingGroupName", groupName)
                .formParam("ActivityIds.member.1", groupIds.get(2))
                .formParam("ActivityIds.member.3", groupIds.get(0))
                .formParam("ActivityIds.member.4", groupIds.get(2))
                .formParam("ActivityIds.member.02", groupIds.get(1))
                .formParam("ActivityIds.member.5.Other", groupIds.get(1))
                .post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult.Activities.member.ActivityId");
        assertEquals(List.of(groupIds.get(0), groupIds.get(2)), returned);
    }

    private static List<String> activityIds(DescribeScalingActivitiesResponse response) {
        return response.activities().stream().map(Activity::activityId).toList();
    }

    private String store(String account, Region region, String group, Instant start) {
        ScalingActivity activity = new ScalingActivity();
        activity.setActivityId(UUID.randomUUID().toString());
        activity.setAutoScalingGroupName(group);
        activity.setRegion(region.id());
        activity.setStartTime(start);
        activity.setStatusCode("Successful");
        activity.setProgress(100);
        String id = activity.getActivityId();
        activities.putForAccount(account, id, activity);
        cleanup.register(() -> activities.deleteForAccount(account, id));
        return id;
    }
}
