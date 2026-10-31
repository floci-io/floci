package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CodeSigningConfig;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.ListCodeSigningConfigsResponse;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class LambdaCodeSigningPaginationIntegrationTest {
    private static final String ACCOUNT = "741681400001";
    private static final String REGION = "eu-west-2";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261028/" + REGION + "/lambda/aws4_request";
    private static final String BASE = "/2020-04-22/code-signing-configs";
    private static final String PROFILE = "arn:aws:signer:" + REGION + ":" + ACCOUNT + ":/signing-profiles/prof/AbCdEfGhIj";
    private final List<String> created = new ArrayList<>();
    private LambdaClient client;

    @BeforeEach
    void openClient() {
        client = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.of(REGION));
    }

    @AfterEach
    void cleanUp() {
        try {
            for (String arn : created) {
                client.deleteCodeSigningConfig(request -> request.codeSigningConfigArn(arn));
            }
        } finally {
            client.close();
        }
    }

    @Test
    void sdkPagesAndAutomaticPaginatorTraverseEachConfigurationOnce() {
        create(3);
        ListCodeSigningConfigsResponse first = client.listCodeSigningConfigs(request -> request.maxItems(1));
        assertEquals(1, first.codeSigningConfigs().size());
        assertNotNull(first.nextMarker());
        ListCodeSigningConfigsResponse last = client.listCodeSigningConfigs(request -> request
                .marker(first.nextMarker()).maxItems(2));
        assertEquals(2, last.codeSigningConfigs().size());
        assertNull(last.nextMarker());
        List<String> ids = new ArrayList<>(arns(first));
        ids.addAll(arns(last));
        assertEquals(created.stream().sorted().toList(), ids);
        assertEquals(ids, client.listCodeSigningConfigsPaginator(request -> request.maxItems(1))
                .stream().flatMap(page -> page.codeSigningConfigs().stream())
                .map(CodeSigningConfig::codeSigningConfigArn).toList());
    }

    @Test
    void omittedAndMaximumPageSizesReturnACompleteFinalPage() {
        create(3);
        for (Integer size : new Integer[]{null, 10000}) {
            ListCodeSigningConfigsResponse page = client.listCodeSigningConfigs(request -> request.maxItems(size));
            assertEquals(created.stream().sorted().toList(), arns(page));
            assertNull(page.nextMarker());
        }
    }

    @Test
    void cursorSurvivesDeletionOfItsConfiguration() {
        create(3);
        ListCodeSigningConfigsResponse first = client.listCodeSigningConfigs(request -> request.maxItems(1));
        assertNotNull(first.nextMarker());
        String deleted = first.codeSigningConfigs().getFirst().codeSigningConfigArn();
        client.deleteCodeSigningConfig(request -> request.codeSigningConfigArn(deleted));
        created.remove(deleted);
        ListCodeSigningConfigsResponse last = client.listCodeSigningConfigs(request -> request
                .marker(first.nextMarker()).maxItems(2));
        assertEquals(created.stream().sorted().toList(), arns(last));
        assertNull(last.nextMarker());
    }

    @Test
    void markersCannotCrossAccountOrRegion() {
        create(2);
        String marker = client.listCodeSigningConfigs(request -> request.maxItems(1)).nextMarker();
        assertNotNull(marker);
        try (LambdaClient other = AwsTestClients.create(LambdaClient.builder(), "741681400002", Region.of(REGION));
             LambdaClient region = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertTrue(other.listCodeSigningConfigs(request -> request.maxItems(1)).codeSigningConfigs().isEmpty());
            assertTrue(region.listCodeSigningConfigs(request -> request.maxItems(1)).codeSigningConfigs().isEmpty());
            assertThrows(InvalidParameterValueException.class, () -> other.listCodeSigningConfigs(request -> request.marker(marker)));
            assertThrows(InvalidParameterValueException.class, () -> region.listCodeSigningConfigs(request -> request.marker(marker)));
        }
    }

    @Test
    void emptyListingHasNoMarker() {
        ListCodeSigningConfigsResponse page = client.listCodeSigningConfigs(request -> request.maxItems(1));
        assertTrue(page.codeSigningConfigs().isEmpty());
        assertNull(page.nextMarker());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10001", "2147483648", "4294967297", "1.5", "invalid"})
    void invalidPageSizesKeepAwsValidationErrors(String size) {
        given().header("Authorization", AUTH).queryParam("MaxItems", size).get(BASE)
                .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%%%", "not-a-marker", "AA", "1AA"})
    void malformedMarkersAreRejected(String marker) {
        assertThrows(InvalidParameterValueException.class, () -> client.listCodeSigningConfigs(request -> request.marker(marker)));
    }

    private void create(int count) {
        for (int i = 0; i < count; i++) {
            created.add(client.createCodeSigningConfig(request -> request.allowedPublishers(publishers -> publishers
                    .signingProfileVersionArns(PROFILE))).codeSigningConfig().codeSigningConfigArn());
        }
    }

    private List<String> arns(ListCodeSigningConfigsResponse page) {
        return page.codeSigningConfigs().stream().map(CodeSigningConfig::codeSigningConfigArn).toList();
    }
}
