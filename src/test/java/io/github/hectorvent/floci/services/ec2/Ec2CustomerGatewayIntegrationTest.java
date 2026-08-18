package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for customer gateways over the EC2 Query protocol: create with inline
 * tags, describe by id and by filter, tag round trip through CreateTags, and delete.
 *
 * <p>Ordered because the cases walk one gateway through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2CustomerGatewayIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String IP_ADDRESS = "203.0.113.12";

    private static String customerGatewayId;

    @Test
    @Order(1)
    void createReturnsAvailableOnTheFirstRead() {
        customerGatewayId = given()
            .formParam("Action", "CreateCustomerGateway")
            .formParam("Type", "ipsec.1")
            .formParam("IpAddress", IP_ADDRESS)
            .formParam("BgpAsn", "65001")
            .formParam("DeviceName", "branch-office-router")
            .formParam("TagSpecification.1.ResourceType", "customer-gateway")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "branch-office")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateCustomerGatewayResponse.customerGateway.state", equalTo("available"))
            .body("CreateCustomerGatewayResponse.customerGateway.type", equalTo("ipsec.1"))
            .body("CreateCustomerGatewayResponse.customerGateway.ipAddress", equalTo(IP_ADDRESS))
            .body("CreateCustomerGatewayResponse.customerGateway.bgpAsn", equalTo("65001"))
            .body("CreateCustomerGatewayResponse.customerGateway.deviceName", equalTo("branch-office-router"))
            .body("CreateCustomerGatewayResponse.customerGateway.tagSet.item.key", equalTo("Name"))
            .body("CreateCustomerGatewayResponse.customerGateway.tagSet.item.value", equalTo("branch-office"))
            .extract().path("CreateCustomerGatewayResponse.customerGateway.customerGatewayId");

        assertTrue(customerGatewayId.startsWith("cgw-"), "id must use the cgw- prefix");
    }

    @Test
    @Order(2)
    void describeByIdReturnsTheCreatedGatewayStillAvailable() {
        given()
            .formParam("Action", "DescribeCustomerGateways")
            .formParam("CustomerGatewayId.1", customerGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCustomerGatewaysResponse.customerGatewaySet.item.customerGatewayId",
                    equalTo(customerGatewayId))
            .body("DescribeCustomerGatewaysResponse.customerGatewaySet.item.state", equalTo("available"))
            .body("DescribeCustomerGatewaysResponse.customerGatewaySet.item.ipAddress", equalTo(IP_ADDRESS));
    }

    @Test
    @Order(3)
    void describeSupportsIpAddressAndTagFilters() {
        given()
            .formParam("Action", "DescribeCustomerGateways")
            .formParam("Filter.1.Name", "ip-address")
            .formParam("Filter.1.Value.1", IP_ADDRESS)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCustomerGatewaysResponse.customerGatewaySet.item.customerGatewayId",
                    equalTo(customerGatewayId));

        given()
            .formParam("Action", "DescribeCustomerGateways")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "branch-office")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCustomerGatewaysResponse.customerGatewaySet.item.customerGatewayId",
                    equalTo(customerGatewayId));
    }

    @Test
    @Order(4)
    void createTagsIsVisibleOnTheNextDescribe() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", customerGatewayId)
            .formParam("Tag.1.Key", "env")
            .formParam("Tag.1.Value", "staging")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeCustomerGateways")
            .formParam("Filter.1.Name", "tag:env")
            .formParam("Filter.1.Value.1", "staging")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCustomerGatewaysResponse.customerGatewaySet.item.customerGatewayId",
                    equalTo(customerGatewayId));
    }

    @Test
    @Order(5)
    void createWithoutBgpAsnUsesTheAwsDefault() {
        given()
            .formParam("Action", "CreateCustomerGateway")
            .formParam("Type", "ipsec.1")
            .formParam("IpAddress", "203.0.113.99")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateCustomerGatewayResponse.customerGateway.bgpAsn", equalTo("65000"));
    }

    @Test
    @Order(6)
    void unsupportedGatewayTypeIsRejected() {
        given()
            .formParam("Action", "CreateCustomerGateway")
            .formParam("Type", "ipsec.2")
            .formParam("IpAddress", "203.0.113.13")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(7)
    void deleteRemovesTheGateway() {
        given()
            .formParam("Action", "DeleteCustomerGateway")
            .formParam("CustomerGatewayId", customerGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteCustomerGatewayResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeCustomerGateways")
            .formParam("Filter.1.Name", "ip-address")
            .formParam("Filter.1.Value.1", IP_ADDRESS)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeCustomerGatewaysResponse.customerGatewaySet", emptyOrNullString());
    }

    @Test
    @Order(8)
    void describingAMissingGatewayReturnsTheModelledError() {
        given()
            .formParam("Action", "DescribeCustomerGateways")
            .formParam("CustomerGatewayId.1", "cgw-00000000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidCustomerGatewayID.NotFound"))
            .body("Response.Errors.Error.Message", containsString("cgw-00000000000000000"));
    }
}
