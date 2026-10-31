package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for SES v2 ListRecommendations, derived from the verified domains of the region
 * and the TXT records their Route53 zones hold. The region is one no other test uses, so Virtual
 * Deliverability Manager is off in it until a test enables it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesRecommendationV2IntegrationTest {

    private static final String REGION = "eu-south-2";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + REGION + "/ses/aws4_request";
    private static final String DOMAIN = "recommend-it.floci.test";
    private static final String ARN = "arn:aws:ses:" + REGION + ":000000000000:identity/" + DOMAIN;
    private static final String PATH = "/v2/email/vdm/recommendations";

    private static String zoneId;

    private static RequestSpecification v2() {
        return given().contentType("application/json").header("Authorization", AUTH);
    }

    private static void addTxtRecord(String name, String value) {
        given()
            .contentType("application/xml")
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>%s</Name>
                          <Type>TXT</Type>
                          <TTL>300</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>"%s"</Value></ResourceRecord>
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(name, value))
        .when()
            .post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(1)
    void emptyWithoutIdentities() {
        v2().body("{}")
                .when().post(PATH).then().statusCode(200)
                .body("Recommendations", empty())
                .body("$", hasKey("NextToken"))
                .body("NextToken", nullValue());
    }

    @Test
    @Order(2)
    void pendingDomainIsNotAdvised() {
        v2().body("{\"EmailIdentity\":\"pending-" + DOMAIN + "\"}")
                .when().post("/v2/email/identities").then().statusCode(200);
        v2().body("{}").when().post(PATH).then().statusCode(200).body("Recommendations", empty());
    }

    @Test
    @Order(3)
    void verifiedDomainWithoutRecordsGetsBimiAndDmarcOnceVdmIsEnabled() {
        zoneId = SesDomainIdentityTestHelper.createVerified(DOMAIN, AUTH);
        v2().body("{}").when().post(PATH).then().statusCode(200).body("Recommendations", empty());
        v2().body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\"}}")
                .when().put("/v2/email/account/vdm").then().statusCode(200);
        v2().body("{}")
                .when().post(PATH).then().statusCode(200)
                .body("Recommendations.Description", contains("BIMI1", "DMARC1"))
                .body("Recommendations.Type", contains("BIMI", "DMARC"))
                .body("Recommendations.Impact", contains("LOW", "HIGH"))
                .body("Recommendations.Status", everyItem(equalTo("OPEN")))
                .body("Recommendations.ResourceArn", everyItem(equalTo(ARN)))
                .body("Recommendations[0].CreatedTimestamp", instanceOf(Number.class))
                .body("Recommendations[0].LastUpdatedTimestamp", notNullValue());
    }

    @Test
    @Order(4)
    void publishedTxtRecordsClearTheirFindings() {
        addTxtRecord("_dmarc." + DOMAIN, "v=DMARC1; p=none");
        v2().body("{\"Filter\":{\"RESOURCE_ARN\":\"" + ARN + "\"}}")
                .when().post(PATH).then().statusCode(200)
                .body("Recommendations.Description", contains("BIMI1"));
        addTxtRecord("default._bimi." + DOMAIN, "v=BIMI1; l=https://example.com/logo.svg");
        v2().body("{}").when().post(PATH).then().statusCode(200).body("Recommendations", empty());
    }

    @Test
    @Order(5)
    void disabledSigningIsDkim1() {
        v2().body("{\"SigningEnabled\":false}")
                .when().put("/v2/email/identities/" + DOMAIN + "/dkim").then().statusCode(200);
        v2().body("{}")
                .when().post(PATH).then().statusCode(200)
                .body("Recommendations.Description", contains("DKIM1"))
                .body("Recommendations[0].Impact", equalTo("HIGH"));
    }

    @Test
    @Order(6)
    void shortEasyDkimKeyIsDkim3() {
        v2().body("{\"SigningEnabled\":true}")
                .when().put("/v2/email/identities/" + DOMAIN + "/dkim").then().statusCode(200);
        v2().body("{\"SigningAttributesOrigin\":\"AWS_SES\","
                        + "\"SigningAttributes\":{\"NextSigningKeyLength\":\"RSA_1024_BIT\"}}")
                .when().put("/v2/email/identities/" + DOMAIN + "/dkim/signing").then().statusCode(200);
        v2().body("{\"Filter\":{\"TYPE\":\"DKIM\",\"IMPACT\":\"HIGH\"}}")
                .when().post(PATH).then().statusCode(200)
                .body("Recommendations.Description", contains("DKIM3"))
                .body("Recommendations[0].Impact", equalTo("LOW"));
    }

    @Test
    @Order(7)
    void filterSizeIsBoundedByTheModel() {
        v2().body("{\"Filter\":{}}")
                .when().post(PATH).then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Member must have length greater than or equal to 1"));
        v2().body("{\"Filter\":{\"TYPE\":\"DKIM\",\"IMPACT\":\"LOW\",\"STATUS\":\"OPEN\"}}")
                .when().post(PATH).then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'filter' failed to satisfy "
                        + "constraint: Member must have length less than or equal to 2"));
    }

    @Test
    @Order(8)
    void invalidFilterValuesAndPaging() {
        assertBadRequest("{\"Filter\":{\"TYPE\":\"NOPE\"}}", "Invalid recommendation TYPE specified");
        assertBadRequest("{\"Filter\":{\"RESOURCE_ARN\":\"not-an-arn\"}}", "Filter resource is invalid");
        assertBadRequest("{\"Filter\":{\"RESOURCE_ARN\":\"arn:aws:ses:" + REGION + ":111111111111:identity/x.test\"}}",
                "Provided resource ARN does not match the caller account");
        assertBadRequest("{\"Filter\":{\"RESOURCE_ARN\":\"" + "a".repeat(513) + "\"}}",
                "1 validation error detected: Value at 'filter' failed to satisfy constraint: Map value must "
                        + "satisfy constraint: [Member must have length less than or equal to 512, "
                        + "Member must have length greater than or equal to 1]");
        assertBadRequest("{\"PageSize\":0}", "The PageSize should be between 1 and 1000");
        assertBadRequest("{\"NextToken\":\"garbage\"}", "Failed to deserialize token.");
    }

    private static void assertBadRequest(String body, String message) {
        v2().body(body)
                .when().post(PATH).then().statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo(message));
    }
}
