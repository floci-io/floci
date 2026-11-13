package io.floci.aws.services.route53;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.InvalidChangeBatchException;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Route53MultiValueIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "0", "TRUE", "False", "", " true "})
    void malformedMultiValueAnswerRejectsTheWholeChangeBatch(String value) {
        String zoneId = Route53HostedZoneTestSupport.createZone(
                "multivalue-invalid.example.test.", "multivalue-invalid-" + UUID.randomUUID());
        String record = """
                <Change><Action>CREATE</Action><ResourceRecordSet>
                  <Name>%s.multivalue-invalid.example.test.</Name><Type>A</Type>
                  <SetIdentifier>instance-1</SetIdentifier>%s<TTL>10</TTL>
                  <ResourceRecords><ResourceRecord><Value>192.0.2.1</Value></ResourceRecord></ResourceRecords>
                </ResourceRecordSet></Change>
                """;
        String body = """
                <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <ChangeBatch><Changes>%s%s</Changes></ChangeBatch>
                </ChangeResourceRecordSetsRequest>
                """.formatted(record.formatted("valid", "<MultiValueAnswer>true</MultiValueAnswer>"),
                        record.formatted("invalid", "<MultiValueAnswer>" + value + "</MultiValueAnswer>"));
        try {
            given().contentType("application/xml").body(body)
                    .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                    .then().statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidInput"))
                    .body("ErrorResponse.Error.Message", containsString("MultiValueAnswer"));
            given().when().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                    .then().statusCode(200)
                    .body("ListResourceRecordSetsResponse.ResourceRecordSets.ResourceRecordSet.size()", equalTo(2));
        } finally {
            given().when().delete("/2013-04-01/hostedzone/" + zoneId).then().statusCode(200);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true, false})
    void multiValueAnswerSurvivesCreateListAndUpsert(Boolean multiValueAnswer) {
        try (Route53Client client = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1)) {
            String name = "multivalue-" + UUID.randomUUID() + ".example.test.";
            String zoneId = client.createHostedZone(request -> request.name(name).callerReference(name))
                    .hostedZone().id();
            ResourceRecordSet record = ResourceRecordSet.builder()
                    .name("lb." + name).type(RRType.A).setIdentifier("instance-1")
                    .multiValueAnswer(multiValueAnswer).ttl(10L)
                    .resourceRecords(ResourceRecord.builder().value("192.0.2.1").build()).build();
            try {
                change(client, zoneId, ChangeAction.CREATE, record);
                ResourceRecordSet observed = readRecord(client, zoneId, record.name());
                assertEquals(record, observed);

                ResourceRecordSet updated = record.toBuilder().ttl(20L).build();
                change(client, zoneId, ChangeAction.UPSERT, updated);
                assertEquals(updated, readRecord(client, zoneId, record.name()));
            } finally {
                change(client, zoneId, ChangeAction.DELETE, readRecord(client, zoneId, record.name()));
                client.deleteHostedZone(request -> request.id(zoneId));
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {false})
    void deleteRejectsMismatchedMultiValueAnswer(Boolean requestedMultiValueAnswer) {
        try (Route53Client client = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1)) {
            String name = "multivalue-delete-" + UUID.randomUUID() + ".example.test.";
            String zoneId = client.createHostedZone(request -> request.name(name).callerReference(name))
                    .hostedZone().id();
            ResourceRecordSet record = ResourceRecordSet.builder()
                    .name("lb." + name).type(RRType.A).setIdentifier("instance-1")
                    .multiValueAnswer(true).ttl(10L)
                    .resourceRecords(ResourceRecord.builder().value("192.0.2.1").build()).build();
            try {
                change(client, zoneId, ChangeAction.CREATE, record);
                ResourceRecordSet mismatched = record.toBuilder().multiValueAnswer(requestedMultiValueAnswer).build();
                assertThrows(InvalidChangeBatchException.class,
                        () -> change(client, zoneId, ChangeAction.DELETE, mismatched));
                assertEquals(record, readRecord(client, zoneId, record.name()));
            } finally {
                change(client, zoneId, ChangeAction.DELETE, record);
                client.deleteHostedZone(request -> request.id(zoneId));
            }
        }
    }

    private ResourceRecordSet readRecord(Route53Client client, String zoneId, String name) {
        return client.listResourceRecordSets(request -> request.hostedZoneId(zoneId))
                .resourceRecordSets().stream().filter(record -> name.equals(record.name())).findFirst().orElseThrow();
    }

    private void change(Route53Client client, String zoneId, ChangeAction action, ResourceRecordSet record) {
        client.changeResourceRecordSets(request -> request.hostedZoneId(zoneId)
                .changeBatch(batch -> batch.changes(Change.builder().action(action).resourceRecordSet(record).build())));
        if (action == ChangeAction.DELETE) {
            assertTrue(client.listResourceRecordSets(request -> request.hostedZoneId(zoneId))
                    .resourceRecordSets().stream().noneMatch(observed -> record.name().equals(observed.name())));
        }
    }
}
