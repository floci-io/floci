package io.github.hectorvent.floci.services.servicecatalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.servicecatalog.ServiceCatalogClient;
import software.amazon.awssdk.services.servicecatalog.model.ListPortfoliosResponse;
import software.amazon.awssdk.services.servicecatalog.model.PortfolioDetail;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ServiceCatalogPortfolioPaginationIntegrationTest {

    private static final String ACCOUNT = "741660500001";
    private static final String OTHER_ACCOUNT = "741660500002";
    private static final Region REGION = Region.US_EAST_1;

    @Inject
    StorageFactory storageFactory;

    @Inject
    ObjectMapper objectMapper;

    @TestHTTPResource
    URI endpoint;

    private final List<AccountAwareStorageBackend<ObjectNode>> stores = new ArrayList<>();
    private ServiceCatalogClient client;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        for (String name : List.of("portfolios", "products", "associations")) {
            stores.add(storageFactory.create("servicecatalog", "servicecatalog-" + name + ".json",
                    new TypeReference<Map<String, ObjectNode>>() {}));
        }
        client = client(ACCOUNT, REGION);
    }

    @AfterEach
    void cleanUp() {
        client.close();
        for (AccountAwareStorageBackend<ObjectNode> store : stores) {
            for (String account : List.of(ACCOUNT, OTHER_ACCOUNT)) {
                store.keysForAccount(account).forEach(key -> store.deleteForAccount(account, key));
            }
        }
    }

    @Test
    void sdkPaginatorReturnsEveryPortfolioIncludingTheSeedExactlyOnce() {
        List<String> expected = createTwo();
        ListPortfoliosResponse first = client.listPortfolios(request -> request.pageSize(1));
        assertEquals(List.of(expected.getFirst()), ids(first));
        assertNotNull(first.nextPageToken());
        List<String> actual = client.listPortfoliosPaginator(request -> request.pageSize(1)).stream()
                .flatMap(response -> response.portfolioDetails().stream()).map(PortfolioDetail::id).toList();
        assertEquals(expected, actual);
        ListPortfoliosResponse last = client.listPortfolios(request -> request.pageSize(100).pageToken(first.nextPageToken()));
        assertEquals(expected.subList(1, expected.size()), ids(last));
        assertNull(last.nextPageToken());
    }

    @Test
    void omittedAndZeroPageSizesUseTheBoundedEmulatorDefault() {
        for (int i = 0; i < 100; i++) {
            String id = "port-test" + i;
            stores.getFirst().putForAccount(ACCOUNT, id, objectMapper.createObjectNode()
                    .put("Id", id).put("DisplayName", id).put("ProviderName", "test"));
        }
        for (Integer size : new Integer[] {null, 0, 100}) {
            ListPortfoliosResponse first = client.listPortfolios(request -> request.pageSize(size));
            assertEquals(100, first.portfolioDetails().size());
            assertNotNull(first.nextPageToken());
            ListPortfoliosResponse last = client.listPortfolios(request -> request.pageToken(first.nextPageToken()));
            assertEquals(1, last.portfolioDetails().size());
            assertNull(last.nextPageToken());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void outOfRangePageSizesAreRejected(int size) {
        invalid(() -> client.listPortfolios(request -> request.pageSize(size)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "1.5", "\"1\"", "{}", "[]"})
    void malformedPageSizesAreRejectedBeforeCoercion(String value) {
        raw("{\"PageSize\":" + value + "}", 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"%%%%\"", "17", "{}", "[]"})
    void malformedPageTokensAreRejected(String value) {
        raw("{\"PageToken\":" + value + "}", 400);
    }

    @Test
    void oversizedTokensWithAValidScopeAreRejected() {
        createTwo();
        String token = client.listPortfolios(request -> request.pageSize(1)).nextPageToken();
        assertNotNull(token);
        String cursor = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String oversized = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((cursor + "x".repeat(2024)).getBytes(StandardCharsets.UTF_8));
        assertTrue(oversized.length() > 2024);
        invalid(() -> client.listPortfolios(request -> request.pageToken(oversized)));
    }

    @Test
    void tokensCannotCrossAccountOrRegion() {
        createTwo();
        String token = client.listPortfolios(request -> request.pageSize(1)).nextPageToken();
        assertNotNull(token);
        try (ServiceCatalogClient otherAccount = client(OTHER_ACCOUNT, REGION);
             ServiceCatalogClient otherRegion = client(ACCOUNT, Region.EU_WEST_1)) {
            invalid(() -> otherAccount.listPortfolios(request -> request.pageToken(token)));
            invalid(() -> otherRegion.listPortfolios(request -> request.pageToken(token)));
        }
    }

    @Test
    void deletingTheCursorPortfolioStillResumesAtTheNextId() {
        List<String> expected = createTwo();
        ListPortfoliosResponse first = client.listPortfolios(request -> request.pageSize(1));
        assertEquals(1, first.portfolioDetails().size());
        String id = first.portfolioDetails().getFirst().id();
        client.deletePortfolio(request -> request.id(id));
        ListPortfoliosResponse last = client.listPortfolios(request -> request.pageSize(100).pageToken(first.nextPageToken()));
        assertEquals(expected.stream().filter(value -> !value.equals(id)).toList(), ids(last));
        assertNull(last.nextPageToken());
    }

    @Test
    void seededOnlyFinalPageOmitsTokenAndAcceptsNullOrEmptyToken() {
        for (String body : List.of("{}", "{\"PageToken\":null}", "{\"PageToken\":\"\"}")) {
            Map<String, Object> response = raw(body, 200);
            assertFalse(response.containsKey("NextPageToken"));
            assertEquals(1, ((List<?>) response.get("PortfolioDetails")).size());
        }
    }

    private List<String> createTwo() {
        String first = client.createPortfolio(request -> request.displayName("pagination-a").providerName("test"))
                .portfolioDetail().id();
        String second = client.createPortfolio(request -> request.displayName("pagination-b").providerName("test"))
                .portfolioDetail().id();
        return List.of(first, second, ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_ID).stream().sorted().toList();
    }

    private List<String> ids(ListPortfoliosResponse response) {
        return response.portfolioDetails().stream().map(PortfolioDetail::id).toList();
    }

    private void invalid(Runnable call) {
        AwsServiceException error = assertThrows(AwsServiceException.class, call::run);
        assertEquals(400, error.statusCode());
        assertEquals("InvalidParametersException", error.awsErrorDetails().errorCode());
    }

    private Map<String, Object> raw(String body, int status) {
        Map<String, Object> response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWS242ServiceCatalogService.ListPortfolios")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/servicecatalog/aws4_request")
                .body(body).when().post("/").then().statusCode(status).extract().jsonPath().getMap("");
        if (status == 400) {
            assertEquals("InvalidParametersException", response.get("__type"));
        }
        return response;
    }

    private ServiceCatalogClient client(String account, Region region) {
        return ServiceCatalogClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }
}
