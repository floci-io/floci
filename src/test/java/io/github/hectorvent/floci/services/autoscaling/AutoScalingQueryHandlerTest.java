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
                java.util.Map.of());

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
    void describeAutoScalingGroupsIncludesInstanceLaunchTemplateMetadata() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION,
                "query-asg",
                null,
                "lt-current",
                null,
                "$Latest",
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
                java.util.Map.of());
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
                java.util.Map.of());

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
}
