package io.github.hectorvent.floci.services.glue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetSecurityConfigurationsResponse;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.SecurityConfiguration;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GlueSecurityConfigurationPaginationIntegrationTest {

    @Test
    void sdkPagesConfigurationsInNameOrderWithinTheAccountAndRegion() {
        String prefix = "page-" + UUID.randomUUID();
        List<String> names = List.of(prefix + "-a", prefix + "-b", prefix + "-c");
        try (GlueClient client = client("741648700001", Region.US_EAST_1);
                GlueClient foreignAccount = client("741648700002", Region.US_EAST_1);
                GlueClient foreignRegion = client("741648700001", Region.US_WEST_2)) {
            for (String name : names.reversed()) {
                client.createSecurityConfiguration(request -> request.name(name).encryptionConfiguration(builder -> {}));
            }
            foreignAccount.createSecurityConfiguration(request -> request.name(prefix + "-0")
                    .encryptionConfiguration(builder -> {}));
            foreignRegion.createSecurityConfiguration(request -> request.name(prefix + "-0")
                    .encryptionConfiguration(builder -> {}));
            try {
                assertEquals(names, client.getSecurityConfigurations(request -> {}).securityConfigurations().stream()
                        .map(SecurityConfiguration::name).sorted().toList());
                GetSecurityConfigurationsResponse first = client.getSecurityConfigurations(request -> request.maxResults(1));
                assertEquals(List.of(names.get(0)), first.securityConfigurations().stream()
                        .map(SecurityConfiguration::name).toList());
                assertNotNull(first.nextToken());
                GetSecurityConfigurationsResponse second = client.getSecurityConfigurations(request -> request
                        .maxResults(1).nextToken(first.nextToken()));
                assertEquals(List.of(names.get(1)), second.securityConfigurations().stream()
                        .map(SecurityConfiguration::name).toList());
                assertNotNull(second.nextToken());
                GetSecurityConfigurationsResponse last = client.getSecurityConfigurations(request -> request
                        .maxResults(1).nextToken(second.nextToken()));
                assertEquals(List.of(names.get(2)), last.securityConfigurations().stream()
                        .map(SecurityConfiguration::name).toList());
                assertNull(last.nextToken());
                assertEquals(names, client.getSecurityConfigurationsPaginator(request -> request.maxResults(2))
                        .securityConfigurations().stream().map(SecurityConfiguration::name).toList());
            } finally {
                names.forEach(name -> client.deleteSecurityConfiguration(request -> request.name(name)));
                foreignAccount.deleteSecurityConfiguration(request -> request.name(prefix + "-0"));
                foreignRegion.deleteSecurityConfiguration(request -> request.name(prefix + "-0"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 201})
    void sdkRejectsPageSizesOutsideTheDocumentedRange(int maxResults) {
        try (GlueClient client = client("741648700003", Region.US_EAST_1)) {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getSecurityConfigurations(request -> request.maxResults(maxResults)));
            assertEquals(400, failure.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "-1", "2147483647"})
    void sdkRejectsMalformedOrOutOfRangeTokens(String token) {
        try (GlueClient client = client("741648700003", Region.US_EAST_1)) {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getSecurityConfigurations(request -> request.nextToken(token)));
            assertEquals(400, failure.statusCode());
        }
    }

    @Test
    void sdkReadsAnEmptyPageAtTheMaximumPageSizeWithoutAToken() {
        try (GlueClient client = client("741648700003", Region.US_EAST_1)) {
            GetSecurityConfigurationsResponse response = client.getSecurityConfigurations(request -> request.maxResults(200));
            assertTrue(response.securityConfigurations().isEmpty());
            assertNull(response.nextToken());
        }
    }

    private static GlueClient client(String account, Region region) {
        return GlueClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
