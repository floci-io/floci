package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AttributePayload;
import software.amazon.awssdk.services.iot.model.AttachPolicyRequest;
import software.amazon.awssdk.services.iot.model.AttachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.CertificateStatus;
import software.amazon.awssdk.services.iot.model.Action;
import software.amazon.awssdk.services.iot.model.AddThingToThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateJobRequest;
import software.amazon.awssdk.services.iot.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iot.model.CreateThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.CreateThingTypeRequest;
import software.amazon.awssdk.services.iot.model.CreateTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingGroupRequest;
import software.amazon.awssdk.services.iot.model.DeleteTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingTypeRequest;
import software.amazon.awssdk.services.iot.model.DescribeCertificateRequest;
import software.amazon.awssdk.services.iot.model.DescribeEndpointRequest;
import software.amazon.awssdk.services.iot.model.DescribeJobRequest;
import software.amazon.awssdk.services.iot.model.DescribeThingRequest;
import software.amazon.awssdk.services.iot.model.DescribeThingTypeRequest;
import software.amazon.awssdk.services.iot.model.DisableTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.DeprecateThingTypeRequest;
import software.amazon.awssdk.services.iot.model.DetachPolicyRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.EnableTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.GetTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.ListCertificatesRequest;
import software.amazon.awssdk.services.iot.model.ListJobExecutionsForThingRequest;
import software.amazon.awssdk.services.iot.model.ListJobsRequest;
import software.amazon.awssdk.services.iot.model.ListPoliciesRequest;
import software.amazon.awssdk.services.iot.model.ListThingGroupsForThingRequest;
import software.amazon.awssdk.services.iot.model.ListThingsInThingGroupRequest;
import software.amazon.awssdk.services.iot.model.ListThingsRequest;
import software.amazon.awssdk.services.iot.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.iot.model.ListThingPrincipalsRequest;
import software.amazon.awssdk.services.iot.model.ListThingTypesRequest;
import software.amazon.awssdk.services.iot.model.ListTopicRulesRequest;
import software.amazon.awssdk.services.iot.model.RemoveThingFromThingGroupRequest;
import software.amazon.awssdk.services.iot.model.SqsAction;
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.Tag;
import software.amazon.awssdk.services.iot.model.TagResourceRequest;
import software.amazon.awssdk.services.iot.model.ThingGroupProperties;
import software.amazon.awssdk.services.iot.model.ThingTypeProperties;
import software.amazon.awssdk.services.iot.model.TopicRulePayload;
import software.amazon.awssdk.services.iot.model.UntagResourceRequest;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;
import software.amazon.awssdk.services.iot.model.UpdateThingRequest;
import software.amazon.awssdk.services.iot.model.UpdateThingTypeRequest;
import software.amazon.awssdk.services.iot.model.VersionConflictException;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.iotdataplane.model.DeleteConnectionRequest;
import software.amazon.awssdk.services.iotdataplane.model.DeleteThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.GetThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.ListNamedShadowsForThingRequest;
import software.amazon.awssdk.services.iotdataplane.model.PublishRequest;
import software.amazon.awssdk.services.iotdataplane.model.UpdateThingShadowRequest;
import software.amazon.awssdk.services.iotjobsdataplane.IotJobsDataPlaneClient;
import software.amazon.awssdk.services.iotjobsdataplane.model.GetPendingJobExecutionsRequest;
import software.amazon.awssdk.services.iotjobsdataplane.model.JobExecutionStatus;
import software.amazon.awssdk.services.iotjobsdataplane.model.StartNextPendingJobExecutionRequest;
import software.amazon.awssdk.services.iotjobsdataplane.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AWS IoT")
class IotTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    private final IotClient iot = TestFixtures.iotClient();
    private final IotDataPlaneClient iotData = TestFixtures.iotDataClient();
    private final IotJobsDataPlaneClient iotJobsData = TestFixtures.iotJobsDataClient();
    private final SqsClient sqs = TestFixtures.sqsClient();

    @Test
    void describeEndpoint() {
        var response = iot.describeEndpoint(DescribeEndpointRequest.builder()
                .endpointType("iot:Data-ATS")
                .build());

        assertThat(response.endpointAddress()).isNotBlank();
    }

    @Test
    void thingRegistryCrud() {
        String thingName = "java-iot-thing";
        String otherThingName = "java-iot-other-thing";
        try {
            iot.deleteThing(DeleteThingRequest.builder().thingName(thingName).build());
            iot.deleteThing(DeleteThingRequest.builder().thingName(otherThingName).build());
        } catch (Exception ignored) {
        }

        assertThatThrownBy(() -> iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build()))
                .isInstanceOf(ResourceNotFoundException.class);

        var created = iot.createThing(CreateThingRequest.builder()
                .thingName(thingName)
                .attributePayload(AttributePayload.builder().attributes(Map.of("env", "java")).build())
                .build());
        assertThat(created.thingName()).isEqualTo(thingName);
        assertThat(created.thingArn()).endsWith(":thing/" + thingName);

        var idempotent = iot.createThing(CreateThingRequest.builder()
                .thingName(thingName)
                .attributePayload(AttributePayload.builder().attributes(Map.of("env", "java")).build())
                .build());
        assertThat(idempotent.thingName()).isEqualTo(thingName);

        assertThatThrownBy(() -> iot.createThing(CreateThingRequest.builder().thingName(thingName).build()))
                .isInstanceOf(ResourceAlreadyExistsException.class);

        var described = iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build());
        assertThat(described.attributes()).containsEntry("env", "java");

        iot.createThing(CreateThingRequest.builder().thingName(otherThingName).build());

        var listed = iot.listThings(ListThingsRequest.builder().build());
        assertThat(listed.things()).anyMatch(thing -> thingName.equals(thing.thingName()));

        var firstPage = iot.listThings(ListThingsRequest.builder().maxResults(1).build());
        assertThat(firstPage.things()).hasSize(1);
        assertThat(firstPage.nextToken()).isNotBlank();
        var secondPage = iot.listThings(ListThingsRequest.builder()
                .maxResults(1)
                .nextToken(firstPage.nextToken())
                .build());
        assertThat(secondPage.things()).hasSize(1);

        iot.updateThing(UpdateThingRequest.builder()
                .thingName(thingName)
                .attributePayload(AttributePayload.builder()
                        .attributes(Map.of("env", "updated", "owner", "iot"))
                        .build())
                .build());

        iot.updateThing(UpdateThingRequest.builder()
                .thingName(thingName)
                .expectedVersion(2L)
                .attributePayload(AttributePayload.builder()
                        .attributes(Map.of("env", "versioned", "owner", "iot"))
                        .build())
                .build());

        assertThatThrownBy(() -> iot.updateThing(UpdateThingRequest.builder()
                .thingName(thingName)
                .expectedVersion(2L)
                .attributePayload(AttributePayload.builder().attributes(Map.of("env", "stale")).build())
                .build()))
                .isInstanceOf(VersionConflictException.class);

        var updated = iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build());
        assertThat(updated.attributes()).containsEntry("env", "versioned").containsEntry("owner", "iot");

        iot.deleteThing(DeleteThingRequest.builder().thingName(thingName).build());
        iot.deleteThing(DeleteThingRequest.builder().thingName(otherThingName).build());
        assertThatThrownBy(() -> iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void thingTags() {
        String thingName = "java-iot-tagged-thing";
        try {
            iot.deleteThing(DeleteThingRequest.builder().thingName(thingName).build());
        } catch (Exception ignored) {
        }

        var created = iot.createThing(CreateThingRequest.builder().thingName(thingName).build());
        String thingArn = created.thingArn();

        var emptyTags = iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(thingArn)
                .build());
        assertThat(emptyTags.tags()).isEmpty();

        iot.tagResource(TagResourceRequest.builder()
                .resourceArn(thingArn)
                .tags(Tag.builder().key("env").value("java").build(),
                        Tag.builder().key("owner").value("iot").build())
                .build());

        var tags = iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(thingArn)
                .build());
        assertThat(tags.tags()).extracting(Tag::key).containsExactlyInAnyOrder("env", "owner");
        assertThat(tags.tags()).extracting(Tag::value).contains("java", "iot");

        iot.untagResource(UntagResourceRequest.builder()
                .resourceArn(thingArn)
                .tagKeys("env")
                .build());

        var remainingTags = iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(thingArn)
                .build());
        assertThat(remainingTags.tags()).extracting(Tag::key).containsExactly("owner");

        assertThatThrownBy(() -> iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn("arn:aws:iot:us-east-1:000000000000:thing/missing-tagged-thing")
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void certificatesPoliciesAndAttachments() {
        var cert = iot.createKeysAndCertificate(CreateKeysAndCertificateRequest.builder()
                .setAsActive(true)
                .build());
        assertThat(cert.certificatePem()).contains("BEGIN CERTIFICATE");
        assertThat(cert.keyPair().publicKey()).contains("BEGIN PUBLIC KEY");

        var described = iot.describeCertificate(DescribeCertificateRequest.builder()
                .certificateId(cert.certificateId())
                .build());
        assertThat(described.certificateDescription().status()).isEqualTo(CertificateStatus.ACTIVE);

        var certs = iot.listCertificates(ListCertificatesRequest.builder().build());
        assertThat(certs.certificates()).anyMatch(item -> cert.certificateArn().equals(item.certificateArn()));

        iot.updateCertificate(UpdateCertificateRequest.builder()
                .certificateId(cert.certificateId())
                .newStatus(CertificateStatus.INACTIVE)
                .build());
        described = iot.describeCertificate(DescribeCertificateRequest.builder()
                .certificateId(cert.certificateId())
                .build());
        assertThat(described.certificateDescription().status()).isEqualTo(CertificateStatus.INACTIVE);

        String policyName = "java-iot-policy";
        String policyDocument = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        var policy = iot.createPolicy(CreatePolicyRequest.builder()
                .policyName(policyName)
                .policyDocument(policyDocument)
                .build());
        assertThat(policy.policyName()).isEqualTo(policyName);

        var gotPolicy = iot.getPolicy(GetPolicyRequest.builder().policyName(policyName).build());
        assertThat(gotPolicy.policyDocument()).contains("2012-10-17");

        var policies = iot.listPolicies(ListPoliciesRequest.builder().build());
        assertThat(policies.policies()).anyMatch(item -> policyName.equals(item.policyName()));

        iot.attachPolicy(AttachPolicyRequest.builder().policyName(policyName).target(cert.certificateArn()).build());
        iot.detachPolicy(DetachPolicyRequest.builder().policyName(policyName).target(cert.certificateArn()).build());

        String thingName = "java-iot-principal-thing";
        try {
            iot.deleteThing(DeleteThingRequest.builder().thingName(thingName).build());
        } catch (Exception ignored) {
        }
        iot.createThing(CreateThingRequest.builder().thingName(thingName).build());
        iot.attachThingPrincipal(AttachThingPrincipalRequest.builder()
                .thingName(thingName)
                .principal(cert.certificateArn())
                .build());
        var principals = iot.listThingPrincipals(ListThingPrincipalsRequest.builder().thingName(thingName).build());
        assertThat(principals.principals()).contains(cert.certificateArn());
        iot.detachThingPrincipal(DetachThingPrincipalRequest.builder()
                .thingName(thingName)
                .principal(cert.certificateArn())
                .build());
    }

    @Test
    void iotDataShadowsAndPublish() {
        String thingName = "java-iot-shadow-thing";
        assertThatThrownBy(() -> iotData.deleteConnection(DeleteConnectionRequest.builder()
                .clientId("java-iot-missing-client")
                .build()))
                .isInstanceOf(software.amazon.awssdk.services.iotdataplane.model.ResourceNotFoundException.class);

        assertThatThrownBy(() -> iotData.getThingShadow(GetThingShadowRequest.builder()
                .thingName(thingName)
                .build()))
                .isInstanceOf(software.amazon.awssdk.services.iotdataplane.model.ResourceNotFoundException.class);

        var updated = iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                .thingName(thingName)
                .payload(SdkBytes.fromUtf8String("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"))
                .build());
        assertThat(updated.payload().asUtf8String()).contains("\"version\":1");

        iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                .thingName(thingName)
                .payload(SdkBytes.fromUtf8String("{\"state\":{\"reported\":{\"color\":\"green\"}}}"))
                .build());
        var got = iotData.getThingShadow(GetThingShadowRequest.builder().thingName(thingName).build());
        assertThat(got.payload().asUtf8String()).contains("blue").contains("green");

        iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                .thingName(thingName)
                .shadowName("settings")
                .payload(SdkBytes.fromUtf8String("{\"state\":{\"desired\":{\"mode\":\"auto\"}}}"))
                .build());
        var named = iotData.listNamedShadowsForThing(ListNamedShadowsForThingRequest.builder()
                .thingName(thingName)
                .build());
        assertThat(named.results()).contains("settings");

        iotData.publish(PublishRequest.builder()
                .topic("devices/" + thingName + "/events")
                .payload(SdkBytes.fromUtf8String("payload"))
                .build());
        iotData.deleteThingShadow(DeleteThingShadowRequest.builder().thingName(thingName).shadowName("settings").build());
        iotData.deleteThingShadow(DeleteThingShadowRequest.builder().thingName(thingName).build());
    }

    @Test
    void topicRuleCrudAndSqsAction() {
        String ruleName = "java-iot-topic-rule";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName("java-iot-rule-queue")
                .build()).queueUrl();

        try {
            iot.createTopicRule(CreateTopicRuleRequest.builder()
                    .ruleName(ruleName)
                    .topicRulePayload(TopicRulePayload.builder()
                            .sql("SELECT * FROM 'devices/java-iot/rules'")
                            .description("java topic rule")
                            .ruleDisabled(false)
                            .actions(Action.builder()
                                    .sqs(SqsAction.builder()
                                            .roleArn("arn:aws:iam::000000000000:role/iot-rule-role")
                                            .queueUrl(queueUrl)
                                            .useBase64(false)
                                            .build())
                                    .build())
                            .build())
                    .build());

            var got = iot.getTopicRule(GetTopicRuleRequest.builder().ruleName(ruleName).build());
            assertThat(got.rule().ruleName()).isEqualTo(ruleName);
            assertThat(got.rule().actions().get(0).sqs().queueUrl()).isEqualTo(queueUrl);

            iot.disableTopicRule(DisableTopicRuleRequest.builder().ruleName(ruleName).build());
            var listed = iot.listTopicRules(ListTopicRulesRequest.builder().build());
            assertThat(listed.rules()).anyMatch(rule -> ruleName.equals(rule.ruleName()) && rule.ruleDisabled());

            iot.enableTopicRule(EnableTopicRuleRequest.builder().ruleName(ruleName).build());
            iotData.publish(PublishRequest.builder()
                    .topic("devices/java-iot/rules")
                    .payload(SdkBytes.fromUtf8String("java-rule-payload"))
                    .build());

            var received = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .build());
            assertThat(received.messages()).hasSize(1);
            assertThat(received.messages().get(0).body()).isEqualTo("java-rule-payload");
        } finally {
            try {
                iot.deleteTopicRule(DeleteTopicRuleRequest.builder().ruleName(ruleName).build());
            } catch (Exception ignored) {
            }
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
        }
    }

    @Test
    void thingTypesGroupsAndJobs() {
        String thingType = "java-iot-type";
        String thingName = "java-iot-typed-thing";
        String groupName = "java-iot-group";
        String jobId = "java-iot-job";
        try {
            iot.deleteThing(DeleteThingRequest.builder().thingName(thingName).build());
        } catch (Exception ignored) {
        }
        try {
            iot.deleteThingGroup(DeleteThingGroupRequest.builder().thingGroupName(groupName).build());
        } catch (Exception ignored) {
        }
        try {
            iot.deprecateThingType(DeprecateThingTypeRequest.builder().thingTypeName(thingType).build());
            iot.deleteThingType(DeleteThingTypeRequest.builder().thingTypeName(thingType).build());
        } catch (Exception ignored) {
        }

        var jobsEndpoint = iot.describeEndpoint(DescribeEndpointRequest.builder().endpointType("iot:Jobs").build());
        assertThat(jobsEndpoint.endpointAddress()).isNotBlank();

        var createdType = iot.createThingType(CreateThingTypeRequest.builder()
                .thingTypeName(thingType)
                .thingTypeProperties(ThingTypeProperties.builder()
                        .thingTypeDescription("java type")
                        .searchableAttributes("model")
                        .build())
                .build());
        assertThat(createdType.thingTypeName()).isEqualTo(thingType);
        var describedType = iot.describeThingType(DescribeThingTypeRequest.builder().thingTypeName(thingType).build());
        assertThat(describedType.thingTypeProperties().thingTypeDescription()).isEqualTo("java type");
        assertThat(iot.listThingTypes(ListThingTypesRequest.builder().build()).thingTypes())
                .anyMatch(type -> thingType.equals(type.thingTypeName()));

        iot.updateThingType(UpdateThingTypeRequest.builder()
                .thingTypeName(thingType)
                .thingTypeProperties(ThingTypeProperties.builder()
                        .thingTypeDescription("java type updated")
                        .searchableAttributes("model", "fw")
                        .build())
                .build());

        var createdThing = iot.createThing(CreateThingRequest.builder()
                .thingName(thingName)
                .thingTypeName(thingType)
                .attributePayload(AttributePayload.builder().attributes(Map.of("model", "j1")).build())
                .build());
        assertThat(iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build()).thingTypeName()).isEqualTo(thingType);

        var createdGroup = iot.createThingGroup(CreateThingGroupRequest.builder()
                .thingGroupName(groupName)
                .thingGroupProperties(ThingGroupProperties.builder()
                        .thingGroupDescription("java group")
                        .attributePayload(AttributePayload.builder().attributes(Map.of("fleet", "java")).build())
                        .build())
                .build());
        assertThat(createdGroup.thingGroupName()).isEqualTo(groupName);
        iot.addThingToThingGroup(AddThingToThingGroupRequest.builder().thingGroupName(groupName).thingName(thingName).build());
        assertThat(iot.listThingsInThingGroup(ListThingsInThingGroupRequest.builder().thingGroupName(groupName).build()).things()).contains(thingName);
        assertThat(iot.listThingGroupsForThing(ListThingGroupsForThingRequest.builder().thingName(thingName).build()).thingGroups())
                .anyMatch(group -> groupName.equals(group.groupName()));

        var createdJob = iot.createJob(CreateJobRequest.builder()
                .jobId(jobId)
                .targets(createdThing.thingArn())
                .document("{\"operation\":\"reboot\"}")
                .description("java job")
                .build());
        assertThat(createdJob.jobId()).isEqualTo(jobId);
        assertThat(iot.describeJob(DescribeJobRequest.builder().jobId(jobId).build()).job().statusAsString()).isEqualTo("IN_PROGRESS");
        assertThat(iot.listJobs(ListJobsRequest.builder().build()).jobs()).anyMatch(job -> jobId.equals(job.jobId()));
        assertThat(iot.listJobExecutionsForThing(ListJobExecutionsForThingRequest.builder().thingName(thingName).build()).executionSummaries())
                .anyMatch(execution -> jobId.equals(execution.jobId()));

        var pending = iotJobsData.getPendingJobExecutions(GetPendingJobExecutionsRequest.builder().thingName(thingName).build());
        assertThat(pending.queuedJobs()).anyMatch(job -> jobId.equals(job.jobId()));
        var started = iotJobsData.startNextPendingJobExecution(StartNextPendingJobExecutionRequest.builder()
                .thingName(thingName)
                .statusDetails(Map.of("phase", "download"))
                .build());
        assertThat(started.execution().status()).isEqualTo(JobExecutionStatus.IN_PROGRESS);
        var updated = iotJobsData.updateJobExecution(UpdateJobExecutionRequest.builder()
                .thingName(thingName)
                .jobId(jobId)
                .status(JobExecutionStatus.SUCCEEDED)
                .expectedVersion(2L)
                .includeJobExecutionState(true)
                .includeJobDocument(true)
                .build());
        assertThat(updated.executionState().status()).isEqualTo(JobExecutionStatus.SUCCEEDED);
        assertThat(updated.jobDocument()).contains("reboot");

        iot.removeThingFromThingGroup(RemoveThingFromThingGroupRequest.builder().thingGroupName(groupName).thingName(thingName).build());
        iot.deleteThingGroup(DeleteThingGroupRequest.builder().thingGroupName(groupName).build());
        iot.deleteThing(DeleteThingRequest.builder().thingName(thingName).build());
        iot.deprecateThingType(DeprecateThingTypeRequest.builder().thingTypeName(thingType).build());
        iot.deleteThingType(DeleteThingTypeRequest.builder().thingTypeName(thingType).build());
    }

    @Test
    void mqttConnectPublishSubscribe() throws Exception {
        String topic = "devices/java-iot-mqtt/events";
        byte[] payload = "java-mqtt".getBytes(StandardCharsets.UTF_8);

        try (Socket subscriber = mqttConnect("java-iot-mqtt-sub")) {
            mqttSubscribe(subscriber, topic);
            try (Socket publisher = mqttConnect("java-iot-mqtt-pub")) {
                mqttPublish(publisher, topic, payload);
            }

            MqttPublish received = mqttReadPublish(subscriber.getInputStream());
            assertThat(received.topic()).isEqualTo(topic);
            assertThat(received.payload()).isEqualTo(payload);
        }
    }

    @Test
    void mqtt5Connect() throws Exception {
        try (Socket client = mqtt5Connect("java-iot-mqtt5")) {
            assertThat(client.isConnected()).isTrue();
        }
    }

    @Test
    void mqttShadowReservedTopics() throws Exception {
        String thingName = "java-iot-shadow";
        try (Socket subscriber = mqttConnect("java-iot-shadow-sub")) {
            mqttSubscribe(subscriber, "$aws/things/" + thingName + "/shadow/update/accepted");
            mqttSubscribe(subscriber, "$aws/things/" + thingName + "/shadow/get/accepted");
            mqttSubscribe(subscriber, "$aws/things/" + thingName + "/shadow/delete/accepted");

            try (Socket publisher = mqttConnect("java-iot-shadow-pub")) {
                mqttPublish(publisher, "$aws/things/" + thingName + "/shadow/update",
                        "{\"state\":{\"desired\":{\"color\":\"blue\"}},\"clientToken\":\"update-token\"}".getBytes(StandardCharsets.UTF_8));
                MqttPublish accepted = mqttReadPublish(subscriber.getInputStream());
                assertThat(accepted.topic()).isEqualTo("$aws/things/" + thingName + "/shadow/update/accepted");
                JsonNode acceptedPayload = OBJECT_MAPPER.readTree(accepted.payload());
                assertThat(acceptedPayload.path("clientToken").asText()).isEqualTo("update-token");

                mqttPublish(publisher, "$aws/things/" + thingName + "/shadow/get",
                        "{\"clientToken\":\"get-token\"}".getBytes(StandardCharsets.UTF_8));
                MqttPublish got = mqttReadPublish(subscriber.getInputStream());
                assertThat(got.topic()).isEqualTo("$aws/things/" + thingName + "/shadow/get/accepted");
                JsonNode gotPayload = OBJECT_MAPPER.readTree(got.payload());
                assertThat(gotPayload.path("clientToken").asText()).isEqualTo("get-token");

                mqttPublish(publisher, "$aws/things/" + thingName + "/shadow/delete",
                        "{\"clientToken\":\"delete-token\"}".getBytes(StandardCharsets.UTF_8));
                MqttPublish deleted = mqttReadPublish(subscriber.getInputStream());
                assertThat(deleted.topic()).isEqualTo("$aws/things/" + thingName + "/shadow/delete/accepted");
                JsonNode deletedPayload = OBJECT_MAPPER.readTree(deleted.payload());
                assertThat(deletedPayload.path("clientToken").asText()).isEqualTo("delete-token");
            }
        }
    }

    private Socket mqttConnect(String clientId) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("floci", 1883), 5_000);
        socket.setSoTimeout(5_000);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        mqttUtf8(body, "MQTT");
        body.write(0x04);
        body.write(0x02);
        body.write(0x00);
        body.write(0x3c);
        mqttUtf8(body, clientId);
        socket.getOutputStream().write(mqttPacket(0x10, body.toByteArray()));
        assertThat(mqttReadPacket(socket.getInputStream())).containsExactly(0x20, 0x02, 0x00, 0x00);
        return socket;
    }

    private Socket mqtt5Connect(String clientId) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("floci", 1883), 5_000);
        socket.setSoTimeout(5_000);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        mqttUtf8(body, "MQTT");
        body.write(0x05);
        body.write(0x02);
        body.write(0x00);
        body.write(0x3c);
        body.write(0x00);
        mqttUtf8(body, clientId);
        socket.getOutputStream().write(mqttPacket(0x10, body.toByteArray()));
        mqttAssertV5Connack(mqttReadPacket(socket.getInputStream()));
        return socket;
    }

    private void mqttAssertV5Connack(byte[] packet) {
        assertThat(packet[0] & 0xff).isEqualTo(0x20);
        int index = 1;
        while ((packet[index] & 0x80) != 0) {
            index++;
        }
        index++;
        assertThat(packet[index] & 0xff).isEqualTo(0x00);
        assertThat(packet[index + 1] & 0xff).isEqualTo(0x00);
    }

    private void mqttSubscribe(Socket socket, String topic) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);
        body.write(0x01);
        mqttUtf8(body, topic);
        body.write(0x00);
        socket.getOutputStream().write(mqttPacket(0x82, body.toByteArray()));
        assertThat(mqttReadPacket(socket.getInputStream())).containsExactly(0x90, 0x03, 0x00, 0x01, 0x00);
    }

    private void mqttPublish(Socket socket, String topic, byte[] payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        mqttUtf8(body, topic);
        body.write(payload);
        socket.getOutputStream().write(mqttPacket(0x30, body.toByteArray()));
    }

    private MqttPublish mqttReadPublish(InputStream input) throws IOException {
        byte[] packet = mqttReadPacket(input);
        assertThat(packet[0] & 0xf0).isEqualTo(0x30);
        int index = 1;
        while ((packet[index] & 0x80) != 0) {
            index++;
        }
        index++;
        int topicLength = ((packet[index] & 0xff) << 8) | (packet[index + 1] & 0xff);
        index += 2;
        String topic = new String(packet, index, topicLength, StandardCharsets.UTF_8);
        index += topicLength;
        return new MqttPublish(topic, Arrays.copyOfRange(packet, index, packet.length));
    }

    private byte[] mqttPacket(int type, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        mqttRemainingLength(out, body.length);
        out.write(body);
        return out.toByteArray();
    }

    private byte[] mqttReadPacket(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int first = input.read();
        if (first < 0) {
            throw new IOException("No MQTT packet received");
        }
        out.write(first);

        int multiplier = 1;
        int remainingLength = 0;
        int encoded;
        do {
            encoded = input.read();
            if (encoded < 0) {
                throw new IOException("Incomplete MQTT remaining length");
            }
            out.write(encoded);
            remainingLength += (encoded & 127) * multiplier;
            multiplier *= 128;
        } while ((encoded & 128) != 0);

        byte[] body = input.readNBytes(remainingLength);
        if (body.length != remainingLength) {
            throw new IOException("Incomplete MQTT packet body");
        }
        out.write(body);
        return out.toByteArray();
    }

    private void mqttUtf8(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 8) & 0xff);
        out.write(bytes.length & 0xff);
        out.write(bytes);
    }

    private void mqttRemainingLength(OutputStream out, int length) throws IOException {
        int value = length;
        do {
            int encoded = value % 128;
            value /= 128;
            if (value > 0) {
                encoded |= 128;
            }
            out.write(encoded);
        } while (value > 0);
    }

    private record MqttPublish(String topic, byte[] payload) {
    }
}
