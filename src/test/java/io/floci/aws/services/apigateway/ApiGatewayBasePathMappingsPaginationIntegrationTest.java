package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.BasePathMapping;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.EndpointType;
import software.amazon.awssdk.services.apigateway.model.GetBasePathMappingsResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayBasePathMappingsPaginationIntegrationTest {

    @Inject
    StorageFactory storageFactory;

    @Test
    void explicitLimitAndSdkTraversalKeepEveryMappingExactlyOnce() {
        try (Fixture fixture = new Fixture(3)) {
            GetBasePathMappingsResponse first = fixture.client.getBasePathMappings(r ->
                    r.domainName(fixture.domain).limit(1));
            assertEquals(1, first.items().size());
            assertNotNull(first.position());
            Set<String> seen = new HashSet<>();
            fixture.client.getBasePathMappingsPaginator(r -> r.domainName(fixture.domain).limit(1))
                    .forEach(page -> {
                        assertEquals(1, page.items().size());
                        page.items().forEach(mapping -> assertTrue(seen.add(mapping.basePath())));
                    });
            assertEquals(fixture.paths, seen);
            GetBasePathMappingsResponse complete = fixture.client.getBasePathMappings(r ->
                    r.domainName(fixture.domain).limit(500));
            assertEquals(fixture.paths, complete.items().stream()
                    .map(mapping -> mapping.basePath()).collect(Collectors.toSet()));
            assertNull(complete.position());
        }
    }

    @Test
    void omittedLimitUsesTwentyFiveAndTheLastPageHasNoPosition() {
        try (Fixture fixture = new Fixture(26)) {
            GetBasePathMappingsResponse first = fixture.client.getBasePathMappings(r ->
                    r.domainName(fixture.domain));
            assertEquals(25, first.items().size());
            assertNotNull(first.position());
            GetBasePathMappingsResponse last = fixture.client.getBasePathMappings(r ->
                    r.domainName(fixture.domain).position(first.position()));
            assertEquals(1, last.items().size());
            assertNull(last.position());
        }
    }

    @Test
    void emptyDomainAndMissingParentKeepTheirExistingResponses() {
        try (Fixture fixture = new Fixture(0)) {
            GetBasePathMappingsResponse empty = fixture.client.getBasePathMappings(r ->
                    r.domainName(fixture.domain).limit(1));
            assertTrue(empty.items().isEmpty());
            assertNull(empty.position());
            assertThrows(NotFoundException.class, () -> fixture.client.getBasePathMappings(r ->
                    r.domainName("missing-" + fixture.domain).limit(1)));
        }
    }

    @Test
    void positionCannotBeReplayedOnAnotherDomainAccountOrRegion() {
        String account = account();
        try (Fixture first = new Fixture(account, Region.US_EAST_1, 2);
                Fixture otherDomain = new Fixture(account, Region.US_EAST_1, 2);
                Fixture otherAccount = new Fixture(account(), Region.US_EAST_1, 2, first.domain);
                ApiGatewayClient otherRegion = AwsTestClients.create(
                        ApiGatewayClient.builder(), account, Region.US_WEST_2)) {
            String position = first.client.getBasePathMappings(r ->
                    r.domainName(first.domain).limit(1)).position();
            assertNotNull(position);
            for (Fixture other : List.of(otherDomain, otherAccount)) {
                assertThrows(BadRequestException.class, () -> other.client.getBasePathMappings(r ->
                        r.domainName(other.domain).limit(1).position(position)));
            }
            assertThrows(NotFoundException.class, () -> otherRegion.getBasePathMappings(r ->
                    r.domainName(first.domain).limit(1).position(position)));
        }
    }

    @Test
    void legacyRecordsWithTheSameReportedBasePathRemainDistinct() {
        try (Fixture fixture = new Fixture(0)) {
            AccountAwareStorageBackend<BasePathMapping> store = mappings();
            List<String> rawPaths = List.of("", "/", "(none)");
            Set<String> expectedIds = new HashSet<>();
            for (int i = 0; i < rawPaths.size(); i++) {
                String id = "legacy-api-" + i;
                expectedIds.add(id);
                store.putForAccount(fixture.account, fixture.region.id() + "::" + fixture.domain
                        + "::" + rawPaths.get(i), new BasePathMapping("(none)", id, "prod"));
            }
            Set<String> seen = new HashSet<>();
            fixture.client.getBasePathMappingsPaginator(r -> r.domainName(fixture.domain).limit(1))
                    .forEach(page -> {
                        assertEquals(1, page.items().size());
                        page.items().forEach(mapping -> {
                            assertEquals("(none)", mapping.basePath());
                            assertTrue(seen.add(mapping.restApiId()));
                        });
                    });
            assertEquals(expectedIds, seen);
        }
    }

    private AccountAwareStorageBackend<BasePathMapping> mappings() {
        return storageFactory.create("apigateway", "apigateway-mappings.json",
                new TypeReference<Map<String, BasePathMapping>>() {});
    }

    private static String account() {
        return String.format("%012d", Math.floorMod(System.nanoTime(), 1_000_000_000_000L));
    }

    private static final class Fixture implements AutoCloseable {
        private final String account;
        private final Region region;
        private final ApiGatewayClient client;
        private final String domain;
        private final String apiId;
        private final Set<String> paths = new HashSet<>();

        private Fixture(int count) {
            this(account(), Region.US_EAST_1, count);
        }

        private Fixture(String account, Region region, int count) {
            this(account, region, count, "basepath-page-" + UUID.randomUUID() + ".example.test");
        }

        private Fixture(String account, Region region, int count, String domain) {
            this.domain = domain;
            this.account = account;
            this.region = region;
            client = AwsTestClients.create(ApiGatewayClient.builder(), account, region);
            apiId = client.createRestApi(r -> r.name("basepath-page-" + UUID.randomUUID())).id();
            client.createDomainName(r -> r.domainName(domain)
                    .endpointConfiguration(c -> c.types(EndpointType.REGIONAL)));
            for (int i = 0; i < count; i++) {
                String path = "path-" + i;
                paths.add(path);
                client.createBasePathMapping(r -> r.domainName(domain).basePath(path)
                        .restApiId(apiId).stage("prod"));
            }
        }

        @Override
        public void close() {
            try {
                client.deleteDomainName(r -> r.domainName(domain));
                client.deleteRestApi(r -> r.restApiId(apiId));
            } finally {
                client.close();
            }
        }
    }
}
