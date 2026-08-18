package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for DHCP options over the EC2 Query protocol: create with a full
 * {@code DhcpConfiguration} set, describe it back with the exact configuration echoed, associate
 * it with a VPC and confirm {@code DescribeVpcs} reflects the new {@code dhcpOptionsId}, and
 * delete.
 *
 * <p>Ordered because the cases build on one DHCP options set and one VPC, mirroring how a client
 * (e.g. the terraform-aws-modules/terraform-aws-vpc "complete" example, which creates a custom
 * {@code aws_vpc_dhcp_options} and associates it with the VPC by default) drives the resource
 * through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2DhcpOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String dhcpOptionsId;
    private static String vpcId;

    @Test
    @Order(1)
    void createDhcpOptionsWithFullConfigurationSet() {
        dhcpOptionsId = given()
            .formParam("Action", "CreateDhcpOptions")
            .formParam("DhcpConfiguration.1.Key", "domain-name")
            .formParam("DhcpConfiguration.1.Value.1", "example.com")
            .formParam("DhcpConfiguration.2.Key", "domain-name-servers")
            .formParam("DhcpConfiguration.2.Value.1", "10.0.0.2")
            .formParam("DhcpConfiguration.2.Value.2", "10.0.1.2")
            .formParam("DhcpConfiguration.3.Key", "ntp-servers")
            .formParam("DhcpConfiguration.3.Value.1", "10.0.0.3")
            .formParam("DhcpConfiguration.4.Key", "netbios-name-servers")
            .formParam("DhcpConfiguration.4.Value.1", "10.0.0.4")
            .formParam("DhcpConfiguration.5.Key", "netbios-node-type")
            .formParam("DhcpConfiguration.5.Value.1", "2")
            .formParam("TagSpecification.1.ResourceType", "dhcp-options")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "integration-dhcp-options")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateDhcpOptionsResponse.dhcpOptions.dhcpConfigurationSet.item.key",
                    hasItems("domain-name", "domain-name-servers", "ntp-servers",
                            "netbios-name-servers", "netbios-node-type"))
            .body("CreateDhcpOptionsResponse.dhcpOptions.tagSet.item.key", equalTo("Name"))
            .body("CreateDhcpOptionsResponse.dhcpOptions.tagSet.item.value", equalTo("integration-dhcp-options"))
            .extract().path("CreateDhcpOptionsResponse.dhcpOptions.dhcpOptionsId");

        org.junit.jupiter.api.Assertions.assertTrue(dhcpOptionsId.startsWith("dopt-"));
    }

    @Test
    @Order(2)
    void describeEchoesTheExactConfiguration() {
        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("DhcpOptionsId.1", dhcpOptionsId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpOptionsId", equalTo(dhcpOptionsId))
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpConfigurationSet.item.find { it.key == 'domain-name' }.valueSet.item.value",
                    equalTo("example.com"))
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpConfigurationSet.item.find { it.key == 'domain-name-servers' }.valueSet.item.value",
                    hasItems("10.0.0.2", "10.0.1.2"))
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpConfigurationSet.item.find { it.key == 'netbios-node-type' }.valueSet.item.value",
                    equalTo("2"));
    }

    @Test
    @Order(3)
    void describeFiltersByDhcpOptionsIdAndKey() {
        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("Filter.1.Name", "dhcp-options-id")
            .formParam("Filter.1.Value.1", dhcpOptionsId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpOptionsId", equalTo(dhcpOptionsId));

        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("Filter.1.Name", "key")
            .formParam("Filter.1.Value.1", "ntp-servers")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeDhcpOptionsResponse.dhcpOptionsSet.item.dhcpOptionsId", equalTo(dhcpOptionsId));
    }

    @Test
    @Order(4)
    void associateDhcpOptionsUpdatesTheVpc() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.42.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "AssociateDhcpOptions")
            .formParam("DhcpOptionsId", dhcpOptionsId)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateDhcpOptionsResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.dhcpOptionsId", equalTo(dhcpOptionsId));
    }

    @Test
    @Order(5)
    void associatingTheLiteralDefaultResetsTheVpc() {
        given()
            .formParam("Action", "AssociateDhcpOptions")
            .formParam("DhcpOptionsId", "default")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateDhcpOptionsResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.dhcpOptionsId", equalTo("dopt-default"));

        // Re-associate so the delete-time DependencyViolation case below still exercises a real
        // association rather than testing against an already-disassociated set.
        given()
            .formParam("Action", "AssociateDhcpOptions")
            .formParam("DhcpOptionsId", dhcpOptionsId)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void deletingAnAssociatedDhcpOptionsSetIsRejected() {
        given()
            .formParam("Action", "DeleteDhcpOptions")
            .formParam("DhcpOptionsId", dhcpOptionsId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
    }

    @Test
    @Order(7)
    void describeUnknownIdIsRejected() {
        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("DhcpOptionsId.1", "dopt-doesnotexist")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidDhcpOptionID.NotFound"));
    }

    @Test
    @Order(8)
    void deleteDhcpOptionsAfterDisassociating() {
        given()
            .formParam("Action", "AssociateDhcpOptions")
            .formParam("DhcpOptionsId", "default")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteDhcpOptions")
            .formParam("DhcpOptionsId", dhcpOptionsId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteDhcpOptionsResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeDhcpOptions")
            .formParam("DhcpOptionsId.1", dhcpOptionsId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidDhcpOptionID.NotFound"));
    }
}
