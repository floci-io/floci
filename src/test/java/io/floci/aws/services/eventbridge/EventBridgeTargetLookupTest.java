package io.floci.aws.services.eventbridge;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.eventbridge.model.EventBus;
import io.floci.aws.services.eventbridge.model.Rule;
import io.floci.aws.services.eventbridge.model.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class EventBridgeTargetLookupTest {
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String TARGET = "arn:aws:sqs:us-west-2:222222222222:foreign-target";

    @Test
    void defaultAccountLegacyRulesAreReadInPlaceAndExplicitRecordsTakePrecedence() {
        Fixture fixture = new Fixture();
        Rule legacy = fixture.rule(ACCOUNT, REGION, "default", "owned", true);
        fixture.target(ACCOUNT, REGION, "default", "owned", true);
        String key = "rule:" + REGION + ":default/owned";
        assertEquals(List.of("owned"), fixture.lookup(ACCOUNT).list(TARGET, null, null, null, REGION).items());
        assertSame(legacy, fixture.rawRules.get(key).orElseThrow());
        assertEquals(Set.of(key), fixture.rawRules.keys());
        assertEquals(Set.of(key), fixture.rawTargets.keys());
        assertEquals(List.of(), fixture.lookup(OTHER).list(TARGET, null, null, null, REGION).items());

        Rule explicit = fixture.rule(ACCOUNT, REGION, "default", "owned", false);
        fixture.targets.putForAccount(ACCOUNT, key, List.of(new Target("other", "other-target", null, null)));
        assertEquals(List.of(), fixture.lookup(ACCOUNT).list(TARGET, null, null, null, REGION).items());
        assertSame(explicit, fixture.rules.getForAccount(ACCOUNT, key).orElseThrow());
        assertSame(legacy, fixture.rawRules.get(key).orElseThrow());
        assertEquals(Set.of(key, ACCOUNT + "/" + key), fixture.rawRules.keys());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "region", "partition", "name", "bus", "accountField"})
    void malformedOrForeignPersistedRuleIdentityIsExcludedWithoutMutation(String mismatch) {
        Fixture fixture = new Fixture();
        Rule rule = fixture.rule(ACCOUNT, REGION, "default", "owned", true);
        fixture.target(ACCOUNT, REGION, "default", "owned", true);
        switch (mismatch) {
            case "account" -> rule.setArn(arn(OTHER, REGION, "rule/owned"));
            case "region" -> rule.setArn(arn(ACCOUNT, "us-west-2", "rule/owned"));
            case "partition" -> rule.setArn("arn:aws-cn:events:us-east-1:" + ACCOUNT + ":rule/owned");
            case "name" -> rule.setName("different");
            case "bus" -> rule.setEventBusName("different");
            case "accountField" -> rule.setAccountId(OTHER);
            default -> throw new IllegalArgumentException(mismatch);
        }
        Map<String, Rule> before = fixture.rules.scanAllAccountsWithRawKeys();
        assertEquals(List.of(), fixture.lookup(ACCOUNT).list(TARGET, null, null, null, REGION).items());
        assertEquals(before, fixture.rules.scanAllAccountsWithRawKeys());
        assertSame(rule, fixture.rawRules.get("rule:" + REGION + ":default/owned").orElseThrow());
    }

    @Test
    void accountPrefixedAliasesCannotReadForeignRulesOrMoveTheirKeys() {
        Fixture fixture = new Fixture();
        fixture.rule(OTHER, REGION, "default", "foreign", false);
        fixture.target(OTHER, REGION, "default", "foreign", false);
        Map<String, Rule> before = fixture.rules.scanAllAccountsWithRawKeys();
        assertEquals(List.of(), fixture.lookup(ACCOUNT).list(TARGET, null, null, null, REGION).items());
        assertEquals(List.of("foreign"), fixture.lookup(OTHER).list(TARGET, null, null, null, REGION).items());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.lookup(ACCOUNT).list(TARGET, OTHER + "/default", null, null, REGION)).getErrorCode());
        assertEquals(before, fixture.rules.scanAllAccountsWithRawKeys());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "region", "partition", "name"})
    void aLegacyBusMustProveItsStoredIdentity(String mismatch) {
        Fixture fixture = new Fixture();
        EventBus bus = new EventBus("custom", arn(ACCOUNT, REGION, "event-bus/custom"), null, null);
        switch (mismatch) {
            case "account" -> bus.setArn(arn(OTHER, REGION, "event-bus/custom"));
            case "region" -> bus.setArn(arn(ACCOUNT, "us-west-2", "event-bus/custom"));
            case "partition" -> bus.setArn("arn:aws-cn:events:us-east-1:" + ACCOUNT + ":event-bus/custom");
            case "name" -> bus.setName("different");
            default -> throw new IllegalArgumentException(mismatch);
        }
        fixture.rawBuses.put("bus:" + REGION + ":custom", bus);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.lookup(ACCOUNT).list(TARGET, "custom", null, null, REGION)).getErrorCode());
        assertEquals(Set.of("bus:" + REGION + ":custom"), fixture.rawBuses.keys());
        assertSame(bus, fixture.rawBuses.get("bus:" + REGION + ":custom").orElseThrow());
    }

    @Test
    void localCursorScopeBindsEveryRequestArgumentAndCallerScope() {
        Fixture fixture = new Fixture();
        for (String account : List.of(ACCOUNT, OTHER)) {
            for (String region : List.of(REGION, "us-west-2")) {
                for (String name : List.of("alpha", "omega")) {
                    fixture.rule(account, region, "default", name, false);
                    fixture.target(account, region, "default", name, false);
                }
            }
        }
        EventBridgeTargetLookup lookup = fixture.lookup(ACCOUNT);
        PaginatedResult<String> first = lookup.list(TARGET, null, 1, null, REGION);
        String token = first.nextToken();
        assertNotNull(token);
        assertEquals(List.of("alpha"), first.items(), "lexical order is Floci's pagination policy");
        assertEquals(List.of("omega"), lookup.list(TARGET, null, 1, token, REGION).items());
        assertNull(lookup.list(TARGET, null, 1, token, REGION).nextToken());
        assertInvalid(() -> lookup.list("different-target", null, 1, token, REGION));
        assertInvalid(() -> lookup.list(TARGET, "default", 1, token, REGION));
        assertInvalid(() -> lookup.list(TARGET, arn(ACCOUNT, REGION, "event-bus/default"), 1, token, REGION));
        assertInvalid(() -> lookup.list(TARGET, null, 2, token, REGION));
        assertInvalid(() -> lookup.list(TARGET, null, null, token, REGION));
        assertInvalid(() -> lookup.list(TARGET, null, 1, token, "us-west-2"));
        assertInvalid(() -> fixture.lookup(OTHER).list(TARGET, null, 1, token, REGION));
        assertInvalid(() -> lookup.list(TARGET, null, 1, "not-a-cursor", REGION));
    }

    @Test
    void maximumLengthFilterStillProducesAModeledSizeCursor() {
        Fixture fixture = new Fixture();
        String filter = "x".repeat(1600);
        for (String name : List.of("alpha", "omega")) {
            fixture.rule(ACCOUNT, REGION, "default", name, false);
            fixture.targets.putForAccount(ACCOUNT, "rule:" + REGION + ":default/" + name,
                    List.of(new Target("one", filter, null, null)));
        }
        PaginatedResult<String> first = fixture.lookup(ACCOUNT).list(filter, null, 1, null, REGION);
        assertNotNull(first.nextToken());
        assertTrue(first.nextToken().length() <= 2048);
        assertEquals(List.of("omega"), fixture.lookup(ACCOUNT).list(filter, null, 1, first.nextToken(), REGION).items());
    }

    @Test
    void replacedRuleDuringTargetReadIsExcluded() {
        Fixture fixture = new Fixture();
        String key = "rule:" + REGION + ":default/owned";
        fixture.rule(ACCOUNT, REGION, "default", "owned", false);
        AtomicBoolean replaced = new AtomicBoolean();
        InMemoryStorage<String, List<Target>> changingTargets = new InMemoryStorage<>() {
            @Override
            public Optional<List<Target>> get(String requestedKey) {
                if (requestedKey.equals(ACCOUNT + "/" + key) && replaced.compareAndSet(false, true)) {
                    fixture.rule(ACCOUNT, REGION, "default", "owned", false);
                }
                return super.get(requestedKey);
            }
        };
        changingTargets.put(ACCOUNT + "/" + key, List.of(new Target("one", TARGET, null, null)));
        EventBridgeTargetLookup lookup = new EventBridgeTargetLookup(fixture.buses, fixture.rules,
                new AccountAwareStorageBackend<>(changingTargets, null, ACCOUNT), new RegionResolver(REGION, ACCOUNT));
        assertEquals(List.of(), lookup.list(TARGET, null, null, null, REGION).items());
        assertTrue(replaced.get());
        assertEquals(List.of("owned"), lookup.list(TARGET, null, null, null, REGION).items());
    }

    private static void assertInvalid(Runnable call) {
        assertEquals("InvalidToken", assertThrows(AwsException.class, call::run).getErrorCode());
    }

    private static String arn(String account, String region, String resource) {
        return AwsArnUtils.Arn.of("events", region, account, resource).toString();
    }

    private static final class Fixture {
        private final InMemoryStorage<String, EventBus> rawBuses = new InMemoryStorage<>();
        private final InMemoryStorage<String, Rule> rawRules = new InMemoryStorage<>();
        private final InMemoryStorage<String, List<Target>> rawTargets = new InMemoryStorage<>();
        private final AccountAwareStorageBackend<EventBus> buses = new AccountAwareStorageBackend<>(rawBuses, null, ACCOUNT);
        private final AccountAwareStorageBackend<Rule> rules = new AccountAwareStorageBackend<>(rawRules, null, ACCOUNT);
        private final AccountAwareStorageBackend<List<Target>> targets = new AccountAwareStorageBackend<>(rawTargets, null, ACCOUNT);

        private EventBridgeTargetLookup lookup(String account) {
            return new EventBridgeTargetLookup(buses, rules, targets, new RegionResolver(REGION, account));
        }

        private Rule rule(String account, String region, String bus, String name, boolean legacy) {
            Rule rule = new Rule();
            rule.setName(name);
            rule.setEventBusName(bus);
            rule.setAccountId(legacy ? null : account);
            rule.setArn(arn(account, region, "default".equals(bus) ? "rule/" + name : "rule/" + bus + "/" + name));
            String key = "rule:" + region + ":" + bus + "/" + name;
            if (legacy) {
                rawRules.put(key, rule);
            } else {
                rules.putForAccount(account, key, rule);
            }
            return rule;
        }

        private void target(String account, String region, String bus, String name, boolean legacy) {
            String key = "rule:" + region + ":" + bus + "/" + name;
            List<Target> value = List.of(new Target("one", TARGET, null, null));
            if (legacy) {
                rawTargets.put(key, value);
            } else {
                targets.putForAccount(account, key, value);
            }
        }
    }
}
