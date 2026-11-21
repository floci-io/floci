package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AttributeBooleanValue;
import software.amazon.awssdk.services.ec2.model.AttributeValue;
import software.amazon.awssdk.services.ec2.model.ConnectionTrackingSpecificationRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.EnaSrdSpecification;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.ModifyNetworkInterfaceAttributeRequest;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.NetworkInterfaceAttachmentChanges;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2InstanceGroupSynchronizationIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementUpdatesStoredAndEmbeddedGroupsWithoutChangingInterfaceMetadata(boolean primaryAtLaunch) {
        Fixture fixture = fixture(primaryAtLaunch);
        NetworkInterface before = networkInterface(fixture);

        replace(fixture, fixture.replacement());

        assertEquals(List.of(fixture.replacement()), groupIds(networkInterface(fixture).groups()));
        Instance instance = instance(fixture);
        assertEquals(List.of(primaryAtLaunch ? fixture.replacement() : fixture.original()),
                groupIds(instance.securityGroups()));
        assertEquals(List.of(fixture.replacement()), groupIds(instance.networkInterfaces().stream()
                .filter(eni -> fixture.eni().equals(eni.networkInterfaceId())).findFirst().orElseThrow().groups()));
        NetworkInterface after = networkInterface(fixture);
        assertEquals(before.toBuilder().groups(after.groups()).build(), after,
                "Only stored group membership should change");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detachedInterfaceProtectsReplacementAfterInstanceReferenceIsRemoved(boolean primaryAtLaunch) {
        Fixture fixture = fixture(primaryAtLaunch);
        replace(fixture, fixture.replacement());
        fixture.client().detachNetworkInterface(request -> request.attachmentId(fixture.attachment()));
        // Leave no instance reference to the replacement: only the standalone ENI protects it. The instance now
        // has at most one interface, so the instance-level change is one AWS accepts.
        fixture.client().modifyInstanceAttribute(request -> request.instanceId(fixture.instance())
                .groups(fixture.original()));

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                .deleteSecurityGroup(request -> request.groupId(fixture.replacement())));

        assertEquals("DependencyViolation", error.awsErrorDetails().errorCode());
        assertEquals(List.of(fixture.replacement()), groupIds(networkInterface(fixture).groups()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementReleasesThePreviousGroupForDeletion(boolean primaryAtLaunch) {
        Fixture fixture = fixture(primaryAtLaunch);
        replace(fixture, fixture.replacement());
        if (!primaryAtLaunch) {
            // The launch-created primary holds the old group too; on an instance with two interfaces each is
            // changed on its own.
            fixture.client().modifyNetworkInterfaceAttribute(request -> request
                    .networkInterfaceId(primaryInterfaceId(fixture)).groups(fixture.replacement()));
        }

        fixture.client().deleteSecurityGroup(request -> request.groupId(fixture.original()));

        assertEquals(List.of(fixture.replacement()), groupIds(networkInterface(fixture).groups()));
    }

    @Test
    void invalidReplacementLeavesBothRepresentationsUnchanged() {
        Fixture fixture = fixture(true);
        NetworkInterface before = networkInterface(fixture);
        List<GroupIdentifier> beforeGroups = instance(fixture).securityGroups();

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                .modifyInstanceAttribute(request -> request.instanceId(fixture.instance())
                        .groups(fixture.replacement(), "sg-00000000000000000")));

        assertEquals("InvalidGroup.NotFound", error.awsErrorDetails().errorCode());
        assertEquals(before, networkInterface(fixture));
        assertEquals(beforeGroups, instance(fixture).securityGroups());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignAccountOrRegionCannotModifyTheOwnerInterfaceGroups(boolean otherRegion) {
        Fixture fixture = fixture(false);
        NetworkInterface before = networkInterface(fixture);
        Ec2Client foreign = client(otherRegion ? "555566667780" : "555566667781",
                otherRegion ? Region.US_EAST_2 : Region.US_WEST_2);

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> foreign.modifyInstanceAttribute(
                request -> request.instanceId(fixture.instance()).groups(fixture.replacement())));

        assertEquals("InvalidInstanceID.NotFound", error.awsErrorDetails().errorCode());
        assertEquals(before, networkInterface(fixture));
    }

    // Catches: ModifyNetworkInterfaceAttribute reading the group list under a name the SDKs do not send (they
    // send SecurityGroupId.N), so the call reports success and changes nothing.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modifyNetworkInterfaceAttributeReplacesTheInterfaceGroups(boolean primaryAtLaunch) {
        Fixture fixture = fixture(primaryAtLaunch);

        fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(fixture.eni())
                .groups(fixture.replacement()));

        assertEquals(List.of(fixture.replacement()), groupIds(networkInterface(fixture).groups()));
        // Only the primary interface's groups are the instance's.
        assertEquals(List.of(primaryAtLaunch ? fixture.replacement() : fixture.original()),
                groupIds(instance(fixture).securityGroups()));
    }

    // Catches: an instance's launch-created primary interface, which has no standalone record, answering
    // InvalidNetworkInterfaceID.NotFound, or taking the groups on only one of its two copies.
    @Test
    void theLaunchCreatedPrimaryInterfaceTakesTheGroups() {
        Fixture fixture = fixture(false);
        String primary = primaryInterfaceId(fixture);

        fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(primary)
                .groups(fixture.replacement()));

        assertEquals(List.of(fixture.replacement()), groupIds(fixture.client().describeNetworkInterfaces(
                request -> request.networkInterfaceIds(primary)).networkInterfaces().getFirst().groups()));
        assertEquals(List.of(fixture.replacement()), groupIds(instance(fixture).securityGroups()));
    }

    // Catches: the attributes other than groups, which Terraform sends through the same action one per call,
    // being accepted and dropped.
    @Test
    void modifyNetworkInterfaceAttributeAppliesTheOtherAttributes() {
        Fixture fixture = fixture(false);

        fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(fixture.eni())
                .description(AttributeValue.builder().value("changed description").build()));
        fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(fixture.eni())
                .sourceDestCheck(AttributeBooleanValue.builder().value(false).build()));
        fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(fixture.eni())
                .attachment(NetworkInterfaceAttachmentChanges.builder().attachmentId(fixture.attachment())
                        .deleteOnTermination(true).build()));

        NetworkInterface changed = networkInterface(fixture);
        assertEquals("changed description", changed.description());
        assertFalse(changed.sourceDestCheck());
        assertTrue(changed.attachment().deleteOnTermination());
        // Back to false, so terminating the instance during cleanup leaves the interface to be deleted.
        fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(fixture.eni())
                .attachment(NetworkInterfaceAttachmentChanges.builder().attachmentId(fixture.attachment())
                        .deleteOnTermination(false).build()));
        assertFalse(networkInterface(fixture).attachment().deleteOnTermination());
    }

    // Catches: an instance-level group or source/destination check change on an instance with several
    // interfaces overwriting every interface, groups a secondary interface was given on its own included, where
    // AWS refuses the call and asks for the interface instead.
    @Test
    void anInstanceWithSeveralInterfacesRefusesInstanceLevelChanges() {
        Fixture fixture = fixture(false);
        fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(fixture.eni())
                .groups(fixture.replacement()));
        NetworkInterface before = networkInterface(fixture);

        Ec2Exception groups = assertThrows(Ec2Exception.class, () -> fixture.client().modifyInstanceAttribute(
                request -> request.instanceId(fixture.instance()).groups(fixture.original())));
        Ec2Exception sourceDestCheck = assertThrows(Ec2Exception.class, () -> fixture.client()
                .modifyInstanceAttribute(request -> request.instanceId(fixture.instance())
                        .sourceDestCheck(AttributeBooleanValue.builder().value(false).build())));

        for (Ec2Exception error : List.of(groups, sourceDestCheck)) {
            assertEquals("InvalidInstanceID", error.awsErrorDetails().errorCode());
            assertEquals("There are multiple interfaces attached to instance '" + fixture.instance()
                    + "'. Please specify an interface ID for the operation instead.", error.awsErrorDetails().errorMessage());
        }
        assertEquals(before, networkInterface(fixture));
        assertEquals(List.of(fixture.original()), groupIds(instance(fixture).securityGroups()));
    }

    // Catches: a member the action does not apply answering true with nothing changed; Terraform sends
    // EnablePrimaryIpv6 through this action for aws_instance.
    @Test
    void membersTheActionDoesNotApplyAreRefused() {
        Fixture fixture = fixture(false);
        List<Consumer<ModifyNetworkInterfaceAttributeRequest.Builder>> unapplied = List.of(
                request -> request.enablePrimaryIpv6(true),
                request -> request.associatePublicIpAddress(true),
                request -> request.associatedSubnetIds("subnet-00000000000000000"),
                request -> request.enaSrdSpecification(EnaSrdSpecification.builder().enaSrdEnabled(true).build()),
                request -> request.connectionTrackingSpecification(ConnectionTrackingSpecificationRequest.builder()
                        .tcpEstablishedTimeout(120).build()),
                request -> request.attachment(NetworkInterfaceAttachmentChanges.builder()
                        .attachmentId(fixture.attachment()).enaQueueCount(2).build()),
                request -> request.attachment(NetworkInterfaceAttachmentChanges.builder()
                        .attachmentId(fixture.attachment()).defaultEnaQueueCount(true).build()));
        NetworkInterface before = networkInterface(fixture);

        for (Consumer<ModifyNetworkInterfaceAttributeRequest.Builder> member : unapplied) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                    .modifyNetworkInterfaceAttribute(request -> member.accept(request.networkInterfaceId(fixture.eni()))));
            assertEquals("UnsupportedOperation", error.awsErrorDetails().errorCode());
        }
        assertEquals(before, networkInterface(fixture));
    }

    @Test
    void anUnknownGroupLeavesTheInterfaceUnchanged() {
        Fixture fixture = fixture(false);
        NetworkInterface before = networkInterface(fixture);

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client().modifyNetworkInterfaceAttribute(
                request -> request.networkInterfaceId(fixture.eni()).groups(fixture.replacement(),
                        "sg-00000000000000000")));

        assertEquals("InvalidGroup.NotFound", error.awsErrorDetails().errorCode());
        assertEquals(before, networkInterface(fixture));
    }

    @Test
    void anAttachmentTheInterfaceDoesNotHaveIsRefused() {
        Fixture fixture = fixture(false);

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client().modifyNetworkInterfaceAttribute(
                request -> request.networkInterfaceId(fixture.eni()).attachment(NetworkInterfaceAttachmentChanges
                        .builder().attachmentId("eni-attach-00000000000000000").deleteOnTermination(true).build())));

        assertEquals("InvalidAttachmentID.NotFound", error.awsErrorDetails().errorCode());
        assertFalse(networkInterface(fixture).attachment().deleteOnTermination());
    }

    @Test
    void anUnknownInterfaceIsNotFound() {
        Fixture fixture = fixture(false);

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client().modifyNetworkInterfaceAttribute(
                request -> request.networkInterfaceId("eni-00000000000000000").groups(fixture.replacement())));

        assertEquals("InvalidNetworkInterfaceID.NotFound", error.awsErrorDetails().errorCode());
    }

    private Fixture fixture(boolean primaryAtLaunch) {
        Ec2Client client = client("555566667780", Region.US_WEST_2);
        String vpc = client.createVpc(request -> request.cidrBlock("10.232.0.0/16")).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String original = group(client, vpc);
        String replacement = group(client, vpc);
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.232.0.0/24"))
                .subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        String eni = client.createNetworkInterface(request -> request.subnetId(subnet).groups(original)
                .description("owned instance group synchronization fixture"))
                .networkInterface().networkInterfaceId();
        cleanup.register(() -> client.deleteNetworkInterface(request -> request.networkInterfaceId(eni)));
        String token = UUID.randomUUID().toString();
        cleanup.register(() -> client.describeInstances(request -> request.filters(
                Filter.builder().name("client-token").values(token).build())).reservations().forEach(reservation ->
                        reservation.instances().forEach(instance -> {
                            client.terminateInstances(request -> request.instanceIds(instance.instanceId()));
                            client.describeInstances(request -> request.instanceIds(instance.instanceId()));
                        })));
        String instance = client.runInstances(request -> {
            request.imageId("ami-amazonlinux2023").instanceType("t2.micro").minCount(1).maxCount(1)
                    .clientToken(token);
            if (primaryAtLaunch) {
                request.networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                        .networkInterfaceId(eni).deviceIndex(0).deleteOnTermination(false).build());
            } else {
                request.subnetId(subnet).securityGroupIds(original);
            }
        }).instances().getFirst().instanceId();
        client.describeInstances(request -> request.instanceIds(instance));
        String attachment = primaryAtLaunch
                ? client.describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst().attachment().attachmentId()
                : client.attachNetworkInterface(request -> request.networkInterfaceId(eni)
                        .instanceId(instance).deviceIndex(1)).attachmentId();
        cleanup.register(() -> {
            NetworkInterface current = client.describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                    .networkInterfaces().getFirst();
            if (current.attachment() != null) {
                client.detachNetworkInterface(request -> request.attachmentId(current.attachment().attachmentId()));
            }
        });
        return new Fixture(client, instance, eni, attachment, original, replacement, primaryAtLaunch);
    }

    private Ec2Client client(String account, Region region) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), account, region);
        cleanup.register(client::close);
        return client;
    }

    private String group(Ec2Client client, String vpc) {
        String name = "instance-groups-" + UUID.randomUUID();
        cleanup.register(() -> client.describeSecurityGroups(request -> request.filters(
                Filter.builder().name("vpc-id").values(vpc).build(),
                Filter.builder().name("group-name").values(name).build())).securityGroups().forEach(group ->
                        client.deleteSecurityGroup(request -> request.groupId(group.groupId()))));
        return client.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("owned instance group synchronization fixture")).groupId();
    }

    /**
     * Gives the fixture interface {@code group} the way AWS allows: through the instance while it is the instance's
     * only interface, through the interface itself once the instance has another.
     */
    private static void replace(Fixture fixture, String group) {
        if (fixture.primaryAtLaunch()) {
            fixture.client().modifyInstanceAttribute(request -> request.instanceId(fixture.instance()).groups(group));
        } else {
            fixture.client().modifyNetworkInterfaceAttribute(request -> request.networkInterfaceId(fixture.eni())
                    .groups(group));
        }
    }

    private static NetworkInterface networkInterface(Fixture fixture) {
        return fixture.client().describeNetworkInterfaces(request -> request.networkInterfaceIds(fixture.eni()))
                .networkInterfaces().getFirst();
    }

    private static Instance instance(Fixture fixture) {
        return fixture.client().describeInstances(request -> request.instanceIds(fixture.instance()))
                .reservations().getFirst().instances().getFirst();
    }

    private static String primaryInterfaceId(Fixture fixture) {
        return instance(fixture).networkInterfaces().stream()
                .filter(eni -> eni.attachment().deviceIndex() == 0).findFirst().orElseThrow().networkInterfaceId();
    }

    private static List<String> groupIds(List<GroupIdentifier> groups) {
        return groups.stream().map(GroupIdentifier::groupId).toList();
    }

    private record Fixture(Ec2Client client, String instance, String eni, String attachment,
                           String original, String replacement, boolean primaryAtLaunch) { }
}
