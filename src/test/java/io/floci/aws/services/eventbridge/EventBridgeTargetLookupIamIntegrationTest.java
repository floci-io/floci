package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.model.EventBus;
import io.floci.aws.services.eventbridge.model.Rule;
import io.floci.aws.services.eventbridge.model.Target;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EventBridgeTargetLookupIamIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    @Inject
    StorageFactory factory;
    @Inject
    RegionResolver resolver;
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"bus-resource", "wrong-action", "explicit-deny"})
    void authorizationUsesWildcardAndBothDeniedAndAllowedReadsPreserveLegacyRows(String kind) throws Exception {
        StorageBackend<String, EventBus> buses = raw(factory.create("eventbridge", "eventbridge-buses.json",
                new TypeReference<Map<String, EventBus>>() {}));
        StorageBackend<String, Rule> rules = raw(factory.create("eventbridge", "eventbridge-rules.json",
                new TypeReference<Map<String, Rule>>() {}));
        StorageBackend<String, List<Target>> targets = raw(factory.create("eventbridge", "eventbridge-targets.json",
                new TypeReference<Map<String, List<Target>>>() {}));
        String account = resolver.getDefaultAccountId();
        String name = "reverse-legacy-" + UUID.randomUUID();
        String busKey = "bus:us-east-1:" + name;
        String ruleKey = "rule:us-east-1:" + name + "/owned";
        String arn = "arn:aws:events:us-east-1:" + account + ":event-bus/" + name;
        String targetArn = "arn:aws:sqs:us-east-1:" + account + ":reverse-owned";
        EventBus bus = new EventBus(name, arn, "owned legacy", Instant.EPOCH);
        Rule rule = new Rule();
        rule.setName("owned");
        rule.setArn("arn:aws:events:us-east-1:" + account + ":rule/" + name + "/owned");
        rule.setEventBusName(name);
        List<Target> values = List.of(new Target("one", targetArn, null, null));
        JsonNode before = MAPPER.valueToTree(List.of(bus, rule, values));
        buses.put(busKey, bus);
        rules.put(ruleKey, rule);
        targets.put(ruleKey, values);
        String policy = switch (kind) {
            case "bus-resource" -> statement("Allow", "events:ListRuleNamesByTarget", arn);
            case "wrong-action" -> statement("Allow", "events:ListRules", "*");
            case "explicit-deny" -> statement("Allow", "events:*", "*") + ","
                    + statement("Deny", "events:ListRuleNamesByTarget", "*");
            default -> throw new IllegalArgumentException(kind);
        };
        try (EventBridgeClient denied = caller(policy)) {
            assertAll(
                    () -> {
                        EventBridgeException error = assertThrows(EventBridgeException.class,
                                () -> denied.listRuleNamesByTarget(request -> request.eventBusName(name).targetArn(targetArn)));
                        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                    },
                    () -> unchanged(buses, busKey, bus, account),
                    () -> unchanged(rules, ruleKey, rule, account),
                    () -> unchanged(targets, ruleKey, values, account),
                    () -> assertEquals(before, MAPPER.valueToTree(List.of(bus, rule, values))));
            try (EventBridgeClient allowed = caller(statement("Allow", "events:ListRuleNamesByTarget", "*"))) {
                assertAll(
                        () -> assertEquals(List.of("owned"), allowed.listRuleNamesByTarget(
                                request -> request.eventBusName(name).targetArn(targetArn)).ruleNames()),
                        () -> unchanged(buses, busKey, bus, account),
                        () -> unchanged(rules, ruleKey, rule, account),
                        () -> unchanged(targets, ruleKey, values, account),
                        () -> assertEquals(before, MAPPER.valueToTree(List.of(bus, rule, values))));
            }
        } finally {
            buses.delete(busKey);
            buses.delete(account + "/" + busKey);
            rules.delete(ruleKey);
            rules.delete(account + "/" + ruleKey);
            targets.delete(ruleKey);
            targets.delete(account + "/" + ruleKey);
        }
    }

    private EventBridgeClient caller(String statements) {
        String user = "reverse-caller-" + UUID.randomUUID();
        String iamAuth = auth("test", "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAuth, user, "reverse",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(EventBridgeClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1);
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static <T> void unchanged(StorageBackend<String, T> store, String key, T value, String account) {
        assertSame(value, store.get(key).orElse(null));
        assertTrue(store.get(account + "/" + key).isEmpty());
    }

    /** Public APIs cannot create pre-account rows; only the three owned fixture keys use this seam. */
    @SuppressWarnings("unchecked")
    private static <T> StorageBackend<String, T> raw(AccountAwareStorageBackend<T> store) throws ReflectiveOperationException {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, T>) delegate.get(store);
    }
}
