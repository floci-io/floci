package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * CreateVpc's InstanceTenancy on the Query API: {@code default} or {@code dedicated} is stored and
 * read back by DescribeVpcs; omitting it means {@code default}; {@code host} is refused, as the
 * EC2 reference says ("The host value cannot be used with this parameter").
 */
@QuarkusTest
class Ec2CreateVpcInstanceTenancyIntegrationTest {

    private final List<String> createdVpcs = new ArrayList<>();

    private static ValidatableResponse ec2(String action, String... formParams) {
        RequestSpecification request = given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20261007/us-east-1/ec2/aws4_request")
                .formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    @AfterEach
    void deleteCreatedVpcs() {
        for (String vpcId : createdVpcs) {
            ec2("DeleteVpc", "VpcId", vpcId).statusCode(200);
        }
    }

    @Test
    void dedicatedTenancyIsStoredAndDescribed() {
        String vpcId = createVpc("10.97.0.0/16", "InstanceTenancy", "dedicated");
        ec2("DescribeVpcs", "VpcId.1", vpcId).statusCode(200)
                .body("DescribeVpcsResponse.vpcSet.item.instanceTenancy", equalTo("dedicated"));
    }

    @Test
    void omittedTenancyIsDefault() {
        String vpcId = createVpc("10.98.0.0/16");
        ec2("DescribeVpcs", "VpcId.1", vpcId).statusCode(200)
                .body("DescribeVpcsResponse.vpcSet.item.instanceTenancy", equalTo("default"));
    }

    @Test
    void hostTenancyIsRejected() {
        ec2("CreateVpc", "CidrBlock", "10.99.0.0/16", "InstanceTenancy", "host").statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message",
                        equalTo("Value (host) for parameter instanceTenancy is invalid. "
                                + "Valid values are: default, dedicated."));
    }

    private String createVpc(String cidr, String... extraParams) {
        String[] params = new String[2 + extraParams.length];
        params[0] = "CidrBlock";
        params[1] = cidr;
        System.arraycopy(extraParams, 0, params, 2, extraParams.length);
        String vpcId = ec2("CreateVpc", params).statusCode(200)
                .extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        createdVpcs.add(vpcId);
        return vpcId;
    }
}
