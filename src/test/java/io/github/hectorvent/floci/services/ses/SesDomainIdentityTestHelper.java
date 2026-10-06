package io.github.hectorvent.floci.services.ses;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * Creates a domain identity that is verified for sending. A domain identity stays Pending until
 * its DKIM CNAME records exist in a Route 53 hosted zone, so this publishes them in a zone of its
 * own.
 */
final class SesDomainIdentityTestHelper {

    private SesDomainIdentityTestHelper() {
    }

    static void createVerified(String domain, String sesAuthorization) {
        List<String> tokens = given()
            .contentType("application/json")
            .header("Authorization", sesAuthorization)
            .body("{\"EmailIdentity\": \"" + domain + "\"}")
        .when()
            .post("/v2/email/identities")
        .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("DkimAttributes.Tokens", String.class);

        String location = given()
            .contentType("application/xml")
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                </CreateHostedZoneRequest>
                """.formatted(domain, UUID.randomUUID()))
        .when()
            .post("/2013-04-01/hostedzone")
        .then()
            .statusCode(201)
            .extract()
            .header("Location");

        StringBuilder changes = new StringBuilder();
        for (String token : tokens) {
            changes.append("""
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>%s._domainkey.%s.</Name>
                          <Type>CNAME</Type>
                          <TTL>300</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>%s.dkim.amazonses.com.</Value></ResourceRecord>
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    """.formatted(token, domain, token));
        }
        given()
            .contentType("application/xml")
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                %s    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(changes))
        .when()
            .post("/2013-04-01/hostedzone/" + location.substring(location.lastIndexOf('/') + 1) + "/rrset")
        .then()
            .statusCode(200);
    }
}
