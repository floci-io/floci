package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Concurrent mutations of shared EC2 resources must not lose updates (issue #1464):
 * storage get() returns the live stored object, so unsynchronized list mutations under
 * parallel clients (Terraform applies 10-wide by default) dropped route table
 * associations, security group rules, network ACL entries, and tags. Each scenario
 * repeats over several trials so a regression surfaces reliably.
 */
class Ec2ServiceConcurrencyTest {

    private static final int TRIALS = 30;
    private static final int N = 24;
    private static final int THREADS = 8;

    private static Ec2Service newService() {
        return new Ec2Service(mockConfig(), mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory());
    }

    @Test
    void concurrentAssociateRouteTableKeepsEveryAssociation() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            String region = "race-rtb-" + trial;
            Ec2Service service = newService();
            String vpcId = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
            String rtbId = service.createRouteTable(region, vpcId).getRouteTableId();

            Set<String> returnedIds = runRace(i ->
                    service.associateRouteTable(region, rtbId, "subnet-" + i).getRouteTableAssociationId());

            RouteTable rt = service.describeRouteTables(region, List.of(rtbId), Map.of()).getFirst();
            assertEquals(N, rt.getAssociations().size(),
                    "trial " + trial + ": lost route table associations");
            Set<String> storedIds = rt.getAssociations().stream()
                    .map(a -> a.getRouteTableAssociationId())
                    .collect(Collectors.toSet());
            assertEquals(returnedIds, storedIds, "trial " + trial + ": returned ids must all be stored");
            assertTrue(rt.getAssociations().stream()
                            .allMatch(a -> "associated".equals(a.getAssociationState())),
                    "every association must report associated");
        }
    }

    @Test
    void concurrentAuthorizeIngressKeepsEveryRule() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            String region = "race-sg-" + trial;
            Ec2Service service = newService();
            String vpcId = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
            String groupId = service.createSecurityGroup(region, "sg-race", "d", vpcId).getGroupId();

            runRace(i -> {
                IpPermission perm = new IpPermission();
                perm.setIpProtocol("tcp");
                perm.setFromPort(1000 + i);
                perm.setToPort(1000 + i);
                IpRange range = new IpRange();
                range.setCidrIp("10.0." + i + ".0/24");
                perm.setIpRanges(List.of(range));
                return service.authorizeSecurityGroupIngress(region, groupId, List.of(perm))
                        .getFirst().getSecurityGroupRuleId();
            });

            SecurityGroup sg = service.describeSecurityGroups(region, List.of(groupId), List.of(), Map.of()).getFirst();
            assertEquals(N, sg.getIpPermissions().size(),
                    "trial " + trial + ": lost security group ingress permissions");
            long ingressRules = service.describeSecurityGroupRules(region, List.of(groupId), List.of()).stream()
                    .filter(r -> !r.isEgress())
                    .count();
            assertEquals(N, ingressRules, "trial " + trial + ": lost security group rule records");
        }
    }

    @Test
    void concurrentNetworkAclEntriesKeptWhileReadable() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            String region = "race-nacl-" + trial;
            Ec2Service service = newService();
            String vpcId = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
            String aclId = service.createNetworkAcl(region, vpcId).getNetworkAclId();
            int seeded = service.describeNetworkAcls(region, List.of(aclId), Map.of())
                    .getFirst().getEntries().size();

            Thread reader = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    // Must never throw ConcurrentModificationException or see a corrupt list.
                    service.describeNetworkAcls(region, List.of(aclId), Map.of())
                            .getFirst().getEntries().forEach(e -> e.getRuleNumber());
                }
            });
            reader.start();
            try {
                runRace(i -> {
                    service.createNetworkAclEntry(region, aclId, 100 + i, "6", "allow", false,
                            "10.0." + i + ".0/24", 80, 80, false);
                    return "entry-" + i;
                });
            } finally {
                reader.interrupt();
                reader.join(TimeUnit.SECONDS.toMillis(5));
            }

            NetworkAcl acl = service.describeNetworkAcls(region, List.of(aclId), Map.of()).getFirst();
            assertEquals(seeded + N, acl.getEntries().size(),
                    "trial " + trial + ": lost network ACL entries");
        }
    }

    @Test
    void concurrentCreateTagsKeepsEveryTag() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            String region = "race-tag-" + trial;
            Ec2Service service = newService();
            String vpcId = service.createVpc(region, "10.0.0.0/16", false).getVpcId();

            runRace(i -> {
                service.createTags(region, List.of(vpcId), List.of(new Tag("k" + i, "v" + i)));
                return "k" + i;
            });

            Map<String, List<String>> filters = Map.of("resource-id", List.of(vpcId));
            assertEquals(N, service.describeTags(region, filters).size(),
                    "trial " + trial + ": lost tags");
        }
    }

    @Test
    void concurrentReplaceNetworkAclAssociationMovesTheSubnetOnce() throws Exception {
        for (int trial = 0; trial < TRIALS; trial++) {
            String region = "race-aclassoc-" + trial;
            Ec2Service service = newService();
            String vpcId = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
            String subnetId = service.createSubnet(region, vpcId, "10.0.1.0/24", null).getSubnetId();
            String targetAclId = service.createNetworkAcl(region, vpcId).getNetworkAclId();
            String associationId = service.describeNetworkAcls(region, List.of(), Map.of()).stream()
                    .flatMap(a -> a.getAssociations().stream())
                    .filter(a -> subnetId.equals(a.getSubnetId()))
                    .findFirst()
                    .orElseThrow()
                    .getNetworkAclAssociationId();

            AtomicInteger replaced = new AtomicInteger();
            AtomicInteger notFound = new AtomicInteger();
            runRaceAllowing(i -> {
                try {
                    service.replaceNetworkAclAssociation(region, associationId, targetAclId);
                    replaced.incrementAndGet();
                } catch (AwsException e) {
                    assertEquals("InvalidAssociationID.NotFound", e.getErrorCode());
                    notFound.incrementAndGet();
                }
            });

            assertEquals(1, replaced.get(), "trial " + trial + ": exactly one caller may claim the association");
            assertEquals(N - 1, notFound.get(), "trial " + trial + ": losers must see InvalidAssociationID.NotFound");

            NetworkAcl target = service.describeNetworkAcls(region, List.of(targetAclId), Map.of()).getFirst();
            assertEquals(1, target.getAssociations().size(),
                    "trial " + trial + ": subnet must be associated to the target ACL exactly once");
            assertEquals(subnetId, target.getAssociations().getFirst().getSubnetId());
            long subnetAssociations = service.describeNetworkAcls(region, List.of(), Map.of()).stream()
                    .flatMap(a -> a.getAssociations().stream())
                    .filter(a -> subnetId.equals(a.getSubnetId()))
                    .count();
            assertEquals(1, subnetAssociations, "trial " + trial + ": a subnet has exactly one NACL association");
        }
    }

    /** Runs N concurrent invocations of the same op; the op absorbs its own expected failures. */
    private static void runRaceAllowing(java.util.function.IntConsumer op) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < N; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                op.accept(idx);
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** Runs N indexed mutations across THREADS with a common start gate; returns their results. */
    private static Set<String> runRace(java.util.function.IntFunction<String> op) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < N; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return op.apply(idx);
            }));
        }
        start.countDown();
        Set<String> results = new java.util.HashSet<>();
        for (var f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return results;
    }

    private static EmulatorConfig mockConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        return config;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return new InMemoryStorage<>();
        }
    }
}
