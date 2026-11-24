package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * DescribeSubnets omitted privateDnsNameOptionsOnLaunch entirely and ModifySubnetAttribute
 * ignored PrivateDnsHostnameTypeOnLaunch and the two EnableResourceNameDns*OnLaunch flags, so
 * Terraform's aws_subnet with private_dns_hostname_type_on_launch set planned an update on
 * every run even when the value was AWS's own default ip-name.
 */
@QuarkusTest
class Ec2SubnetPrivateDnsNameOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private String vpcId;

    @BeforeEach
    void createVpc() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.91.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private String createSubnet() {
        return given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.91.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.privateDnsNameOptionsOnLaunch.hostnameType",
                    equalTo("ip-name"))
            .body("CreateSubnetResponse.subnet.privateDnsNameOptionsOnLaunch.enableResourceNameDnsARecord",
                    equalTo("false"))
            .body("CreateSubnetResponse.subnet.privateDnsNameOptionsOnLaunch.enableResourceNameDnsAAAARecord",
                    equalTo("false"))
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    void aNewSubnetReportsTheAwsDefaultPrivateDnsNameOptions() {
        createSubnet();
    }

    @Test
    void modifySubnetAttributeChangesTheHostnameTypeOnLaunch() {
        String subnetId = createSubnet();

        modify("PrivateDnsHostnameTypeOnLaunch", "resource-name", subnetId);

        describe(subnetId)
            .body("DescribeSubnetsResponse.subnetSet.item.privateDnsNameOptionsOnLaunch.hostnameType",
                    equalTo("resource-name"));
    }

    @Test
    void modifySubnetAttributeChangesBothResourceNameFlags() {
        String subnetId = createSubnet();

        modify("EnableResourceNameDnsARecordOnLaunch.Value", "true", subnetId);
        modify("EnableResourceNameDnsAAAARecordOnLaunch.Value", "true", subnetId);

        describe(subnetId)
            .body("DescribeSubnetsResponse.subnetSet.item.privateDnsNameOptionsOnLaunch"
                    + ".enableResourceNameDnsARecord", equalTo("true"))
            .body("DescribeSubnetsResponse.subnetSet.item.privateDnsNameOptionsOnLaunch"
                    + ".enableResourceNameDnsAAAARecord", equalTo("true"))
            .body("DescribeSubnetsResponse.subnetSet.item.privateDnsNameOptionsOnLaunch.hostnameType",
                    equalTo("ip-name"));
    }

    @Test
    void aRejectedHostnameTypeLeavesTheStoredValueAlone() {
        String subnetId = createSubnet();

        modify("PrivateDnsHostnameTypeOnLaunch", "resource-name", subnetId);
        given()
            .formParam("Action", "ModifySubnetAttribute")
            .formParam("SubnetId", subnetId)
            .formParam("PrivateDnsHostnameTypeOnLaunch", "no-such-type")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));

        describe(subnetId)
            .body("DescribeSubnetsResponse.subnetSet.item.privateDnsNameOptionsOnLaunch.hostnameType",
                    equalTo("resource-name"));
    }

    private void modify(String param, String value, String subnetId) {
        given()
            .formParam("Action", "ModifySubnetAttribute")
            .formParam("SubnetId", subnetId)
            .formParam(param, value)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private ValidatableResponse describe(String subnetId) {
        return given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
