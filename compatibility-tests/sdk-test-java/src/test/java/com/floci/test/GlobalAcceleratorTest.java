package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.globalaccelerator.GlobalAcceleratorClient;
import software.amazon.awssdk.services.globalaccelerator.model.AcceleratorNotFoundException;
import software.amazon.awssdk.services.globalaccelerator.model.CreateAcceleratorResponse;
import software.amazon.awssdk.services.globalaccelerator.model.CreateEndpointGroupResponse;
import software.amazon.awssdk.services.globalaccelerator.model.CreateListenerResponse;
import software.amazon.awssdk.services.globalaccelerator.model.DescribeAcceleratorAttributesResponse;
import software.amazon.awssdk.services.globalaccelerator.model.DescribeAcceleratorResponse;
import software.amazon.awssdk.services.globalaccelerator.model.DescribeEndpointGroupResponse;
import software.amazon.awssdk.services.globalaccelerator.model.HealthState;
import software.amazon.awssdk.services.globalaccelerator.model.InvalidArgumentException;
import software.amazon.awssdk.services.globalaccelerator.model.IpAddressType;
import software.amazon.awssdk.services.globalaccelerator.model.ListAcceleratorsRequest;
import software.amazon.awssdk.services.globalaccelerator.model.ListAcceleratorsResponse;
import software.amazon.awssdk.services.globalaccelerator.model.ListEndpointGroupsResponse;
import software.amazon.awssdk.services.globalaccelerator.model.ListListenersResponse;
import software.amazon.awssdk.services.globalaccelerator.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.globalaccelerator.model.PortRange;
import software.amazon.awssdk.services.globalaccelerator.model.Protocol;
import software.amazon.awssdk.services.globalaccelerator.model.Tag;
import software.amazon.awssdk.services.globalaccelerator.model.UpdateAcceleratorAttributesResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Global Accelerator")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlobalAcceleratorTest {

    private static GlobalAcceleratorClient globalAccelerator;
    private static String acceleratorName;
    private static String acceleratorArn;
    private static String listenerArn;
    private static String endpointGroupArn;

    @BeforeAll
    static void setup() {
        globalAccelerator = TestFixtures.globalAcceleratorClient();
        acceleratorName = "sdk-test-accelerator-" + System.currentTimeMillis();
    }

    @AfterAll
    static void cleanup() {
        if (globalAccelerator == null) {
            return;
        }
        if (acceleratorArn != null) {
            try {
                globalAccelerator.updateAccelerator(r -> r.acceleratorArn(acceleratorArn).enabled(false));
                globalAccelerator.deleteAccelerator(r -> r.acceleratorArn(acceleratorArn));
            } catch (RuntimeException e) {
                System.out.println("Global Accelerator cleanup skipped: " + e.getMessage());
            }
        }
        globalAccelerator.close();
    }

    @Test
    @Order(1)
    void createAccelerator() {
        CreateAcceleratorResponse response = globalAccelerator.createAccelerator(r -> r
                .name(acceleratorName)
                .ipAddressType(IpAddressType.IPV4)
                .enabled(true)
                .tags(Tag.builder().key("team").value("platform").build()));

        acceleratorArn = response.accelerator().acceleratorArn();

        assertThat(acceleratorArn).contains(":globalaccelerator::").contains(":accelerator/");
        assertThat(response.accelerator().name()).isEqualTo(acceleratorName);
        assertThat(response.accelerator().enabled()).isTrue();
        assertThat(response.accelerator().dnsName()).endsWith(".awsglobalaccelerator.com");
        assertThat(response.accelerator().ipSets()).hasSize(1);
        assertThat(response.accelerator().ipSets().get(0).ipAddresses()).hasSize(2);
    }

    @Test
    @Order(2)
    void describeAcceleratorReportsDeployed() {
        DescribeAcceleratorResponse response = globalAccelerator.describeAccelerator(r -> r
                .acceleratorArn(acceleratorArn));

        // The SDK and Terraform waiters poll until DEPLOYED, so the first read must already say so.
        assertThat(response.accelerator().status().toString()).isEqualTo("DEPLOYED");
        assertThat(response.accelerator().createdTime()).isNotNull();
    }

    @Test
    @Order(3)
    void listAcceleratorsIncludesTheNewAccelerator() {
        ListAcceleratorsResponse response = globalAccelerator.listAccelerators(ListAcceleratorsRequest.builder().build());

        assertThat(response.accelerators())
                .anySatisfy(a -> assertThat(a.acceleratorArn()).isEqualTo(acceleratorArn));
    }

    @Test
    @Order(4)
    void acceleratorAttributesRoundTrip() {
        UpdateAcceleratorAttributesResponse updated = globalAccelerator.updateAcceleratorAttributes(r -> r
                .acceleratorArn(acceleratorArn)
                .flowLogsEnabled(true)
                .flowLogsS3Bucket("floci-flow-logs")
                .flowLogsS3Prefix("globalaccelerator/"));

        assertThat(updated.acceleratorAttributes().flowLogsEnabled()).isTrue();

        DescribeAcceleratorAttributesResponse described = globalAccelerator.describeAcceleratorAttributes(r -> r
                .acceleratorArn(acceleratorArn));

        assertThat(described.acceleratorAttributes().flowLogsS3Bucket()).isEqualTo("floci-flow-logs");
        assertThat(described.acceleratorAttributes().flowLogsS3Prefix()).isEqualTo("globalaccelerator/");
    }

    @Test
    @Order(5)
    void createListener() {
        CreateListenerResponse response = globalAccelerator.createListener(r -> r
                .acceleratorArn(acceleratorArn)
                .protocol(Protocol.TCP)
                .clientAffinity("SOURCE_IP")
                .portRanges(PortRange.builder().fromPort(80).toPort(80).build(),
                        PortRange.builder().fromPort(443).toPort(443).build()));

        listenerArn = response.listener().listenerArn();

        assertThat(listenerArn).startsWith(acceleratorArn + "/listener/");
        assertThat(response.listener().protocol()).isEqualTo(Protocol.TCP);
        assertThat(response.listener().portRanges()).hasSize(2);

        ListListenersResponse listed = globalAccelerator.listListeners(r -> r.acceleratorArn(acceleratorArn));
        assertThat(listed.listeners())
                .anySatisfy(l -> assertThat(l.listenerArn()).isEqualTo(listenerArn));
    }

    @Test
    @Order(6)
    void createEndpointGroup() {
        CreateEndpointGroupResponse response = globalAccelerator.createEndpointGroup(r -> r
                .listenerArn(listenerArn)
                .endpointGroupRegion("us-west-2")
                .trafficDialPercentage(80.0f)
                .healthCheckProtocol("HTTP")
                .healthCheckPath("/health")
                .healthCheckIntervalSeconds(30)
                .thresholdCount(3)
                .endpointConfigurations(c -> c.endpointId("i-0123456789abcdef0").weight(128)));

        endpointGroupArn = response.endpointGroup().endpointGroupArn();

        assertThat(endpointGroupArn).startsWith(listenerArn + "/endpoint-group/");
        assertThat(response.endpointGroup().endpointGroupRegion()).isEqualTo("us-west-2");
        assertThat(response.endpointGroup().healthCheckPath()).isEqualTo("/health");
        assertThat(response.endpointGroup().trafficDialPercentage()).isEqualTo(80.0f);
        assertThat(response.endpointGroup().endpointDescriptions()).hasSize(1);
        assertThat(response.endpointGroup().endpointDescriptions().get(0).healthState())
                .isEqualTo(HealthState.HEALTHY);

        ListEndpointGroupsResponse listed = globalAccelerator.listEndpointGroups(r -> r.listenerArn(listenerArn));
        assertThat(listed.endpointGroups())
                .anySatisfy(g -> assertThat(g.endpointGroupArn()).isEqualTo(endpointGroupArn));
    }

    @Test
    @Order(7)
    void addAndRemoveEndpoints() {
        globalAccelerator.addEndpoints(r -> r
                .endpointGroupArn(endpointGroupArn)
                .endpointConfigurations(c -> c.endpointId("i-00000000000000002").weight(32)));

        DescribeEndpointGroupResponse described = globalAccelerator.describeEndpointGroup(r -> r
                .endpointGroupArn(endpointGroupArn));

        assertThat(described.endpointGroup().endpointDescriptions())
                .anySatisfy(e -> assertThat(e.endpointId()).isEqualTo("i-00000000000000002"));

        globalAccelerator.removeEndpoints(r -> r
                .endpointGroupArn(endpointGroupArn)
                .endpointIdentifiers(i -> i.endpointId("i-00000000000000002")));

        assertThat(globalAccelerator.describeEndpointGroup(r -> r.endpointGroupArn(endpointGroupArn))
                .endpointGroup().endpointDescriptions())
                .noneSatisfy(e -> assertThat(e.endpointId()).isEqualTo("i-00000000000000002"));
    }

    @Test
    @Order(8)
    void updateEndpointGroupHealthCheck() {
        globalAccelerator.updateEndpointGroup(r -> r
                .endpointGroupArn(endpointGroupArn)
                .healthCheckPath("/ready")
                .thresholdCount(5));

        assertThat(globalAccelerator.describeEndpointGroup(r -> r.endpointGroupArn(endpointGroupArn))
                .endpointGroup())
                .satisfies(group -> {
                    assertThat(group.healthCheckPath()).isEqualTo("/ready");
                    assertThat(group.thresholdCount()).isEqualTo(5);
                });
    }

    @Test
    @Order(9)
    void tagResourceRoundTrip() {
        globalAccelerator.tagResource(r -> r
                .resourceArn(acceleratorArn)
                .tags(Tag.builder().key("env").value("test").build()));

        ListTagsForResourceResponse tagged = globalAccelerator.listTagsForResource(r -> r
                .resourceArn(acceleratorArn));

        assertThat(tagged.tags()).anySatisfy(tag -> {
            assertThat(tag.key()).isEqualTo("env");
            assertThat(tag.value()).isEqualTo("test");
        });

        globalAccelerator.untagResource(r -> r.resourceArn(acceleratorArn).tagKeys("env"));

        assertThat(globalAccelerator.listTagsForResource(r -> r.resourceArn(acceleratorArn)).tags())
                .noneSatisfy(tag -> assertThat(tag.key()).isEqualTo("env"));
    }

    @Test
    @Order(10)
    void listenerRejectsMoreThanTenPortRanges() {
        List<PortRange> portRanges = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            portRanges.add(PortRange.builder().fromPort(1000 + i).toPort(1000 + i).build());
        }

        assertThatThrownBy(() -> globalAccelerator.createListener(r -> r
                .acceleratorArn(acceleratorArn)
                .protocol(Protocol.TCP)
                .portRanges(portRanges)))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    @Order(11)
    void deleteTheAcceleratorAndItsChildren() {
        globalAccelerator.deleteEndpointGroup(r -> r.endpointGroupArn(endpointGroupArn));
        endpointGroupArn = null;

        globalAccelerator.deleteListener(r -> r.listenerArn(listenerArn));
        listenerArn = null;

        // AWS refuses to delete an enabled accelerator, so the provider disables it first.
        globalAccelerator.updateAccelerator(r -> r.acceleratorArn(acceleratorArn).enabled(false));
        globalAccelerator.deleteAccelerator(r -> r.acceleratorArn(acceleratorArn));

        String deleted = acceleratorArn;
        acceleratorArn = null;

        assertThatThrownBy(() -> globalAccelerator.describeAccelerator(r -> r.acceleratorArn(deleted)))
                .isInstanceOf(AcceleratorNotFoundException.class);
    }
}
