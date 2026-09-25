package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * DescribeSubnets reports a subnet id that does not exist as an error.
 *
 * <p>It used to return an empty list, which tells a caller the subnet is gone when the id may
 * simply be wrong, and leaves a waiter polling for a subnet it just created unable to tell "not
 * yet" from "never". DescribeVpcs already reads its own ids this way.
 */
@QuarkusTest
class Ec2DescribeSubnetsNotFoundIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/ec2/aws4_request";

    private static ValidatableResponse ec2(String action, String... formParams) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    @Test
    void anUnknownSubnetIdIsRefused() {
        ec2("DescribeSubnets", "SubnetId.1", "subnet-0000000000000dead")
            .statusCode(400)
            .body(containsString("InvalidSubnetID.NotFound"))
            .body(containsString("subnet-0000000000000dead"));
    }

    @Test
    void aKnownSubnetIdStillReturns() {
        String vpcId = ec2("CreateVpc", "CidrBlock", "10.72.0.0/16")
            .statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnetId = ec2("CreateSubnet", "VpcId", vpcId, "CidrBlock", "10.72.1.0/24")
            .statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");

        ec2("DescribeSubnets", "SubnetId.1", subnetId)
            .statusCode(200)
            .body(containsString(subnetId));
    }

    /** One bad id among good ones still fails, the way a per-id lookup has to. */
    @Test
    void oneUnknownIdAmongKnownOnesIsRefused() {
        String vpcId = ec2("CreateVpc", "CidrBlock", "10.73.0.0/16")
            .statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnetId = ec2("CreateSubnet", "VpcId", vpcId, "CidrBlock", "10.73.1.0/24")
            .statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");

        ec2("DescribeSubnets", "SubnetId.1", subnetId, "SubnetId.2", "subnet-0000000000000beef")
            .statusCode(400)
            .body(containsString("InvalidSubnetID.NotFound"));
    }

    /** A describe with no ids lists the region and raises nothing. */
    @Test
    void describingWithNoIdsStillLists() {
        ec2("DescribeSubnets").statusCode(200).body(containsString("subnetSet"));
    }
}
