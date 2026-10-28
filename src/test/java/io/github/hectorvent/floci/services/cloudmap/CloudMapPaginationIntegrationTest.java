package io.github.hectorvent.floci.services.cloudmap;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudmap.model.Namespace;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
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
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.ListNamespacesResponse;
import software.amazon.awssdk.services.servicediscovery.model.ListOperationsResponse;
import software.amazon.awssdk.services.servicediscovery.model.ListServicesResponse;
import software.amazon.awssdk.services.servicediscovery.model.OperationFilter;
import software.amazon.awssdk.services.servicediscovery.model.ServiceFilter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
class CloudMapPaginationIntegrationTest {

    private static final String ACCOUNT = "741660100001";
    private static final String OTHER_ACCOUNT = "741660100002";
    private static final Region REGION = Region.US_EAST_1;
    private static final List<String> ACTIONS = List.of("ListNamespaces", "ListServices", "ListOperations");

    @Inject
    StorageFactory storageFactory;

    @TestHTTPResource
    URI endpoint;

    private AccountAwareStorageBackend<Namespace> namespaces;
    private AccountAwareStorageBackend<Service> services;
    private AccountAwareStorageBackend<Operation> operations;
    private final List<StoredResources> created = new ArrayList<>();
    private ServiceDiscoveryClient client;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        namespaces = storageFactory.create("cloudmap", "cloudmap-namespaces.json",
                new TypeReference<Map<String, Namespace>>() {});
        services = storageFactory.create("cloudmap", "cloudmap-services.json",
                new TypeReference<Map<String, Service>>() {});
        operations = storageFactory.create("cloudmap", "cloudmap-operations.json",
                new TypeReference<Map<String, Operation>>() {});
        client = client(ACCOUNT, REGION);
    }

    @AfterEach
    void cleanUp() {
        client.close();
        for (StoredResources item : created) {
            namespaces.deleteForAccount(item.account(), item.namespace());
            services.deleteForAccount(item.account(), item.service());
            operations.deleteForAccount(item.account(), item.operation());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListNamespaces", "ListServices", "ListOperations"})
    void sdkPaginatorsRoundTripEveryItemExactlyOnce(String action) {
        seedThree();
        Page first = page(client, action, 1, null);
        assertEquals(List.of(prefix(action) + "a"), first.ids());
        assertNotNull(first.token());
        List<String> expected = List.of(prefix(action) + "a", prefix(action) + "b", prefix(action) + "c");
        List<String> actual = switch (action) {
            case "ListNamespaces" -> client.listNamespacesPaginator(request -> request.maxResults(1)).stream()
                    .flatMap(response -> response.namespaces().stream()).map(item -> item.id()).toList();
            case "ListServices" -> client.listServicesPaginator(request -> request.maxResults(1)).stream()
                    .flatMap(response -> response.services().stream()).map(item -> item.id()).toList();
            case "ListOperations" -> client.listOperationsPaginator(request -> request.maxResults(1)).stream()
                    .flatMap(response -> response.operations().stream()).map(item -> item.id()).toList();
            default -> throw new IllegalArgumentException(action);
        };
        assertEquals(expected, actual);
        Page last = page(client, action, 100, first.token());
        assertEquals(expected.subList(1, 3), last.ids());
        assertNull(last.token());
    }

    @Test
    void allThreeActionsDefaultTo100AndAcceptTheMaximumPageSize() {
        for (int i = 0; i < 101; i++) {
            seed(ACCOUNT, REGION, "item" + i, "ns-item" + i, "SUCCESS");
        }
        for (String action : ACTIONS) {
            Page first = page(client, action, null, null);
            assertEquals(100, first.ids().size());
            assertNotNull(first.token());
            Page last = page(client, action, 100, first.token());
            assertEquals(1, last.ids().size());
            assertNull(last.token());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void sdkRejectsOutOfRangeLimits(int limit) {
        for (String action : ACTIONS) {
            invalid(() -> page(client, action, limit, null));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "1.5", "\"1\"", "{}", "[]"})
    void malformedLimitsAreRejectedBeforeCoercion(String value) {
        for (String action : ACTIONS) {
            raw(action, "{\"MaxResults\":" + value + "}", 400);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"%%%%\"", "17", "{}", "[]"})
    void invalidTokenShapesAreRejected(String value) {
        for (String action : ACTIONS) {
            raw(action, "{\"NextToken\":" + value + "}", 400);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListNamespaces", "ListServices", "ListOperations"})
    void oversizedTokensAreRejectedEvenWithAValidDecodedScope(String action) {
        seedThree();
        String token = page(client, action, 1, null).token();
        assertNotNull(token);
        String cursor = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String oversized = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((cursor + "x".repeat(4096)).getBytes(StandardCharsets.UTF_8));
        assertTrue(oversized.length() > 4096);
        invalid(() -> page(client, action, 1, oversized));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListNamespaces", "ListServices", "ListOperations"})
    void tokensCannotCrossAccountRegionOrAction(String action) {
        seedThree();
        String token = page(client, action, 1, null).token();
        assertNotNull(token);
        seed(OTHER_ACCOUNT, REGION, "a", "ns-a", "SUCCESS");
        seed(ACCOUNT, Region.EU_WEST_1, "z", "ns-z", "SUCCESS");
        try (ServiceDiscoveryClient otherAccount = client(OTHER_ACCOUNT, REGION);
             ServiceDiscoveryClient otherRegion = client(ACCOUNT, Region.EU_WEST_1)) {
            invalid(() -> page(otherAccount, action, 1, token));
            invalid(() -> page(otherRegion, action, 1, token));
        }
        String otherAction = action.equals("ListNamespaces") ? "ListServices" : "ListNamespaces";
        invalid(() -> page(client, otherAction, 1, token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListNamespaces", "ListServices", "ListOperations"})
    void deletedCursorItemsDoNotPreventResuming(String action) {
        seedThree();
        Page first = page(client, action, 1, null);
        assertNotNull(first.token());
        switch (action) {
            case "ListNamespaces" -> namespaces.deleteForAccount(ACCOUNT, "ns-a");
            case "ListServices" -> services.deleteForAccount(ACCOUNT, "srv-a");
            case "ListOperations" -> operations.deleteForAccount(ACCOUNT, "op-a");
            default -> throw new IllegalArgumentException(action);
        }
        assertEquals(List.of(prefix(action) + "b", prefix(action) + "c"), page(client, action, 100, first.token()).ids());
    }

    @Test
    void serviceFilteringCanReturnAnEmptyPageWithAContinuationToken() {
        seedThree();
        ServiceFilter filter = ServiceFilter.builder().name("NAMESPACE_ID").values("ns-b").condition("EQ").build();
        ListServicesResponse first = client.listServices(request -> request.maxResults(1).filters(filter));
        assertTrue(first.services().isEmpty());
        assertNotNull(first.nextToken());
        assertEquals(List.of("srv-b", "srv-c"), client.listServicesPaginator(request -> request.maxResults(1).filters(filter))
                .stream().flatMap(response -> response.services().stream()).map(item -> item.id()).toList());
        ServiceFilter changed = ServiceFilter.builder().name("NAMESPACE_ID").values("ns-a").condition("EQ").build();
        invalid(() -> client.listServices(request -> request.maxResults(1).filters(changed).nextToken(first.nextToken())));
    }

    @Test
    void operationFilteringCanReturnAnEmptyPageWithAContinuationToken() {
        seedThree();
        OperationFilter filter = OperationFilter.builder().name("STATUS").values("SUCCESS").condition("IN").build();
        ListOperationsResponse first = client.listOperations(request -> request.maxResults(1).filters(filter));
        assertTrue(first.operations().isEmpty());
        assertNotNull(first.nextToken());
        assertEquals(List.of("op-b", "op-c"), client.listOperationsPaginator(request -> request.maxResults(1).filters(filter))
                .stream().flatMap(response -> response.operations().stream()).map(item -> item.id()).toList());
        invalid(() -> client.listOperations(request -> request.nextToken(first.nextToken())));
    }

    @Test
    void equivalentOperationFilterOrderCanResumeButAnEmptyValuedFilterIsDifferent() {
        seedThree();
        OperationFilter status = OperationFilter.builder().name("STATUS").values("SUCCESS", "PENDING").condition("IN").build();
        OperationFilter type = OperationFilter.builder().name("TYPE").values("CREATE_NAMESPACE").condition("EQ").build();
        String token = client.listOperations(request -> request.maxResults(1).filters(status, type)).nextToken();
        assertNotNull(token);
        OperationFilter reordered = OperationFilter.builder().name("STATUS").values("PENDING", "SUCCESS").condition("IN").build();
        assertEquals(List.of("op-b", "op-c"), client.listOperations(request -> request.filters(type, reordered).nextToken(token))
                .operations().stream().map(item -> item.id()).toList());
        String unfiltered = client.listOperations(request -> request.maxResults(1)).nextToken();
        OperationFilter empty = OperationFilter.builder().name("STATUS").values(List.of()).build();
        invalid(() -> client.listOperations(request -> request.filters(empty).nextToken(unfiltered)));
    }

    @Test
    void emptyAndFinalPagesOmitNextToken() {
        for (String action : ACTIONS) {
            assertFalse(raw(action, "{\"NextToken\":\"\"}", 200).containsKey("NextToken"));
        }
        seed(ACCOUNT, REGION, "a", "ns-a", "SUCCESS");
        for (String action : ACTIONS) {
            assertFalse(raw(action, "{}", 200).containsKey("NextToken"));
        }
    }

    private Page page(ServiceDiscoveryClient sdk, String action, Integer limit, String token) {
        return switch (action) {
            case "ListNamespaces" -> {
                ListNamespacesResponse response = sdk.listNamespaces(request -> request.maxResults(limit).nextToken(token));
                yield new Page(response.namespaces().stream().map(item -> item.id()).toList(), response.nextToken());
            }
            case "ListServices" -> {
                ListServicesResponse response = sdk.listServices(request -> request.maxResults(limit).nextToken(token));
                yield new Page(response.services().stream().map(item -> item.id()).toList(), response.nextToken());
            }
            case "ListOperations" -> {
                ListOperationsResponse response = sdk.listOperations(request -> request.maxResults(limit).nextToken(token));
                yield new Page(response.operations().stream().map(item -> item.id()).toList(), response.nextToken());
            }
            default -> throw new IllegalArgumentException(action);
        };
    }

    private void invalid(Runnable call) {
        AwsServiceException error = assertThrows(AwsServiceException.class, call::run);
        assertEquals(400, error.statusCode());
        assertEquals("InvalidInput", error.awsErrorDetails().errorCode());
    }

    private Map<String, Object> raw(String action, String body, int status) {
        Map<String, Object> response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Route53AutoNaming_v20170314." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/servicediscovery/aws4_request")
                .body(body).when().post("/").then().statusCode(status).extract().jsonPath().getMap("");
        if (status == 400) {
            assertEquals("InvalidInput", response.get("__type"));
        }
        return response;
    }

    private void seedThree() {
        seed(ACCOUNT, REGION, "a", "ns-a", "PENDING");
        seed(ACCOUNT, REGION, "b", "ns-b", "SUCCESS");
        seed(ACCOUNT, REGION, "c", "ns-b", "SUCCESS");
    }

    private void seed(String account, Region region, String suffix, String serviceNamespace, String status) {
        Namespace namespace = new Namespace();
        namespace.setId("ns-" + suffix);
        namespace.setName("namespace-" + suffix);
        namespace.setArn("arn:aws:servicediscovery:" + region.id() + ":" + account + ":namespace/" + namespace.getId());
        namespace.setType("HTTP");
        namespace.setRegion(region.id());
        namespace.setCreateDate(Instant.EPOCH);
        namespaces.putForAccount(account, namespace.getId(), namespace);
        Service service = new Service();
        service.setId("srv-" + suffix);
        service.setName("service-" + suffix);
        service.setArn("arn:aws:servicediscovery:" + region.id() + ":" + account + ":service/" + service.getId());
        service.setNamespaceId(serviceNamespace);
        service.setType("HTTP");
        service.setRegion(region.id());
        service.setCreateDate(Instant.EPOCH);
        services.putForAccount(account, service.getId(), service);
        Operation operation = new Operation();
        operation.setId("op-" + suffix);
        operation.setType("CREATE_NAMESPACE");
        operation.setStatus(status);
        operation.setRegion(region.id());
        operation.setCreateDate(Instant.EPOCH);
        operation.setUpdateDate(Instant.EPOCH);
        operation.getTargets().put("NAMESPACE", namespace.getId());
        operations.putForAccount(account, operation.getId(), operation);
        created.add(new StoredResources(account, namespace.getId(), service.getId(), operation.getId()));
    }

    private ServiceDiscoveryClient client(String account, Region region) {
        return ServiceDiscoveryClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private String prefix(String action) {
        return switch (action) {
            case "ListNamespaces" -> "ns-";
            case "ListServices" -> "srv-";
            case "ListOperations" -> "op-";
            default -> throw new IllegalArgumentException(action);
        };
    }

    private record Page(List<String> ids, String token) {}
    private record StoredResources(String account, String namespace, String service, String operation) {}
}
