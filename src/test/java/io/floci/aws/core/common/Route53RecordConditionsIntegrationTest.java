package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.Route53Exception;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Route53RecordConditionsIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void authorizesEveryChangeAndPreservesTheBodyForTheController() {
        String user = "dns-conditions-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Route53Client client = AwsTestClients.create(Route53Client.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1, endpoint);
             Route53Client admin = AwsTestClients.create(Route53Client.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            String zone = admin.createHostedZone(r -> r.name("example.test").callerReference(UUID.randomUUID().toString()))
                    .hostedZone().id();
            String resource = "arn:aws:route53:::hostedzone/" + zone.replace("/hostedzone/", "");
            String grant = """
                    {"Effect":"Allow","Action":"route53:ChangeResourceRecordSets","Resource":"%s",
                     "Condition":{"ForAllValues:StringEquals":{
                      "route53:ChangeResourceRecordSetsActions":["UPSERT"],
                      "route53:ChangeResourceRecordSetsRecordTypes":["A"],
                      "route53:ChangeResourceRecordSetsNormalizedRecordNames":["probe.example.test","second.example.test"]},
                     "Null":{"route53:ChangeResourceRecordSetsActions":"false",
                      "route53:ChangeResourceRecordSetsRecordTypes":"false",
                      "route53:ChangeResourceRecordSetsNormalizedRecordNames":"false"}}}
                    """.formatted(resource);
            try {
                policy(user, grant);
                Change allowed = change("UPSERT", "PROBE.Example.test.", "A");
                Change second = change("UPSERT", "second.example.test", "A");
                denied(client, zone, allowed, change("UPSERT", "forbidden.example.test", "A"));
                assertTrue(admin.listResourceRecordSets(r -> r.hostedZoneId(zone)).resourceRecordSets().stream()
                        .noneMatch(r -> r.name().equalsIgnoreCase("probe.example.test.")));
                denied(client, zone, allowed, change("CREATE", "second.example.test", "A"));
                denied(client, zone, allowed, change("UPSERT", "second.example.test", "TXT"));
                client.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(allowed, second)));
                assertTrue(admin.listResourceRecordSets(r -> r.hostedZoneId(zone)).resourceRecordSets().stream()
                        .anyMatch(r -> r.name().equalsIgnoreCase("probe.example.test.") && r.resourceRecords().getFirst().value()
                                .equals("192.0.2.1")));
                // Octal letters address the same record, rather than a second independently stored spelling.
                client.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(
                        change("UPSERT", "\\160robe.example.test", "A"))));
                assertEquals(1, admin.listResourceRecordSets(r -> r.hostedZoneId(zone)).resourceRecordSets().stream()
                        .filter(r -> !List.of("NS", "SOA").contains(r.typeAsString())
                                && !r.name().equals("second.example.test.")).count());
                policy(user, "{\"Effect\":\"Allow\",\"Action\":\"route53:ChangeResourceRecordSets\",\"Resource\":\""
                        + resource + "\"}");
                Route53Exception invalid = assertThrows(Route53Exception.class,
                        () -> client.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(
                                change("REPLACE", "probe.example.test", "A")))));
                assertEquals(400, invalid.statusCode());
                assertEquals("InvalidInput", invalid.awsErrorDetails().errorCode());
                assertTrue(invalid.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("Content-Type")
                        .orElse("").contains("xml"));
                List<ResourceRecordSet> beforeInvalid = admin.listResourceRecordSets(r -> r.hostedZoneId(zone))
                        .resourceRecordSets();
                given().port(endpoint.getPort()).header("Authorization", auth(credentials.accessKeyId(), "route53"))
                        .contentType("application/xml").body("<ChangeResourceRecordSetsRequest><ChangeBatch><Changes>")
                        .post("/2013-04-01/hostedzone/" + zone.replace("/hostedzone/", "") + "/rrset")
                        .then().statusCode(400).contentType("application/xml")
                        .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
                assertEquals(beforeInvalid, admin.listResourceRecordSets(r -> r.hostedZoneId(zone)).resourceRecordSets());
                client.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(
                        change("DELETE", "probe.example.test", "A"))));
                assertTrue(admin.listResourceRecordSets(r -> r.hostedZoneId(zone)).resourceRecordSets().stream()
                        .filter(r -> !List.of("NS", "SOA").contains(r.typeAsString()))
                        .allMatch(r -> r.name().equals("second.example.test.")));
                policy(user, grant + "," + """
                        {"Effect":"Deny","Action":"route53:ChangeResourceRecordSets","Resource":"%s",
                         "Condition":{"ForAnyValue:StringEquals":{
                          "route53:ChangeResourceRecordSetsNormalizedRecordNames":"second.example.test"}}}
                        """.formatted(resource));
                denied(client, zone, allowed, second);
            } finally {
                List<ResourceRecordSet> records = admin.listResourceRecordSets(r -> r.hostedZoneId(zone))
                        .resourceRecordSets().stream().filter(r -> !List.of("NS", "SOA").contains(r.typeAsString())).toList();
                if (!records.isEmpty()) {
                    admin.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(records.stream()
                            .map(record -> Change.builder().action("DELETE").resourceRecordSet(record).build()).toList())));
                }
                admin.deleteHostedZone(r -> r.id(zone));
            }
        }
    }

    private Change change(String action, String name, String type) {
        return Change.builder().action(action).resourceRecordSet(r -> r.name(name).type(type).ttl(60L)
                .resourceRecords(v -> v.value(type.equals("TXT") ? "\"probe\"" : "192.0.2.1"))).build();
    }

    private void denied(Route53Client client, String zone, Change... changes) {
        Route53Exception error = assertThrows(Route53Exception.class, () -> client.changeResourceRecordSets(
                r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(changes))));
        assertEquals(403, error.statusCode());
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), user,
                "dns", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }
}
