package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoScalingIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/autoscaling/aws4_request";

    private static String policyArn;
    private static String instanceRefreshId;
    private static String pagedInstanceRefreshId;

    // ── Launch Configurations ─────────────────────────────────────────────────

    @Test
    @Order(1)
    void createLaunchConfiguration() {
        given()
                .formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", "my-lc")
                .formParam("ImageId", "ami-12345678")
                .formParam("InstanceType", "t3.micro")
                .formParam("SecurityGroups.member.1", "sg-default")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateLaunchConfigurationResponse"));
    }

    @Test
    @Order(2)
    void describeLaunchConfigurations() {
        given()
                .formParam("Action", "DescribeLaunchConfigurations")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("my-lc"))
                .body(containsString("ami-12345678"))
                .body(containsString("t3.micro"));
    }

    // ── Auto Scaling Groups ───────────────────────────────────────────────────

    @Test
    @Order(3)
    void createAutoScalingGroup() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("LaunchConfigurationName", "my-lc")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "3")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("Tags.member.1.Key", "env")
                .formParam("Tags.member.1.Value", "test")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateAutoScalingGroupResponse"));
    }

    @Test
    @Order(4)
    void describeAutoScalingGroups() {
        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("my-asg"))
                .body(containsString("my-lc"))
                .body(containsString("us-east-1a"))
                .body(containsString("<DesiredCapacity>0</DesiredCapacity>"))
                .body(containsString("<MinSize>0</MinSize>"))
                .body(containsString("<MaxSize>3</MaxSize>"))
                .body(containsString("env"))
                .body(containsString("test"));
    }

    @Test
    @Order(5)
    void setDesiredCapacity() {
        given()
                .formParam("Action", "SetDesiredCapacity")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("DesiredCapacity", "1")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("SetDesiredCapacityResponse"));
    }

    @Test
    @Order(6)
    void describeAutoScalingGroupsAfterSetDesired() {
        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<DesiredCapacity>1</DesiredCapacity>"));
    }

    @Test
    @Order(7)
    void updateAutoScalingGroup() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("MaxSize", "5")
                .formParam("DefaultCooldown", "180")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("UpdateAutoScalingGroupResponse"));

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<MaxSize>5</MaxSize>"))
                .body(containsString("<DefaultCooldown>180</DefaultCooldown>"));
    }

    // ── Target group attachment ───────────────────────────────────────────────

    @Test
    @Order(8)
    void attachLoadBalancerTargetGroups() {
        given()
                .formParam("Action", "AttachLoadBalancerTargetGroups")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("TargetGroupARNs.member.1",
                        "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-tg/abc123")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(9)
    void describeLoadBalancerTargetGroups() {
        given()
                .formParam("Action", "DescribeLoadBalancerTargetGroups")
                .formParam("AutoScalingGroupName", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("my-tg"));
    }

    @Test
    @Order(10)
    void detachLoadBalancerTargetGroups() {
        given()
                .formParam("Action", "DetachLoadBalancerTargetGroups")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("TargetGroupARNs.member.1",
                        "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-tg/abc123")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeLoadBalancerTargetGroups")
                .formParam("AutoScalingGroupName", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("my-tg")));
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void putLifecycleHook() {
        given()
                .formParam("Action", "PutLifecycleHook")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("LifecycleHookName", "launch-hook")
                .formParam("LifecycleTransition", "autoscaling:EC2_INSTANCE_LAUNCHING")
                .formParam("DefaultResult", "CONTINUE")
                .formParam("HeartbeatTimeout", "300")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(12)
    void describeLifecycleHooks() {
        given()
                .formParam("Action", "DescribeLifecycleHooks")
                .formParam("AutoScalingGroupName", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("launch-hook"))
                .body(containsString("autoscaling:EC2_INSTANCE_LAUNCHING"))
                .body(containsString("CONTINUE"));
    }

    @Test
    @Order(13)
    void deleteLifecycleHook() {
        given()
                .formParam("Action", "DeleteLifecycleHook")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("LifecycleHookName", "launch-hook")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeLifecycleHooks")
                .formParam("AutoScalingGroupName", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("launch-hook")));
    }

    // ── Scaling policies ───────────────────────────────────────────────────────

    @Test
    @Order(14)
    void putScalingPolicy() {
        policyArn = given()
                .formParam("Action", "PutScalingPolicy")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("PolicyName", "scale-out")
                .formParam("PolicyType", "SimpleScaling")
                .formParam("AdjustmentType", "ChangeInCapacity")
                .formParam("ScalingAdjustment", "1")
                .formParam("Cooldown", "60")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("PolicyARN"))
                .extract().xmlPath().getString("PutScalingPolicyResponse.PutScalingPolicyResult.PolicyARN");
    }

    @Test
    @Order(15)
    void describePolicies() {
        given()
                .formParam("Action", "DescribePolicies")
                .formParam("AutoScalingGroupName", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("scale-out"))
                .body(containsString("SimpleScaling"))
                .body(containsString("ChangeInCapacity"));
    }

    @Test
    @Order(16)
    void deletePolicy() {
        given()
                .formParam("Action", "DeletePolicy")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("PolicyName", "scale-out")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribePolicies")
                .formParam("AutoScalingGroupName", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("scale-out")));
    }

    @Test
    @Order(17)
    void putTargetTrackingScalingPolicy() {
        given()
                .formParam("Action", "PutScalingPolicy")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("PolicyName", "cpu-target")
                .formParam("PolicyType", "TargetTrackingScaling")
                .formParam("EstimatedInstanceWarmup", "180")
                .formParam("TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                        "ASGAverageCPUUtilization")
                .formParam("TargetTrackingConfiguration.TargetValue", "55.5")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("PolicyARN"));
    }

    @Test
    @Order(18)
    void describeTargetTrackingScalingPolicy() {
        given()
                .formParam("Action", "DescribePolicies")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("PolicyNames.member.1", "cpu-target")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<PolicyName>cpu-target</PolicyName>"))
                .body(containsString("<PolicyType>TargetTrackingScaling</PolicyType>"))
                .body(containsString("<EstimatedInstanceWarmup>180</EstimatedInstanceWarmup>"))
                .body(containsString("<TargetTrackingConfiguration>"))
                .body(containsString("<PredefinedMetricSpecification>"))
                .body(containsString("<PredefinedMetricType>ASGAverageCPUUtilization</PredefinedMetricType>"))
                .body(containsString("<TargetValue>55.5</TargetValue>"));
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Test
    @Order(19)
    void describeTerminationPolicyTypes() {
        given()
                .formParam("Action", "DescribeTerminationPolicyTypes")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("Default"))
                .body(containsString("OldestInstance"));
    }

    @Test
    @Order(20)
    void describeAccountLimits() {
        given()
                .formParam("Action", "DescribeAccountLimits")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("MaxNumberOfAutoScalingGroups"));
    }

    @Test
    @Order(21)
    void describeLifecycleHookTypes() {
        given()
                .formParam("Action", "DescribeLifecycleHookTypes")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("autoscaling:EC2_INSTANCE_LAUNCHING"))
                .body(containsString("autoscaling:EC2_INSTANCE_TERMINATING"));
    }

    @Test
    @Order(22)
    void describeScalingActivities() {
        given()
                .formParam("Action", "DescribeScalingActivities")
                .formParam("AutoScalingGroupName", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DescribeScalingActivitiesResponse"));
    }

    @Test
    @Order(23)
    void createAutoScalingGroupWithLaunchTemplateId() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("LaunchTemplate.LaunchTemplateId", "lt-0123456789abcdef0")
                .formParam("LaunchTemplate.Version", "$Latest")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("VPCZoneIdentifier", "subnet-12345678,subnet-87654321")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateAutoScalingGroupResponse"));

        given()
                .formParam("Action", "CreateOrUpdateTags")
                .formParam("Tags.member.1.ResourceId", "my-lt-asg")
                .formParam("Tags.member.1.ResourceType", "auto-scaling-group")
                .formParam("Tags.member.1.Key", "owner")
                .formParam("Tags.member.1.Value", "sample-app")
                .formParam("Tags.member.1.PropagateAtLaunch", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateOrUpdateTagsResponse"));
    }

    @Test
    @Order(24)
    void describeAutoScalingGroupWithLaunchTemplateId() {
        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-lt-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("my-lt-asg"))
                .body(containsString("<LaunchTemplateId>lt-0123456789abcdef0</LaunchTemplateId>"))
                .body(containsString("<Version>$Latest</Version>"))
                .body(containsString("<VPCZoneIdentifier>subnet-12345678,subnet-87654321</VPCZoneIdentifier>"))
                .body(containsString("owner"))
                .body(containsString("sample-app"));

        given()
                .formParam("Action", "DeleteTags")
                .formParam("Tags.member.1.ResourceId", "my-lt-asg")
                .formParam("Tags.member.1.ResourceType", "auto-scaling-group")
                .formParam("Tags.member.1.Key", "owner")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DeleteTagsResponse"));

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-lt-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("sample-app")));
    }

    @Test
    @Order(25)
    void startInstanceRefresh() {
        instanceRefreshId = given()
                .formParam("Action", "StartInstanceRefresh")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("Strategy", "Rolling")
                .formParam("DesiredConfiguration.LaunchTemplate.LaunchTemplateId", "lt-0fedcba9876543210")
                .formParam("DesiredConfiguration.LaunchTemplate.Version", "2")
                .formParam("Preferences.MinHealthyPercentage", "90")
                .formParam("Preferences.MaxHealthyPercentage", "120")
                .formParam("Preferences.InstanceWarmup", "200")
                .formParam("Preferences.SkipMatching", "true")
                .formParam("Preferences.AutoRollback", "true")
                .formParam("Preferences.CheckpointPercentages.member.1", "50")
                .formParam("Preferences.CheckpointPercentages.member.2", "100")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("StartInstanceRefreshResponse"))
                .body(containsString("InstanceRefreshId"))
                .extract().xmlPath()
                .getString("StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");

        assertThat(instanceRefreshId, not(emptyOrNullString()));
    }

    @Test
    @Order(26)
    void describeInstanceRefreshes() {
        String body = given()
                .formParam("Action", "DescribeInstanceRefreshes")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("InstanceRefreshIds.member.1", instanceRefreshId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DescribeInstanceRefreshesResponse"))
                .extract().body().asString();

        assertThat(body, containsString(instanceRefreshId));
        assertThat(body, containsString("<AutoScalingGroupName>my-lt-asg</AutoScalingGroupName>"));
        assertThat(body, containsString("<Status>Successful</Status>"));
        assertThat(body, containsString("<PercentageComplete>100</PercentageComplete>"));
        assertThat(body, containsString("<InstancesToUpdate>0</InstancesToUpdate>"));
        assertThat(body, containsString("<Strategy>Rolling</Strategy>"));
        assertThat(body, containsString("<LaunchTemplateId>lt-0fedcba9876543210</LaunchTemplateId>"));
        assertThat(body, containsString("<Version>2</Version>"));
        assertThat(body, containsString("<MinHealthyPercentage>90</MinHealthyPercentage>"));
        assertThat(body, containsString("<MaxHealthyPercentage>120</MaxHealthyPercentage>"));
        assertThat(body, containsString("<InstanceWarmup>200</InstanceWarmup>"));
        assertThat(body, containsString("<SkipMatching>true</SkipMatching>"));
        assertThat(body, containsString("<AutoRollback>true</AutoRollback>"));
        assertThat(body, containsString("<CheckpointPercentages>"));

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-lt-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<LaunchTemplateId>lt-0fedcba9876543210</LaunchTemplateId>"))
                .body(containsString("<Version>2</Version>"));
    }

    @Test
    @Order(27)
    void describeInstanceRefreshesPaginatesNewestFirst() {
        pagedInstanceRefreshId = given()
                .formParam("Action", "StartInstanceRefresh")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("Preferences.SkipMatching", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().xmlPath()
                .getString("StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");

        String firstPage = given()
                .formParam("Action", "DescribeInstanceRefreshes")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("MaxRecords", "1")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<NextToken>1</NextToken>"))
                .extract().body().asString();

        assertThat(firstPage, containsString(pagedInstanceRefreshId));
        assertThat(firstPage, not(containsString(instanceRefreshId)));

        given()
                .formParam("Action", "DescribeInstanceRefreshes")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("MaxRecords", "1")
                .formParam("NextToken", "1")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString(instanceRefreshId))
                .body(not(containsString("<NextToken>")));
    }

    @Test
    @Order(28)
    void createAutoScalingGroupWithMixedInstancesPolicy() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-mixed-asg")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        "lt-mixed")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "3")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "t4g.medium")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.2.InstanceType", "m7g.large")
                .formParam("MixedInstancesPolicy.InstancesDistribution.OnDemandBaseCapacity", "1")
                .formParam("MixedInstancesPolicy.InstancesDistribution.OnDemandPercentageAboveBaseCapacity", "25")
                .formParam("MixedInstancesPolicy.InstancesDistribution.SpotAllocationStrategy", "capacity-optimized")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "4")
                .formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateAutoScalingGroupResponse"));

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-mixed-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<MixedInstancesPolicy>"))
                .body(containsString("<LaunchTemplateSpecification>"))
                .body(containsString("<LaunchTemplateId>lt-mixed</LaunchTemplateId>"))
                .body(containsString("<Version>3</Version>"))
                .body(containsString("<InstanceType>t4g.medium</InstanceType>"))
                .body(containsString("<InstanceType>m7g.large</InstanceType>"))
                .body(containsString("<OnDemandBaseCapacity>1</OnDemandBaseCapacity>"))
                .body(containsString("<OnDemandPercentageAboveBaseCapacity>25</OnDemandPercentageAboveBaseCapacity>"))
                .body(containsString("<SpotAllocationStrategy>capacity-optimized</SpotAllocationStrategy>"));
    }

    @Test
    @Order(29)
    void updateAutoScalingGroupFromLaunchTemplateToMixedInstancesPolicy() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        "lt-replacement")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "4")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "c7g.large")
                .formParam("MixedInstancesPolicy.InstancesDistribution.OnDemandBaseCapacity", "0")
                .formParam("MixedInstancesPolicy.InstancesDistribution.OnDemandPercentageAboveBaseCapacity", "10")
                .formParam("MixedInstancesPolicy.InstancesDistribution.SpotAllocationStrategy", "lowest-price")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("UpdateAutoScalingGroupResponse"));

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-lt-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<MixedInstancesPolicy>"))
                .body(containsString("<LaunchTemplateId>lt-replacement</LaunchTemplateId>"))
                .body(containsString("<Version>4</Version>"))
                .body(containsString("<InstanceType>c7g.large</InstanceType>"))
                .body(containsString("<OnDemandBaseCapacity>0</OnDemandBaseCapacity>"))
                .body(containsString("<OnDemandPercentageAboveBaseCapacity>10</OnDemandPercentageAboveBaseCapacity>"))
                .body(containsString("<SpotAllocationStrategy>lowest-price</SpotAllocationStrategy>"));
    }

    @Test
    @Order(30)
    void updateMixedInstancesPolicyOverridesAndDistribution() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-mixed-asg")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        "lt-mixed")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "5")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "r7g.large")
                .formParam("MixedInstancesPolicy.InstancesDistribution.OnDemandBaseCapacity", "2")
                .formParam("MixedInstancesPolicy.InstancesDistribution.OnDemandPercentageAboveBaseCapacity", "50")
                .formParam("MixedInstancesPolicy.InstancesDistribution.SpotAllocationStrategy", "price-capacity-optimized")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("UpdateAutoScalingGroupResponse"));

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-mixed-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<Version>5</Version>"))
                .body(containsString("<InstanceType>r7g.large</InstanceType>"))
                .body(not(containsString("<InstanceType>t4g.medium</InstanceType>")))
                .body(containsString("<OnDemandBaseCapacity>2</OnDemandBaseCapacity>"))
                .body(containsString("<OnDemandPercentageAboveBaseCapacity>50</OnDemandPercentageAboveBaseCapacity>"))
                .body(containsString("<SpotAllocationStrategy>price-capacity-optimized</SpotAllocationStrategy>"));
    }

    @Test
    @Order(31)
    void deleteLaunchTemplateAutoScalingGroup() {
        given()
                .formParam("Action", "DeleteAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-lt-asg")
                .formParam("ForceDelete", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DeleteAutoScalingGroupResponse"));
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(32)
    void deleteAutoScalingGroup() {
        given()
                .formParam("Action", "DeleteAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-asg")
                .formParam("ForceDelete", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DeleteAutoScalingGroupResponse"));
    }

    @Test
    @Order(33)
    void deleteMixedInstancesAutoScalingGroup() {
        given()
                .formParam("Action", "DeleteAutoScalingGroup")
                .formParam("AutoScalingGroupName", "my-mixed-asg")
                .formParam("ForceDelete", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DeleteAutoScalingGroupResponse"));
    }

    @Test
    @Order(34)
    void deleteLaunchConfiguration() {
        given()
                .formParam("Action", "DeleteLaunchConfiguration")
                .formParam("LaunchConfigurationName", "my-lc")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DeleteLaunchConfigurationResponse"));
    }

    @Test
    @Order(35)
    void describeAutoScalingGroupsEmpty() {
        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "my-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("my-asg")));
    }
}
