package io.floci.aws.services.opensearch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.opensearch.model.Domain;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.opensearch.OpenSearchClient;
import software.amazon.awssdk.services.opensearch.model.DomainStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #5664: an OpenSearch domain comes back after Floci restarts with the documents it held. Shutdown removes the
 * domain's container; the startup restore starts a new one on the domain's data, so a document indexed before is still
 * there. Removing the container stands in for the shutdown, reloading the domain records from disk for what a
 * restart reads back, and the restore is what startup calls.
 *
 * <p>A nightly check: it runs a real OpenSearch node, which the shared test configuration mocks, with the domain
 * records on disk, which it keeps in memory.
 */
@QuarkusTest
@TestProfile(OpenSearchDomainRestartNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
// Bounded waits: the OpenSearch image's pull, then the node starting twice.
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class OpenSearchDomainRestartNightlyIntegrationTest {

    /** Real OpenSearch nodes, their domain records on disk. */
    public static final class Profile implements QuarkusTestProfile {
        // Its own directory, so the restore finds no domain records but this test's.
        private static final String DATA = "./target/test-data/opensearch-restart-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.opensearch.mock", "false",
                    "floci.storage.services.opensearch.mode", "persistent",
                    "floci.storage.persistent-path", DATA);
        }
    }

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String DOCUMENT = "/restart-check/_doc/1";
    private static final Duration DOMAIN_TIMEOUT = Duration.ofMinutes(4);

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Inject
    DockerClient dockerClient;

    @Inject
    OpenSearchService openSearchService;

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for the OpenSearch restart check");
    }

    @Test
    void aDomainKeepsItsDocumentsAcrossARestart() throws Exception {
        String name = "restart-" + UUID.randomUUID().toString().substring(0, 8);
        try (OpenSearchClient client = AwsTestClients.create(OpenSearchClient.builder(), ACCOUNT, Region.of(REGION))) {
            client.createDomain(request -> request.domainName(name).engineVersion(OpenSearchVersions.DEFAULT_VERSION));
            try {
                String endpoint = awaitReady(client, name);
                assertEquals(201, send(endpoint, "PUT", DOCUMENT + "?refresh=true", "{\"kept\":true}").statusCode());
                Domain running = openSearchService.describeDomain(name, REGION);
                String container = running.getContainerId();

                dockerClient.removeContainerCmd(container).withForce(true).exec();
                // What a restart reads back: the records on disk, not the ones in memory.
                storageFactory.create("opensearch", "opensearch-domains.json",
                        new TypeReference<Map<String, Domain>>() {}).load();
                Domain reloaded = openSearchService.describeDomain(name, REGION);
                assertNotSame(running, reloaded);
                assertEquals(container, reloaded.getContainerId());
                openSearchService.restorePersistedRuntime().join();

                String restored = awaitReady(client, name);
                assertNotEquals(container, openSearchService.describeDomain(name, REGION).getContainerId());
                HttpResponse<String> document = send(restored, "GET", DOCUMENT, null);
                assertEquals(200, document.statusCode());
                assertTrue(document.body().contains("\"kept\":true"), document.body());
            } finally {
                client.deleteDomain(request -> request.domainName(name));
            }
        }
    }

    /** Waits for the domain to stop processing and returns its endpoint as DescribeDomain reports it. */
    private static String awaitReady(OpenSearchClient client, String name) {
        await("domain " + name + " ready").atMost(DOMAIN_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> !describe(client, name).processing());
        return "http://" + describe(client, name).endpoint();
    }

    private static DomainStatus describe(OpenSearchClient client, String name) {
        return client.describeDomain(request -> request.domainName(name)).domainStatus();
    }

    private HttpResponse<String> send(String endpoint, String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
