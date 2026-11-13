package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.RRType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An update that renames a hosted zone (a create-only change) and its record, followed by a resource
 * that fails, rolls back: the stack's zone is the old one again and still holds the old record, and
 * the zone the update made is gone.
 */
@QuarkusTest
class Route53ZoneReplacementRollbackIntegrationTest {

    private static String template(String zoneName, String later) {
        return """
                {"Resources": {
                  "Zone": {"Type": "AWS::Route53::HostedZone", "Properties": {"Name": "%1$s"}},
                  "Record": {"Type": "AWS::Route53::RecordSet", "Properties": {"HostedZoneId": {"Ref": "Zone"},
                    "Name": "www.%1$s", "Type": "A", "TTL": "300", "ResourceRecords": ["192.0.2.1"]}}%2$s
                }}
                """.formatted(zoneName, later);
    }

    @Test
    void aRenamedZoneThatRollsBackLeavesTheOldZoneWithItsRecordAndTheNewZoneGone() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "repl-zone-rollback-" + suffix;
        String before = "repl-rb-a-" + suffix + ".example.test";
        String after = "repl-rb-b-" + suffix + ".example.test";
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
                Route53Client route53 = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1)) {
            CfnStackWaits.createStack(cfn, stack, template(before, ""), "CREATE_COMPLETE");
            String oldZone = cfn.describeStackResource(request -> request.stackName(stack)
                    .logicalResourceId("Zone")).stackResourceDetail().physicalResourceId();

            CfnStackWaits.updateStack(cfn, stack, template(after, CfnStackWaits.failingResourceAfter("Record")),
                    "UPDATE_ROLLBACK_COMPLETE");

            String zoneNow = cfn.describeStackResource(request -> request.stackName(stack)
                    .logicalResourceId("Zone")).stackResourceDetail().physicalResourceId();
            assertEquals(oldZone, zoneNow, "the stack points at the old zone again");
            List<String> records = route53.listResourceRecordSets(request -> request.hostedZoneId(oldZone))
                    .resourceRecordSets().stream()
                    .filter(set -> set.type() == RRType.A).map(set -> set.name()).toList();
            assertEquals(List.of("www." + before + "."), records, "the old zone holds the old record");
            List<String> zones = route53.listHostedZones().hostedZones().stream().map(zone -> zone.name()).toList();
            assertTrue(zones.contains(before + "."));
            assertFalse(zones.contains(after + "."), "the zone the update made is deleted");
        } finally {
            CfnStackWaits.deleteStack(stack);
        }
    }
}
