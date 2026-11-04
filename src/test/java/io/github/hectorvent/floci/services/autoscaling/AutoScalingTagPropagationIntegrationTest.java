package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.Tag;
import software.amazon.awssdk.services.autoscaling.model.TagDescription;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingTagPropagationIntegrationTest {

    private static final String ACCOUNT = "585858585858";
    private static final String REGION = "us-east-1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261028/" + REGION + "/autoscaling/aws4_request";
    private static final String GROUP =
            "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member";
    private static final String TAGS = "DescribeTagsResponse.DescribeTagsResult.Tags.member";

    @Inject
    AutoScalingService service;

    @Inject
    AutoScalingReconciler reconciler;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"true", "false"})
    void createGroupHonorsTagPropagation(String flag) {
        verifyPropagation(flag, flag, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void createOrUpdateTagsHonorsTagPropagation(String flag) {
        verifyPropagation(flag, flag, true);
    }

    @ParameterizedTest
    @CsvSource({",false", "false,"})
    void omittedFlagDoesNotOverrideAnotherTagsExplicitFalse(String esFlag, String stackFlag) {
        verifyPropagation(esFlag, stackFlag, false);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void createOrUpdateTagsRejectsOmittedFlagBeforeChangingBatch(boolean existingTag, boolean invalidFirst) {
        String name = "tag-update-validation-" + UUID.randomUUID();
        try (AutoScalingClient client = autoScalingClient()) {
            client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-12345678").instanceType("t3.micro"));
            boolean groupCreated = false;
            try {
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(0).maxSize(1).desiredCapacity(0)
                        .availabilityZones(REGION + "a").tags(
                                tag -> tag.key("existing").value("before").propagateAtLaunch(true),
                                tag -> tag.key("stable").value("untouched").propagateAtLaunch(false)));
                groupCreated = true;
                Map<String, TagDescription> before = describeGroupTags(client, name);
                assertEquals(Map.of("existing", "before", "stable", "untouched"),
                        before.values().stream().collect(Collectors.toMap(TagDescription::key, TagDescription::value)));
                assertTrue(before.get("existing").propagateAtLaunch());
                assertEquals(false, before.get("stable").propagateAtLaunch());

                List<Tag> batch = new ArrayList<>(List.of(
                        Tag.builder().resourceId(name).resourceType("auto-scaling-group")
                                .key("existing").value("changed").propagateAtLaunch(false).build(),
                        Tag.builder().resourceId(name).resourceType("auto-scaling-group")
                                .key("added").value("new").propagateAtLaunch(true).build()));
                Tag invalid = Tag.builder().resourceId(name).resourceType("auto-scaling-group")
                        .key(existingTag ? "stable" : "missing").value("invalid").build();
                batch.add(invalidFirst ? 0 : batch.size(), invalid);
                AutoScalingException error = assertThrows(AutoScalingException.class,
                        () -> client.createOrUpdateTags(request -> request.tags(batch)));
                assertEquals(400, error.statusCode());
                assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                assertTrue(error.awsErrorDetails().errorMessage()
                        .contains("Tags.member." + (invalidFirst ? 1 : 3) + ".PropagateAtLaunch"));
                assertEquals(before, describeGroupTags(client, name));
            } finally {
                try {
                    if (groupCreated) {
                        client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                    }
                } finally {
                    client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
                }
            }
        }
    }

    @Test
    void deleteTagsAllowsOmittedPropagationFlag() {
        String name = "tag-delete-validation-" + UUID.randomUUID();
        try (AutoScalingClient client = autoScalingClient()) {
            client.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-12345678").instanceType("t3.micro"));
            boolean groupCreated = false;
            try {
                client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(0).maxSize(1).desiredCapacity(0)
                        .availabilityZones(REGION + "a").tags(
                                tag -> tag.key("delete").value("removed").propagateAtLaunch(true),
                                tag -> tag.key("keep").value("retained").propagateAtLaunch(false)));
                groupCreated = true;
                TagDescription retained = describeGroupTags(client, name).get("keep");
                client.deleteTags(request -> request.tags(tag -> tag.resourceId(name)
                        .resourceType("auto-scaling-group").key("delete")));
                assertEquals(Map.of("keep", retained), describeGroupTags(client, name));
            } finally {
                try {
                    if (groupCreated) {
                        client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                    }
                } finally {
                    client.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
                }
            }
        }
    }

    private static AutoScalingClient autoScalingClient() {
        return AutoScalingClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build();
    }

    private static Map<String, TagDescription> describeGroupTags(AutoScalingClient client, String name) {
        return client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst().tags().stream()
                .collect(Collectors.toMap(TagDescription::key, tag -> tag));
    }

    private void verifyPropagation(String esFlag, String stackFlag, boolean update) {
        String name = "tag-propagation-" + UUID.randomUUID();
        boolean propagateEs = !"false".equals(esFlag);
        boolean propagateStack = !"false".equals(stackFlag);
        Map<String, String> expectedTags = new HashMap<>();
        if (propagateEs) {
            expectedTags.put("es", name);
        }
        if (propagateStack) {
            expectedTags.put("stack", "test");
        }
        request("CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name)
                .formParam("ImageId", "ami-12345678")
                .formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        boolean groupCreated = false;
        try {
            RequestSpecification create = request("CreateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name)
                    .formParam("LaunchConfigurationName", name)
                    .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 0)
                    .formParam("AvailabilityZones.member.1", REGION + "a");
            if (update) {
                create.formParam("Tags.member.1.Key", "stack").formParam("Tags.member.1.Value", "old")
                        .formParam("Tags.member.1.PropagateAtLaunch", String.valueOf(!propagateStack));
            } else {
                addTags(create, name, esFlag, stackFlag, false);
            }
            create.post("/").then().statusCode(200);
            groupCreated = true;
            if (update) {
                addTags(request("CreateOrUpdateTags"), name, esFlag, stackFlag, true)
                        .post("/").then().statusCode(200);
            }
            request("SetDesiredCapacity").formParam("AutoScalingGroupName", name)
                    .formParam("DesiredCapacity", 1).post("/").then().statusCode(200);
            RequestScopes.runAs(ACCOUNT, REGION, () -> reconciler.reconcile(
                    service.describeAutoScalingGroups(REGION, List.of(name)).getFirst()));

            XmlPath group = request("DescribeAutoScalingGroups")
                    .formParam("AutoScalingGroupNames.member.1", name)
                    .post("/").then().statusCode(200).extract().xmlPath();
            List<String> instanceIds = group.getList(GROUP + ".Instances.member.InstanceId");
            assertEquals(1, instanceIds.size());
            assertInstanceTags(name, instanceIds.getFirst(), expectedTags);

            assertEquals(String.valueOf(propagateEs),
                    group.getString(GROUP + ".Tags.member.find { it.Key == 'es' }.PropagateAtLaunch"));
            assertEquals(String.valueOf(propagateStack),
                    group.getString(GROUP + ".Tags.member.find { it.Key == 'stack' }.PropagateAtLaunch"));
            XmlPath tags = request("DescribeTags")
                    .formParam("Filters.member.1.Name", "auto-scaling-group")
                    .formParam("Filters.member.1.Values.member.1", name)
                    .post("/").then().statusCode(200).extract().xmlPath();
            assertEquals(List.of("es", "stack"), tags.getList(TAGS + ".Key"));
            assertEquals(List.of(String.valueOf(propagateEs), String.valueOf(propagateStack)),
                    tags.getList(TAGS + ".PropagateAtLaunch"));
            XmlPath filtered = request("DescribeTags")
                    .formParam("Filters.member.1.Name", "auto-scaling-group")
                    .formParam("Filters.member.1.Values.member.1", name)
                    .formParam("Filters.member.2.Name", "propagate-at-launch")
                    .formParam("Filters.member.2.Values.member.1", "true")
                    .post("/").then().statusCode(200).extract().xmlPath();
            assertEquals(expectedTags.keySet().stream().sorted().toList(), filtered.getList(TAGS + ".Key"));
        } finally {
            try {
                if (groupCreated) {
                    request("DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                            .formParam("ForceDelete", true).post("/").then().statusCode(200);
                }
            } finally {
                request("DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                        .post("/").then().statusCode(200);
            }
        }
    }

    private static RequestSpecification addTags(RequestSpecification request, String name,
                                                 String esFlag, String stackFlag, boolean update) {
        request.formParam("Tags.member.1.Key", "es").formParam("Tags.member.1.Value", name)
                .formParam("Tags.member.2.Key", "stack").formParam("Tags.member.2.Value", "test");
        for (int i = 1; i <= 2; i++) {
            String prefix = "Tags.member." + i + ".";
            String flag = i == 1 ? esFlag : stackFlag;
            if (flag != null) {
                request.formParam(prefix + "PropagateAtLaunch", flag);
            }
            if (update) {
                request.formParam(prefix + "ResourceId", name)
                        .formParam(prefix + "ResourceType", "auto-scaling-group");
            }
        }
        return request;
    }

    private static void assertInstanceTags(String name, String instanceId, Map<String, String> expectedTags) {
        try (Ec2Client ec2 = Ec2Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build()) {
            List<Instance> instances = ec2.describeInstances(request -> request.instanceIds(instanceId))
                    .reservations().stream().flatMap(reservation -> reservation.instances().stream()).toList();
            assertEquals(1, instances.size());
            Map<String, String> tags = instances.getFirst().tags().stream()
                    .collect(Collectors.toMap(tag -> tag.key(), tag -> tag.value()));
            assertEquals(name, tags.get("aws:autoscaling:groupName"));
            tags.keySet().removeIf(key -> key.startsWith("aws:"));
            assertEquals(expectedTags, tags);

            List<String> discovered = ec2.describeInstances(request -> request.filters(
                            Filter.builder().name("tag:es").values(name).build(),
                            Filter.builder().name("tag:stack").values("test").build()))
                    .reservations().stream().flatMap(reservation -> reservation.instances().stream())
                    .map(Instance::instanceId).toList();
            assertEquals(expectedTags.size() == 2 ? List.of(instanceId) : List.of(), discovered);
        }
    }

    private static RequestSpecification request(String action) {
        return given().header("Authorization", AUTH).formParam("Action", action);
    }
}
