package com.floci.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.lightsail.model.CreateInstancesRequest;
import software.amazon.awssdk.services.lightsail.model.InstanceState;
import software.amazon.awssdk.services.lightsail.model.NetworkProtocol;
import software.amazon.awssdk.services.lightsail.model.PortInfo;
import software.amazon.awssdk.services.lightsail.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;

class LightsailTest {

    private LightsailClient lightsail;
    private String suffix;

    @BeforeEach
    void setUp() {
        lightsail = TestFixtures.lightsailClient();
        suffix = TestFixtures.uniqueName("ls");
    }

    @Test
    void sdkCanCreateAndManageCoreLightsailResources() {
        String instanceName = suffix + "-web";
        String diskName = suffix + "-data";
        String staticIpName = suffix + "-ip";
        String keyPairName = suffix + "-key";

        assertThat(lightsail.getBlueprints().blueprints())
                .extracting("blueprintId")
                .contains("ubuntu_22_04");
        assertThat(lightsail.getBundles().bundles())
                .extracting("bundleId")
                .contains("nano_3_0");
        assertThat(lightsail.getRegions(r -> r.includeAvailabilityZones(true)).regions().get(0).availabilityZones())
                .isNotEmpty();

        var keyPair = lightsail.createKeyPair(r -> r
                .keyPairName(keyPairName)
                .tags(Tag.builder().key("owner").value("sdk").build()));
        assertThat(keyPair.keyPair().name()).isEqualTo(keyPairName);
        assertThat(keyPair.keyPair().arn()).startsWith("arn:aws:lightsail:");
        assertThat(keyPair.privateKeyBase64()).isNotBlank();
        assertThat(keyPair.operation().operationTypeAsString()).isEqualTo("CreateKeyPair");

        var createInstances = lightsail.createInstances(CreateInstancesRequest.builder()
                .instanceNames(instanceName)
                .availabilityZone("us-east-1a")
                .blueprintId("ubuntu_22_04")
                .bundleId("nano_3_0")
                .keyPairName(keyPairName)
                .tags(Tag.builder().key("component").value("web").build())
                .build());
        assertThat(createInstances.operations()).hasSize(1);
        assertThat(createInstances.operations().get(0).operationTypeAsString()).isEqualTo("CreateInstance");

        var instance = lightsail.getInstance(r -> r.instanceName(instanceName)).instance();
        assertThat(instance.name()).isEqualTo(instanceName);
        assertThat(instance.arn()).startsWith("arn:aws:lightsail:");
        assertThat(instance.location().regionNameAsString()).isEqualTo("us-east-1");
        assertThat(instance.location().availabilityZone()).isEqualTo("us-east-1a");
        assertThat(instance.state().name()).isEqualTo("running");
        assertThat(instance.tags()).extracting(Tag::key).contains("component");

        lightsail.stopInstance(r -> r.instanceName(instanceName));
        InstanceState stopped = lightsail.getInstanceState(r -> r.instanceName(instanceName)).state();
        assertThat(stopped.name()).isEqualTo("stopped");
        assertThat(stopped.code()).isEqualTo(80);
        lightsail.startInstance(r -> r.instanceName(instanceName));
        assertThat(lightsail.getInstance(r -> r.instanceName(instanceName)).instance().state().name()).isEqualTo("running");

        lightsail.openInstancePublicPorts(r -> r
                .instanceName(instanceName)
                .portInfo(PortInfo.builder()
                        .fromPort(80)
                        .toPort(80)
                        .protocol(NetworkProtocol.TCP)
                        .cidrs("0.0.0.0/0")
                        .build()));
        assertThat(lightsail.getInstancePortStates(r -> r.instanceName(instanceName)).portStates())
                .anySatisfy(port -> {
                    assertThat(port.fromPort()).isEqualTo(80);
                    assertThat(port.toPort()).isEqualTo(80);
                    assertThat(port.protocol()).isEqualTo(NetworkProtocol.TCP);
                    assertThat(port.stateAsString()).isEqualTo("open");
                });

        lightsail.createDisk(r -> r.diskName(diskName).availabilityZone("us-east-1a").sizeInGb(8));
        lightsail.attachDisk(r -> r.diskName(diskName).instanceName(instanceName).diskPath("/dev/xvdf"));
        var disk = lightsail.getDisk(r -> r.diskName(diskName)).disk();
        assertThat(disk.name()).isEqualTo(diskName);
        assertThat(disk.attachedTo()).isEqualTo(instanceName);
        assertThat(disk.isAttached()).isTrue();

        lightsail.allocateStaticIp(r -> r.staticIpName(staticIpName));
        lightsail.attachStaticIp(r -> r.staticIpName(staticIpName).instanceName(instanceName));
        var staticIp = lightsail.getStaticIp(r -> r.staticIpName(staticIpName)).staticIp();
        assertThat(staticIp.name()).isEqualTo(staticIpName);
        assertThat(staticIp.attachedTo()).isEqualTo(instanceName);
        assertThat(staticIp.isAttached()).isTrue();

        lightsail.tagResource(r -> r.resourceName(instanceName)
                .tags(Tag.builder().key("env").value("test").build()));
        assertThat(lightsail.getInstance(r -> r.instanceName(instanceName)).instance().tags())
                .extracting(Tag::key)
                .contains("component", "env");

        assertThat(lightsail.getActiveNames().activeNames())
                .contains(instanceName, diskName, staticIpName, keyPairName);
        assertThat(lightsail.getOperationsForResource(r -> r.resourceName(instanceName)).operations())
                .isNotEmpty();
        assertThat(lightsail.getBuckets(r -> {}).buckets()).isEmpty();

        lightsail.detachStaticIp(r -> r.staticIpName(staticIpName));
        lightsail.releaseStaticIp(r -> r.staticIpName(staticIpName));
        lightsail.detachDisk(r -> r.diskName(diskName));
        lightsail.deleteDisk(r -> r.diskName(diskName));
        lightsail.deleteInstance(r -> r.instanceName(instanceName));
        lightsail.deleteKeyPair(r -> r.keyPairName(keyPairName));

        assertThat(lightsail.getInstances().instances()).noneMatch(i -> instanceName.equals(i.name()));
    }
}
