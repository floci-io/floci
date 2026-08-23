package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoScalingQueryHandlerTest {

    private static final String REGION = "us-east-1";

    @Test
    void startAndDescribeInstanceRefreshUseAwsQueryXmlShape() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION,
                "query-asg",
                null,
                "lt-original",
                null,
                "1",
                null,
                0,
                3,
                1,
                300,
                List.of("us-east-1a"),
                List.of(),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                java.util.Map.of(), java.util.Map.of());

        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);
        MultivaluedHashMap<String, String> startParams = new MultivaluedHashMap<>();
        startParams.add("AutoScalingGroupName", "query-asg");
        startParams.add("DesiredConfiguration.LaunchTemplate.LaunchTemplateId", "lt-updated");
        startParams.add("DesiredConfiguration.LaunchTemplate.Version", "2");
        startParams.add("Preferences.MinHealthyPercentage", "90");
        startParams.add("Preferences.SkipMatching", "true");

        Response startResponse = handler.handle("StartInstanceRefresh", startParams, REGION);

        assertEquals(200, startResponse.getStatus());
        String startXml = (String) startResponse.getEntity();
        assertTrue(startXml.contains("<StartInstanceRefreshResponse"));
        assertTrue(startXml.contains("<StartInstanceRefreshResult>"));
        assertTrue(startXml.contains("<InstanceRefreshId>"));
        String refreshId = service.describeInstanceRefreshes(REGION, "query-asg", List.of(), null, null)
                .instanceRefreshes().getFirst().getInstanceRefreshId();

        MultivaluedHashMap<String, String> describeParams = new MultivaluedHashMap<>();
        describeParams.add("AutoScalingGroupName", "query-asg");
        describeParams.add("InstanceRefreshIds.member.1", refreshId);

        Response describeResponse = handler.handle("DescribeInstanceRefreshes", describeParams, REGION);

        assertEquals(200, describeResponse.getStatus());
        String describeXml = (String) describeResponse.getEntity();
        assertTrue(describeXml.contains("<DescribeInstanceRefreshesResponse"));
        assertTrue(describeXml.contains("<InstanceRefreshes>"));
        assertTrue(describeXml.contains("<InstanceRefreshId>" + refreshId + "</InstanceRefreshId>"));
        assertTrue(describeXml.contains("<AutoScalingGroupName>query-asg</AutoScalingGroupName>"));
        assertTrue(describeXml.contains("<Status>Successful</Status>"));
        assertTrue(describeXml.contains("<PercentageComplete>100</PercentageComplete>"));
        assertTrue(describeXml.contains("<InstancesToUpdate>0</InstancesToUpdate>"));
        assertTrue(describeXml.contains("<DesiredConfiguration>"));
        assertTrue(describeXml.contains("<LaunchTemplateId>lt-updated</LaunchTemplateId>"));
        assertTrue(describeXml.contains("<Version>2</Version>"));
        assertTrue(describeXml.contains("<Preferences>"));
        assertTrue(describeXml.contains("<MinHealthyPercentage>90</MinHealthyPercentage>"));
        assertTrue(describeXml.contains("<SkipMatching>true</SkipMatching>"));
    }

    @Test
    void updateAutoScalingGroupRejectsDesiredConfigurationChangeDuringActiveInstanceRefresh() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION,
                "query-asg",
                null,
                "lt-original",
                null,
                "1",
                null,
                0,
                3,
                1,
                300,
                List.of("us-east-1a"),
                List.of(),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                java.util.Map.of(),
                java.util.Map.of());
        AsgInstance instance = new AsgInstance();
        instance.setInstanceId("i-original");
        instance.setAvailabilityZone("us-east-1a");
        instance.setLifecycleState("InService");
        instance.setHealthStatus("Healthy");
        instance.setLaunchTemplateId("lt-original");
        instance.setLaunchTemplateVersion("1");
        service.describeAutoScalingGroups(REGION, List.of("query-asg"))
                .getFirst()
                .getInstances()
                .add(instance);

        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);
        MultivaluedHashMap<String, String> startParams = new MultivaluedHashMap<>();
        startParams.add("AutoScalingGroupName", "query-asg");
        startParams.add("DesiredConfiguration.LaunchTemplate.LaunchTemplateId", "lt-refresh");
        startParams.add("DesiredConfiguration.LaunchTemplate.Version", "2");
        assertEquals(200, handler.handle("StartInstanceRefresh", startParams, REGION).getStatus());

        MultivaluedHashMap<String, String> updateParams = new MultivaluedHashMap<>();
        updateParams.add("AutoScalingGroupName", "query-asg");
        updateParams.add("LaunchTemplate.LaunchTemplateId", "lt-next");
        updateParams.add("LaunchTemplate.Version", "3");

        Response response = handler.handle("UpdateAutoScalingGroup", updateParams, REGION);

        assertEquals(400, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<Code>ValidationError</Code>"));
        assertTrue(xml.contains(AutoScalingService.ACTIVE_INSTANCE_REFRESH_DESIRED_CONFIGURATION_MESSAGE));
    }

    @Test
    void describeAutoScalingGroupsIncludesInstanceLaunchTemplateMetadata() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION,
                "query-asg",
                null,
                "lt-current",
                null,
                "$Latest",
                null,
                0,
                3,
                1,
                300,
                List.of("us-east-1a"),
                List.of(),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                java.util.Map.of(), java.util.Map.of());
        AsgInstance instance = new AsgInstance();
        instance.setInstanceId("i-current");
        instance.setAvailabilityZone("us-east-1a");
        instance.setLifecycleState("InService");
        instance.setHealthStatus("Healthy");
        instance.setLaunchTemplateId("lt-current");
        instance.setLaunchTemplateVersion("7");
        service.describeAutoScalingGroups(REGION, List.of("query-asg"))
                .getFirst()
                .getInstances()
                .add(instance);

        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AutoScalingGroupNames.member.1", "query-asg");

        Response response = handler.handle("DescribeAutoScalingGroups", params, REGION);

        assertEquals(200, response.getStatus());
        String xml = (String) response.getEntity();
        assertTrue(xml.contains("<InstanceId>i-current</InstanceId>"));
        assertTrue(xml.contains("<LaunchTemplate>"));
        assertTrue(xml.contains("<LaunchTemplateId>lt-current</LaunchTemplateId>"));
        assertTrue(xml.contains("<Version>7</Version>"));
    }

    @Test
    void targetTrackingScalingPolicyUsesAwsQueryXmlShape() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION,
                "target-tracking-asg",
                null,
                "lt-current",
                null,
                "$Latest",
                null,
                0,
                3,
                1,
                300,
                List.of("us-east-1a"),
                List.of(),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                java.util.Map.of(), java.util.Map.of());

        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);
        MultivaluedHashMap<String, String> putParams = new MultivaluedHashMap<>();
        putParams.add("AutoScalingGroupName", "target-tracking-asg");
        putParams.add("PolicyName", "cpu-target");
        putParams.add("PolicyType", "TargetTrackingScaling");
        putParams.add("EstimatedInstanceWarmup", "180");
        putParams.add("TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                "ASGAverageCPUUtilization");
        putParams.add("TargetTrackingConfiguration.TargetValue", "55.5");

        Response putResponse = handler.handle("PutScalingPolicy", putParams, REGION);

        assertEquals(200, putResponse.getStatus());
        assertTrue(((String) putResponse.getEntity()).contains("<PolicyARN>"));

        MultivaluedHashMap<String, String> describeParams = new MultivaluedHashMap<>();
        describeParams.add("AutoScalingGroupName", "target-tracking-asg");
        describeParams.add("PolicyNames.member.1", "cpu-target");

        Response describeResponse = handler.handle("DescribePolicies", describeParams, REGION);

        assertEquals(200, describeResponse.getStatus());
        String xml = (String) describeResponse.getEntity();
        assertTrue(xml.contains("<PolicyName>cpu-target</PolicyName>"));
        assertTrue(xml.contains("<PolicyType>TargetTrackingScaling</PolicyType>"));
        assertTrue(xml.contains("<EstimatedInstanceWarmup>180</EstimatedInstanceWarmup>"));
        assertTrue(xml.contains("<TargetTrackingConfiguration>"));
        assertTrue(xml.contains("<PredefinedMetricSpecification>"));
        assertTrue(xml.contains("<PredefinedMetricType>ASGAverageCPUUtilization</PredefinedMetricType>"));
        assertTrue(xml.contains("<TargetValue>55.5</TargetValue>"));
    }

    @Test
    void createAutoScalingGroupWithMixedInstancesPolicyUsesAwsQueryXmlShape() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);

        MultivaluedHashMap<String, String> createParams = new MultivaluedHashMap<>();
        createParams.add("AutoScalingGroupName", "mixed-asg");
        createParams.add("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                "lt-mixed");
        createParams.add("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "3");
        createParams.add("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "t4g.medium");
        createParams.add("MixedInstancesPolicy.LaunchTemplate.Overrides.member.2.InstanceType", "m7g.large");
        createParams.add("MixedInstancesPolicy.InstancesDistribution.OnDemandBaseCapacity", "1");
        createParams.add("MixedInstancesPolicy.InstancesDistribution.OnDemandPercentageAboveBaseCapacity", "25");
        createParams.add("MixedInstancesPolicy.InstancesDistribution.SpotAllocationStrategy", "capacity-optimized");
        createParams.add("MinSize", "0");
        createParams.add("MaxSize", "4");
        createParams.add("DesiredCapacity", "1");
        createParams.add("AvailabilityZones.member.1", "us-east-1a");

        Response createResponse = handler.handle("CreateAutoScalingGroup", createParams, REGION);

        assertEquals(200, createResponse.getStatus());

        MultivaluedHashMap<String, String> describeParams = new MultivaluedHashMap<>();
        describeParams.add("AutoScalingGroupNames.member.1", "mixed-asg");

        Response describeResponse = handler.handle("DescribeAutoScalingGroups", describeParams, REGION);

        assertEquals(200, describeResponse.getStatus());
        String xml = (String) describeResponse.getEntity();
        assertTrue(xml.contains("<MixedInstancesPolicy>"));
        assertTrue(xml.contains("<LaunchTemplateSpecification>"));
        assertTrue(xml.contains("<LaunchTemplateId>lt-mixed</LaunchTemplateId>"));
        assertTrue(xml.contains("<Version>3</Version>"));
        assertTrue(xml.contains("<Overrides>"));
        assertTrue(xml.contains("<InstanceType>t4g.medium</InstanceType>"));
        assertTrue(xml.contains("<InstanceType>m7g.large</InstanceType>"));
        assertTrue(xml.contains("<OnDemandBaseCapacity>1</OnDemandBaseCapacity>"));
        assertTrue(xml.contains("<OnDemandPercentageAboveBaseCapacity>25</OnDemandPercentageAboveBaseCapacity>"));
        assertTrue(xml.contains("<SpotAllocationStrategy>capacity-optimized</SpotAllocationStrategy>"));
    }

    // The four tests below are corpus-autoscaling-complete's own regression:
    // aws_autoscaling_policy's Enabled, StepAdjustments and
    // PredictiveScalingConfiguration had no field on the model at all (PUT
    // accepted and silently dropped every one of them), and Cooldown
    // defaulted to 300 for every policy type rather than only SimpleScaling
    // - real AWS documents Cooldown as "only used if PolicyType is
    // SimpleScaling, otherwise ignored". Field/element names are taken from
    // aws-sdk-go-v2's service/autoscaling/types (StepAdjustment,
    // PredictiveScalingConfiguration, PredictiveScalingMetricSpecification),
    // not guessed.

    private AutoScalingQueryHandler newHandlerWithGroup(String asgName) {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);

        MultivaluedHashMap<String, String> createParams = new MultivaluedHashMap<>();
        createParams.add("AutoScalingGroupName", asgName);
        createParams.add("LaunchTemplate.LaunchTemplateId", "lt-policy-test");
        createParams.add("MinSize", "0");
        createParams.add("MaxSize", "1");
        createParams.add("AvailabilityZones.member.1", "us-east-1a");
        assertEquals(200, handler.handle("CreateAutoScalingGroup", createParams, REGION).getStatus());
        return handler;
    }

    @Test
    void stepScalingPolicyRoundTripsEnabledAndStepAdjustmentsWithNoCooldownAtAll() {
        AutoScalingQueryHandler handler = newHandlerWithGroup("step-policy-asg");

        MultivaluedHashMap<String, String> putParams = new MultivaluedHashMap<>();
        putParams.add("AutoScalingGroupName", "step-policy-asg");
        putParams.add("PolicyName", "scale-out");
        putParams.add("PolicyType", "StepScaling");
        putParams.add("AdjustmentType", "ExactCapacity");
        putParams.add("StepAdjustments.member.1.ScalingAdjustment", "1");
        putParams.add("StepAdjustments.member.1.MetricIntervalLowerBound", "0");
        putParams.add("StepAdjustments.member.1.MetricIntervalUpperBound", "10");
        putParams.add("StepAdjustments.member.2.ScalingAdjustment", "2");
        putParams.add("StepAdjustments.member.2.MetricIntervalLowerBound", "10");
        assertEquals(200, handler.handle("PutScalingPolicy", putParams, REGION).getStatus());

        MultivaluedHashMap<String, String> describeParams = new MultivaluedHashMap<>();
        describeParams.add("AutoScalingGroupName", "step-policy-asg");
        Response describeResponse = handler.handle("DescribePolicies", describeParams, REGION);
        assertEquals(200, describeResponse.getStatus());
        String xml = (String) describeResponse.getEntity();

        // Enabled defaults true - never sent, never modeled before this fix.
        assertTrue(xml.contains("<Enabled>true</Enabled>"), xml);
        // StepScaling: Cooldown was never sent, and must not be invented.
        assertTrue(!xml.contains("<Cooldown>"), xml);
        assertTrue(xml.contains("<StepAdjustments>"), xml);
        assertTrue(xml.contains("<ScalingAdjustment>1</ScalingAdjustment>"), xml);
        assertTrue(xml.contains("<MetricIntervalLowerBound>0.0</MetricIntervalLowerBound>"), xml);
        assertTrue(xml.contains("<MetricIntervalUpperBound>10.0</MetricIntervalUpperBound>"), xml);
        assertTrue(xml.contains("<ScalingAdjustment>2</ScalingAdjustment>"), xml);
        assertTrue(xml.contains("<MetricIntervalLowerBound>10.0</MetricIntervalLowerBound>"), xml);
    }

    @Test
    void simpleScalingPolicyDefaultsCooldownTo300OnlyWhenNoneIsSent() {
        AutoScalingQueryHandler handler = newHandlerWithGroup("simple-policy-asg");

        MultivaluedHashMap<String, String> putParams = new MultivaluedHashMap<>();
        putParams.add("AutoScalingGroupName", "simple-policy-asg");
        putParams.add("PolicyName", "simple");
        putParams.add("PolicyType", "SimpleScaling");
        putParams.add("AdjustmentType", "ChangeInCapacity");
        putParams.add("ScalingAdjustment", "1");
        assertEquals(200, handler.handle("PutScalingPolicy", putParams, REGION).getStatus());

        MultivaluedHashMap<String, String> describeParams = new MultivaluedHashMap<>();
        describeParams.add("AutoScalingGroupName", "simple-policy-asg");
        String xml = (String) handler.handle("DescribePolicies", describeParams, REGION).getEntity();
        assertTrue(xml.contains("<Cooldown>300</Cooldown>"), xml);

        MultivaluedHashMap<String, String> putExplicit = new MultivaluedHashMap<>();
        putExplicit.add("AutoScalingGroupName", "simple-policy-asg");
        putExplicit.add("PolicyName", "simple-explicit");
        putExplicit.add("PolicyType", "SimpleScaling");
        putExplicit.add("AdjustmentType", "ChangeInCapacity");
        putExplicit.add("ScalingAdjustment", "1");
        putExplicit.add("Cooldown", "60");
        assertEquals(200, handler.handle("PutScalingPolicy", putExplicit, REGION).getStatus());
        String xml2 = (String) handler.handle("DescribePolicies", describeParams, REGION).getEntity();
        assertTrue(xml2.contains("<Cooldown>60</Cooldown>"), xml2);
    }

    @Test
    void predictiveScalingPolicyRoundTripsItsWholeConfiguration() {
        AutoScalingQueryHandler handler = newHandlerWithGroup("predictive-policy-asg");

        MultivaluedHashMap<String, String> putParams = new MultivaluedHashMap<>();
        putParams.add("AutoScalingGroupName", "predictive-policy-asg");
        putParams.add("PolicyName", "predictive-scaling");
        putParams.add("PolicyType", "PredictiveScaling");
        putParams.add("PredictiveScalingConfiguration.Mode", "ForecastAndScale");
        putParams.add("PredictiveScalingConfiguration.SchedulingBufferTime", "10");
        putParams.add("PredictiveScalingConfiguration.MaxCapacityBreachBehavior", "IncreaseMaxCapacity");
        putParams.add("PredictiveScalingConfiguration.MaxCapacityBuffer", "10");
        putParams.add("PredictiveScalingConfiguration.MetricSpecifications.member.1.TargetValue", "32");
        putParams.add("PredictiveScalingConfiguration.MetricSpecifications.member.1."
                + "PredefinedScalingMetricSpecification.PredefinedMetricType", "ASGAverageCPUUtilization");
        putParams.add("PredictiveScalingConfiguration.MetricSpecifications.member.1."
                + "PredefinedScalingMetricSpecification.ResourceLabel", "testLabel");
        putParams.add("PredictiveScalingConfiguration.MetricSpecifications.member.1."
                + "PredefinedLoadMetricSpecification.PredefinedMetricType", "ASGTotalCPUUtilization");
        putParams.add("PredictiveScalingConfiguration.MetricSpecifications.member.1."
                + "PredefinedLoadMetricSpecification.ResourceLabel", "testLabel");
        assertEquals(200, handler.handle("PutScalingPolicy", putParams, REGION).getStatus());

        MultivaluedHashMap<String, String> describeParams = new MultivaluedHashMap<>();
        describeParams.add("AutoScalingGroupName", "predictive-policy-asg");
        String xml = (String) handler.handle("DescribePolicies", describeParams, REGION).getEntity();

        assertTrue(xml.contains("<PredictiveScalingConfiguration>"), xml);
        assertTrue(xml.contains("<Mode>ForecastAndScale</Mode>"), xml);
        assertTrue(xml.contains("<SchedulingBufferTime>10</SchedulingBufferTime>"), xml);
        assertTrue(xml.contains("<MaxCapacityBreachBehavior>IncreaseMaxCapacity</MaxCapacityBreachBehavior>"), xml);
        assertTrue(xml.contains("<MaxCapacityBuffer>10</MaxCapacityBuffer>"), xml);
        assertTrue(xml.contains("<TargetValue>32.0</TargetValue>"), xml);
        assertTrue(xml.contains("<PredefinedScalingMetricSpecification>"), xml);
        assertTrue(xml.contains("<PredefinedMetricType>ASGAverageCPUUtilization</PredefinedMetricType>"), xml);
        assertTrue(xml.contains("<PredefinedLoadMetricSpecification>"), xml);
        assertTrue(xml.contains("<PredefinedMetricType>ASGTotalCPUUtilization</PredefinedMetricType>"), xml);
        // Both predefined specs share ResourceLabel "testLabel" - two occurrences.
        assertEquals(2, xml.split("<ResourceLabel>testLabel</ResourceLabel>", -1).length - 1);
    }

    @Test
    void targetTrackingPolicyRoundTripsResourceLabelOnThePredefinedMetric() {
        AutoScalingQueryHandler handler = newHandlerWithGroup("tt-policy-asg");

        MultivaluedHashMap<String, String> putParams = new MultivaluedHashMap<>();
        putParams.add("AutoScalingGroupName", "tt-policy-asg");
        putParams.add("PolicyName", "request-count-per-target");
        putParams.add("PolicyType", "TargetTrackingScaling");
        putParams.add("TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                "ALBRequestCountPerTarget");
        putParams.add("TargetTrackingConfiguration.PredefinedMetricSpecification.ResourceLabel",
                "app/complete/hash/targetgroup/tf-abc/def");
        putParams.add("TargetTrackingConfiguration.TargetValue", "800");
        assertEquals(200, handler.handle("PutScalingPolicy", putParams, REGION).getStatus());

        MultivaluedHashMap<String, String> describeParams = new MultivaluedHashMap<>();
        describeParams.add("AutoScalingGroupName", "tt-policy-asg");
        String xml = (String) handler.handle("DescribePolicies", describeParams, REGION).getEntity();

        assertTrue(xml.contains("<ResourceLabel>app/complete/hash/targetgroup/tf-abc/def</ResourceLabel>"), xml);
        assertTrue(xml.contains("<PredefinedMetricType>ALBRequestCountPerTarget</PredefinedMetricType>"), xml);
        assertTrue(xml.contains("<TargetValue>800.0</TargetValue>"), xml);
    }
}
