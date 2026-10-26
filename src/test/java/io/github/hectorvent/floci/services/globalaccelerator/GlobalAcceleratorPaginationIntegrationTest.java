package io.github.hectorvent.floci.services.globalaccelerator;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.globalaccelerator.model.Accelerator;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
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
import software.amazon.awssdk.services.globalaccelerator.GlobalAcceleratorClient;
import software.amazon.awssdk.services.globalaccelerator.model.InvalidArgumentException;
import software.amazon.awssdk.services.globalaccelerator.model.InvalidNextTokenException;
import software.amazon.awssdk.services.globalaccelerator.model.ListAcceleratorsResponse;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GlobalAcceleratorPaginationIntegrationTest {

    private static final String ACCOUNT = "741649500001";
    private static final String OTHER_ACCOUNT = "741649500002";
    private static final Region REGION = Region.US_WEST_2;

    @Inject
    StorageFactory storageFactory;

    private AccountAwareStorageBackend<Accelerator> accelerators;
    private final List<SeededAccelerator> seeded = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        accelerators = storageFactory.create("globalaccelerator", "globalaccelerator-accelerators.json",
                new TypeReference<Map<String, Accelerator>>() {});
    }

    @AfterEach
    void cleanUp() {
        seeded.forEach(item -> accelerators.deleteForAccount(item.account(), item.arn()));
    }

    @Test
    void sdkPagesTheCallingAccountsGlobalAcceleratorsAcrossSigningRegions() {
        List<String> arns = seed(ACCOUNT, 3).stream().sorted().toList();
        List<String> foreignArns = seed(OTHER_ACCOUNT, 1);
        try (GlobalAcceleratorClient client = client(ACCOUNT, REGION);
                GlobalAcceleratorClient otherRegion = client(ACCOUNT, Region.US_EAST_1);
                GlobalAcceleratorClient otherAccount = client(OTHER_ACCOUNT, REGION)) {
            ListAcceleratorsResponse first = client.listAccelerators(request -> request.maxResults(1));
            assertEquals(List.of(arns.get(0)), first.accelerators().stream()
                    .map(accelerator -> accelerator.acceleratorArn()).toList());
            assertNotNull(first.nextToken());
            ListAcceleratorsResponse second = otherRegion.listAccelerators(request -> request
                    .maxResults(1).nextToken(first.nextToken()));
            assertEquals(List.of(arns.get(1)), second.accelerators().stream()
                    .map(accelerator -> accelerator.acceleratorArn()).toList());
            assertNotNull(second.nextToken());
            ListAcceleratorsResponse last = client.listAccelerators(request -> request
                    .maxResults(1).nextToken(second.nextToken()));
            assertEquals(List.of(arns.get(2)), last.accelerators().stream()
                    .map(accelerator -> accelerator.acceleratorArn()).toList());
            assertNull(last.nextToken());
            assertEquals(arns, client.listAcceleratorsPaginator(request -> request.maxResults(2))
                    .accelerators().stream().map(accelerator -> accelerator.acceleratorArn()).toList());
            assertEquals(foreignArns, otherAccount.listAccelerators(request -> {}).accelerators().stream()
                    .map(accelerator -> accelerator.acceleratorArn()).toList());
        }
    }

    @Test
    void sdkUsesTheDocumentedTenItemDefaultAndAcceptsTheHundredItemMaximum() {
        seed(ACCOUNT, 11);
        try (GlobalAcceleratorClient client = client(ACCOUNT, REGION)) {
            ListAcceleratorsResponse first = client.listAccelerators(request -> {});
            assertEquals(10, first.accelerators().size());
            assertNotNull(first.nextToken());
            ListAcceleratorsResponse last = client.listAccelerators(request -> request.nextToken(first.nextToken()));
            assertEquals(1, last.accelerators().size());
            assertNull(last.nextToken());
            assertEquals(11, client.listAccelerators(request -> request.maxResults(100)).accelerators().size());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void sdkRejectsPageSizesOutsideTheDocumentedRange(int maxResults) {
        try (GlobalAcceleratorClient client = client(ACCOUNT, REGION)) {
            InvalidArgumentException failure = assertThrows(InvalidArgumentException.class,
                    () -> client.listAccelerators(request -> request.maxResults(maxResults)));
            assertEquals(400, failure.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "%%%%"})
    void sdkRejectsMalformedContinuationTokens(String token) {
        try (GlobalAcceleratorClient client = client(ACCOUNT, REGION)) {
            InvalidNextTokenException failure = assertThrows(InvalidNextTokenException.class,
                    () -> client.listAccelerators(request -> request.nextToken(token)));
            assertEquals(400, failure.statusCode());
        }
    }

    @Test
    void sdkRejectsTokensLongerThanTheDocumentedMaximum() {
        try (GlobalAcceleratorClient client = client(ACCOUNT, REGION)) {
            InvalidNextTokenException failure = assertThrows(InvalidNextTokenException.class,
                    () -> client.listAccelerators(request -> request.nextToken("x".repeat(256))));
            assertEquals(400, failure.statusCode());
        }
    }

    @Test
    void sdkDoesNotAcceptAnotherAccountsContinuationToken() {
        seed(ACCOUNT, 3);
        try (GlobalAcceleratorClient client = client(ACCOUNT, REGION);
                GlobalAcceleratorClient otherAccount = client(OTHER_ACCOUNT, REGION)) {
            String token = client.listAccelerators(request -> request.maxResults(1)).nextToken();
            assertNotNull(token);
            assertEquals(400, assertThrows(InvalidNextTokenException.class,
                    () -> otherAccount.listAccelerators(request -> request.nextToken(token))).statusCode());
        }
    }

    @Test
    void sdkReturnsAnEmptyPageWithoutAToken() {
        try (GlobalAcceleratorClient client = client(ACCOUNT, REGION)) {
            ListAcceleratorsResponse response = client.listAccelerators(request -> request.maxResults(100));
            assertTrue(response.accelerators().isEmpty());
            assertNull(response.nextToken());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"MaxResults\":4294967297}", "{\"MaxResults\":1.5}",
            "{\"MaxResults\":\"1\"}"})
    void httpRejectsMalformedPageSizes(String body) {
        given().header("X-Amz-Target", "GlobalAccelerator_V20180706.ListAccelerators")
                .contentType("application/x-amz-json-1.1").body(body)
                .post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidArgumentException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"NextToken\":{}}", "{\"NextToken\":[]}"})
    void httpRejectsMalformedTokenFields(String body) {
        given().header("X-Amz-Target", "GlobalAccelerator_V20180706.ListAccelerators")
                .contentType("application/x-amz-json-1.1").body(body)
                .post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidNextTokenException"));
    }

    private List<String> seed(String account, int count) {
        List<String> arns = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String id = UUID.randomUUID().toString();
            String arn = AwsArnUtils.Arn.global(AwsRegions.partitionFor(REGION.id()), "globalaccelerator",
                    account, "accelerator/" + id).toString();
            Accelerator accelerator = new Accelerator();
            accelerator.setAcceleratorArn(arn);
            accelerator.setName("page-" + id);
            accelerator.setIpAddressType("IPV4");
            accelerator.setEnabled(true);
            accelerator.setStatus("DEPLOYED");
            accelerator.setCreatedTime(Instant.now().getEpochSecond());
            accelerator.setLastModifiedTime(accelerator.getCreatedTime());
            accelerators.putForAccount(account, arn, accelerator);
            seeded.add(new SeededAccelerator(account, arn));
            arns.add(arn);
        }
        return arns;
    }

    private static GlobalAcceleratorClient client(String account, Region region) {
        return GlobalAcceleratorClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private record SeededAccelerator(String account, String arn) {}
}
