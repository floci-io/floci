package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ListHostedZonesResponse;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53HostedZonesPaginationIntegrationTest {

    @Inject
    Route53Service service;

    @Test
    void listHostedZonesUsesTheNextPagesFirstZoneAsMarker() {
        String firstZoneId = createZone("pagination-first.example.com.");
        String secondZoneId = null;

        try {
            secondZoneId = createZone("pagination-second.example.com.");
            List<HostedZone> allZones = service.listHostedZones(null, Integer.MAX_VALUE);
            List<String> allZoneIds = allZones.stream().map(HostedZone::getId).toList();
            assertTrue(allZoneIds.contains(firstZoneId));
            assertTrue(allZoneIds.contains(secondZoneId));

            XmlPath initialPage = given()
                    .queryParam("maxitems", 1)
                    .when().get("/2013-04-01/hostedzone")
                    .then().statusCode(200)
                    .extract().xmlPath();

            assertEquals("/hostedzone/" + allZones.getFirst().getId(),
                    initialPage.getString("ListHostedZonesResponse.HostedZones.HostedZone.Id"));
            assertTrue(initialPage.getBoolean("ListHostedZonesResponse.IsTruncated"));
            assertEquals(allZones.get(1).getId(), initialPage.getString("ListHostedZonesResponse.NextMarker"));

            String penultimateId = allZones.get(allZones.size() - 2).getId();
            String lastId = allZones.getLast().getId();

            XmlPath firstPage = given()
                    .queryParam("marker", penultimateId)
                    .queryParam("maxitems", 1)
                    .when().get("/2013-04-01/hostedzone")
                    .then().statusCode(200)
                    .extract().xmlPath();

            assertEquals("/hostedzone/" + penultimateId,
                    firstPage.getString("ListHostedZonesResponse.HostedZones.HostedZone.Id"));
            assertEquals(penultimateId, firstPage.getString("ListHostedZonesResponse.Marker"));
            assertTrue(firstPage.getBoolean("ListHostedZonesResponse.IsTruncated"));
            assertEquals(lastId, firstPage.getString("ListHostedZonesResponse.NextMarker"));

            String lastPageBody = given()
                    .queryParam("marker", firstPage.getString("ListHostedZonesResponse.NextMarker"))
                    .queryParam("maxitems", 1)
                    .when().get("/2013-04-01/hostedzone")
                    .then().statusCode(200)
                    .extract().body().asString();
            XmlPath lastPage = new XmlPath(lastPageBody);

            assertEquals("/hostedzone/" + lastId,
                    lastPage.getString("ListHostedZonesResponse.HostedZones.HostedZone.Id"));
            assertEquals(lastId, lastPage.getString("ListHostedZonesResponse.Marker"));
            assertFalse(lastPage.getBoolean("ListHostedZonesResponse.IsTruncated"));
            assertFalse(lastPageBody.contains("<NextMarker>"));
        } finally {
            if (secondZoneId != null) {
                given().when().delete("/2013-04-01/hostedzone/" + secondZoneId).then().statusCode(200);
            }
            given().when().delete("/2013-04-01/hostedzone/" + firstZoneId).then().statusCode(200);
        }
    }

    @Test
    void awsSdkPaginatesListHostedZonesWithStableDuplicateNames() {
        String firstZoneId = createZone("sdk-pagination.example.com.");
        String secondZoneId = null;

        try {
            secondZoneId = createZone("sdk-pagination.example.com.");
            String createdSecondZoneId = secondZoneId;
            List<HostedZone> expected = service.listHostedZones(null, Integer.MAX_VALUE);
            assertEquals(List.of(firstZoneId, secondZoneId).stream().sorted().toList(),
                    expected.stream().map(HostedZone::getId)
                            .filter(id -> id.equals(firstZoneId) || id.equals(createdSecondZoneId)).toList());
            try (Route53Client client = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1)) {
                List<ListHostedZonesResponse> pages = client.listHostedZonesPaginator(request -> request.maxItems("1"))
                        .stream().toList();
                assertEquals(expected.size(), pages.size());
                for (int i = 0; i < pages.size(); i++) {
                    ListHostedZonesResponse page = pages.get(i);
                    assertEquals(1, page.hostedZones().size());
                    assertEquals(expected.get(i).getId(), page.hostedZones().getFirst().id());
                    boolean hasNext = i + 1 < pages.size();
                    assertEquals(hasNext, page.isTruncated());
                    assertEquals(hasNext ? expected.get(i + 1).getId() : null, page.nextMarker());
                }
            }
        } finally {
            if (secondZoneId != null) {
                given().when().delete("/2013-04-01/hostedzone/" + secondZoneId).then().statusCode(200);
            }
            given().when().delete("/2013-04-01/hostedzone/" + firstZoneId).then().statusCode(200);
        }
    }

    private String createZone(String name) {
        return Route53HostedZoneTestSupport.createZone(name, "pagination-" + name + "-" + System.nanoTime());
    }
}
