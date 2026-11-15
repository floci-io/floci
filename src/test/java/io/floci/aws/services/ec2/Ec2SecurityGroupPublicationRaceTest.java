package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.Reservation;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.SecurityGroupRule;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import io.floci.aws.services.ec2.model.VpcEndpoint;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.ThreadStates.awaitBlockedOrDone;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2SecurityGroupPublicationRaceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rulePublicationProtectsReferencedGroupFromDeletion(boolean egress) throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String source = service.createSecurityGroup(region, "source", "source", vpc).getGroupId();
        String target = service.createSecurityGroup(region, "target", "target", vpc).getGroupId();
        IpPermission permission = groupPermission(target);
        factory.rules.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<SecurityGroupRule>> authorized = executor.submit(() -> egress
                    ? service.authorizeSecurityGroupEgress(region, source, List.of(permission))
                    : service.authorizeSecurityGroupIngress(region, source, List.of(permission)));
            assertTrue(factory.rules.awaitParked(), "Authorization must reach rule publication");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = deleteGroup(executor, deletingThread, service, region, target);
            awaitBlockedOrDone(deletingThread, deleted);
            factory.rules.release();
            List<SecurityGroupRule> published = authorized.get(5, TimeUnit.SECONDS);
            AwsException failure = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(failure, "Deletion must observe the published cross-group reference");
            assertEquals("DependencyViolation", failure.getErrorCode());
            assertEquals(target, published.getFirst().getReferencedGroupInfo().getGroupId());
            assertEquals(1, service.describeSecurityGroups(region, List.of(target), List.of(), Map.of()).size());
        } finally {
            factory.rules.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownGroupReferenceKeepsExistingCompatibility(boolean egress) {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String source = service.createSecurityGroup(region, "source", "source", vpc).getGroupId();
        List<SecurityGroupRule> rules = egress
                ? service.authorizeSecurityGroupEgress(region, source, List.of(groupPermission("sg-unknown")))
                : service.authorizeSecurityGroupIngress(region, source, List.of(groupPermission("sg-unknown")));
        assertEquals("sg-unknown", rules.getFirst().getReferencedGroupInfo().getGroupId());
        assertTrue(service.describeSecurityGroupRules(region, List.of(source), List.of()).stream()
                .anyMatch(rule -> rules.getFirst().getSecurityGroupRuleId().equals(rule.getSecurityGroupRuleId())));
    }

    private static IpPermission groupPermission(String target) {
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId(target);
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        permission.setUserIdGroupPairs(List.of(pair));
        return permission;
    }

    @ParameterizedTest
    @CsvSource({"group,false", "group,true", "rule,false", "rule,true"})
    void failedCreationRemovesOnlyItsOwnGroupAndDefaultRule(String failingStore, boolean afterWrite) {
        TestStores factory = new TestStores();
        InMemoryStorage<String, SecurityGroup> groups = spy(new InMemoryStorage<>());
        InMemoryStorage<String, SecurityGroupRule> rules = spy(new InMemoryStorage<>());
        factory.storage.withStore("ec2-security-groups.json", groups)
                .withStore("ec2-security-group-rules.json", rules);
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        SecurityGroup existing = service.createSecurityGroup(region, "existing", "existing", vpc);
        List<String> beforeGroups = service.describeSecurityGroups(region, List.of(), List.of(), Map.of())
                .stream().map(SecurityGroup::getGroupId).sorted().toList();
        List<String> beforeRules = service.describeSecurityGroupRules(region, List.of(), List.of())
                .stream().map(SecurityGroupRule::getSecurityGroupRuleId).sorted().toList();
        IllegalStateException failure = new IllegalStateException("injected publication failure");
        if ("group".equals(failingStore)) {
            doAnswer(call -> {
                if (afterWrite) {
                    call.callRealMethod();
                }
                throw failure;
            }).when(groups).put(anyString(), any(SecurityGroup.class));
        } else {
            doAnswer(call -> {
                if (afterWrite) {
                    call.callRealMethod();
                }
                throw failure;
            }).when(rules).put(anyString(), any(SecurityGroupRule.class));
        }
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createSecurityGroup(region, "failed", "failed", vpc)));
        assertEquals(beforeGroups, service.describeSecurityGroups(region, List.of(), List.of(), Map.of())
                .stream().map(SecurityGroup::getGroupId).sorted().toList());
        assertEquals(beforeRules, service.describeSecurityGroupRules(region, List.of(), List.of())
                .stream().map(SecurityGroupRule::getSecurityGroupRuleId).sorted().toList());
        reset(groups, rules);
        SecurityGroup retry = service.createSecurityGroup(region, "failed", "retry", vpc);
        assertNotEquals(existing.getGroupId(), retry.getGroupId());
        assertEquals(1, service.describeSecurityGroupRules(region, List.of(retry.getGroupId()), List.of()).size());
    }

    @Test
    void failedCreationRetainsOriginalFailureAndAttemptsBothCleanupWrites() {
        TestStores factory = new TestStores();
        InMemoryStorage<String, SecurityGroup> groups = spy(new InMemoryStorage<>());
        InMemoryStorage<String, SecurityGroupRule> rules = spy(new InMemoryStorage<>());
        factory.storage.withStore("ec2-security-groups.json", groups)
                .withStore("ec2-security-group-rules.json", rules);
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        IllegalStateException failure = new IllegalStateException("rule publication failed");
        IllegalStateException ruleCleanup = new IllegalStateException("rule cleanup failed");
        IllegalStateException groupCleanup = new IllegalStateException("group cleanup failed");
        doThrow(failure).when(rules).put(anyString(), any(SecurityGroupRule.class));
        doThrow(ruleCleanup).when(rules).delete(anyString());
        doThrow(groupCleanup).when(groups).delete(anyString());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createSecurityGroup(region, "failed", "failed", vpc)));
        assertArrayEquals(new Throwable[]{ruleCleanup, groupCleanup}, failure.getSuppressed());
        verify(rules).delete(anyString());
        verify(groups).delete(anyString());
    }

    @Test
    void concurrentSameNameCreationRejectsTheSecondPublication() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        factory.groups.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SecurityGroup> first = executor.submit(() ->
                    service.createSecurityGroup(region, "same-name", "first", vpc));
            assertTrue(factory.groups.awaitParked(), "Creator must reach group publication");
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<AwsException> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                try {
                    service.createSecurityGroup(region, "same-name", "second", vpc);
                    return null;
                } catch (AwsException failure) {
                    return failure;
                }
            });
            awaitBlockedOrDone(secondThread, second);
            assertFalse(second.isDone(), "Second creation must wait for the first publication");
            factory.groups.release();
            SecurityGroup created = first.get(5, TimeUnit.SECONDS);
            AwsException duplicate = second.get(5, TimeUnit.SECONDS);
            assertNotNull(duplicate, "Only one group with the same name and VPC may be published");
            assertEquals("InvalidGroup.Duplicate", duplicate.getErrorCode());
            List<SecurityGroup> groups = service.describeSecurityGroups(region, List.of(),
                    List.of("same-name"), Map.of());
            assertEquals(List.of(created.getGroupId()), groups.stream().map(SecurityGroup::getGroupId).toList());
            assertEquals(1, service.describeSecurityGroupRules(region, List.of(created.getGroupId()), List.of()).size());
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sameNameInDifferentVpcsRemainsAllowed() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String firstVpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String secondVpc = service.createVpc(region, "10.1.0.0/16", false).getVpcId();
        SecurityGroup first = service.createSecurityGroup(region, "same-name", "first", firstVpc);
        SecurityGroup second = service.createSecurityGroup(region, "same-name", "second", secondVpc);
        assertNotEquals(first.getGroupId(), second.getGroupId());
        assertEquals(2, service.describeSecurityGroups(region, List.of(), List.of("same-name"), Map.of()).size());
    }

    @Test
    void deletingPublishedGroupWaitsForItsDefaultRule() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        service.describeSecurityGroups(region, List.of(), List.of(), Map.of());
        factory.rules.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SecurityGroup> created = executor.submit(() ->
                    service.createSecurityGroup(region, "default-rule-race", "race", null));
            assertTrue(factory.rules.awaitParked(), "Creator must reach default-rule publication");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<String> deleted = executor.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return service.deleteSecurityGroup(region, null, "default-rule-race", false);
            });
            awaitBlockedOrDone(deletingThread, deleted);
            assertFalse(deleted.isDone(), "Deletion must wait until the default rule is persisted");
            factory.rules.release();
            SecurityGroup group = created.get(5, TimeUnit.SECONDS);
            assertEquals(group.getGroupId(), deleted.get(5, TimeUnit.SECONDS));
            assertTrue(service.describeSecurityGroups(region, List.of(group.getGroupId()),
                    List.of(), Map.of()).isEmpty());
            assertTrue(service.describeSecurityGroupRules(region, List.of(group.getGroupId()), List.of()).isEmpty(),
                    "A deleted group must not leave an orphaned default rule");
        } finally {
            factory.rules.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void publishedInterfaceCannotReferenceSuccessfullyDeletedGroup() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "race", "race", vpc).getGroupId();
        factory.interfaces.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<NetworkInterface> created = executor.submit(() -> service.createNetworkInterface(
                    region, subnet, "race", null, List.of(), List.of(group), List.of()));
            assertTrue(factory.interfaces.awaitParked(), "Creator must reach the ENI publication boundary");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = deleteGroup(executor, deletingThread, service, region, group);
            awaitBlockedOrDone(deletingThread, deleted);
            factory.interfaces.release();
            NetworkInterface networkInterface = created.get(5, TimeUnit.SECONDS);
            AwsException error = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "Deletion must observe the creator's published group reference");
            assertEquals("DependencyViolation", error.getErrorCode());
            assertTrue(networkInterface.getGroups().stream().anyMatch(value -> group.equals(value.getGroupId())));
            assertEquals(1, service.describeSecurityGroups(region, List.of(group), List.of(), Map.of()).size());
        } finally {
            factory.interfaces.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void existingInterfacePreventsDeletion() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "positive", "positive", vpc).getGroupId();
        service.createNetworkInterface(region, subnet, "positive", null, List.of(), List.of(group), List.of());
        AwsException error = assertThrows(AwsException.class, () -> service.deleteSecurityGroup(region, group));
        assertEquals("DependencyViolation", error.getErrorCode());
        assertEquals(1, service.describeSecurityGroups(region, List.of(group), List.of(), Map.of()).size());
    }

    @Test
    void creationAfterDeletionRejectsMissingGroup() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "deleted", "deleted", vpc).getGroupId();
        service.deleteSecurityGroup(region, group);
        AwsException error = assertThrows(AwsException.class, () -> service.createNetworkInterface(
                region, subnet, "rejected", null, List.of(), List.of(group), List.of()));
        assertEquals("InvalidSecurityGroupID.NotFound", error.getErrorCode());
    }

    @Test
    void publishedInstanceCannotReferenceSuccessfullyDeletedGroup() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "launch", "launch", vpc).getGroupId();
        factory.instances.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Reservation> created = executor.submit(() -> service.runInstances(region, "ami-test", "t3.micro",
                    1, 1, null, List.of(group), subnet, null, List.of(), null, null));
            assertTrue(factory.instances.awaitParked(), "Launch must reach instance publication");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = deleteGroup(executor, deletingThread, service, region, group);
            awaitBlockedOrDone(deletingThread, deleted);
            factory.instances.release();
            Reservation reservation = created.get(5, TimeUnit.SECONDS);
            AwsException error = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "Deletion must observe the published instance reference");
            assertEquals("DependencyViolation", error.getErrorCode());
            assertEquals(group, reservation.getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            assertEquals(1, service.describeSecurityGroups(region, List.of(group), List.of(), Map.of()).size());
        } finally {
            factory.instances.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void launchRechecksGroupDeletedAfterInitialResolutionBeforeAllocating() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "deleted-launch", "launch", vpc).getGroupId();
        int available = service.describeSubnets(region, List.of(subnet), Map.of()).getFirst().getAvailableIpAddressCount();
        factory.groups.parkNextRead();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AwsException> created = executor.submit(() -> {
                try {
                    service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                            List.of(group), subnet, null, List.of(), null, null);
                    return null;
                } catch (AwsException error) {
                    return error;
                }
            });
            assertTrue(factory.groups.awaitParked(), "Launch must capture its original security group");
            service.deleteSecurityGroup(region, group);
            factory.groups.release();
            AwsException error = created.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "Launch must reject the now-deleted captured group");
            assertEquals("InvalidGroup.NotFound", error.getErrorCode());
            assertTrue(service.describeInstances(region, List.of(), Map.of()).isEmpty());
            assertTrue(service.describeVolumes(region, List.of(), Map.of()).isEmpty());
            assertEquals(available,
                    service.describeSubnets(region, List.of(subnet), Map.of()).getFirst().getAvailableIpAddressCount());
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void launchRejectsSubnetDeletedAfterResolutionBeforeAllocating() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "deleted-subnet", "launch", vpc).getGroupId();
        factory.groups.parkNextRead();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AwsException> created = executor.submit(() -> {
                try {
                    service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                            List.of(group), subnet, null, List.of(), null, null);
                    return null;
                } catch (AwsException error) {
                    return error;
                }
            });
            assertTrue(factory.groups.awaitParked(), "Launch must resolve its subnet before reading the group");
            service.deleteSubnet(region, subnet);
            factory.groups.release();
            AwsException error = created.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "Launch must reject the subnet deleted after its initial resolution");
            assertEquals("InvalidSubnetID.NotFound", error.getErrorCode());
            assertTrue(service.describeInstances(region, List.of(), Map.of()).isEmpty());
            assertTrue(service.describeVolumes(region, List.of(), Map.of()).isEmpty());
            assertTrue(service.describeNetworkInterfaces(region, List.of(), Map.of()).networkInterfaces().isEmpty());
            assertTrue(service.describeSubnets(region, List.of(subnet), Map.of()).isEmpty());
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void subnetDeletionWaitsForLaunchPublicationAndRetainsResidentSubnet() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "resident-subnet", "launch", vpc).getGroupId();
        factory.instances.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Reservation> created = executor.submit(() -> service.runInstances(region, "ami-test", "t3.micro",
                    1, 1, null, List.of(group), subnet, null, List.of(), null, null));
            assertTrue(factory.instances.awaitParked(), "Launch must reach instance publication");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = executor.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return assertThrows(AwsException.class, () -> service.deleteSubnet(region, subnet));
            });
            awaitBlockedOrDone(deletingThread, deleted);
            assertFalse(deleted.isDone(), "Deletion must wait for launch publication");
            factory.instances.release();
            Reservation reservation = created.get(5, TimeUnit.SECONDS);
            AwsException failure = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(failure, "Stored endpoint membership must prevent group deletion");
            assertEquals("DependencyViolation", failure.getErrorCode());
            assertEquals(subnet, reservation.getInstances().getFirst().getSubnetId());
            assertEquals(1, service.describeSubnets(region, List.of(subnet), Map.of()).size());
        } finally {
            factory.instances.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void existingInstancePreventsDeletionUntilTerminated() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "existing-launch", "launch", vpc).getGroupId();
        String id = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(group), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSecurityGroup(region, group)).getErrorCode());
        service.terminateInstances(region, List.of(id));
        service.deleteSecurityGroup(region, group);
        assertTrue(service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                .noneMatch(value -> group.equals(value.getGroupId())));
    }

    @Test
    void modifyRejectsGroupDeletedAfterCapturedRead() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "original-modify", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "replacement-modify", "replacement", vpc).getGroupId();
        String instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        factory.groups.parkNextRead();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AwsException> modified = executor.submit(() -> {
                try {
                    service.modifyInstanceGroups(region, instance, List.of(replacement));
                    return null;
                } catch (AwsException error) {
                    return error;
                }
            });
            assertTrue(factory.groups.awaitParked(), "Modifier must capture the replacement group before deletion");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = deleteGroup(executor, deletingThread, service, region, replacement);
            awaitBlockedOrDone(deletingThread, deleted);
            factory.groups.release();
            AwsException modifyError = modified.get(5, TimeUnit.SECONDS);
            AwsException deleteError = deleted.get(5, TimeUnit.SECONDS);
            assertTrue(modifyError != null || deleteError != null,
                    "Deletion and a new assignment cannot both succeed for the captured group");
            if (deleteError == null) {
                assertEquals("InvalidGroup.NotFound", modifyError.getErrorCode());
                assertEquals(original, service.describeInstances(region, List.of(instance), Map.of()).getFirst()
                        .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            } else {
                assertEquals("DependencyViolation", deleteError.getErrorCode());
                assertNull(modifyError);
                assertEquals(replacement, service.describeInstances(region, List.of(instance), Map.of()).getFirst()
                        .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            }
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void modifyExistingGroupMovesDependencyToReplacement() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "original-positive", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "replacement-positive", "replacement", vpc).getGroupId();
        String instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        service.modifyInstanceGroups(region, instance, List.of(replacement));
        service.deleteSecurityGroup(region, original);
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSecurityGroup(region, replacement)).getErrorCode());
        assertEquals(replacement, service.describeInstances(region, List.of(instance), Map.of()).getFirst()
                .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
    }

    @Test
    void modifyMissingGroupPreservesOriginalDependency() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "original-missing", "original", vpc).getGroupId();
        String instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        assertEquals("InvalidGroup.NotFound", assertThrows(AwsException.class,
                () -> service.modifyInstanceGroups(region, instance, List.of("sg-missing"))).getErrorCode());
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSecurityGroup(region, original)).getErrorCode());
    }

    @Test
    void successfulSameInstanceUpdatesPublishInFirewallOrder() throws Exception {
        TestStores factory = new TestStores();
        EmulatorConfig config = mockConfig();
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory.storage);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "ordered-original", "original", vpc).getGroupId();
        String firstGroup = service.createSecurityGroup(region, "ordered-first", "first", vpc).getGroupId();
        String secondGroup = service.createSecurityGroup(region, "ordered-second", "second", vpc).getGroupId();
        Instance instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst();
        instance.setState(InstanceState.running());
        EmulatorConfig.NetworkConfig network = mock(EmulatorConfig.NetworkConfig.class);
        EmulatorConfig.SecurityGroupEnforcementConfig enforcement = mock(EmulatorConfig.SecurityGroupEnforcementConfig.class);
        when(config.network()).thenReturn(network);
        when(network.securityGroupEnforcement()).thenReturn(enforcement);
        when(enforcement.enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);
        CountDownLatch applied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Set<String>> firewall = new AtomicReference<>();
        doAnswer(invocation -> {
            Set<String> groups = invocation.getArgument(1);
            firewall.set(Set.copyOf(groups));
            if (groups.contains(firstGroup)) {
                applied.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(manager).updateSecurityGroups(anyString(), anySet(), anyMap(), anyMap());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() ->
                    service.modifyInstanceGroups(region, instance.getInstanceId(), List.of(firstGroup)));
            assertTrue(applied.await(5, TimeUnit.SECONDS), "First firewall update must precede its publication");
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<?> second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                service.modifyInstanceGroups(region, instance.getInstanceId(), List.of(secondGroup));
            });
            awaitBlockedOrDone(secondThread, second);
            boolean secondOvertook = second.isDone();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            Instance stored = service.describeInstances(region, List.of(instance.getInstanceId()), Map.of())
                    .getFirst().getInstances().getFirst();
            assertAll(
                    () -> assertFalse(secondOvertook, "Same-instance update must wait through firewall publication"),
                    () -> assertEquals(Set.of(secondGroup), firewall.get()),
                    () -> assertEquals(secondGroup, stored.getSecurityGroups().getFirst().getGroupId()),
                    () -> assertEquals(secondGroup, stored.getNetworkInterfaces().getFirst().getGroups().getFirst().getGroupId()),
                    () -> assertEquals(secondGroup, service.describeNetworkInterfaces(region,
                            List.of(stored.getNetworkInterfaces().getFirst().getNetworkInterfaceId()), Map.of())
                            .networkInterfaces().getFirst().getGroups().getFirst().getGroupId()));
            service.deleteSecurityGroup(region, firstGroup);
            assertEquals("DependencyViolation", assertThrows(AwsException.class,
                    () -> service.deleteSecurityGroup(region, secondGroup)).getErrorCode());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void pendingFirewallAssignmentBlocksOnlyItsGroupAndReleasesOnFailure() throws Exception {
        TestStores factory = new TestStores();
        EmulatorConfig config = mockConfig();
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory.storage);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "firewall-original", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "firewall-replacement", "replacement", vpc).getGroupId();
        String unrelated = service.createSecurityGroup(region, "firewall-unrelated", "unrelated", vpc).getGroupId();
        Instance instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst();
        instance.setState(InstanceState.running());
        EmulatorConfig.NetworkConfig network = mock(EmulatorConfig.NetworkConfig.class);
        EmulatorConfig.SecurityGroupEnforcementConfig enforcement = mock(EmulatorConfig.SecurityGroupEnforcementConfig.class);
        when(config.network()).thenReturn(network);
        when(network.securityGroupEnforcement()).thenReturn(enforcement);
        when(enforcement.enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);
        CountDownLatch updating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("Owned firewall failure");
        doAnswer(invocation -> {
            updating.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw failure;
        }).when(manager).updateSecurityGroups(anyString(), anySet(), anyMap(), anyMap());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> modified = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.modifyInstanceGroups(region, instance.getInstanceId(), List.of(replacement))));
            assertTrue(updating.await(5, TimeUnit.SECONDS), "Must reach actual firewall update call");
            Future<?> independent = executor.submit(() -> service.deleteSecurityGroup(region, unrelated));
            independent.get(5, TimeUnit.SECONDS);
            assertEquals("DependencyViolation", assertThrows(AwsException.class,
                    () -> service.deleteSecurityGroup(region, replacement)).getErrorCode());
            release.countDown();
            assertSame(failure, modified.get(5, TimeUnit.SECONDS));
            service.deleteSecurityGroup(region, replacement);
            assertEquals(original, service.describeInstances(region, List.of(instance.getInstanceId()), Map.of()).getFirst()
                    .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            assertEquals("DependencyViolation", assertThrows(AwsException.class,
                    () -> service.deleteSecurityGroup(region, original)).getErrorCode());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentFailedAssignmentsReleaseOnlyTheirOwnPendingReference() throws Exception {
        TestStores factory = new TestStores();
        EmulatorConfig config = mockConfig();
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory.storage);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "parallel-original", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "parallel-replacement", "replacement", vpc).getGroupId();
        List<Instance> instances = service.runInstances(region, "ami-test", "t3.micro", 2, 2, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances();
        instances.forEach(instance -> instance.setState(InstanceState.running()));
        EmulatorConfig.NetworkConfig network = mock(EmulatorConfig.NetworkConfig.class);
        EmulatorConfig.SecurityGroupEnforcementConfig enforcement = mock(EmulatorConfig.SecurityGroupEnforcementConfig.class);
        when(config.network()).thenReturn(network);
        when(network.securityGroupEnforcement()).thenReturn(enforcement);
        when(enforcement.enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            boolean first = calls.incrementAndGet() == 1;
            (first ? firstEntered : secondEntered).countDown();
            assertTrue((first ? firstRelease : secondRelease).await(5, TimeUnit.SECONDS));
            throw new IllegalStateException("Owned parallel firewall failure");
        }).when(manager).updateSecurityGroups(anyString(), anySet(), anyMap(), anyMap());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.modifyInstanceGroups(region, instances.getFirst().getInstanceId(), List.of(replacement))));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.modifyInstanceGroups(region, instances.getLast().getInstanceId(), List.of(replacement))));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            firstRelease.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals("DependencyViolation", assertThrows(AwsException.class,
                    () -> service.deleteSecurityGroup(region, replacement)).getErrorCode());
            secondRelease.countDown();
            second.get(5, TimeUnit.SECONDS);
            service.deleteSecurityGroup(region, replacement);
            assertEquals(2, calls.get());
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void endpointCreationRechecksAGroupDeletedAfterItsFirstRead() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String group = service.createSecurityGroup(region, "endpoint-race", "endpoint", vpc).getGroupId();
        factory.groups.parkNextRead();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AwsException> created = executor.submit(() -> {
                try {
                    service.createVpcEndpoint(region, vpc, "test-service", "Interface",
                            List.of(), List.of(), List.of(group), false, null,
                            List.of(new Tag("EndpointRace", "rejected")));
                    return null;
                } catch (AwsException failure) {
                    return failure;
                }
            });
            assertTrue(factory.groups.awaitParked());
            service.deleteSecurityGroup(region, group);
            factory.groups.release();
            AwsException failure = created.get(5, TimeUnit.SECONDS);
            assertNotNull(failure, "Creation must reject the deleted group");
            assertEquals("InvalidGroup.NotFound", failure.getErrorCode());
            assertTrue(service.describeVpcEndpoints(region, List.of(), Map.of()).isEmpty());
            assertTrue(service.describeTags(region, Map.of("key", List.of("EndpointRace"))).isEmpty());
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void endpointPublicationProtectsItsGroupUntilEndpointDeletion() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String group = service.createSecurityGroup(region, "endpoint-reference", "endpoint", vpc).getGroupId();
        factory.endpoints.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<VpcEndpoint> created = executor.submit(() -> createEndpoint(service, region, vpc, group));
            assertTrue(factory.endpoints.awaitParked());
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = deleteGroup(executor, deletingThread, service, region, group);
            awaitBlockedOrDone(deletingThread, deleted);
            assertFalse(deleted.isDone());
            factory.endpoints.release();
            VpcEndpoint endpoint = created.get(5, TimeUnit.SECONDS);
            AwsException failure = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(failure, "Stored endpoint membership must prevent group deletion");
            assertEquals("DependencyViolation", failure.getErrorCode());
            service.deleteVpcEndpoints(region, List.of(endpoint.getVpcEndpointId()));
            service.deleteSecurityGroup(region, group);
        } finally {
            factory.endpoints.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void endpointGroupModificationProtectsValidatedReferenceFromDeletion() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String original = service.createSecurityGroup(region, "endpoint-original", "endpoint", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "endpoint-replacement", "endpoint", vpc).getGroupId();
        VpcEndpoint endpoint = createEndpoint(service, region, vpc, original);
        factory.groups.parkNextRead();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<VpcEndpoint> modified = executor.submit(() -> service.modifyVpcEndpoint(region,
                    endpoint.getVpcEndpointId(), List.of(), List.of(), List.of(), List.of(),
                    List.of(replacement), List.of(original), null, null, null));
            assertTrue(factory.groups.awaitParked());
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = deleteGroup(executor, deletingThread, service, region, replacement);
            awaitBlockedOrDone(deletingThread, deleted);
            assertFalse(deleted.isDone());
            factory.groups.release();
            assertEquals(List.of(replacement), modified.get(5, TimeUnit.SECONDS).getSecurityGroupIds());
            AwsException failure = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(failure, "Stored endpoint membership must prevent group deletion");
            assertEquals("DependencyViolation", failure.getErrorCode());
            service.deleteSecurityGroup(region, original);
            service.deleteVpcEndpoints(region, List.of(endpoint.getVpcEndpointId()));
            service.deleteSecurityGroup(region, replacement);
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void endpointDeletionWaitsForModificationWithoutResurrection() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String group = service.createSecurityGroup(region, "endpoint-delete", "endpoint", vpc).getGroupId();
        VpcEndpoint endpoint = createEndpoint(service, region, vpc, group);
        factory.endpoints.parkNextRead();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<VpcEndpoint> modified = executor.submit(() -> service.modifyVpcEndpoint(region,
                    endpoint.getVpcEndpointId(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), "replacement-policy", null, null));
            assertTrue(factory.endpoints.awaitParked());
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<List<VpcEndpoint>> deleted = executor.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return service.deleteVpcEndpoints(region, List.of(endpoint.getVpcEndpointId()));
            });
            awaitBlockedOrDone(deletingThread, deleted);
            assertFalse(deleted.isDone());
            factory.endpoints.release();
            modified.get(5, TimeUnit.SECONDS);
            assertEquals(1, deleted.get(5, TimeUnit.SECONDS).size());
            assertTrue(service.describeVpcEndpoints(region, List.of(), Map.of()).isEmpty());
            service.deleteSecurityGroup(region, group);
            assertEquals("InvalidVpcEndpointId.NotFound", assertThrows(AwsException.class,
                    () -> service.modifyVpcEndpoint(region, endpoint.getVpcEndpointId(), List.of(), List.of(),
                            List.of(), List.of(), List.of(group), List.of(), null, null, null)).getErrorCode());
        } finally {
            factory.endpoints.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Future<AwsException> deleteGroup(ExecutorService executor, AtomicReference<Thread> deletingThread,
                                                   Ec2Service service, String region, String group) {
        return executor.submit(() -> {
            deletingThread.set(Thread.currentThread());
            try {
                service.deleteSecurityGroup(region, group);
                return null;
            } catch (AwsException failure) {
                return failure;
            }
        });
    }

    private static VpcEndpoint createEndpoint(Ec2Service service, String region, String vpc, String group) {
        return service.createVpcEndpoint(region, vpc, "test-service", "Interface", List.of(), List.of(),
                List.of(group), false, null, List.of());
    }

    private static Ec2Service service(TestStores factory) {
        return new Ec2Service(mockConfig(), mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory.storage);
    }

    private static EmulatorConfig mockConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        return config;
    }

    private static final class TestStores {
        private final ParkingStore<VpcEndpoint> endpoints = new ParkingStore<>();
        private final ParkingStore<NetworkInterface> interfaces = new ParkingStore<>();
        private final ParkingStore<Instance> instances = new ParkingStore<>();
        private final ParkingStore<SecurityGroupRule> rules = new ParkingStore<>();
        private final ParkingStore<SecurityGroup> groups = new ParkingStore<>();
        private final InMemoryStorageFactory storage = new InMemoryStorageFactory("000000000000")
                .withStore("ec2-vpc-endpoints.json", endpoints)
                .withStore("ec2-network-interfaces.json", interfaces)
                .withStore("ec2-instances.json", instances)
                .withStore("ec2-security-groups.json", groups)
                .withStore("ec2-security-group-rules.json", rules);
    }
}
