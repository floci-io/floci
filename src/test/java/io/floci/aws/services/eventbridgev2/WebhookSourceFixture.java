package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.testing.ManualClock;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A real Secrets Manager service under the EventBridge V2 service, with one bus, for webhook source tests. */
final class WebhookSourceFixture {
    static final String REGION = "us-east-1";
    static final String ACCOUNT = "000000000000";
    static final Instant NOW = Instant.parse("2026-11-25T06:00:00Z");
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ROLE = "arn:aws:iam::000000000000:role/Hook";
    static final String STATIC_AUTH = "{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Tok\","
            + "\"SecretValue\":\"original-token\"}}";
    static final String HMAC_AUTH = "{\"HmacAuthConfiguration\":{\"Algorithm\":\"SHA256\","
            + "\"Encoding\":\"HEX\",\"SignatureHttpHeader\":\"X-Sig\",\"SignedMessageComposition\":\"BODY\","
            + "\"SecretValue\":\"0123456789abcdef\"}}";

    final SecretsManagerService secrets;
    final EventBridgeV2Service service;
    final EventBridgeV2Webhooks webhooks;
    final ManualClock clock;
    final String bus;

    WebhookSourceFixture(EventBridgeV2Delivery delivery) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("secretsmanager"), any(), any(TypeReference.class)))
                .thenAnswer(call -> AccountAwareStorageBackend.<Secret>inMemory(ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().secretsmanager().defaultRecoveryWindowDays()).thenReturn(30);
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        secrets = new SecretsManagerService(factory, config, regionResolver, null, MAPPER, null);
        clock = new ManualClock(NOW);
        EventBridgeV2EventSources sources = new EventBridgeV2EventSources(
                AccountAwareStorageBackend.inMemory(ACCOUNT), secrets, regionResolver, clock, "http://floci.test:4566",
                mock(TlsCertificateManager.class), mock(EventBridgeService.class));
        service = new EventBridgeV2Service(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), regionResolver, delivery, sources, clock, true);
        webhooks = new EventBridgeV2Webhooks(service, sources, secrets, clock);
        bus = service.createEventBus(json("{\"Name\":\"orders\"}"), REGION).getArn();
    }

    EventSourceV2 source(String name, String auth) {
        return service.createEventSource(json("{\"ClientToken\":\"" + name + "-token\",\"Name\":\"" + name
                + "\",\"EventBusArn\":\"" + bus + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                + "{\"AuthConfiguration\":" + auth + ",\"RoleArn\":\"" + ROLE + "\"}}}"), REGION);
    }

    static JsonNode json(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
