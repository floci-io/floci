package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ListHealthChecksResponse;

import java.util.List;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53HealthChecksPaginationIntegrationTest {

    @Inject
    Route53Service service;

    @Test
    void listHealthChecksUsesTheMarkerItemAndNextMarker() {
        List<String> createdIds = createHealthChecks(2);
        try {
            List<HealthCheck> allChecks = service.listHealthChecks(null, Integer.MAX_VALUE);
            assertTrue(allChecks.size() >= 2);
            String marker = allChecks.get(allChecks.size() - 2).getId();
            String lastId = allChecks.getLast().getId();

            XmlPath penultimatePage = given()
                    .queryParam("marker", marker)
                    .queryParam("maxitems", 1)
                    .when().get("/2013-04-01/healthcheck")
                    .then().statusCode(200)
                    .extract().xmlPath();

            assertEquals(marker,
                    penultimatePage.getString("ListHealthChecksResponse.HealthChecks.HealthCheck.Id"));
            assertEquals(marker, penultimatePage.getString("ListHealthChecksResponse.Marker"));
            assertTrue(penultimatePage.getBoolean("ListHealthChecksResponse.IsTruncated"));
            assertEquals(lastId, penultimatePage.getString("ListHealthChecksResponse.NextMarker"));

            String lastPageBody = given()
                    .queryParam("marker", lastId)
                    .queryParam("maxitems", 1)
                    .when().get("/2013-04-01/healthcheck")
                    .then().statusCode(200)
                    .extract().body().asString();
            XmlPath lastPage = new XmlPath(lastPageBody);

            assertEquals(lastId,
                    lastPage.getString("ListHealthChecksResponse.HealthChecks.HealthCheck.Id"));
            assertEquals(lastId, lastPage.getString("ListHealthChecksResponse.Marker"));
            assertFalse(lastPage.getBoolean("ListHealthChecksResponse.IsTruncated"));
            assertFalse(lastPageBody.contains("<NextMarker>"));
        } finally {
            createdIds.forEach(service::deleteHealthCheck);
        }
    }

    @Test
    void awsSdkPaginatesListHealthChecks() {
        List<String> createdIds = createHealthChecks(2);
        try {
            List<String> expected = service.listHealthChecks(null, Integer.MAX_VALUE).stream()
                    .map(HealthCheck::getId)
                    .toList();
            assertEquals(expected.stream().sorted().toList(), expected);
            try (Route53Client client = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1)) {
                List<ListHealthChecksResponse> pages = client.listHealthChecksPaginator(request -> request.maxItems("1"))
                        .stream().toList();
                assertEquals(expected.size(), pages.stream().mapToInt(page -> page.healthChecks().size()).sum());
                for (int i = 0; i < pages.size(); i++) {
                    ListHealthChecksResponse page = pages.get(i);
                    boolean hasNext = i + 1 < pages.size();
                    assertEquals(1, page.healthChecks().size());
                    assertEquals(expected.get(i), page.healthChecks().getFirst().id());
                    assertEquals(hasNext, page.isTruncated());
                    assertEquals(hasNext ? expected.get(i + 1) : null, page.nextMarker());
                }
            }
        } finally {
            createdIds.forEach(service::deleteHealthCheck);
        }
    }

    @Test
    void listHealthChecksCapsOversizedPagesAtOneThousand() {
        List<String> createdIds = createHealthChecks(1001);
        try {
            List<String> expected = service.listHealthChecks(null, Integer.MAX_VALUE).stream()
                    .map(HealthCheck::getId)
                    .toList();
            XmlPath page = given()
                    .queryParam("maxitems", 2000)
                    .when().get("/2013-04-01/healthcheck")
                    .then().statusCode(200)
                    .extract().xmlPath();

            List<String> returnedIds = page.getList("ListHealthChecksResponse.HealthChecks.HealthCheck.Id");
            assertEquals(1000, returnedIds.size());
            IntStream.range(0, 1000).forEach(index ->
                    assertEquals(expected.get(index), returnedIds.get(index), "health check at index " + index));
            assertTrue(page.getBoolean("ListHealthChecksResponse.IsTruncated"));
            assertEquals(expected.get(1000), page.getString("ListHealthChecksResponse.NextMarker"));
            assertEquals("2000", page.getString("ListHealthChecksResponse.MaxItems"));
        } finally {
            createdIds.forEach(service::deleteHealthCheck);
        }
    }

    private List<String> createHealthChecks(int count) {
        HealthCheckConfig config = new HealthCheckConfig();
        config.setType("HTTPS");
        config.setFullyQualifiedDomainName("example.com");
        config.setPort(443);
        config.setResourcePath("/health");
        config.setRequestInterval(30);
        config.setFailureThreshold(3);
        return IntStream.range(0, count)
                .mapToObj(index -> service.createHealthCheck(
                        "pagination-" + System.nanoTime() + "-" + index,
                        config,
                        "ResourcePath=/health"))
                .map(HealthCheck::getId)
                .toList();
    }
}
