package io.github.hectorvent.floci.services.globalaccelerator;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlobalAcceleratorIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "GlobalAccelerator_V20180706.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260215/us-west-2/globalaccelerator/aws4_request";

    private static String acceleratorArn;
    private static String listenerArn;
    private static String endpointGroupArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification call(String action, String body) {
        return given()
                .header("Authorization", AUTH)
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }

    // ──────────────────────────── Accelerator ────────────────────────────

    @Test
    @Order(10)
    void createAcceleratorIsDeployedWithStaticIps() {
        acceleratorArn = call("CreateAccelerator", """
                {
                  "Name": "edge-accelerator",
                  "IdempotencyToken": "token-create-accelerator",
                  "Enabled": true,
                  "Tags": [{ "Key": "env", "Value": "test" }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerator.Name", equalTo("edge-accelerator"))
            .body("Accelerator.Status", equalTo("DEPLOYED"))
            .body("Accelerator.Enabled", equalTo(true))
            .body("Accelerator.IpAddressType", equalTo("IPV4"))
            .body("Accelerator.DnsName", endsWith(".awsglobalaccelerator.com"))
            .body("Accelerator.IpSets", hasSize(1))
            .body("Accelerator.IpSets[0].IpFamily", equalTo("IPv4"))
            .body("Accelerator.IpSets[0].IpAddressFamily", equalTo("IPv4"))
            .body("Accelerator.IpSets[0].IpAddresses", hasSize(2))
            .body("Accelerator.CreatedTime", notNullValue())
            .body("Accelerator.Events", hasSize(0))
            .extract().path("Accelerator.AcceleratorArn");
    }

    @Test
    @Order(11)
    void acceleratorArnIsGlobalWithEmptyRegionSegment() {
        org.junit.jupiter.api.Assertions.assertTrue(
                acceleratorArn.startsWith("arn:aws:globalaccelerator::"),
                "Global Accelerator ARNs carry an empty region segment, got: " + acceleratorArn);
        org.junit.jupiter.api.Assertions.assertTrue(
                acceleratorArn.contains(":accelerator/"),
                "Accelerator ARN should carry an accelerator/ resource segment, got: " + acceleratorArn);
    }

    @Test
    @Order(12)
    void describeAcceleratorReturnsTerminalStatus() {
        call("DescribeAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerator.AcceleratorArn", equalTo(acceleratorArn))
            .body("Accelerator.Status", equalTo("DEPLOYED"))
            .body("Accelerator.Name", equalTo("edge-accelerator"))
            .body("Accelerator.IpSets[0].IpAddresses", hasSize(2));
    }

    @Test
    @Order(13)
    void listAcceleratorsIncludesCreatedAccelerator() {
        call("ListAccelerators", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerators.AcceleratorArn", hasItem(acceleratorArn));
    }

    @Test
    @Order(14)
    void createAcceleratorTagsAreReadableWithListTagsForResource() {
        call("ListTagsForResource", "{\"ResourceArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"))
            .body("Tags[0].Value", equalTo("test"));
    }

    @Test
    @Order(15)
    void describeAcceleratorAttributesDefaultsToFlowLogsDisabled() {
        call("DescribeAcceleratorAttributes", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AcceleratorAttributes.FlowLogsEnabled", equalTo(false));
    }

    @Test
    @Order(16)
    void updateAcceleratorAttributesPersists() {
        call("UpdateAcceleratorAttributes", """
                {
                  "AcceleratorArn": "%s",
                  "FlowLogsEnabled": true,
                  "FlowLogsS3Bucket": "flow-logs-bucket",
                  "FlowLogsS3Prefix": "gax/"
                }
                """.formatted(acceleratorArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AcceleratorAttributes.FlowLogsEnabled", equalTo(true))
            .body("AcceleratorAttributes.FlowLogsS3Bucket", equalTo("flow-logs-bucket"));

        call("DescribeAcceleratorAttributes", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AcceleratorAttributes.FlowLogsS3Prefix", equalTo("gax/"));
    }

    @Test
    @Order(17)
    void updateAcceleratorRenamesAndStaysDeployed() {
        call("UpdateAccelerator", """
                { "AcceleratorArn": "%s", "Name": "edge-accelerator-v2" }
                """.formatted(acceleratorArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerator.Name", equalTo("edge-accelerator-v2"))
            .body("Accelerator.Status", equalTo("DEPLOYED"));
    }

    // ──────────────────────────── Listener ────────────────────────────

    @Test
    @Order(20)
    void createListenerEchoesPortRanges() {
        listenerArn = call("CreateListener", """
                {
                  "AcceleratorArn": "%s",
                  "IdempotencyToken": "token-create-listener",
                  "Protocol": "TCP",
                  "PortRanges": [{ "FromPort": 80, "ToPort": 81 }]
                }
                """.formatted(acceleratorArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Listener.Protocol", equalTo("TCP"))
            .body("Listener.ClientAffinity", equalTo("NONE"))
            .body("Listener.PortRanges", hasSize(1))
            .body("Listener.PortRanges[0].FromPort", equalTo(80))
            .body("Listener.PortRanges[0].ToPort", equalTo(81))
            .extract().path("Listener.ListenerArn");

        org.junit.jupiter.api.Assertions.assertTrue(listenerArn.startsWith(acceleratorArn + "/listener/"),
                "Listener ARN extends the accelerator ARN, got: " + listenerArn);
    }

    @Test
    @Order(21)
    void describeListenerReturnsCreatedListener() {
        call("DescribeListener", "{\"ListenerArn\":\"" + listenerArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Listener.ListenerArn", equalTo(listenerArn))
            .body("Listener.Protocol", equalTo("TCP"));
    }

    @Test
    @Order(22)
    void listListenersIsScopedToTheAccelerator() {
        call("ListListeners", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Listeners", hasSize(1))
            .body("Listeners[0].ListenerArn", equalTo(listenerArn));
    }

    @Test
    @Order(23)
    void updateListenerChangesClientAffinity() {
        call("UpdateListener", """
                { "ListenerArn": "%s", "ClientAffinity": "SOURCE_IP" }
                """.formatted(listenerArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Listener.ClientAffinity", equalTo("SOURCE_IP"))
            .body("Listener.PortRanges[0].FromPort", equalTo(80));
    }

    @Test
    @Order(24)
    void createListenerWithInvalidPortRangeIsRejected() {
        call("CreateListener", """
                {
                  "AcceleratorArn": "%s",
                  "IdempotencyToken": "token-bad-ports",
                  "Protocol": "TCP",
                  "PortRanges": [{ "FromPort": 900, "ToPort": 80 }]
                }
                """.formatted(acceleratorArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidPortRangeException"));
    }

    // ──────────────────────── Endpoint group ────────────────────────

    @Test
    @Order(30)
    void createEndpointGroupReportsHealthyEndpointsAndDefaults() {
        endpointGroupArn = call("CreateEndpointGroup", """
                {
                  "ListenerArn": "%s",
                  "EndpointGroupRegion": "us-west-2",
                  "IdempotencyToken": "token-create-endpoint-group",
                  "EndpointConfigurations": [
                    { "EndpointId": "i-0123456789abcdef0", "Weight": 50 }
                  ]
                }
                """.formatted(listenerArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EndpointGroup.EndpointGroupRegion", equalTo("us-west-2"))
            .body("EndpointGroup.TrafficDialPercentage", equalTo(100.0f))
            .body("EndpointGroup.HealthCheckProtocol", equalTo("TCP"))
            .body("EndpointGroup.HealthCheckPath", equalTo("/"))
            .body("EndpointGroup.HealthCheckIntervalSeconds", equalTo(30))
            .body("EndpointGroup.HealthCheckPort", equalTo(80))
            .body("EndpointGroup.ThresholdCount", equalTo(3))
            .body("EndpointGroup.PortOverrides", hasSize(0))
            .body("EndpointGroup.EndpointDescriptions", hasSize(1))
            .body("EndpointGroup.EndpointDescriptions[0].EndpointId", equalTo("i-0123456789abcdef0"))
            .body("EndpointGroup.EndpointDescriptions[0].Weight", equalTo(50))
            .body("EndpointGroup.EndpointDescriptions[0].HealthState", equalTo("HEALTHY"))
            .extract().path("EndpointGroup.EndpointGroupArn");

        org.junit.jupiter.api.Assertions.assertTrue(
                endpointGroupArn.startsWith(listenerArn + "/endpoint-group/"),
                "Endpoint group ARN extends the listener ARN, got: " + endpointGroupArn);
    }

    @Test
    @Order(31)
    void describeEndpointGroupReturnsCreatedGroup() {
        call("DescribeEndpointGroup", "{\"EndpointGroupArn\":\"" + endpointGroupArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EndpointGroup.EndpointGroupArn", equalTo(endpointGroupArn))
            .body("EndpointGroup.EndpointDescriptions[0].HealthState", equalTo("HEALTHY"));
    }

    @Test
    @Order(32)
    void listEndpointGroupsIsScopedToTheListener() {
        call("ListEndpointGroups", "{\"ListenerArn\":\"" + listenerArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EndpointGroups", hasSize(1))
            .body("EndpointGroups[0].EndpointGroupArn", equalTo(endpointGroupArn));
    }

    @Test
    @Order(33)
    void secondEndpointGroupInSameRegionIsRejected() {
        call("CreateEndpointGroup", """
                {
                  "ListenerArn": "%s",
                  "EndpointGroupRegion": "us-west-2",
                  "IdempotencyToken": "token-duplicate-region"
                }
                """.formatted(listenerArn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EndpointGroupAlreadyExistsException"));
    }

    @Test
    @Order(34)
    void updateEndpointGroupChangesHealthCheckAndTrafficDial() {
        call("UpdateEndpointGroup", """
                {
                  "EndpointGroupArn": "%s",
                  "TrafficDialPercentage": 50,
                  "HealthCheckProtocol": "HTTP",
                  "HealthCheckPath": "/healthz",
                  "HealthCheckIntervalSeconds": 10,
                  "ThresholdCount": 2,
                  "PortOverrides": [{ "ListenerPort": 80, "EndpointPort": 8080 }]
                }
                """.formatted(endpointGroupArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EndpointGroup.TrafficDialPercentage", equalTo(50.0f))
            .body("EndpointGroup.HealthCheckProtocol", equalTo("HTTP"))
            .body("EndpointGroup.HealthCheckPath", equalTo("/healthz"))
            .body("EndpointGroup.HealthCheckIntervalSeconds", equalTo(10))
            .body("EndpointGroup.ThresholdCount", equalTo(2))
            .body("EndpointGroup.PortOverrides[0].EndpointPort", equalTo(8080));
    }

    @Test
    @Order(35)
    void addEndpointsAppendsToTheGroup() {
        call("AddEndpoints", """
                {
                  "EndpointGroupArn": "%s",
                  "EndpointConfigurations": [
                    { "EndpointId": "i-abcdef01234567890", "Weight": 10, "ClientIPPreservationEnabled": true }
                  ]
                }
                """.formatted(endpointGroupArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EndpointGroupArn", equalTo(endpointGroupArn))
            .body("EndpointDescriptions", hasSize(2))
            .body("EndpointDescriptions.EndpointId", hasItem("i-abcdef01234567890"))
            .body("EndpointDescriptions.HealthState", contains("HEALTHY", "HEALTHY"));
    }

    @Test
    @Order(36)
    void removeEndpointsDropsTheEndpoint() {
        call("RemoveEndpoints", """
                {
                  "EndpointGroupArn": "%s",
                  "EndpointIdentifiers": [{ "EndpointId": "i-abcdef01234567890" }]
                }
                """.formatted(endpointGroupArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("DescribeEndpointGroup", "{\"EndpointGroupArn\":\"" + endpointGroupArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EndpointGroup.EndpointDescriptions", hasSize(1))
            .body("EndpointGroup.EndpointDescriptions.EndpointId", not(hasItem("i-abcdef01234567890")));
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Test
    @Order(40)
    void tagAndUntagListener() {
        call("TagResource", """
                { "ResourceArn": "%s", "Tags": [{ "Key": "team", "Value": "platform" }] }
                """.formatted(listenerArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + listenerArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags[0].Key", equalTo("team"))
            .body("Tags[0].Value", equalTo("platform"));

        call("UntagResource", """
                { "ResourceArn": "%s", "TagKeys": ["team"] }
                """.formatted(listenerArn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("ListTagsForResource", "{\"ResourceArn\":\"" + listenerArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    @Test
    @Order(41)
    void listTagsForUnknownAcceleratorReturnsAcceleratorNotFound() {
        call("ListTagsForResource",
                "{\"ResourceArn\":\"arn:aws:globalaccelerator::000000000000:accelerator/does-not-exist\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AcceleratorNotFoundException"));
    }

    // ──────────────────────── Dual stack ────────────────────────

    @Test
    @Order(50)
    void dualStackAcceleratorCarriesTwoIpSetsAndADualStackDnsName() {
        String dualStackArn = call("CreateAccelerator", """
                {
                  "Name": "dual-stack-accelerator",
                  "IdempotencyToken": "token-dual-stack",
                  "IpAddressType": "DUAL_STACK",
                  "Enabled": false
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerator.IpAddressType", equalTo("DUAL_STACK"))
            .body("Accelerator.IpSets", hasSize(2))
            .body("Accelerator.IpSets[1].IpAddressFamily", equalTo("IPv6"))
            .body("Accelerator.IpSets[1].IpAddresses", hasSize(2))
            .body("Accelerator.DualStackDnsName", endsWith(".dualstack.awsglobalaccelerator.com"))
            .extract().path("Accelerator.AcceleratorArn");

        call("DeleteAccelerator", "{\"AcceleratorArn\":\"" + dualStackArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(51)
    void byoipAddressesArePinnedOnTheAccelerator() {
        String byoipArn = call("CreateAccelerator", """
                {
                  "Name": "byoip-accelerator",
                  "IdempotencyToken": "token-byoip",
                  "Enabled": false,
                  "IpAddresses": ["198.51.100.10", "198.51.100.11"]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerator.IpSets[0].IpAddresses", contains("198.51.100.10", "198.51.100.11"))
            .extract().path("Accelerator.AcceleratorArn");

        call("DeleteAccelerator", "{\"AcceleratorArn\":\"" + byoipArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    // ──────────────────────────── Teardown ────────────────────────────

    @Test
    @Order(60)
    void deleteEnabledAcceleratorIsRejected() {
        call("DeleteAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AcceleratorNotDisabledException"));
    }

    @Test
    @Order(61)
    void deleteListenerWithEndpointGroupIsRejected() {
        call("DeleteListener", "{\"ListenerArn\":\"" + listenerArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AssociatedEndpointGroupFoundException"));
    }

    @Test
    @Order(62)
    void deleteEndpointGroupThenDescribeReturnsNotFound() {
        call("DeleteEndpointGroup", "{\"EndpointGroupArn\":\"" + endpointGroupArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("DescribeEndpointGroup", "{\"EndpointGroupArn\":\"" + endpointGroupArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EndpointGroupNotFoundException"));
    }

    @Test
    @Order(63)
    void deleteAcceleratorWithListenerIsRejected() {
        call("UpdateAccelerator", """
                { "AcceleratorArn": "%s", "Enabled": false }
                """.formatted(acceleratorArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerator.Enabled", equalTo(false));

        call("DeleteAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AssociatedListenerFoundException"));
    }

    @Test
    @Order(64)
    void deleteListenerThenDescribeReturnsNotFound() {
        call("DeleteListener", "{\"ListenerArn\":\"" + listenerArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("DescribeListener", "{\"ListenerArn\":\"" + listenerArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ListenerNotFoundException"));
    }

    @Test
    @Order(65)
    void deleteAcceleratorThenDescribeReturnsNotFound() {
        call("DeleteAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("DescribeAccelerator", "{\"AcceleratorArn\":\"" + acceleratorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AcceleratorNotFoundException"))
            .body("message", containsString(acceleratorArn));
    }

    @Test
    @Order(66)
    void unsupportedOperationFailsFast() {
        call("ProvisionByoipCidr", "{\"Cidr\":\"198.51.100.0/24\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    @Order(67)
    void listAcceleratorsStillRespondsAfterTeardown() {
        call("ListAccelerators", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accelerators", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    @Order(68)
    void createAcceleratorRejectsAMissingName() {
        call("CreateAccelerator", "{\"IdempotencyToken\":\"token-no-name\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArgumentException"))
            .body("message", startsWith("Name"));
    }
}
