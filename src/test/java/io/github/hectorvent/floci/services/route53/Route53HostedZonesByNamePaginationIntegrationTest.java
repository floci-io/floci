package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ListHostedZonesByNameResponse;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53HostedZonesByNamePaginationIntegrationTest {

    @Test
    void awsSdkContinuesDuplicateNamesWithBothCursorFields() {
        String name = "byname-sdk-" + UUID.randomUUID() + ".example.test.";
        String firstId = Route53HostedZoneTestSupport.createZone(name, "byname-sdk-first-" + name);
        String secondId = null;
        try {
            secondId = Route53HostedZoneTestSupport.createZone(name, "byname-sdk-second-" + name);
            try (Route53Client client = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1)) {
                ListHostedZonesByNameResponse firstPage = client.listHostedZonesByName(request -> request
                        .dnsName(name).maxItems("1"));
                assertEquals(1, firstPage.hostedZones().size());
                String returnedId = firstPage.hostedZones().getFirst().id();
                assertTrue(returnedId.equals("/hostedzone/" + firstId)
                        || returnedId.equals("/hostedzone/" + secondId));
                String expectedNextId = returnedId.equals("/hostedzone/" + firstId) ? secondId : firstId;
                assertTrue(firstPage.isTruncated());
                assertEquals(name, firstPage.nextDNSName());
                assertEquals(expectedNextId, firstPage.nextHostedZoneId());

                ListHostedZonesByNameResponse secondPage = client.listHostedZonesByName(request -> request
                        .dnsName(firstPage.nextDNSName())
                        .hostedZoneId(firstPage.nextHostedZoneId())
                        .maxItems("1"));
                assertEquals(1, secondPage.hostedZones().size());
                assertEquals("/hostedzone/" + expectedNextId, secondPage.hostedZones().getFirst().id());
                assertEquals(name, secondPage.dnsName());
                assertEquals(expectedNextId, secondPage.hostedZoneId());
            }
        } finally {
            if (secondId != null) {
                given().when().delete("/2013-04-01/hostedzone/" + secondId).then().statusCode(200);
            }
            given().when().delete("/2013-04-01/hostedzone/" + firstId).then().statusCode(200);
        }
    }

    @Test
    void duplicateNamesContinueFromTheNextZoneId() {
        String name = "byname-" + UUID.randomUUID() + ".example.test.";
        String firstId = Route53HostedZoneTestSupport.createZone(name, "byname-first-" + name);
        String secondId = null;
        try {
            secondId = Route53HostedZoneTestSupport.createZone(name, "byname-second-" + name);
            XmlPath firstPage = given()
                    .queryParam("dnsname", name)
                    .queryParam("maxitems", 1)
                    .when().get("/2013-04-01/hostedzonesbyname")
                    .then().statusCode(200).extract().xmlPath();

            String returnedId = firstPage.getString("ListHostedZonesByNameResponse.HostedZones.HostedZone.Id");
            String expectedNextId = returnedId.equals("/hostedzone/" + firstId) ? secondId : firstId;
            assertTrue(firstPage.getBoolean("ListHostedZonesByNameResponse.IsTruncated"));
            assertEquals(name, firstPage.getString("ListHostedZonesByNameResponse.NextDNSName"));
            assertEquals(expectedNextId,
                    firstPage.getString("ListHostedZonesByNameResponse.NextHostedZoneId"));

            XmlPath secondPage = given()
                    .queryParam("dnsname", firstPage.getString("ListHostedZonesByNameResponse.NextDNSName"))
                    .queryParam("hostedzoneid", firstPage.getString("ListHostedZonesByNameResponse.NextHostedZoneId"))
                    .queryParam("maxitems", 1)
                    .when().get("/2013-04-01/hostedzonesbyname")
                    .then().statusCode(200).extract().xmlPath();

            assertEquals("/hostedzone/" + expectedNextId,
                    secondPage.getString("ListHostedZonesByNameResponse.HostedZones.HostedZone.Id"));
            assertEquals(name, secondPage.getString("ListHostedZonesByNameResponse.DNSName"));
            assertEquals(expectedNextId, secondPage.getString("ListHostedZonesByNameResponse.HostedZoneId"));
            given().queryParam("maxitems", 0)
                    .when().get("/2013-04-01/hostedzonesbyname")
                    .then().statusCode(400);
        } finally {
            if (secondId != null) {
                given().when().delete("/2013-04-01/hostedzone/" + secondId).then().statusCode(200);
            }
            given().when().delete("/2013-04-01/hostedzone/" + firstId).then().statusCode(200);
        }
    }
}
