package io.github.hectorvent.floci.services.aps;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.amp.AmpClient;
import software.amazon.awssdk.services.amp.model.ConflictException;
import software.amazon.awssdk.services.amp.model.CreateWorkspaceResponse;
import software.amazon.awssdk.services.amp.model.ValidationException;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApsCreateWorkspaceIdempotencyIntegrationTest {

    private static final String ACCOUNT = "741648800001";
    private static final String OTHER_ACCOUNT = "741648800002";
    private static final Region REGION = Region.EU_CENTRAL_1;
    private final ConcurrentLinkedQueue<Creation> created = new ConcurrentLinkedQueue<>();

    @Inject
    StorageFactory storageFactory;

    @AfterEach
    void cleanUp() {
        AccountAwareStorageBackend<PrometheusWorkspace> workspaces = storageFactory.create("aps", "aps-workspaces.json",
                new TypeReference<Map<String, PrometheusWorkspace>>() {});
        created.forEach(item -> {
            workspaces.deleteForAccount(item.account(), item.region().id() + "::" + item.id());
            if (item.token() != null) {
                workspaces.deleteForAccount(item.account(), ":CreateWorkspace::" + item.region().id() + "::" + item.token());
            }
        });
    }

    @Test
    void sdkReplaysTheOriginalResponseWithoutOverwritingLaterWorkspaceChanges() {
        String token = UUID.randomUUID().toString();
        try (AmpClient client = client(ACCOUNT, REGION)) {
            CreateWorkspaceResponse first = create(client, token, "original", Map.of("team", "one"), null, ACCOUNT, REGION);
            client.updateWorkspaceAlias(request -> request.workspaceId(first.workspaceId()).alias("updated"));
            client.tagResource(request -> request.resourceArn(first.arn()).tags(Map.of("team", "two")));
            CreateWorkspaceResponse replay = create(client, token, "original", Map.of("team", "one"), null, ACCOUNT, REGION);
            assertEquals(first.workspaceId(), replay.workspaceId());
            assertEquals(first.arn(), replay.arn());
            assertEquals(first.tags(), replay.tags());
            assertEquals(first.status(), replay.status());
            assertEquals("updated", client.describeWorkspace(request -> request.workspaceId(first.workspaceId()))
                    .workspace().alias());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"alias", "tags", "kms"})
    void sdkRejectsTokenReuseWithDifferentWorkspaceParameters(String changed) {
        String token = UUID.randomUUID().toString();
        try (AmpClient client = client(ACCOUNT, REGION)) {
            create(client, token, "original", Map.of("team", "one"), null, ACCOUNT, REGION);
            String kms = "kms".equals(changed) ? AwsArnUtils.Arn.of("kms", REGION.id(), ACCOUNT,
                    "key/" + UUID.randomUUID()).toString() : null;
            ConflictException error = assertThrows(ConflictException.class, () -> create(client, token,
                    "alias".equals(changed) ? "different" : "original",
                    "tags".equals(changed) ? Map.of("team", "two") : Map.of("team", "one"), kms, ACCOUNT, REGION));
            assertEquals(409, error.statusCode());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void sdkRejectsTokensOutsideTheDocumentedShape(String token) {
        try (AmpClient client = client(ACCOUNT, REGION)) {
            assertEquals(400, assertThrows(ValidationException.class,
                    () -> create(client, token, "invalid", Map.of(), null, ACCOUNT, REGION)).statusCode());
        }
    }

    static Stream<String> invalidTokens() {
        return Stream.of("", "has space", "line\nbreak", "é", "a".repeat(65));
    }

    @Test
    void sdkAcceptsTheSixtyFourCharacterBoundaryAndKeepsTokensCaseSensitive() {
        try (AmpClient client = client(ACCOUNT, REGION)) {
            String token = "A".repeat(64);
            CreateWorkspaceResponse first = create(client, token, "boundary", Map.of(), null, ACCOUNT, REGION);
            assertEquals(first.workspaceId(), create(client, token, "boundary", Map.of(), null, ACCOUNT, REGION).workspaceId());
            assertNotEquals(first.workspaceId(), create(client, token.toLowerCase(Locale.ROOT),
                    "boundary", Map.of(), null, ACCOUNT, REGION).workspaceId());
        }
    }

    @Test
    void sdkScopesTheSameTokenToAccountAndRegion() {
        String token = UUID.randomUUID().toString();
        try (AmpClient client = client(ACCOUNT, REGION);
                AmpClient otherRegion = client(ACCOUNT, Region.EU_WEST_1);
                AmpClient otherAccount = client(OTHER_ACCOUNT, REGION)) {
            CreateWorkspaceResponse first = create(client, token, "scoped", Map.of(), null, ACCOUNT, REGION);
            CreateWorkspaceResponse regional = create(otherRegion, token, "scoped", Map.of(), null, ACCOUNT, Region.EU_WEST_1);
            CreateWorkspaceResponse foreign = create(otherAccount, token, "scoped", Map.of(), null, OTHER_ACCOUNT, REGION);
            assertEquals(3, Set.of(first.workspaceId(), regional.workspaceId(), foreign.workspaceId()).size());
            assertEquals(first.workspaceId(), create(client, token, "scoped", Map.of(), null, ACCOUNT, REGION).workspaceId());
            assertEquals(OTHER_ACCOUNT, AwsArnUtils.parse(foreign.arn()).accountId());
        }
    }

    @Test
    void concurrentSdkRetriesCreateOneWorkspace() throws Exception {
        String token = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try (AmpClient client = client(ACCOUNT, REGION)) {
            List<Future<String>> replies = Stream.generate(() -> pool.submit(() -> {
                start.await();
                return create(client, token, "concurrent", Map.of(), null, ACCOUNT, REGION).workspaceId();
            })).limit(6).toList();
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (Future<String> reply : replies) {
                ids.add(reply.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, ids.size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void requestsWithoutATokenCreateIndependentWorkspaces() {
        String first = createWithoutToken();
        String second = createWithoutToken();
        assertNotEquals(first, second);
    }

    private String createWithoutToken() {
        Response response = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261026/" + REGION.id() + "/aps/aws4_request")
                .contentType("application/json").body(Map.of("alias", "no-token")).post("/workspaces");
        assertEquals(202, response.statusCode());
        String id = response.jsonPath().getString("workspaceId");
        created.add(new Creation(ACCOUNT, REGION, id, null));
        return id;
    }

    private CreateWorkspaceResponse create(AmpClient client, String token, String alias, Map<String, String> tags,
                                            String kms, String account, Region region) {
        CreateWorkspaceResponse response = client.createWorkspace(request -> request.clientToken(token)
                .alias(alias).tags(tags).kmsKeyArn(kms));
        created.add(new Creation(account, region, response.workspaceId(), token));
        return response;
    }

    private static AmpClient client(String account, Region region) {
        return AmpClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private record Creation(String account, Region region, String id, String token) {}
}
