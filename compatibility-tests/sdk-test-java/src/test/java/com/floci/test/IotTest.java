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
import software.amazon.awssdk.services.iot.model.AuthorizerConfig;
import software.amazon.awssdk.services.iot.model.CertificateMode;
import software.amazon.awssdk.services.iot.model.CertificateStatus;
import software.amazon.awssdk.services.iot.model.Action;
import software.amazon.awssdk.services.iot.model.AddThingToThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CreateDomainConfigurationResponse;
import software.amazon.awssdk.services.iot.model.CreateJobResponse;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateDomainConfigurationRequest;
import software.amazon.awssdk.services.iot.model.CreateJobRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;
import software.amazon.awssdk.services.iot.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iot.model.CreatePolicyResponse;
import software.amazon.awssdk.services.iot.model.CreateThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.CreateThingResponse;
import software.amazon.awssdk.services.iot.model.CreateThingTypeRequest;
import software.amazon.awssdk.services.iot.model.CreateThingTypeResponse;
import software.amazon.awssdk.services.iot.model.CreateTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.DeleteDomainConfigurationRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingGroupRequest;
import software.amazon.awssdk.services.iot.model.DeleteTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingTypeRequest;
import software.amazon.awssdk.services.iot.model.DescribeCertificateRequest;
import software.amazon.awssdk.services.iot.model.DescribeCertificateResponse;
import software.amazon.awssdk.services.iot.model.DescribeDomainConfigurationRequest;
import software.amazon.awssdk.services.iot.model.DescribeDomainConfigurationResponse;
import software.amazon.awssdk.services.iot.model.DescribeEndpointRequest;
import software.amazon.awssdk.services.iot.model.DescribeEndpointResponse;
import software.amazon.awssdk.services.iot.model.DescribeJobRequest;
import software.amazon.awssdk.services.iot.model.DescribeIndexRequest;
import software.amazon.awssdk.services.iot.model.DescribeIndexResponse;
import software.amazon.awssdk.services.iot.model.DeviceDefenderIndexingMode;
import software.amazon.awssdk.services.iot.model.Field;
import software.amazon.awssdk.services.iot.model.GetIndexingConfigurationRequest;
import software.amazon.awssdk.services.iot.model.GetIndexingConfigurationResponse;
import software.amazon.awssdk.services.iot.model.IndexNotReadyException;
import software.amazon.awssdk.services.iot.model.IndexStatus;
import software.amazon.awssdk.services.iot.model.NamedShadowIndexingMode;
import software.amazon.awssdk.services.iot.model.ThingConnectivityIndexingMode;
import software.amazon.awssdk.services.iot.model.ThingGroupIndexingConfiguration;
import software.amazon.awssdk.services.iot.model.ThingGroupIndexingMode;
import software.amazon.awssdk.services.iot.model.ThingIndexingConfiguration;
import software.amazon.awssdk.services.iot.model.ThingIndexingMode;
import software.amazon.awssdk.services.iot.model.UpdateIndexingConfigurationRequest;
import software.amazon.awssdk.services.iot.model.SearchIndexRequest;
import software.amazon.awssdk.services.iot.model.SearchIndexResponse;
import software.amazon.awssdk.services.iot.model.ThingDocument;
import software.amazon.awssdk.services.iot.model.ThingTypeDefinition;
import software.amazon.awssdk.services.iot.model.DescribeThingRequest;
import software.amazon.awssdk.services.iot.model.DescribeThingResponse;
import software.amazon.awssdk.services.iot.model.DescribeThingTypeRequest;
import software.amazon.awssdk.services.iot.model.DescribeThingTypeResponse;
import software.amazon.awssdk.services.iot.model.DomainConfigurationStatus;
import software.amazon.awssdk.services.iot.model.DomainConfigurationSummary;
import software.amazon.awssdk.services.iot.model.DomainType;
import software.amazon.awssdk.services.iot.model.DisableTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.DeprecateThingTypeRequest;
import software.amazon.awssdk.services.iot.model.DetachPolicyRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.EnableTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyResponse;
import software.amazon.awssdk.services.iot.model.GetTopicRuleRequest;
import software.amazon.awssdk.services.iot.model.GetTopicRuleResponse;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.ListCertificatesRequest;
import software.amazon.awssdk.services.iot.model.ListCertificatesResponse;
import software.amazon.awssdk.services.iot.model.ListDomainConfigurationsRequest;
import software.amazon.awssdk.services.iot.model.ListDomainConfigurationsResponse;
import software.amazon.awssdk.services.iot.model.ListJobExecutionsForThingRequest;
import software.amazon.awssdk.services.iot.model.ListJobsRequest;
import software.amazon.awssdk.services.iot.model.ListPoliciesRequest;
import software.amazon.awssdk.services.iot.model.ListPoliciesResponse;
import software.amazon.awssdk.services.iot.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.iot.model.ListThingGroupsForThingRequest;
import software.amazon.awssdk.services.iot.model.ListThingPrincipalsResponse;
import software.amazon.awssdk.services.iot.model.ListThingsInThingGroupRequest;
import software.amazon.awssdk.services.iot.model.ListThingsRequest;
import software.amazon.awssdk.services.iot.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.iot.model.ListThingPrincipalsRequest;
import software.amazon.awssdk.services.iot.model.ListThingTypesRequest;
import software.amazon.awssdk.services.iot.model.ListThingsResponse;
import software.amazon.awssdk.services.iot.model.ListTopicRulesRequest;
import software.amazon.awssdk.services.iot.model.ListTopicRulesResponse;
import software.amazon.awssdk.services.iot.model.RemoveThingFromThingGroupRequest;
import software.amazon.awssdk.services.iot.model.ServerCertificateStatus;
import software.amazon.awssdk.services.iot.model.ServiceType;
import software.amazon.awssdk.services.iot.model.SqsAction;
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.Tag;
import software.amazon.awssdk.services.iot.model.TagResourceRequest;
import software.amazon.awssdk.services.iot.model.ThingGroupProperties;
import software.amazon.awssdk.services.iot.model.ThingTypeProperties;
import software.amazon.awssdk.services.iot.model.TopicRulePayload;
import software.amazon.awssdk.services.iot.model.UpdateDomainConfigurationRequest;
import software.amazon.awssdk.services.iot.model.UntagResourceRequest;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;
import software.amazon.awssdk.services.iot.model.UpdateDomainConfigurationResponse;
import software.amazon.awssdk.services.iot.model.UpdateThingRequest;
import software.amazon.awssdk.services.iot.model.UpdateThingTypeRequest;
import software.amazon.awssdk.services.iot.model.VersionConflictException;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.iotdataplane.model.DeleteConnectionRequest;
import software.amazon.awssdk.services.iotdataplane.model.DeleteThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.GetThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.GetThingShadowResponse;
import software.amazon.awssdk.services.iotdataplane.model.ListNamedShadowsForThingRequest;
import software.amazon.awssdk.services.iotdataplane.model.ListNamedShadowsForThingResponse;
import software.amazon.awssdk.services.iotdataplane.model.PublishRequest;
import software.amazon.awssdk.services.iotdataplane.model.UpdateThingShadowRequest;
import software.amazon.awssdk.services.iotdataplane.model.UpdateThingShadowResponse;
import software.amazon.awssdk.services.iotjobsdataplane.IotJobsDataPlaneClient;
import software.amazon.awssdk.services.iotjobsdataplane.model.GetPendingJobExecutionsRequest;
import software.amazon.awssdk.services.iotjobsdataplane.model.GetPendingJobExecutionsResponse;
import software.amazon.awssdk.services.iotjobsdataplane.model.JobExecutionStatus;
import software.amazon.awssdk.services.iotjobsdataplane.model.StartNextPendingJobExecutionRequest;
import software.amazon.awssdk.services.iotjobsdataplane.model.StartNextPendingJobExecutionResponse;
import software.amazon.awssdk.services.iotjobsdataplane.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.services.iotjobsdataplane.model.UpdateJobExecutionResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
        DescribeEndpointResponse response = iot.describeEndpoint(DescribeEndpointRequest.builder()
                .endpointType("iot:Data-ATS")
                .build());

        assertThat(response.endpointAddress()).isNotBlank();
    }

    @Test
    void domainConfigurationLifecycle() {
        String name = "java-iot-domain";
        String certificateArn = TestFixtures.arn("acm", "000000000000",
                "certificate/11111111-1111-1111-1111-111111111111");
        boolean leftOver = iot.listDomainConfigurations(ListDomainConfigurationsRequest.builder().build())
                .domainConfigurations().stream()
                .anyMatch(summary -> name.equals(summary.domainConfigurationName()));
        if (leftOver) {
            iot.updateDomainConfiguration(UpdateDomainConfigurationRequest.builder()
                    .domainConfigurationName(name)
                    .domainConfigurationStatus(DomainConfigurationStatus.DISABLED)
                    .build());
            iot.deleteDomainConfiguration(DeleteDomainConfigurationRequest.builder().domainConfigurationName(name).build());
        }

        assertThatThrownBy(() -> iot.describeDomainConfiguration(DescribeDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);

        DescribeDomainConfigurationResponse managed = iot.describeDomainConfiguration(DescribeDomainConfigurationRequest.builder()
                .domainConfigurationName("iot:Data-ATS")
                .build());
        assertThat(managed.domainType()).isEqualTo(DomainType.AWS_MANAGED);
        assertThat(managed.domainConfigurationStatus()).isEqualTo(DomainConfigurationStatus.ENABLED);
        assertThat(managed.domainName()).isNotBlank();

        CreateDomainConfigurationResponse created = iot.createDomainConfiguration(CreateDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .domainName("iot.java.example.com")
                .serverCertificateArns(certificateArn)
                .serviceType(ServiceType.DATA)
                .authorizerConfig(AuthorizerConfig.builder()
                        .defaultAuthorizerName("java-authorizer")
                        .allowAuthorizerOverride(true)
                        .build())
                .tags(Tag.builder().key("env").value("java").build())
                .build());
        assertThat(created.domainConfigurationName()).isEqualTo(name);
        assertThat(created.domainConfigurationArn())
                .startsWith(TestFixtures.arn("iot", "000000000000", "domainconfiguration/" + name + "/"));

        assertThatThrownBy(() -> iot.createDomainConfiguration(CreateDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .domainName("iot.java.example.com")
                .serverCertificateArns(certificateArn)
                .build()))
                .isInstanceOf(ResourceAlreadyExistsException.class);

        DescribeDomainConfigurationResponse described = iot.describeDomainConfiguration(DescribeDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .build());
        assertThat(described.domainConfigurationArn()).isEqualTo(created.domainConfigurationArn());
        assertThat(described.domainName()).isEqualTo("iot.java.example.com");
        assertThat(described.domainConfigurationStatus()).isEqualTo(DomainConfigurationStatus.ENABLED);
        assertThat(described.serviceType()).isEqualTo(ServiceType.DATA);
        assertThat(described.domainType()).isEqualTo(DomainType.CUSTOMER_MANAGED);
        assertThat(described.serverCertificates()).hasSize(1);
        assertThat(described.serverCertificates().get(0).serverCertificateArn()).isEqualTo(certificateArn);
        assertThat(described.serverCertificates().get(0).serverCertificateStatus()).isEqualTo(ServerCertificateStatus.VALID);
        assertThat(described.authorizerConfig().defaultAuthorizerName()).isEqualTo("java-authorizer");
        assertThat(described.authorizerConfig().allowAuthorizerOverride()).isTrue();
        assertThat(described.lastStatusChangeDate()).isNotNull();
        assertThat(described.tlsConfig().securityPolicy()).isEqualTo("IoTSecurityPolicy_TLS13_1_2_2022_10");

        UpdateDomainConfigurationResponse enabled = iot.updateDomainConfiguration(UpdateDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .domainConfigurationStatus(DomainConfigurationStatus.ENABLED)
                .build());
        assertThat(enabled.domainConfigurationArn()).isEqualTo(created.domainConfigurationArn());
        assertThat(iot.describeDomainConfiguration(DescribeDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .build()).domainConfigurationStatus())
                .isEqualTo(DomainConfigurationStatus.ENABLED);

        ListTagsForResourceResponse tags = iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(created.domainConfigurationArn())
                .build());
        assertThat(tags.tags()).extracting(Tag::key).contains("env");

        ListDomainConfigurationsResponse listed = iot.listDomainConfigurations(ListDomainConfigurationsRequest.builder()
                .serviceType(ServiceType.DATA)
                .build());
        assertThat(listed.domainConfigurations())
                .extracting(DomainConfigurationSummary::domainConfigurationName)
                .contains(name);

        assertThatThrownBy(() -> iot.deleteDomainConfiguration(DeleteDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .build()))
                .isInstanceOf(InvalidRequestException.class);

        UpdateDomainConfigurationResponse updated = iot.updateDomainConfiguration(UpdateDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .domainConfigurationStatus(DomainConfigurationStatus.DISABLED)
                .build());
        assertThat(updated.domainConfigurationArn()).isEqualTo(created.domainConfigurationArn());
        assertThat(iot.describeDomainConfiguration(DescribeDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .build()).domainConfigurationStatus())
                .isEqualTo(DomainConfigurationStatus.DISABLED);

        iot.deleteDomainConfiguration(DeleteDomainConfigurationRequest.builder().domainConfigurationName(name).build());
        assertThatThrownBy(() -> iot.describeDomainConfiguration(DescribeDomainConfigurationRequest.builder()
                .domainConfigurationName(name)
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void fleetIndexingConfiguration() {
        GetIndexingConfigurationResponse prior =
                iot.getIndexingConfiguration(GetIndexingConfigurationRequest.builder().build());
        try {
            iot.updateIndexingConfiguration(UpdateIndexingConfigurationRequest.builder()
                    .thingIndexingConfiguration(ThingIndexingConfiguration.builder()
                            .thingIndexingMode(ThingIndexingMode.REGISTRY)
                            .thingConnectivityIndexingMode(ThingConnectivityIndexingMode.STATUS)
                            .build())
                    .thingGroupIndexingConfiguration(ThingGroupIndexingConfiguration.builder()
                            .thingGroupIndexingMode(ThingGroupIndexingMode.ON)
                            .build())
                    .build());

            GetIndexingConfigurationResponse enabled =
                    iot.getIndexingConfiguration(GetIndexingConfigurationRequest.builder().build());
            ThingIndexingConfiguration thing = enabled.thingIndexingConfiguration();
            assertThat(thing.thingIndexingMode()).isEqualTo(ThingIndexingMode.REGISTRY);
            assertThat(thing.thingConnectivityIndexingMode()).isEqualTo(ThingConnectivityIndexingMode.STATUS);
            assertThat(thing.deviceDefenderIndexingMode()).isEqualTo(DeviceDefenderIndexingMode.OFF);
            assertThat(thing.namedShadowIndexingMode()).isEqualTo(NamedShadowIndexingMode.OFF);
            assertThat(thing.managedFields())
                    .extracting(field -> field.name() + ":" + field.typeAsString())
                    .containsExactlyInAnyOrder(
                            "thingName:String", "thingId:String", "registry.version:Number",
                            "registry.thingTypeName:String", "registry.thingGroupNames:String",
                            "connectivity.connected:Boolean", "connectivity.timestamp:Number",
                            "connectivity.disconnectReason:String", "connectivity.clientId:String",
                            "connectivity.cleanSession:Boolean", "connectivity.keepAliveDuration:Number",
                            "connectivity.sessionExpiry:Number", "connectivity.version:Number");
            assertThat(thing.filter().namedShadowNames()).isEmpty();
            assertThat(enabled.thingGroupIndexingConfiguration().thingGroupIndexingMode())
                    .isEqualTo(ThingGroupIndexingMode.ON);
            assertThat(enabled.thingGroupIndexingConfiguration().managedFields())
                    .extracting(Field::name)
                    .containsExactlyInAnyOrder("parentGroupNames", "description", "version", "thingGroupName",
                            "thingGroupId");

            DescribeIndexResponse things = iot.describeIndex(DescribeIndexRequest.builder()
                    .indexName("AWS_Things")
                    .build());
            assertThat(things.schema()).isEqualTo("REGISTRY_AND_CONNECTIVITY_STATUS");
            assertIndexStatus(things.indexStatus());
            DescribeIndexResponse groups = iot.describeIndex(DescribeIndexRequest.builder()
                    .indexName("AWS_ThingGroups")
                    .build());
            assertThat(groups.schema()).isEqualTo("REGISTRY");
            assertIndexStatus(groups.indexStatus());

            assertThatThrownBy(() -> iot.updateIndexingConfiguration(UpdateIndexingConfigurationRequest.builder()
                    .thingIndexingConfiguration(ThingIndexingConfiguration.builder()
                            .thingIndexingMode(ThingIndexingMode.REGISTRY_AND_SHADOW)
                            .namedShadowIndexingMode(NamedShadowIndexingMode.ON)
                            .build())
                    .build()))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("NamedShadowNames Filter must not be empty for enabling NamedShadowIndexingMode");

            iot.updateIndexingConfiguration(UpdateIndexingConfigurationRequest.builder()
                    .thingIndexingConfiguration(ThingIndexingConfiguration.builder()
                            .thingIndexingMode(ThingIndexingMode.OFF)
                            .build())
                    .build());
            assertThatThrownBy(() -> iot.describeIndex(DescribeIndexRequest.builder().indexName("AWS_Things").build()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Index AWS_Things does not exist");
            GetIndexingConfigurationResponse thingOff =
                    iot.getIndexingConfiguration(GetIndexingConfigurationRequest.builder().build());
            assertThat(thingOff.thingIndexingConfiguration().thingIndexingMode()).isEqualTo(ThingIndexingMode.OFF);
            assertThat(thingOff.thingIndexingConfiguration().thingConnectivityIndexingMode())
                    .isEqualTo(ThingConnectivityIndexingMode.OFF);
            assertThat(thingOff.thingIndexingConfiguration().managedFields()).isEmpty();
            assertThat(thingOff.thingGroupIndexingConfiguration().thingGroupIndexingMode())
                    .isEqualTo(ThingGroupIndexingMode.ON);
        } finally {
            // Indexing is account wide on AWS: put back whatever the account had before this test.
            iot.updateIndexingConfiguration(UpdateIndexingConfigurationRequest.builder()
                    .thingIndexingConfiguration(prior.thingIndexingConfiguration())
                    .thingGroupIndexingConfiguration(prior.thingGroupIndexingConfiguration())
                    .build());
        }
    }

    @Test
    void fleetIndexSearch() throws InterruptedException {
        String prefix = "fleetsearch" + Long.toString(System.currentTimeMillis(), 36);
        String group = prefix + "group";
        String provider = prefix + "p";
        // AWS keeps a deprecated thing type for five minutes before it can be deleted, so on AWS the
        // test borrows a test thing type the account already has rather than creating one.
        String thingType = TestFixtures.isRealAws() ? existingTestThingType() : prefix + "type";
        GetIndexingConfigurationResponse prior =
                iot.getIndexingConfiguration(GetIndexingConfigurationRequest.builder().build());
        try {
            iot.updateIndexingConfiguration(UpdateIndexingConfigurationRequest.builder()
                    .thingIndexingConfiguration(ThingIndexingConfiguration.builder()
                            .thingIndexingMode(ThingIndexingMode.REGISTRY)
                            .thingConnectivityIndexingMode(ThingConnectivityIndexingMode.STATUS)
                            .build())
                    .build());
            if (!TestFixtures.isRealAws()) {
                iot.createThingType(CreateThingTypeRequest.builder().thingTypeName(thingType).build());
            }
            iot.createThingGroup(CreateThingGroupRequest.builder().thingGroupName(group).build());
            iot.createThing(CreateThingRequest.builder()
                    .thingName(prefix + "a")
                    .thingTypeName(thingType)
                    .attributePayload(AttributePayload.builder()
                            .attributes(Map.of("provider", provider + "1", "site", "north"))
                            .build())
                    .build());
            iot.createThing(CreateThingRequest.builder()
                    .thingName(prefix + "b")
                    .attributePayload(AttributePayload.builder().attributes(Map.of("provider", provider + "1")).build())
                    .build());
            iot.createThing(CreateThingRequest.builder()
                    .thingName(prefix + "c")
                    .attributePayload(AttributePayload.builder().attributes(Map.of("provider", provider + "2")).build())
                    .build());
            iot.addThingToThingGroup(AddThingToThingGroupRequest.builder()
                    .thingGroupName(group)
                    .thingName(prefix + "a")
                    .build());

            List<ThingDocument> all = awaitSearch("thingName:" + prefix + "*", prefix, "a", "b", "c");
            ThingDocument typed = all.get(0);
            assertThat(typed.thingTypeName()).isEqualTo(thingType);
            assertThat(typed.thingGroupNames()).containsExactly(group);
            assertThat(typed.attributes()).containsOnly(Map.entry("provider", provider + "1"), Map.entry("site", "north"));
            assertThat(typed.shadow()).isNull();
            ThingDocument plain = all.get(1);
            assertThat(plain.thingTypeName()).isNull();
            assertThat(plain.hasThingGroupNames()).isFalse();
            assertThat(plain.attributes()).containsOnly(Map.entry("provider", provider + "1"));
            for (ThingDocument thing : all) {
                assertThat(thing.thingId()).isNotBlank();
                // A thing whose client id never connected, in the shape AWS reports it.
                assertThat(thing.connectivity().clientId()).isEqualTo(thing.thingName());
                assertThat(thing.connectivity().connected()).isFalse();
                assertThat(thing.connectivity().timestamp()).isZero();
                assertThat(thing.connectivity().disconnectReason()).isNull();
                assertThat(thing.connectivity().keepAliveDuration()).isNull();
                assertThat(thing.connectivity().cleanSession()).isNull();
            }

            awaitSearch("attributes.provider:" + provider + "1", prefix, "a", "b");
            awaitSearch("thingName:" + prefix + "c", prefix, "c");
            if (thingType != null) {
                awaitSearch("thingTypeName:" + thingType, prefix, "a");
                awaitSearch("thingTypeName:" + thingType + " AND attributes.provider:" + provider + "1", prefix, "a");
            }
            awaitSearch("attributes.provider:" + provider + "2 OR thingName:" + prefix + "a", prefix, "a", "c");
            awaitSearch("thingName:" + prefix + "* AND NOT attributes.provider:" + provider + "1", prefix, "c");
            awaitSearch("attributes.provider:" + provider + "?", prefix, "a", "b", "c");
            awaitSearch("thingGroupNames:" + group, prefix, "a");
            awaitSearch("connectivity.connected:false AND thingName:" + prefix + "*", prefix, "a", "b", "c");

            Set<String> paged = new TreeSet<>();
            String nextToken = null;
            int pages = 0;
            do {
                SearchIndexResponse page = iot.searchIndex(SearchIndexRequest.builder()
                        .queryString("thingName:" + prefix + "*")
                        .maxResults(1)
                        .nextToken(nextToken)
                        .build());
                assertThat(page.things()).hasSizeLessThanOrEqualTo(1);
                page.things().forEach(thing -> paged.add(thing.thingName()));
                nextToken = page.nextToken();
                pages++;
            } while (nextToken != null && pages < 10);
            assertThat(paged).containsExactly(prefix + "a", prefix + "b", prefix + "c");
            assertThat(pages).isBetween(3, 4);

            assertThatThrownBy(() -> iot.searchIndex(SearchIndexRequest.builder()
                    .queryString("thingName:" + prefix + "*")
                    .nextToken("bogus")
                    .build()))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("Invalid nextToken");
            assertThatThrownBy(() -> iot.searchIndex(SearchIndexRequest.builder()
                    .queryString("thingName:" + prefix + "*")
                    .queryVersion("2017-09-31")
                    .build()))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("Invalid queryVersion. Expected one of: [2017-09-30]");
        } finally {
            for (String suffix : List.of("a", "b", "c")) {
                deleteIfPresent(() -> iot.deleteThing(DeleteThingRequest.builder().thingName(prefix + suffix).build()));
            }
            deleteIfPresent(() -> iot.deleteThingGroup(DeleteThingGroupRequest.builder().thingGroupName(group).build()));
            if (!TestFixtures.isRealAws()) {
                deleteIfPresent(() -> iot.deleteThingType(DeleteThingTypeRequest.builder().thingTypeName(thingType).build()));
            }
            // Indexing is account wide on AWS: put back whatever the account had before this test.
            iot.updateIndexingConfiguration(UpdateIndexingConfigurationRequest.builder()
                    .thingIndexingConfiguration(prior.thingIndexingConfiguration())
                    .thingGroupIndexingConfiguration(prior.thingGroupIndexingConfiguration())
                    .build());
        }
    }

    /** A thing type of the account meant for tests and not deprecated, or null when it has none. */
    private String existingTestThingType() {
        return iot.listThingTypesPaginator(ListThingTypesRequest.builder().build()).thingTypes().stream()
                .filter(type -> !Boolean.TRUE.equals(type.thingTypeMetadata().deprecated()))
                .map(ThingTypeDefinition::thingTypeName)
                .filter(name -> name.contains("test"))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    /**
     * This test's things a query finds, in name order, polled until they are the expected ones: AWS
     * indexes a change a few seconds after it and refuses searches while a new index builds.
     */
    private List<ThingDocument> awaitSearch(String query, String prefix, String... expectedSuffixes)
            throws InterruptedException {
        List<String> expected = Arrays.stream(expectedSuffixes).map(suffix -> prefix + suffix).toList();
        Instant deadline = Instant.now().plusSeconds(120);
        while (true) {
            List<ThingDocument> found = new ArrayList<>();
            String notReady = null;
            try {
                String nextToken = null;
                do {
                    SearchIndexResponse page = iot.searchIndex(SearchIndexRequest.builder()
                            .queryString(query)
                            .nextToken(nextToken)
                            .build());
                    page.things().stream().filter(thing -> thing.thingName().startsWith(prefix)).forEach(found::add);
                    nextToken = page.nextToken();
                } while (nextToken != null);
            } catch (IndexNotReadyException e) {
                notReady = e.getMessage();
            }
            found.sort(Comparator.comparing(ThingDocument::thingName));
            List<String> names = found.stream().map(ThingDocument::thingName).toList();
            if (notReady == null && names.equals(expected) || Instant.now().isAfter(deadline)) {
                assertThat(notReady).as("index not ready").isNull();
                assertThat(names).as(query).isEqualTo(expected);
                return found;
            }
            Thread.sleep(2_000);
        }
    }

    private static void deleteIfPresent(Runnable delete) {
        try {
            delete.run();
        } catch (ResourceNotFoundException ignored) {
            // The test failed before creating it.
        }
    }

    private static void assertIndexStatus(IndexStatus status) {
        if (TestFixtures.isRealAws()) {
            // AWS builds the index in the background after a mode change; Floci has it ready at once.
            assertThat(status).isIn(IndexStatus.ACTIVE, IndexStatus.BUILDING, IndexStatus.REBUILDING);
        } else {
            assertThat(status).isEqualTo(IndexStatus.ACTIVE);
        }
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

        CreateThingResponse created = iot.createThing(CreateThingRequest.builder()
                .thingName(thingName)
                .attributePayload(AttributePayload.builder().attributes(Map.of("env", "java")).build())
                .build());
        assertThat(created.thingName()).isEqualTo(thingName);
        assertThat(created.thingArn()).endsWith(":thing/" + thingName);

        CreateThingResponse idempotent = iot.createThing(CreateThingRequest.builder()
                .thingName(thingName)
                .attributePayload(AttributePayload.builder().attributes(Map.of("env", "java")).build())
                .build());
        assertThat(idempotent.thingName()).isEqualTo(thingName);

        assertThatThrownBy(() -> iot.createThing(CreateThingRequest.builder().thingName(thingName).build()))
                .isInstanceOf(ResourceAlreadyExistsException.class);

        DescribeThingResponse described = iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build());
        assertThat(described.attributes()).containsEntry("env", "java");

        iot.createThing(CreateThingRequest.builder().thingName(otherThingName).build());

        ListThingsResponse listed = iot.listThings(ListThingsRequest.builder().build());
        assertThat(listed.things()).anyMatch(thing -> thingName.equals(thing.thingName()));

        ListThingsResponse firstPage = iot.listThings(ListThingsRequest.builder().maxResults(1).build());
        assertThat(firstPage.things()).hasSize(1);
        assertThat(firstPage.nextToken()).isNotBlank();
        ListThingsResponse secondPage = iot.listThings(ListThingsRequest.builder()
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

        DescribeThingResponse updated = iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build());
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

        CreateThingResponse created = iot.createThing(CreateThingRequest.builder().thingName(thingName).build());
        String thingArn = created.thingArn();

        ListTagsForResourceResponse emptyTags = iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(thingArn)
                .build());
        assertThat(emptyTags.tags()).isEmpty();

        iot.tagResource(TagResourceRequest.builder()
                .resourceArn(thingArn)
                .tags(Tag.builder().key("env").value("java").build(),
                        Tag.builder().key("owner").value("iot").build())
                .build());

        ListTagsForResourceResponse tags = iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(thingArn)
                .build());
        assertThat(tags.tags()).extracting(Tag::key).containsExactlyInAnyOrder("env", "owner");
        assertThat(tags.tags()).extracting(Tag::value).contains("java", "iot");

        iot.untagResource(UntagResourceRequest.builder()
                .resourceArn(thingArn)
                .tagKeys("env")
                .build());

        ListTagsForResourceResponse remainingTags = iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(thingArn)
                .build());
        assertThat(remainingTags.tags()).extracting(Tag::key).containsExactly("owner");

        assertThatThrownBy(() -> iot.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(TestFixtures.arn("iot", "000000000000", "thing/missing-tagged-thing"))
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void certificatesPoliciesAndAttachments() {
        CreateKeysAndCertificateResponse cert = iot.createKeysAndCertificate(CreateKeysAndCertificateRequest.builder()
                .setAsActive(true)
                .build());
        assertThat(cert.certificatePem()).contains("BEGIN CERTIFICATE");
        assertThat(cert.keyPair().publicKey()).contains("BEGIN PUBLIC KEY");

        DescribeCertificateResponse described = iot.describeCertificate(DescribeCertificateRequest.builder()
                .certificateId(cert.certificateId())
                .build());
        assertThat(described.certificateDescription().status()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(described.certificateDescription().certificateMode()).isEqualTo(CertificateMode.DEFAULT);
        assertThat(described.certificateDescription().validity().notAfter())
                .isEqualTo(java.time.Instant.parse("2049-12-31T23:59:59Z"));
        assertThat(cert.certificateId()).matches("[0-9a-f]{64}");
        assertThat(cert.keyPair().privateKey()).startsWith("-----BEGIN RSA PRIVATE KEY-----");

        ListCertificatesResponse certs = iot.listCertificates(ListCertificatesRequest.builder().build());
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
        CreatePolicyResponse policy = iot.createPolicy(CreatePolicyRequest.builder()
                .policyName(policyName)
                .policyDocument(policyDocument)
                .build());
        assertThat(policy.policyName()).isEqualTo(policyName);

        GetPolicyResponse gotPolicy = iot.getPolicy(GetPolicyRequest.builder().policyName(policyName).build());
        assertThat(gotPolicy.policyDocument()).contains("2012-10-17");

        ListPoliciesResponse policies = iot.listPolicies(ListPoliciesRequest.builder().build());
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
        ListThingPrincipalsResponse principals = iot.listThingPrincipals(ListThingPrincipalsRequest.builder().thingName(thingName).build());
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

        UpdateThingShadowResponse updated = iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                .thingName(thingName)
                .payload(SdkBytes.fromUtf8String("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"))
                .build());
        assertThat(updated.payload().asUtf8String()).contains("\"version\":1");

        iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                .thingName(thingName)
                .payload(SdkBytes.fromUtf8String("{\"state\":{\"reported\":{\"color\":\"green\"}}}"))
                .build());
        GetThingShadowResponse got = iotData.getThingShadow(GetThingShadowRequest.builder().thingName(thingName).build());
        assertThat(got.payload().asUtf8String()).contains("blue").contains("green");

        iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                .thingName(thingName)
                .shadowName("settings")
                .payload(SdkBytes.fromUtf8String("{\"state\":{\"desired\":{\"mode\":\"auto\"}}}"))
                .build());
        ListNamedShadowsForThingResponse named = iotData.listNamedShadowsForThing(ListNamedShadowsForThingRequest.builder()
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
                                            .roleArn(TestFixtures.globalArn(
                                                    "iam", "000000000000", "role/iot-rule-role"))
                                            .queueUrl(queueUrl)
                                            .useBase64(false)
                                            .build())
                                    .build())
                            .build())
                    .build());

            GetTopicRuleResponse got = iot.getTopicRule(GetTopicRuleRequest.builder().ruleName(ruleName).build());
            assertThat(got.rule().ruleName()).isEqualTo(ruleName);
            assertThat(got.rule().actions().get(0).sqs().queueUrl()).isEqualTo(queueUrl);

            iot.disableTopicRule(DisableTopicRuleRequest.builder().ruleName(ruleName).build());
            ListTopicRulesResponse listed = iot.listTopicRules(ListTopicRulesRequest.builder().build());
            assertThat(listed.rules()).anyMatch(rule -> ruleName.equals(rule.ruleName()) && rule.ruleDisabled());

            iot.enableTopicRule(EnableTopicRuleRequest.builder().ruleName(ruleName).build());
            iotData.publish(PublishRequest.builder()
                    .topic("devices/java-iot/rules")
                    .payload(SdkBytes.fromUtf8String("java-rule-payload"))
                    .build());

            ReceiveMessageResponse received = sqs.receiveMessage(ReceiveMessageRequest.builder()
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
    void namedShadowUpdateReachesATopicRuleOnItsAcceptedTopic() throws Exception {
        String ruleName = "java_iot_shadow_rule";
        String thingName = "java-iot-shadow-rule-thing";
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName("java-iot-shadow-rule-queue")
                .build()).queueUrl();

        try {
            iot.createTopicRule(CreateTopicRuleRequest.builder()
                    .ruleName(ruleName)
                    .topicRulePayload(TopicRulePayload.builder()
                            .sql("SELECT *, topic() AS topic, clientid() AS cid "
                                    + "FROM '$aws/things/+/shadow/name/building/update/accepted' "
                                    + "WHERE endswith(clientToken, 'inbound')")
                            .ruleDisabled(false)
                            .actions(Action.builder()
                                    .sqs(SqsAction.builder()
                                            .roleArn(TestFixtures.globalArn(
                                                    "iam", "000000000000", "role/iot-rule-role"))
                                            .queueUrl(queueUrl)
                                            .useBase64(false)
                                            .build())
                                    .build())
                            .build())
                    .build());

            iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                    .thingName(thingName)
                    .shadowName("building")
                    .payload(SdkBytes.fromUtf8String(
                            "{\"state\":{\"desired\":{\"temp\":20}},\"clientToken\":\"x:outbound\"}"))
                    .build());
            UpdateThingShadowResponse updated = iotData.updateThingShadow(UpdateThingShadowRequest.builder()
                    .thingName(thingName)
                    .shadowName("building")
                    .payload(SdkBytes.fromUtf8String(
                            "{\"state\":{\"desired\":{\"temp\":21}},\"clientToken\":\"x:inbound\"}"))
                    .build());
            JsonNode accepted = OBJECT_MAPPER.readTree(updated.payload().asByteArray());

            ReceiveMessageResponse received = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(5)
                    .build());
            assertThat(received.messages()).hasSize(1);
            JsonNode message = OBJECT_MAPPER.readTree(received.messages().get(0).body());
            assertThat(message.path("topic").asText())
                    .isEqualTo("$aws/things/" + thingName + "/shadow/name/building/update/accepted");
            assertThat(message.path("cid").asText()).isEqualTo("N/A");
            assertThat(message.path("clientToken").asText()).isEqualTo("x:inbound");
            assertThat(message.at("/state/desired/temp").asInt()).isEqualTo(21);
            assertThat(message.path("version").asLong()).isEqualTo(accepted.path("version").asLong());

            ReceiveMessageResponse outbound = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build());
            assertThat(outbound.messages()).isEmpty();
        } finally {
            try {
                iot.deleteTopicRule(DeleteTopicRuleRequest.builder().ruleName(ruleName).build());
            } catch (ResourceNotFoundException ignored) {
                // The rule was never created; there is nothing to clean up.
            }
            try {
                iotData.deleteThingShadow(DeleteThingShadowRequest.builder()
                        .thingName(thingName)
                        .shadowName("building")
                        .build());
            } catch (software.amazon.awssdk.services.iotdataplane.model.ResourceNotFoundException ignored) {
                // Name clash with the IoT control-plane ResourceNotFoundException; the shadow was never created.
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

        DescribeEndpointResponse jobsEndpoint = iot.describeEndpoint(DescribeEndpointRequest.builder().endpointType("iot:Jobs").build());
        assertThat(jobsEndpoint.endpointAddress()).isNotBlank();

        CreateThingTypeResponse createdType = iot.createThingType(CreateThingTypeRequest.builder()
                .thingTypeName(thingType)
                .thingTypeProperties(ThingTypeProperties.builder()
                        .thingTypeDescription("java type")
                        .searchableAttributes("model")
                        .build())
                .build());
        assertThat(createdType.thingTypeName()).isEqualTo(thingType);
        DescribeThingTypeResponse describedType = iot.describeThingType(DescribeThingTypeRequest.builder().thingTypeName(thingType).build());
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

        CreateThingResponse createdThing = iot.createThing(CreateThingRequest.builder()
                .thingName(thingName)
                .thingTypeName(thingType)
                .attributePayload(AttributePayload.builder().attributes(Map.of("model", "j1")).build())
                .build());
        assertThat(iot.describeThing(DescribeThingRequest.builder().thingName(thingName).build()).thingTypeName()).isEqualTo(thingType);

        CreateThingGroupResponse createdGroup = iot.createThingGroup(CreateThingGroupRequest.builder()
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

        CreateJobResponse createdJob = iot.createJob(CreateJobRequest.builder()
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

        GetPendingJobExecutionsResponse pending = iotJobsData.getPendingJobExecutions(GetPendingJobExecutionsRequest.builder().thingName(thingName).build());
        assertThat(pending.queuedJobs()).anyMatch(job -> jobId.equals(job.jobId()));
        StartNextPendingJobExecutionResponse started = iotJobsData.startNextPendingJobExecution(StartNextPendingJobExecutionRequest.builder()
                .thingName(thingName)
                .statusDetails(Map.of("phase", "download"))
                .build());
        assertThat(started.execution().status()).isEqualTo(JobExecutionStatus.IN_PROGRESS);
        UpdateJobExecutionResponse updated = iotJobsData.updateJobExecution(UpdateJobExecutionRequest.builder()
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

    @Test
    void mqttPresenceEventsReachTopicRulesOverSqs() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String clientId = "tklocal-java-" + suffix;
        String queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName("java-iot-presence-" + suffix)
                .build()).queueUrl();
        String connectedRule = "java_iot_presence_connected_" + suffix;
        String disconnectedRule = "java_iot_presence_disconnected_" + suffix;
        createSqsRule(connectedRule,
                "SELECT * FROM '$aws/events/presence/connected/+' WHERE startswith(clientId, 'tklocal-')", queueUrl);
        createSqsRule(disconnectedRule,
                "SELECT * FROM '$aws/events/presence/disconnected/+' WHERE startswith(clientId, 'tklocal-')", queueUrl);
        try {
            try (Socket client = mqttConnect(clientId)) {
                client.getOutputStream().write(new byte[] {(byte) 0xe0, 0x00});
            }

            Map<String, JsonNode> byType = new HashMap<>();
            long deadline = System.currentTimeMillis() + 10_000;
            while (byType.size() < 2 && System.currentTimeMillis() < deadline) {
                for (Message message : sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .build()).messages()) {
                    JsonNode event = OBJECT_MAPPER.readTree(message.body());
                    if (clientId.equals(event.path("clientId").asText())) {
                        byType.put(event.path("eventType").asText(), event);
                    }
                }
                Thread.sleep(100);
            }

            assertThat(byType).containsOnlyKeys("connected", "disconnected");
            JsonNode connected = byType.get("connected");
            JsonNode disconnected = byType.get("disconnected");
            assertThat(connected.path("timestamp").isIntegralNumber()).isTrue();
            assertThat(connected.path("ipAddress").asText()).isNotEmpty();
            assertThat(disconnected.path("sessionIdentifier").asText())
                    .isNotEmpty()
                    .isEqualTo(connected.path("sessionIdentifier").asText());
            assertThat(disconnected.path("versionNumber").asLong()).isEqualTo(connected.path("versionNumber").asLong());
            assertThat(disconnected.path("clientInitiatedDisconnect").asBoolean()).isTrue();
            assertThat(disconnected.path("disconnectReason").asText()).isEqualTo("CLIENT_INITIATED_DISCONNECT");
        } finally {
            iot.deleteTopicRule(DeleteTopicRuleRequest.builder().ruleName(connectedRule).build());
            iot.deleteTopicRule(DeleteTopicRuleRequest.builder().ruleName(disconnectedRule).build());
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
        }
    }

    private void createSqsRule(String ruleName, String sql, String queueUrl) {
        iot.createTopicRule(CreateTopicRuleRequest.builder()
                .ruleName(ruleName)
                .topicRulePayload(TopicRulePayload.builder()
                        .sql(sql)
                        .awsIotSqlVersion("2016-03-23")
                        .ruleDisabled(false)
                        .actions(Action.builder()
                                .sqs(SqsAction.builder()
                                        .roleArn(TestFixtures.globalArn("iam", "000000000000", "role/iot-rule-role"))
                                        .queueUrl(queueUrl)
                                        .useBase64(false)
                                        .build())
                                .build())
                        .build())
                .build());
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
