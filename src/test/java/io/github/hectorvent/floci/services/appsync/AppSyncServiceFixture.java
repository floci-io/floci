package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaCreationWorker;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.services.appsync.graphql.auth.LambdaAuthorizerCache;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import jakarta.enterprise.inject.Instance;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A real {@link AppSyncService} over in-memory storage, shared by the tests that need one. */
public final class AppSyncServiceFixture {

    private AppSyncServiceFixture() {
    }

    public static AppSyncService create(Clock clock) {
        return create(clock, "http://localhost:4566", "us-east-1", null,
                AccountAwareStorageBackend.inMemory("000000000000"), null,
                mock(SchemaRegistry.class), new LambdaAuthorizerCache());
    }

    /**
     * @param partition  the deployment partition, or null to derive it from {@code region}
     * @param apiKeyStore the store for API keys, or null for a fresh in-memory one
     */
    @SuppressWarnings("unchecked")
    public static AppSyncService create(Clock clock, String baseUrl, String region, String partition,
            AccountAwareStorageBackend<GraphqlApi> apiStore, AccountAwareStorageBackend<ApiKey> apiKeyStore,
            SchemaRegistry schemaRegistry, LambdaAuthorizerCache lambdaAuthorizerCache) {
        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory("000000000000")
                .withStore("appsync-apis.json", apiStore);
        if (apiKeyStore != null) {
            storageFactory.withStore("appsync-apikeys.json", apiKeyStore);
        }
        Instance<RequestContext> requestContext = mock(Instance.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn(baseUrl);
        when(config.services().cloudfront().domainSuffix()).thenReturn(Optional.empty());
        return new AppSyncService(
                storageFactory,
                config,
                partition == null ? new RegionResolver(region, "000000000000")
                        : new RegionResolver(region, "000000000000", partition),
                schemaRegistry,
                mock(SchemaCreationWorker.class),
                requestContext,
                new ObjectMapper(),
                AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"),
                clock,
                lambdaAuthorizerCache);
    }

    /** A clock a test moves by hand. */
    public static final class MutableClock extends Clock {
        private Instant instant;

        public MutableClock(Instant instant) {
            this.instant = instant;
        }

        public void set(Instant instant) {
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
