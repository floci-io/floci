package io.github.hectorvent.floci.services.networkfirewall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The association operations read the stored mapping array, work on a detached
 * copy, then replace the whole field on write-back. Without serialising that
 * read-modify-write, two overlapping calls each start from the same snapshot and
 * the later write silently drops the earlier caller's mapping.
 */
class NetworkFirewallConcurrencyTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String NAME = "ConcurrentAssociationFirewall";
    private static final int CALLERS = 16;

    @Test
    void concurrentAssociateSubnets_keepsEveryMapping() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NetworkFirewallService service = new NetworkFirewallService(mapper,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        ObjectNode create = mapper.createObjectNode();
        create.put("FirewallName", NAME);
        create.put("VpcId", "vpc-0123456789abcdef0");
        create.put("SubnetChangeProtection", false);
        create.putArray("SubnetMappings");
        service.createFirewall(create, REGION, ACCOUNT);
        String firewallArn = "arn:aws:network-firewall:" + REGION + ":" + ACCOUNT + ":firewall/" + NAME;

        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> submitted = new ArrayList<>();
        try {
            for (int caller = 0; caller < CALLERS; caller++) {
                String subnetId = String.format("subnet-%017d", caller);
                submitted.add(pool.submit(() -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("FirewallArn", firewallArn);
                    request.putArray("SubnetMappings")
                            .addObject().put("SubnetId", subnetId);
                    start.await();
                    return service.associateSubnets(request);
                }));
            }
            start.countDown();
            for (Future<?> future : submitted) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        ObjectNode described = service.describeFirewall(firewallArn, null, REGION, ACCOUNT);
        assertEquals(CALLERS, described.path("Firewall").path("SubnetMappings").size(),
                "every successful AssociateSubnets call must survive; a lost update means one "
                        + "caller's mapping was overwritten by a concurrent write-back");
    }

    @Test
    void concurrentDisassociateSubnets_removesEveryNamedMapping() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NetworkFirewallService service = new NetworkFirewallService(mapper,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        ObjectNode create = mapper.createObjectNode();
        create.put("FirewallName", NAME);
        create.put("VpcId", "vpc-0123456789abcdef0");
        create.put("SubnetChangeProtection", false);
        var mappings = create.putArray("SubnetMappings");
        for (int caller = 0; caller < CALLERS; caller++) {
            mappings.addObject().put("SubnetId", String.format("subnet-%017d", caller));
        }
        service.createFirewall(create, REGION, ACCOUNT);
        String firewallArn = "arn:aws:network-firewall:" + REGION + ":" + ACCOUNT + ":firewall/" + NAME;

        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> submitted = new ArrayList<>();
        try {
            for (int caller = 0; caller < CALLERS; caller++) {
                String subnetId = String.format("subnet-%017d", caller);
                submitted.add(pool.submit(() -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("FirewallArn", firewallArn);
                    request.putArray("SubnetIds").add(subnetId);
                    start.await();
                    return service.disassociateSubnets(request);
                }));
            }
            start.countDown();
            for (Future<?> future : submitted) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        ObjectNode described = service.describeFirewall(firewallArn, null, REGION, ACCOUNT);
        assertTrue(described.path("Firewall").path("SubnetMappings").isEmpty(),
                "every subnet was disassociated by some caller, so none may survive a "
                        + "concurrent write-back that resurrects a stale snapshot");
    }

    /**
     * Two concurrent UpdateFirewall* calls carrying the same current token must not both
     * succeed: without a common lock around validate-then-rotate, both can read and match
     * the same token before either commits its rotation.
     */
    @Test
    void concurrentUpdateFirewall_onlySingleCallerWithStaleTokenSucceeds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NetworkFirewallService service = new NetworkFirewallService(mapper,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        ObjectNode create = mapper.createObjectNode();
        create.put("FirewallName", NAME);
        create.put("VpcId", "vpc-0123456789abcdef0");
        ObjectNode created = service.createFirewall(create, REGION, ACCOUNT);
        String firewallArn = created.path("Firewall").path("FirewallArn").asText();
        String currentToken =
                service.describeFirewall(firewallArn, null, REGION, ACCOUNT).path("UpdateToken").asText();

        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ObjectNode>> submitted = new ArrayList<>();
        try {
            for (int caller = 0; caller < CALLERS; caller++) {
                int callerIndex = caller;
                submitted.add(pool.submit(() -> {
                    ObjectNode request = mapper.createObjectNode();
                    request.put("FirewallArn", firewallArn);
                    request.put("UpdateToken", currentToken);
                    request.put("Description", "description-" + callerIndex);
                    start.await();
                    return service.updateFirewall("UpdateFirewallDescription", request, REGION, ACCOUNT);
                }));
            }
            start.countDown();

            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            for (Future<ObjectNode> future : submitted) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    succeeded.incrementAndGet();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof AwsException awsException
                            && "InvalidTokenException".equals(awsException.getErrorCode())) {
                        rejected.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
            }

            assertEquals(1, succeeded.get(),
                    "exactly one caller may validate and rotate the shared current token; a "
                            + "nonatomic check-and-rotate lets more than one pass validation");
            assertEquals(CALLERS - 1, rejected.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A firewall persisted before UpdateToken support existed has no token field.
     * DescribeFirewall must backfill a real token rather than reporting the empty
     * string {@code JsonNode.asText()} defaults to, since a caller submitting that
     * empty string back would otherwise always be rejected by requireCurrentToken's
     * null-based default.
     */
    @Test
    void describeFirewall_backfillsATokenForAFirewallWithoutOne() {
        ObjectMapper mapper = new ObjectMapper();
        NetworkFirewallService service = new NetworkFirewallService(mapper,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        ObjectNode create = mapper.createObjectNode();
        create.put("FirewallName", NAME);
        create.put("VpcId", "vpc-0123456789abcdef0");
        ObjectNode created = service.createFirewall(create, REGION, ACCOUNT);
        String firewallArn = created.path("Firewall").path("FirewallArn").asText();

        ObjectNode described = service.describeFirewall(firewallArn, null, REGION, ACCOUNT);
        String token = described.path("UpdateToken").asText();
        assertTrue(token != null && !token.isEmpty(),
                "DescribeFirewall must never report an empty UpdateToken");

        ObjectNode update = mapper.createObjectNode();
        update.put("FirewallArn", firewallArn);
        update.put("UpdateToken", token);
        update.put("Description", "backfilled-token-round-trip");
        ObjectNode updateResponse =
                service.updateFirewall("UpdateFirewallDescription", update, REGION, ACCOUNT);

        assertEquals("backfilled-token-round-trip", updateResponse.path("Description").asText());
    }
}
