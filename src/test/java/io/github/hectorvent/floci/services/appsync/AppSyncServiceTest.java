package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaCreationWorker;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AppSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private AppSyncService service;

    @BeforeEach
    void setUp() {
        service = newService(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validateApiKeyLooksUpByValue() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "a", "authenticationType", "API_KEY"), "us-east-1");
        ApiKey created = service.createApiKey(api.getApiId(), Map.of());
        assertTrue(service.validateApiKey(api.getApiId(), created.getApiKey()).isPresent());
    }

    @Test
    void validateApiKeyExpiresAtBoundaryFails() {
        MutableClock clock = new MutableClock(NOW);
        AppSyncService svc = newService(clock);
        GraphqlApi api = svc.createGraphqlApi(Map.of("name", "b", "authenticationType", "API_KEY"), "us-east-1");
        long expires = NOW.getEpochSecond() + Duration.ofDays(1).getSeconds();
        ApiKey created = svc.createApiKey(api.getApiId(), Map.of("expires", expires));
        assertTrue(svc.validateApiKey(api.getApiId(), created.getApiKey()).isPresent());
        clock.set(NOW.plus(Duration.ofDays(1)));
        assertTrue(svc.validateApiKey(api.getApiId(), created.getApiKey()).isEmpty());
    }

    @Test
    void createApiKeyExpiresNowThrowsOutOfBounds() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "bound-now", "authenticationType", "API_KEY"), "us-east-1");
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApiKey(api.getApiId(), Map.of("expires", NOW.getEpochSecond())));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("ApiKeyValidityOutOfBoundsException", ex.getErrorCode());
    }

    @Test
    void createApiKeyExpiresAfter365DaysThrowsOutOfBounds() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "bound-max", "authenticationType", "API_KEY"), "us-east-1");
        long expires = NOW.getEpochSecond() + Duration.ofDays(366).getSeconds();
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApiKey(api.getApiId(), Map.of("expires", expires)));
        assertEquals("ApiKeyValidityOutOfBoundsException", ex.getErrorCode());
    }

    @Test
    void updateApiKeyExpiresOutOfBoundsThrows() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "bound-update", "authenticationType", "API_KEY"), "us-east-1");
        ApiKey created = service.createApiKey(api.getApiId(), Map.of());
        AwsException tooSoon = assertThrows(AwsException.class,
                () -> service.updateApiKey(api.getApiId(), created.getId(), Map.of("expires", NOW.getEpochSecond())));
        assertEquals("ApiKeyValidityOutOfBoundsException", tooSoon.getErrorCode());
        long tooLate = NOW.getEpochSecond() + Duration.ofDays(366).getSeconds();
        AwsException tooFar = assertThrows(AwsException.class,
                () -> service.updateApiKey(api.getApiId(), created.getId(), Map.of("expires", tooLate)));
        assertEquals("ApiKeyValidityOutOfBoundsException", tooFar.getErrorCode());
    }

    @Test
    void validateApiKeyWrongApiFails() {
        GraphqlApi a = service.createGraphqlApi(Map.of("name", "c", "authenticationType", "API_KEY"), "us-east-1");
        GraphqlApi b = service.createGraphqlApi(Map.of("name", "d", "authenticationType", "API_KEY"), "us-east-1");
        ApiKey created = service.createApiKey(a.getApiId(), Map.of());
        assertTrue(service.validateApiKey(b.getApiId(), created.getApiKey()).isEmpty());
    }

    @Test
    void omittedExpiresDefaultsToSevenDays() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "e", "authenticationType", "API_KEY"), "us-east-1");
        ApiKey created = service.createApiKey(api.getApiId(), Map.of());
        assertEquals(NOW.getEpochSecond() + Duration.ofDays(7).getSeconds(), created.getExpires());
        assertEquals(NOW.getEpochSecond() + Duration.ofDays(67).getSeconds(), created.getDeletes());
    }

    @Test
    void explicitExpiresIsStored() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "f", "authenticationType", "API_KEY"), "us-east-1");
        long expires = NOW.getEpochSecond() + Duration.ofDays(30).getSeconds();
        ApiKey created = service.createApiKey(api.getApiId(), Map.of("expires", expires));
        assertEquals(expires, created.getExpires());
        assertEquals(expires + Duration.ofDays(60).getSeconds(), created.getDeletes());
    }

    @Test
    void omittedExpiresRoundsDownToHour() {
        Instant now = Instant.parse("2026-01-01T00:30:00Z");
        AppSyncService svc = newService(Clock.fixed(now, ZoneOffset.UTC));
        GraphqlApi api = svc.createGraphqlApi(Map.of("name", "round-default", "authenticationType", "API_KEY"), "us-east-1");
        ApiKey created = svc.createApiKey(api.getApiId(), Map.of());
        assertEquals(Instant.parse("2026-01-08T00:00:00Z").getEpochSecond(), created.getExpires());
    }

    @Test
    void explicitExpiresRoundsDownToHour() {
        Instant now = Instant.parse("2026-01-01T00:30:00Z");
        AppSyncService svc = newService(Clock.fixed(now, ZoneOffset.UTC));
        GraphqlApi api = svc.createGraphqlApi(Map.of("name", "round-explicit", "authenticationType", "API_KEY"), "us-east-1");
        long expires = Instant.parse("2026-01-31T00:30:00Z").getEpochSecond();
        ApiKey created = svc.createApiKey(api.getApiId(), Map.of("expires", expires));
        assertEquals(Instant.parse("2026-01-31T00:00:00Z").getEpochSecond(), created.getExpires());
    }

    @Test
    void updateApiKeyExpiresRoundsDownToHour() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "round-update", "authenticationType", "API_KEY"), "us-east-1");
        ApiKey created = service.createApiKey(api.getApiId(), Map.of());
        long expires = Instant.parse("2026-02-01T00:45:00Z").getEpochSecond();
        ApiKey updated = service.updateApiKey(api.getApiId(), created.getId(), Map.of("expires", expires));
        assertEquals(Instant.parse("2026-02-01T00:00:00Z").getEpochSecond(), updated.getExpires());
    }

    @Test
    void duplicateApiKeyProviderRejected() {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "dup-key");
        request.put("authenticationType", "API_KEY");
        request.put("additionalAuthenticationProviders", List.of(Map.of("authenticationType", "API_KEY")));
        AwsException ex = assertThrows(AwsException.class, () -> service.createGraphqlApi(request, "us-east-1"));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("BadRequestException", ex.getErrorCode());
        assertEquals(
                "Authentication type API_KEY for additional authentication provider 1 already specified on the API. It can only be specified once.",
                ex.getMessage());
    }

    @Test
    void duplicateIamAndLambdaRejected() {
        AwsException iam = assertThrows(AwsException.class, () -> service.createGraphqlApi(Map.of(
                "name", "dup-iam",
                "authenticationType", "AWS_IAM",
                "additionalAuthenticationProviders", List.of(Map.of("authenticationType", "AWS_IAM"))
        ), "us-east-1"));
        assertEquals(400, iam.getHttpStatus());
        assertEquals(
                "Authentication type AWS_IAM for additional authentication provider 1 already specified on the API. It can only be specified once.",
                iam.getMessage());

        AwsException lambda = assertThrows(AwsException.class, () -> service.createGraphqlApi(Map.of(
                "name", "dup-lambda",
                "authenticationType", "AWS_LAMBDA",
                "additionalAuthenticationProviders", List.of(Map.of("authenticationType", "AWS_LAMBDA"))
        ), "us-east-1"));
        assertEquals(400, lambda.getHttpStatus());
        assertEquals(
                "Authentication type AWS_LAMBDA for additional authentication provider 1 already specified on the API. It can only be specified once.",
                lambda.getMessage());
    }

    @Test
    void distinctCognitoPoolsAllowed() {
        GraphqlApi api = service.createGraphqlApi(Map.of(
                "name", "multi-cog",
                "authenticationType", "AMAZON_COGNITO_USER_POOLS",
                "userPoolConfig", Map.of("userPoolId", "pool-a", "awsRegion", "us-east-1", "appIdClientRegex", "a"),
                "additionalAuthenticationProviders", List.of(Map.of(
                        "authenticationType", "AMAZON_COGNITO_USER_POOLS",
                        "userPoolConfig", Map.of("userPoolId", "pool-b", "awsRegion", "us-east-1", "appIdClientRegex", "b")
                ))
        ), "us-east-1");
        assertEquals(AuthenticationType.AMAZON_COGNITO_USER_POOLS, api.getAuthenticationType());
        assertEquals(1, api.getAdditionalAuthenticationProviders().size());
    }

    @Test
    void createResolverDoesNotReregisterSchema() {
        GraphqlApi api = service.createGraphqlApi(Map.of("name", "r", "authenticationType", "API_KEY"), "us-east-1");
        service.createDataSource(api.getApiId(), Map.of("name", "none", "type", "NONE"), "us-east-1");
        service.createResolver(api.getApiId(), Map.of(
                "typeName", "Query",
                "fieldName", "hello",
                "dataSourceName", "none"
        ), "us-east-1");
        verify(schemaRegistry, never()).register(any(), any());
    }

    private SchemaRegistry schemaRegistry;

    @SuppressWarnings("unchecked")
    private AppSyncService newService(Clock clock) {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
        Instance<RequestContext> requestContext = mock(Instance.class);
        schemaRegistry = mock(SchemaRegistry.class);
        return new AppSyncService(
                storageFactory,
                mock(EmulatorConfig.class),
                new RegionResolver("us-east-1", "000000000000"),
                schemaRegistry,
                mock(SchemaCreationWorker.class),
                requestContext,
                new ObjectMapper(),
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
