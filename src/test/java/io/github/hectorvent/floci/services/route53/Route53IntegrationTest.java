package io.github.hectorvent.floci.services.route53;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Route53IntegrationTest {

    private static final String XML = "application/xml";

    private static String zoneId;
    private static String changeId;
    private static String healthCheckId;

    // ── Hosted Zones ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createHostedZone_returns201WithLocation() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>example.com</Name>
                  <CallerReference>ref-001</CallerReference>
                  <HostedZoneConfig>
                    <Comment>test zone</Comment>
                    <PrivateZone>false</PrivateZone>
                  </HostedZoneConfig>
                </CreateHostedZoneRequest>
                """;

        String locationHeader = given()
                .contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone")
                .then()
                .statusCode(201)
                .contentType(XML)
                .header("Location", containsString("/2013-04-01/hostedzone/Z"))
                .body("CreateHostedZoneResponse.HostedZone.Name", equalTo("example.com."))
                .body("CreateHostedZoneResponse.HostedZone.Id", startsWith("/hostedzone/Z"))
                .body("CreateHostedZoneResponse.HostedZone.ResourceRecordSetCount", equalTo("2"))
                .body("CreateHostedZoneResponse.ChangeInfo.Status", equalTo("INSYNC"))
                .body("CreateHostedZoneResponse.ChangeInfo.Id", startsWith("/change/C"))
                .body(containsString("ns-1.awsdns-01.org"))
                .extract().header("Location");

        // Location is an absolute URL: http://localhost:PORT/2013-04-01/hostedzone/ZXXX
        zoneId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
    }

    @Test
    @Order(2)
    void getHostedZone_returnsCreatedZone() {
        given()
                .when().get("/2013-04-01/hostedzone/" + zoneId)
                .then()
                .statusCode(200)
                .contentType(XML)
                .body("GetHostedZoneResponse.HostedZone.Id", equalTo("/hostedzone/" + zoneId))
                .body("GetHostedZoneResponse.HostedZone.Name", equalTo("example.com."))
                .body(containsString("ns-1.awsdns-01.org"));
    }

    @Test
    @Order(3)
    void listHostedZones_includesCreatedZone() {
        given()
                .when().get("/2013-04-01/hostedzone")
                .then()
                .statusCode(200)
                .contentType(XML)
                .body("ListHostedZonesResponse.IsTruncated", equalTo("false"))
                .body(containsString("/hostedzone/" + zoneId));
    }

    @Test
    @Order(4)
    void listHostedZonesByName_returnsZone() {
        given()
                .queryParam("dnsname", "example.com.")
                .when().get("/2013-04-01/hostedzonesbyname")
                .then()
                .statusCode(200)
                .contentType(XML)
                .body(containsString("example.com."));
    }

    @Test
    @Order(5)
    void getHostedZoneCount_includesZone() {
        given()
                .when().get("/2013-04-01/hostedzonecount")
                .then()
                .statusCode(200)
                .body("GetHostedZoneCountResponse.HostedZoneCount", not(equalTo("0")));
    }

    // ── Resource Record Sets ──────────────────────────────────────────────────

    @Test
    @Order(6)
    void listResourceRecordSets_autoCreatedSOAandNS() {
        String body = given()
                .when().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(200)
                .contentType(XML)
                .body("ListResourceRecordSetsResponse.IsTruncated", equalTo("false"))
                .extract().body().asString();

        assertThat(body, containsString("<Type>SOA</Type>"));
        assertThat(body, containsString("<Type>NS</Type>"));
    }

    @Test
    @Order(7)
    void changeResourceRecordSets_createARecord() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>www.example.com.</Name>
                          <Type>A</Type>
                          <TTL>300</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>1.2.3.4</Value></ResourceRecord>
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;

        String responseBody = given()
                .contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(200)
                .contentType(XML)
                .body("ChangeResourceRecordSetsResponse.ChangeInfo.Status", equalTo("INSYNC"))
                .body("ChangeResourceRecordSetsResponse.ChangeInfo.Id", startsWith("/change/C"))
                .extract().body().asString();

        // Extract change ID for getChange test
        int start = responseBody.indexOf("/change/") + 8;
        int end = responseBody.indexOf("</Id>", start);
        if (start > 8 && end > start) {
            changeId = responseBody.substring(start, end);
        }
    }

    @Test
    @Order(8)
    void listResourceRecordSets_includesARecord() {
        String body = given()
                .when().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body, containsString("<Type>A</Type>"));
        assertThat(body, containsString("<Value>1.2.3.4</Value>"));
    }

    @Test
    @Order(9)
    void changeResourceRecordSets_deleteSOA_fails() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>DELETE</Action>
                        <ResourceRecordSet>
                          <Name>example.com.</Name>
                          <Type>SOA</Type>
                          <TTL>900</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>ns-1.awsdns-01.org. awsdns-hostmaster.amazon.com. 1 7200 900 1209600 86400</Value></ResourceRecord>
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;

        given()
                .contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidChangeBatch"));
    }

    @Test
    @Order(10)
    void getChange_returnsInsync() {
        if (changeId == null) return;
        given()
                .when().get("/2013-04-01/change/" + changeId)
                .then()
                .statusCode(200)
                .body("GetChangeResponse.ChangeInfo.Status", equalTo("INSYNC"))
                .body("GetChangeResponse.ChangeInfo.Id", equalTo("/change/" + changeId));
    }

    @Test
    @Order(11)
    void changeResourceRecordSets_deleteARecord() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>DELETE</Action>
                        <ResourceRecordSet>
                          <Name>www.example.com.</Name>
                          <Type>A</Type>
                          <TTL>300</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>1.2.3.4</Value></ResourceRecord>
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;

        given()
                .contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(200)
                .body("ChangeResourceRecordSetsResponse.ChangeInfo.Status", equalTo("INSYNC"));
    }

    @Test
    @Order(12)
    void deleteHostedZone_failsWhenNonDefaultRecordsExist() {
        String createBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>nonempty.example.com</Name>
                  <CallerReference>ref-nonempty</CallerReference>
                </CreateHostedZoneRequest>
                """;

        String loc = given()
                .contentType(XML).body(createBody)
                .when().post("/2013-04-01/hostedzone")
                .then().statusCode(201)
                .extract().header("Location");
        String tmpId = loc.substring(loc.lastIndexOf('/') + 1);

        String addRecord = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>sub.nonempty.example.com.</Name>
                          <Type>A</Type>
                          <TTL>60</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>10.0.0.1</Value></ResourceRecord>
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;
        given().contentType(XML).body(addRecord)
               .post("/2013-04-01/hostedzone/" + tmpId + "/rrset")
               .then().statusCode(200);

        given()
                .when().delete("/2013-04-01/hostedzone/" + tmpId)
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("HostedZoneNotEmpty"));

        // Cleanup
        String deleteRecord = addRecord.replace("<Action>CREATE</Action>", "<Action>DELETE</Action>");
        given().contentType(XML).body(deleteRecord)
               .post("/2013-04-01/hostedzone/" + tmpId + "/rrset")
               .then().statusCode(200);
        given().delete("/2013-04-01/hostedzone/" + tmpId).then().statusCode(200);
    }

    @Test
    @Order(13)
    void deleteHostedZone_succeedsAfterRecordsRemoved() {
        given()
                .when().delete("/2013-04-01/hostedzone/" + zoneId)
                .then()
                .statusCode(200)
                .body("DeleteHostedZoneResponse.ChangeInfo.Status", equalTo("INSYNC"));
    }

    @Test
    @Order(14)
    void getHostedZone_returns404AfterDelete() {
        given()
                .when().get("/2013-04-01/hostedzone/" + zoneId)
                .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchHostedZone"));
    }

    // ── Health Checks ─────────────────────────────────────────────────────────

    @Test
    @Order(15)
    void createHealthCheck_returns201() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHealthCheckRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <CallerReference>hc-ref-001</CallerReference>
                  <HealthCheckConfig>
                    <Type>HTTPS</Type>
                    <FullyQualifiedDomainName>example.com</FullyQualifiedDomainName>
                    <Port>443</Port>
                    <ResourcePath>/health</ResourcePath>
                    <RequestInterval>30</RequestInterval>
                    <FailureThreshold>3</FailureThreshold>
                  </HealthCheckConfig>
                </CreateHealthCheckRequest>
                """;

        String loc = given()
                .contentType(XML).body(body)
                .when().post("/2013-04-01/healthcheck")
                .then()
                .statusCode(201)
                .header("Location", containsString("/2013-04-01/healthcheck/"))
                .body("CreateHealthCheckResponse.HealthCheck.CallerReference", equalTo("hc-ref-001"))
                .body("CreateHealthCheckResponse.HealthCheck.HealthCheckConfig.Type", equalTo("HTTPS"))
                .body("CreateHealthCheckResponse.HealthCheck.HealthCheckVersion", equalTo("1"))
                .extract().header("Location");

        healthCheckId = loc.substring(loc.lastIndexOf('/') + 1);
    }

    @Test
    @Order(16)
    void getHealthCheck_returnsCreated() {
        given()
                .when().get("/2013-04-01/healthcheck/" + healthCheckId)
                .then()
                .statusCode(200)
                .body("GetHealthCheckResponse.HealthCheck.Id", equalTo(healthCheckId))
                .body("GetHealthCheckResponse.HealthCheck.HealthCheckConfig.Port", equalTo("443"));
    }

    @Test
    @Order(17)
    void listHealthChecks_includesCreated() {
        String body = given()
                .when().get("/2013-04-01/healthcheck")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body, containsString(healthCheckId));
    }

    @Test
    @Order(18)
    void deleteHealthCheck_returns200() {
        given()
                .when().delete("/2013-04-01/healthcheck/" + healthCheckId)
                .then()
                .statusCode(200);

        given()
                .when().get("/2013-04-01/healthcheck/" + healthCheckId)
                .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchHealthCheck"));
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    @Test
    @Order(19)
    void tagging_addListRemove() {
        String createBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>tagged.example.com</Name>
                  <CallerReference>ref-tagged</CallerReference>
                </CreateHostedZoneRequest>
                """;
        String loc = given()
                .contentType(XML).body(createBody)
                .when().post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location");
        String tagZoneId = loc.substring(loc.lastIndexOf('/') + 1);

        String addTagBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeTagsForResourceRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <AddTags>
                    <Tag><Key>env</Key><Value>test</Value></Tag>
                    <Tag><Key>owner</Key><Value>floci</Value></Tag>
                  </AddTags>
                </ChangeTagsForResourceRequest>
                """;
        given()
                .contentType(XML).body(addTagBody)
                .when().post("/2013-04-01/tags/hostedzone/" + tagZoneId)
                .then().statusCode(200);

        String listBody = given()
                .when().get("/2013-04-01/tags/hostedzone/" + tagZoneId)
                .then()
                .statusCode(200)
                .body("ListTagsForResourceResponse.ResourceTagSet.ResourceType", equalTo("hostedzone"))
                .body("ListTagsForResourceResponse.ResourceTagSet.ResourceId", equalTo(tagZoneId))
                .extract().body().asString();

        assertThat(listBody, containsString("<Key>env</Key>"));
        assertThat(listBody, containsString("<Key>owner</Key>"));

        String removeTagBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeTagsForResourceRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <RemoveTagKeys>
                    <Key>owner</Key>
                  </RemoveTagKeys>
                </ChangeTagsForResourceRequest>
                """;
        given()
                .contentType(XML).body(removeTagBody)
                .when().post("/2013-04-01/tags/hostedzone/" + tagZoneId)
                .then().statusCode(200);

        String afterRemove = given()
                .when().get("/2013-04-01/tags/hostedzone/" + tagZoneId)
                .then().statusCode(200)
                .extract().body().asString();

        assertThat(afterRemove, containsString("<Key>env</Key>"));
        assertThat(afterRemove, not(containsString("<Key>owner</Key>")));

        // Cleanup
        given().delete("/2013-04-01/hostedzone/" + tagZoneId).then().statusCode(200);
    }

    // ── Limits ────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void getAccountLimit_returnsValue() {
        given()
                .when().get("/2013-04-01/accountlimit/MAX_HOSTED_ZONES_BY_OWNER")
                .then()
                .statusCode(200)
                .body("GetAccountLimitResponse.Limit.Type", equalTo("MAX_HOSTED_ZONES_BY_OWNER"))
                .body("GetAccountLimitResponse.Limit.Value", equalTo("500"));
    }
}
