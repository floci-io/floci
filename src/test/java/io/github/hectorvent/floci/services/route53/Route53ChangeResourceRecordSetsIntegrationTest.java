package io.github.hectorvent.floci.services.route53;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class Route53ChangeResourceRecordSetsIntegrationTest {

    private static final String XML = "application/xml";

    @Test
    void malformedChangeBatchIsRejectedAsInvalidInput() {
        String zoneId = createZone("malformed.example.com.", "ref-malformed");

        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>www.malformed.example.com.</Name>
                          <Type>A
                """;

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));

        given().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then().statusCode(200)
                .body(not(containsString("www.malformed.example.com.")));
    }

    @Test
    void incompleteChangeMissingResourceRecordSetIsRejectedAsInvalidInput() {
        String zoneId = createZone("incomplete.example.com.", "ref-incomplete");

        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
    }

    @Test
    void unknownActionIsRejectedAsInvalidInput() {
        String zoneId = createZone("unknownaction.example.com.", "ref-unknown-action");

        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>REPLACE</Action>
                        <ResourceRecordSet>
                          <Name>www.unknownaction.example.com.</Name>
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

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));

        given().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then().statusCode(200)
                .body(not(containsString("www.unknownaction.example.com.")));
    }

    @Test
    void deleteWithMismatchedValueIsRejectedAsInvalidChangeBatchOnTheWire() {
        String zoneId = createZone("wiremismatch.example.com.", "ref-wire-mismatch");

        String create = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>www.wiremismatch.example.com.</Name>
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
        given().contentType(XML).body(create)
                .post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then().statusCode(200);

        String deleteWrongValue = create
                .replace("<Action>CREATE</Action>", "<Action>DELETE</Action>")
                .replace("<Value>1.2.3.4</Value>", "<Value>9.9.9.9</Value>");

        given().contentType(XML).body(deleteWrongValue)
                .post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidChangeBatch"));

        given().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then().statusCode(200)
                .body(containsString("<Value>1.2.3.4</Value>"));
    }

    @ParameterizedTest
    @MethodSource("malformedNumericFieldCases")
    void malformedNumericFieldIsRejectedAsInvalidInput(String elementName, String fieldXml) {
        String zoneId = createZone("badnum-" + elementName.toLowerCase() + ".example.com.",
                "ref-badnum-" + elementName);

        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>www.badnum.example.com.</Name>
                          <Type>A</Type>
                          %s
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(fieldXml);

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message", containsString("at '" + elementName + "'"));
    }

    private static Stream<Arguments> malformedNumericFieldCases() {
        return Stream.of(
                Arguments.of("TTL", "<TTL>not-a-number</TTL>"),
                Arguments.of("Weight", "<Weight>not-a-number</Weight>"));
    }

    @ParameterizedTest
    @MethodSource("missingRequiredElementCases")
    void resourceRecordSetMissingRequiredElementIsRejectedAsInvalidInput(String nameXml, String typeXml) {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          %s
                          %s
                          <TTL>300</TTL>
                          <ResourceRecords>
                            <ResourceRecord><Value>1.2.3.4</Value></ResourceRecord>
                          </ResourceRecords>
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(nameXml, typeXml);

        String zoneId = createZone("missing.example.com.", "ref-missing-" + nameXml.hashCode());

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message", containsString("missing a required Name or Type"));
    }

    private static Stream<Arguments> missingRequiredElementCases() {
        return Stream.of(
                Arguments.of("", "<Type>A</Type>"),
                Arguments.of("<Name>www.missing.example.com.</Name>", ""));
    }

    @Test
    void resourceRecordSetWithNeitherAliasTargetNorTtlAndRecordsIsRejectedAsInvalidInput() {
        String zoneId = createZone("www.example.com.", "ref-no-shape");

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
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "Invalid request: Expected exactly one of [AliasTarget, all of [TTL, and ResourceRecords], "
                                + "or TrafficPolicyInstanceId], but found none in Change with [Action=CREATE, "
                                + "Name=www.example.com., Type=A, SetIdentifier=null]"));
    }

    @Test
    void resourceRecordSetWithBothAliasTargetAndTtlAndRecordsIsRejectedAsInvalidInput() {
        String zoneId = createZone("bothshapes.example.com.", "ref-both-shapes");

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
                          <AliasTarget>
                            <HostedZoneId>Z1</HostedZoneId>
                            <DNSName>alias.example.com.</DNSName>
                            <EvaluateTargetHealth>false</EvaluateTargetHealth>
                          </AliasTarget>
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "Invalid request: Expected exactly one of [AliasTarget, all of [TTL, and ResourceRecords], "
                                + "or TrafficPolicyInstanceId], but found more than one in Change with "
                                + "[Action=CREATE, Name=www.example.com., Type=A, SetIdentifier=null]"));
    }

    @ParameterizedTest
    @MethodSource("recordShapeCases")
    void resourceRecordSetWithWrongNumberOfShapesIsRejectedAsInvalidInput(String caseName, String rrsFieldsXml,
                                                                           String expectedFound) {
        String zoneId = createZone("shape-" + caseName.toLowerCase() + ".example.com.", "ref-shape-" + caseName);

        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes>
                      <Change>
                        <Action>CREATE</Action>
                        <ResourceRecordSet>
                          <Name>www.shape.example.com.</Name>
                          <Type>A</Type>
                          %s
                        </ResourceRecordSet>
                      </Change>
                    </Changes>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(rrsFieldsXml);

        given().contentType(XML)
                .body(body)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "Invalid request: Expected exactly one of [AliasTarget, all of [TTL, and ResourceRecords], "
                                + "or TrafficPolicyInstanceId], but found " + expectedFound + " in Change with "
                                + "[Action=CREATE, Name=www.shape.example.com., Type=A, SetIdentifier=null]"));
    }

    private static Stream<Arguments> recordShapeCases() {
        String alias = """
                <AliasTarget>
                  <HostedZoneId>Z1</HostedZoneId>
                  <DNSName>alias.example.com.</DNSName>
                  <EvaluateTargetHealth>false</EvaluateTargetHealth>
                </AliasTarget>
                """;
        String ttl = "<TTL>300</TTL>";
        String records = """
                <ResourceRecords>
                  <ResourceRecord><Value>1.2.3.4</Value></ResourceRecord>
                </ResourceRecords>
                """;
        return Stream.of(
                Arguments.of("AliasAndResourceRecords", alias + records, "more than one"),
                Arguments.of("TtlOnly", ttl, "none"),
                Arguments.of("ResourceRecordsOnly", records, "none"));
    }

    @ParameterizedTest
    @MethodSource("emptyChangeBatchCases")
    void emptyChangeBatchIsRejectedAsInvalidInput(String caseName, String requestBody) {
        String zoneId = createZone("empty-" + caseName.toLowerCase() + ".example.com.", "ref-empty-" + caseName);

        given().contentType(XML)
                .body(requestBody)
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                .body("ErrorResponse.Error.Message", equalTo(
                        "Invalid XML ; cvc-complex-type.2.4.b: The content of element 'Changes' is not complete. "
                                + "One of '{\"https://route53.amazonaws.com/doc/2013-04-01/\":Change}' is "
                                + "expected."));
    }

    private static Stream<Arguments> emptyChangeBatchCases() {
        String emptyChanges = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch>
                    <Changes/>
                  </ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """;
        String noChangeBatch = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Comment>no ChangeBatch element</Comment>
                </ChangeResourceRecordSetsRequest>
                """;
        return Stream.of(
                Arguments.of("EmptyChanges", emptyChanges),
                Arguments.of("NoChangeBatch", noChangeBatch));
    }

    private static String createZone(String name, String callerReference) {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                </CreateHostedZoneRequest>
                """.formatted(name, callerReference);
        String location = given().contentType(XML).body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
