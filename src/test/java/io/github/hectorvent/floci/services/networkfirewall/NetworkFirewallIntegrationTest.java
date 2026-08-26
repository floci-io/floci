package io.github.hectorvent.floci.services.networkfirewall;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NetworkFirewallIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET_PREFIX = "NetworkFirewall_20201112.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=723679240095/20260101/us-east-1/network-firewall/aws4_request";
    private static final String FIREWALL_ARN =
            "arn:aws:network-firewall:us-east-1:723679240095:firewall/AWSAccelerator-us-east-1-nfw";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createListAndDescribeFirewall_returnsPersistentReadyResource() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallName\":\"AWSAccelerator-us-east-1-nfw\","
                    + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                    + "firewall-policy/AWSAccelerator-us-east-1-nfw-policy\","
                    + "\"VpcId\":\"vpc-1234567890abcdef0\","
                    + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-11111111111111111\"}]}" )
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewall.FirewallArn", equalTo(FIREWALL_ARN));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListFirewalls")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewalls.FirewallArn", hasItem(FIREWALL_ARN));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallArn\":\"" + FIREWALL_ARN + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewall.FirewallArn", equalTo(FIREWALL_ARN))
            .body("FirewallStatus.Status", equalTo("READY"))
            .body("FirewallStatus.SyncStates.us-east-1a.Attachment.Status", equalTo("READY"))
            .body("FirewallStatus.SyncStates.us-east-1a.Attachment.SubnetId",
                    equalTo("subnet-11111111111111111"))
            .body("FirewallStatus.SyncStates.us-east-1a.Attachment.EndpointId",
                    matchesPattern("vpce-[0-9a-f]{17}"));
    }

    @Test
    void describeFirewall_withoutIdentifier_returnsAwsError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void createAndListRuleGroup_returnsAwsMetadataShape() {
        String arn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "stateful-rulegroup/vellum-domain-allow-list";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateRuleGroup")
            .header("Authorization", AUTH_HEADER)
            .body("{\"RuleGroupName\":\"vellum-domain-allow-list\",\"Type\":\"STATEFUL\","
                    + "\"Capacity\":100,\"RuleGroup\":{\"RulesSource\":{\"RulesString\":\"pass ip any any\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleGroupResponse.RuleGroupArn", equalTo(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "ListRuleGroups")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Type\":\"STATEFUL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleGroups.Arn", hasItem(arn))
            .body("RuleGroups.find { it.Arn == '" + arn + "' }.Name",
                    equalTo("vellum-domain-allow-list"));
    }

    @Test
    void associateFirewallPolicy_thenDescribeFirewall_showsNewPolicyArn() {
        String firewallArn = "arn:aws:network-firewall:us-east-1:723679240095:firewall/AssocTestFirewall";
        String initialPolicyArn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/AssocTestFirewall-initial-policy";
        String newPolicyArn = "arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/AssocTestFirewall-new-policy";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallName\":\"AssocTestFirewall\","
                    + "\"FirewallPolicyArn\":\"" + initialPolicyArn + "\","
                    + "\"VpcId\":\"vpc-assoctest0000000000\","
                    + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-22222222222222222\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "CreateFirewallPolicy")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallPolicyName\":\"AssocTestFirewall-new-policy\","
                    + "\"FirewallPolicy\":{\"StatelessDefaultActions\":[\"aws:pass\"],"
                    + "\"StatelessFragmentDefaultActions\":[\"aws:pass\"]}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallPolicyResponse.FirewallPolicyArn", equalTo(newPolicyArn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "AssociateFirewallPolicy")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallArn\":\"" + firewallArn + "\","
                    + "\"FirewallPolicyArn\":\"" + newPolicyArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallPolicyArn", equalTo(newPolicyArn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeFirewall")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallArn\":\"" + firewallArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Firewall.FirewallPolicyArn", equalTo(newPolicyArn));
    }

    @Test
    void deleteFirewall_withDeleteProtection_isRejectedAndLeavesTheFirewall() {
        String name = "DeleteProtectedFirewall";
        createFirewall(name, "\"DeleteProtection\":true,", "subnet-33333333333333333");

        call("DeleteFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.FirewallArn", equalTo(firewallArn(name)));
    }

    @Test
    void associateSubnets_addsToTheExistingMappingsWithoutDuplicating() {
        String name = "AssociateSubnetsFirewall";
        createFirewall(name, "", "subnet-44444444444444444");

        call("AssociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-55555555555555555\"},"
                + "{\"SubnetId\":\"subnet-44444444444444444\"}]}")
            .statusCode(200)
            .body("SubnetMappings.SubnetId", hasItems(
                    "subnet-44444444444444444", "subnet-55555555555555555"))
            .body("SubnetMappings", hasSize(2));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", hasItems(
                    "subnet-44444444444444444", "subnet-55555555555555555"))
            .body("Firewall.SubnetMappings", hasSize(2));
    }

    @Test
    void associateSubnets_withSubnetChangeProtection_isRejected() {
        String name = "SubnetProtectedAssociateFirewall";
        createFirewall(name, "\"SubnetChangeProtection\":true,", "subnet-66666666666666666");

        call("AssociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-77777777777777777\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", not(hasItem("subnet-77777777777777777")));
    }

    @Test
    void disassociateSubnets_removesOnlyTheNamedSubnets() {
        String name = "DisassociateSubnetsFirewall";
        createFirewall(name, "", "subnet-88888888888888888", "subnet-99999999999999999");

        call("DisassociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetIds\":[\"subnet-88888888888888888\"]}")
            .statusCode(200)
            .body("SubnetMappings.SubnetId", contains("subnet-99999999999999999"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", contains("subnet-99999999999999999"));
    }

    @Test
    void disassociateSubnets_removingTheLastSubnet_leavesNoSyncStates() {
        String name = "DrainedSubnetsFirewall";
        createFirewall(name, "", "subnet-aaaaaaaaaaaaaaaaa");

        call("DisassociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetIds\":[\"subnet-aaaaaaaaaaaaaaaaa\"]}")
            .statusCode(200)
            .body("SubnetMappings", empty());

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings", empty())
            .body("FirewallStatus.SyncStates", anEmptyMap());
    }

    @Test
    void disassociateSubnets_withSubnetChangeProtection_isRejected() {
        String name = "SubnetProtectedDisassociateFirewall";
        createFirewall(name, "\"SubnetChangeProtection\":true,", "subnet-bbbbbbbbbbbbbbbbb");

        call("DisassociateSubnets", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"SubnetIds\":[\"subnet-bbbbbbbbbbbbbbbbb\"]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.SubnetMappings.SubnetId", hasItem("subnet-bbbbbbbbbbbbbbbbb"));
    }

    @Test
    void associateAvailabilityZones_addsToTheExistingMappingsWithoutDuplicating() {
        String name = "AssociateZonesFirewall";
        createFirewall(name, "", "subnet-ccccccccccccccccc");

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"}]}")
            .statusCode(200)
            .body("AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1a"));

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"},"
                + "{\"AvailabilityZone\":\"us-east-1b\"}]}")
            .statusCode(200)
            .body("AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1a", "us-east-1b"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.AvailabilityZoneMappings.AvailabilityZone",
                    contains("us-east-1a", "us-east-1b"));
    }

    @Test
    void associateAvailabilityZones_withAvailabilityZoneChangeProtection_isRejected() {
        String name = "ZoneProtectedAssociateFirewall";
        createFirewall(name, "\"AvailabilityZoneChangeProtection\":true,", "subnet-ddddddddddddddddd");

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1c\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.AvailabilityZoneMappings", nullValue());
    }

    @Test
    void disassociateAvailabilityZones_removesOnlyTheNamedZones() {
        String name = "DisassociateZonesFirewall";
        createFirewall(name, "", "subnet-eeeeeeeeeeeeeeeee");

        call("AssociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"},"
                + "{\"AvailabilityZone\":\"us-east-1b\"}]}")
            .statusCode(200);

        call("DisassociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"}]}")
            .statusCode(200)
            .body("AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1b"));

        call("DescribeFirewall", "{\"FirewallArn\":\"" + firewallArn(name) + "\"}")
            .statusCode(200)
            .body("Firewall.AvailabilityZoneMappings.AvailabilityZone", contains("us-east-1b"));
    }

    @Test
    void disassociateAvailabilityZones_withAvailabilityZoneChangeProtection_isRejected() {
        String name = "ZoneProtectedDisassociateFirewall";
        createFirewall(name, "\"AvailabilityZoneChangeProtection\":true,", "subnet-fffffffffffffffff");

        call("DisassociateAvailabilityZones", "{\"FirewallArn\":\"" + firewallArn(name) + "\","
                + "\"AvailabilityZoneMappings\":[{\"AvailabilityZone\":\"us-east-1a\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidOperationException"));
    }

    private static String firewallArn(String name) {
        return "arn:aws:network-firewall:us-east-1:723679240095:firewall/" + name;
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET_PREFIX + action)
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static void createFirewall(String name, String extraFields, String... subnetIds) {
        StringBuilder mappings = new StringBuilder();
        for (String subnetId : subnetIds) {
            if (!mappings.isEmpty()) {
                mappings.append(',');
            }
            mappings.append("{\"SubnetId\":\"").append(subnetId).append("\"}");
        }
        call("CreateFirewall", "{\"FirewallName\":\"" + name + "\","
                + "\"FirewallPolicyArn\":\"arn:aws:network-firewall:us-east-1:723679240095:"
                + "firewall-policy/" + name + "-policy\","
                + "\"VpcId\":\"vpc-0123456789abcdef0\","
                + extraFields
                + "\"SubnetMappings\":[" + mappings + "]}")
            .statusCode(200);
    }
}
