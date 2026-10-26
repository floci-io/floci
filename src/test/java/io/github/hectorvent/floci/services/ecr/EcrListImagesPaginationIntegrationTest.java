package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;
import software.amazon.awssdk.services.ecr.model.ListImagesResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class EcrListImagesPaginationIntegrationTest {

    private static final String ACCOUNT = "741650100001";
    private static final Region REGION = Region.EU_CENTRAL_1;
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @InjectMock
    EcrRegistryManager registryManager;
    @Inject
    StorageFactory storageFactory;
    @Inject
    ObjectMapper mapper;

    private final Map<String, String> images = new ConcurrentHashMap<>();
    private final List<SeededRepository> repositories = new ArrayList<>();
    private final AtomicInteger manifestHeads = new AtomicInteger();
    private HttpServer registry;
    private EcrClient client;
    private String repository;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() throws IOException {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/v2/", this::registryRequest);
        registry.start();
        when(registryManager.tryEnsureStarted()).thenReturn(true);
        when(registryManager.httpClient()).thenReturn(new RegistryHttpClient(
                "http://127.0.0.1:" + registry.getAddress().getPort()));
        when(registryManager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1) + "/" + call.getArgument(2));
        when(registryManager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(call -> "localhost:4566/" + call.getArgument(2));
        client = client(REGION);
        repository = createRepository(client, REGION);
    }

    @AfterEach
    void cleanUp() {
        AccountAwareStorageBackend<Repository> store = storageFactory.create("ecr", "repositories.json",
                new TypeReference<Map<String, Repository>>() {});
        repositories.forEach(repo -> store.deleteForAccount(ACCOUNT,
                repo.region().id() + "::" + ACCOUNT + "::" + repo.name()));
        if (client != null) {
            client.close();
        }
        if (registry != null) {
            registry.stop(0);
        }
    }

    @Test
    void sdkPagesDistinctTagsEvenWhenTheyShareTheSameDigest() {
        seed(3);
        ListImagesResponse first = client.listImages(request -> request.repositoryName(repository).maxResults(1));
        assertEquals(List.of("tag-000"), tags(first));
        assertNotNull(first.nextToken());
        ListImagesResponse second = client.listImages(request -> request.repositoryName(repository).registryId(ACCOUNT)
                .maxResults(1).nextToken(first.nextToken()));
        assertEquals(List.of("tag-001"), tags(second));
        assertNotNull(second.nextToken());
        ListImagesResponse last = client.listImages(request -> request.repositoryName(repository)
                .maxResults(1).nextToken(second.nextToken()));
        assertEquals(List.of("tag-002"), tags(last));
        assertNull(last.nextToken());
        assertEquals(List.of("tag-000", "tag-001", "tag-002"), client.listImagesPaginator(request -> request
                .repositoryName(repository).maxResults(2)).imageIds().stream().map(image -> image.imageTag()).toList());
        assertTrue(first.imageIds().stream().allMatch(image -> DIGEST.equals(image.imageDigest())));
    }

    @Test
    void sdkUsesTheHundredItemDefaultAndAcceptsTheThousandItemMaximum() {
        seed(101);
        ListImagesResponse first = client.listImages(request -> request.repositoryName(repository));
        assertEquals(100, first.imageIds().size());
        assertNotNull(first.nextToken());
        ListImagesResponse last = client.listImages(request -> request.repositoryName(repository)
                .nextToken(first.nextToken()));
        assertEquals(1, last.imageIds().size());
        assertNull(last.nextToken());
        assertEquals(101, client.listImages(request -> request.repositoryName(repository)
                .maxResults(1000)).imageIds().size());
    }

    @Test
    void sdkFetchesOnlyTheDigestsOnEachReturnedPage() {
        seed(3);
        ListImagesResponse first = client.listImages(request -> request.repositoryName(repository).maxResults(1));
        assertEquals(1, manifestHeads.get());
        assertNotNull(first.nextToken());
        ListImagesResponse second = client.listImages(request -> request.repositoryName(repository)
                .maxResults(1).nextToken(first.nextToken()));
        assertEquals(List.of("tag-001"), tags(second));
        assertEquals(2, manifestHeads.get());
    }

    @Test
    void sdkRejectsInvalidTokensWithoutFetchingDigests() {
        seed(3);
        assertThrows(InvalidParameterException.class,
                () -> client.listImages(request -> request.repositoryName(repository).nextToken("%%%%")));
        assertEquals(0, manifestHeads.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1001})
    void sdkRejectsInvalidPageSizes(int maxResults) {
        assertEquals(400, assertThrows(InvalidParameterException.class,
                () -> client.listImages(request -> request.repositoryName(repository).maxResults(maxResults))).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "%%%%"})
    void sdkRejectsMalformedTokens(String token) {
        assertEquals(400, assertThrows(InvalidParameterException.class,
                () -> client.listImages(request -> request.repositoryName(repository).nextToken(token))).statusCode());
    }

    @Test
    void sdkRejectsTokensFromAnotherRepositoryOrRegion() {
        seed(2);
        String token = client.listImages(request -> request.repositoryName(repository).maxResults(1)).nextToken();
        assertNotNull(token);
        String otherRepository = createRepository(client, REGION);
        assertEquals(400, assertThrows(InvalidParameterException.class,
                () -> client.listImages(request -> request.repositoryName(otherRepository).nextToken(token))).statusCode());
        try (EcrClient otherRegion = client(Region.EU_WEST_1)) {
            otherRegion.createRepository(request -> request.repositoryName(repository));
            repositories.add(new SeededRepository(Region.EU_WEST_1, repository));
            assertEquals(400, assertThrows(InvalidParameterException.class,
                    () -> otherRegion.listImages(request -> request.repositoryName(repository).nextToken(token))).statusCode());
        }
    }

    @Test
    void sdkResumesAfterADeletedTagWithoutSkippingTheNextTag() {
        seed(3);
        ListImagesResponse first = client.listImages(request -> request.repositoryName(repository).maxResults(1));
        assertEquals(List.of("tag-000"), tags(first));
        assertNotNull(first.nextToken());
        images.remove("tag-000");
        ListImagesResponse last = client.listImages(request -> request.repositoryName(repository)
                .maxResults(2).nextToken(first.nextToken()));
        assertEquals(List.of("tag-001", "tag-002"), tags(last));
        assertNull(last.nextToken());
    }

    @Test
    void sdkReturnsAnEmptyPageWithoutAToken() {
        ListImagesResponse response = client.listImages(request -> request.repositoryName(repository));
        assertTrue(response.imageIds().isEmpty());
        assertNull(response.nextToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"maxResults\":4294967297", "\"maxResults\":1.5", "\"maxResults\":\"1\"",
            "\"nextToken\":{}", "\"nextToken\":[]"})
    void httpRejectsMalformedPaginationFields(String field) {
        given().header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.ListImages")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261025/" + REGION.id() + "/ecr/aws4_request")
                .contentType("application/x-amz-json-1.1")
                .body("{\"repositoryName\":\"" + repository + "\",\"registryId\":\"" + ACCOUNT + "\"," + field + "}")
                .post("/").then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    private String createRepository(EcrClient api, Region region) {
        String name = "page-" + UUID.randomUUID();
        api.createRepository(request -> request.repositoryName(name));
        repositories.add(new SeededRepository(region, name));
        return name;
    }

    private void seed(int count) {
        IntStream.range(0, count).forEach(index -> images.put("tag-%03d".formatted(index), DIGEST));
    }

    private void registryRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = path.endsWith("/tags/list")
                        ? Map.of("tags", images.keySet().stream().sorted(Comparator.reverseOrder()).toList())
                        : Map.of("repositories", List.of());
                byte[] bytes = mapper.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                manifestHeads.incrementAndGet();
                String digest = images.get(path.substring(path.lastIndexOf('/') + 1));
                if (digest != null) {
                    exchange.getResponseHeaders().set("Docker-Content-Digest", digest);
                }
                exchange.sendResponseHeaders(digest != null ? 200 : 404, -1);
            }
        }
    }

    private static List<String> tags(ListImagesResponse response) {
        return response.imageIds().stream().map(image -> image.imageTag()).toList();
    }

    private static EcrClient client(Region region) {
        return EcrClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build();
    }

    private record SeededRepository(Region region, String name) {}
}
