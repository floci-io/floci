package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.model.EventBus;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.ListRuleNamesByTargetResponse;
import software.amazon.awssdk.services.eventbridge.model.Target;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeTargetLookupIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(742490000000L);
    @Inject
    StorageFactory factory;

    @Test
    void nativeReverseLookupTracksTargetReplacementAndRemovalAcrossBuses() {
        try (Scenario fixture = scenario(Region.US_EAST_1)) {
            String a = fixture.bus("a");
            String b = fixture.bus("b");
            String one = fixture.target("one");
            String two = fixture.target("two");
            fixture.rule(a, "alpha", one);
            fixture.rule(a, "beta", one);
            fixture.rule(a, "gamma", two);
            fixture.rule(b, "delta", one);
            assertEquals(Set.of("alpha", "beta"), names(fixture.client, a, one, 100));
            assertEquals(Set.of("gamma"), names(fixture.client, a, two, 100));
            assertEquals(Set.of("delta"), names(fixture.client, b, one, 100));
            assertEquals(Set.of(), names(fixture.client, null, one, 100));

            fixture.targets(a, "beta", two);
            assertEquals(Set.of("alpha"), names(fixture.client, a, one, 100));
            assertEquals(Set.of("beta", "gamma"), names(fixture.client, a, two, 100));
            fixture.client.removeTargets(request -> request.eventBusName(a).rule("alpha").ids("one"));
            assertEquals(Set.of(), names(fixture.client, a, one, 100));
            assertEquals(Set.of("delta"), names(fixture.client, b, one, 100));
        }
    }

    @Test
    void filterPrecedesPaginationAndARepeatedTargetListsItsRuleOnce() {
        try (Scenario fixture = scenario(Region.US_EAST_1)) {
            String bus = fixture.bus("pages");
            String one = fixture.target("one");
            String two = fixture.target("two");
            fixture.rule(bus, "alpha", two);
            fixture.rule(bus, "beta", one);
            fixture.rule(bus, "gamma", two);
            fixture.rule(bus, "omega", one);
            fixture.client.putTargets(request -> request.eventBusName(bus).rule("beta").targets(
                    Target.builder().id("two").arn(one).build()));
            assertEquals(Set.of("beta", "omega"), names(fixture.client, bus, one, 1));
            String arn = fixture.client.describeEventBus(request -> request.name(bus)).arn();
            assertEquals(Set.of("beta", "omega"), names(fixture.client, arn, one, 100));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "cn-north-1", "us-gov-west-1"})
    void sameBusAndRuleNamesRemainScopedByRegionAndPartition(String otherRegion) {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        try (Scenario owner = new Scenario(account, Region.US_EAST_1);
             Scenario other = new Scenario(account, Region.of(otherRegion))) {
            String bus = owner.bus("scope");
            other.createBus(bus);
            String target = owner.target("one");
            owner.rule(bus, "owned", target);
            other.rule(bus, "other", target);
            assertEquals(Set.of("owned"), names(owner.client, bus, target, 100));
            assertEquals(Set.of("other"), names(other.client, bus, target, 100));
            String arn = owner.client.describeEventBus(request -> request.name(bus)).arn();
            EventBridgeException denied = assertThrows(EventBridgeException.class,
                    () -> names(other.client, arn, target, 100));
            assertEquals("ResourceNotFoundException", denied.awsErrorDetails().errorCode());
            assertEquals(Set.of("owned"), names(owner.client, bus, target, 100));
        }
    }

    @Test
    void sameBusNamesRemainScopedByAccountAndForeignBusArnsAreRefused() {
        try (Scenario owner = scenario(Region.US_EAST_1);
             Scenario other = scenario(Region.US_EAST_1)) {
            String bus = owner.bus("account");
            other.createBus(bus);
            String target = owner.target("one");
            owner.rule(bus, "owned", target);
            other.rule(bus, "other", target);
            assertEquals(Set.of("owned"), names(owner.client, bus, target, 100));
            assertEquals(Set.of("other"), names(other.client, bus, target, 100));
            String arn = owner.client.describeEventBus(request -> request.name(bus)).arn();
            EventBridgeException denied = assertThrows(EventBridgeException.class,
                    () -> names(other.client, arn, target, 100));
            assertEquals("ResourceNotFoundException", denied.awsErrorDetails().errorCode());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void modeledBoundaryLimitsAreAccepted(int limit) {
        try (Scenario fixture = scenario(Region.US_EAST_1)) {
            String bus = fixture.bus("limit");
            fixture.rule(bus, "owned", fixture.target("one"));
            assertEquals(Set.of("owned"), names(fixture.client, bus, fixture.target("one"), limit));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void modeledOutOfRangeLimitsAreRefused(int limit) {
        try (Scenario fixture = scenario(Region.US_EAST_1)) {
            String bus = fixture.bus("limit");
            fixture.rule(bus, "owned", fixture.target("one"));
            EventBridgeException error = assertThrows(EventBridgeException.class,
                    () -> names(fixture.client, bus, fixture.target("one"), limit));
            assertEquals("ValidationException", error.awsErrorDetails().errorCode());
            assertEquals(Set.of("owned"), names(fixture.client, bus, fixture.target("one"), 100));
        }
    }

    @Test
    void unknownBusIsNotFoundAndAnUnusedTargetIsEmpty() {
        try (Scenario fixture = scenario(Region.US_EAST_1)) {
            String bus = fixture.bus("empty");
            assertEquals(Set.of(), names(fixture.client, bus, fixture.target("unused"), 100));
            EventBridgeException error = assertThrows(EventBridgeException.class,
                    () -> names(fixture.client, "absent-" + UUID.randomUUID(), fixture.target("unused"), 100));
            assertEquals("ResourceNotFoundException", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void listingTheLogicalDefaultBusDoesNotCreateAPersistentBusRow() {
        String account = Long.toString(ACCOUNTS.incrementAndGet());
        AccountAwareStorageBackend<EventBus> buses = factory.create("eventbridge", "eventbridge-buses.json",
                new TypeReference<Map<String, EventBus>>() {});
        Set<String> before = Set.copyOf(buses.keysForAccount(account));
        try (EventBridgeClient client = AwsTestClients.create(EventBridgeClient.builder(), account, Region.US_EAST_1)) {
            assertAll(
                    () -> assertEquals(Set.of(), names(client, null, "arn:aws:sqs:us-east-1:" + account + ":unused", 100)),
                    () -> assertEquals(before, buses.keysForAccount(account)));
        }
    }

    @Test
    void continuationCannotBeReusedWithAnotherTargetFilter() {
        try (Scenario fixture = scenario(Region.US_EAST_1)) {
            String bus = fixture.bus("token");
            String target = fixture.target("one");
            fixture.rule(bus, "alpha", target);
            fixture.rule(bus, "omega", target);
            ListRuleNamesByTargetResponse first = fixture.client.listRuleNamesByTarget(request ->
                    request.eventBusName(bus).targetArn(target).limit(1));
            assertNotNull(first.nextToken());
            EventBridgeException error = assertThrows(EventBridgeException.class,
                    () -> fixture.client.listRuleNamesByTarget(request -> request.eventBusName(bus)
                            .targetArn(fixture.target("two")).limit(1).nextToken(first.nextToken())));
            assertEquals("InvalidToken", error.awsErrorDetails().errorCode());
            assertEquals(Set.of("alpha", "omega"), names(fixture.client, bus, target, 1));
        }
    }

    private static Set<String> names(EventBridgeClient client, String bus, String target, int limit) {
        List<String> result = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        String token = null;
        do {
            String current = token;
            ListRuleNamesByTargetResponse page = client.listRuleNamesByTarget(request -> request
                    .eventBusName(bus).targetArn(target).limit(limit).nextToken(current));
            assertTrue(page.ruleNames().size() <= limit);
            result.addAll(page.ruleNames());
            token = page.nextToken();
            if (token != null) {
                assertFalse(token.isEmpty());
                assertTrue(tokens.add(token), "the token must advance");
                assertTrue(tokens.size() < 10, "the owned fixture has at most four rules");
            }
        } while (token != null);
        assertEquals(result.size(), new HashSet<>(result).size());
        return Set.copyOf(result);
    }

    private static Scenario scenario(Region region) {
        return new Scenario(Long.toString(ACCOUNTS.incrementAndGet()), region);
    }

    private record StoredRule(String bus, String name) {}

    private static final class Scenario implements AutoCloseable {
        private final EventBridgeClient client;
        private final String account;
        private final Region region;
        private final List<String> buses = new ArrayList<>();
        private final List<StoredRule> rules = new ArrayList<>();

        private Scenario(String account, Region region) {
            this.account = account;
            this.region = region;
            client = AwsTestClients.create(EventBridgeClient.builder(), account, region);
        }

        private String bus(String suffix) {
            String name = "reverse-" + suffix + "-" + UUID.randomUUID();
            createBus(name);
            return name;
        }

        private void createBus(String name) {
            client.createEventBus(request -> request.name(name));
            buses.add(name);
        }

        private String target(String name) {
            return "arn:aws:sqs:" + region.id() + ":" + account + ":" + name;
        }

        private void rule(String bus, String name, String target) {
            client.putRule(request -> request.eventBusName(bus).name(name).eventPattern("{\"source\":[\"reverse\"]}"));
            rules.add(new StoredRule(bus, name));
            targets(bus, name, target);
        }

        private void targets(String bus, String name, String target) {
            assertEquals(0, client.putTargets(request -> request.eventBusName(bus).rule(name)
                    .targets(Target.builder().id("one").arn(target).build())).failedEntryCount());
        }

        @Override
        public void close() {
            try {
                for (StoredRule rule : rules.reversed()) {
                    client.removeTargets(request -> request.eventBusName(rule.bus()).rule(rule.name()).ids("one", "two"));
                    client.deleteRule(request -> request.eventBusName(rule.bus()).name(rule.name()));
                }
                for (String bus : buses.reversed()) {
                    client.deleteEventBus(request -> request.name(bus));
                }
            } finally {
                client.close();
            }
        }
    }
}
