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
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class Ec2DescribeVpcsNotFoundIntegrationTest {

    private static final String UNKNOWN = "vpc-0000000000000dead";
    private final List<String> createdVpcs = new ArrayList<>();

    private static ValidatableResponse ec2(String action, String... formParams) {
        RequestSpecification request = given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260926/us-east-1/ec2/aws4_request")
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
    void unknownRequestedIdIsStillAnEc2Error() {
        ec2("DescribeVpcs", "VpcId.1", UNKNOWN).statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidVpcID.NotFound"))
                .body("Response.Errors.Error.Message", equalTo("The vpc ID '" + UNKNOWN + "' does not exist"));
    }

    @Test
    void unsupportedFilterIsReportedBeforeAnUnknownRequestedId() {
        ec2("DescribeVpcs", "VpcId.1", UNKNOWN, "Filter.1.Name", "unsupported-filter",
                "Filter.1.Value.1", "value").statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    void unknownIdAmongKnownIdsIsStillAnEc2Error() {
        String known = createVpc();
        ec2("DescribeVpcs", "VpcId.1", known, "VpcId.2", UNKNOWN).statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidVpcID.NotFound"));
    }

    @Test
    void knownIdAndUnfilteredListingStillReturnTheVpc() {
        String known = createVpc();
        ec2("DescribeVpcs", "VpcId.1", known).statusCode(200)
                .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(known));
        ec2("DescribeVpcs").statusCode(200)
                .body("DescribeVpcsResponse.vpcSet.item.vpcId", hasItem(known));
    }

    @Test
    void filteringOutAnExistingVpcDoesNotMakeItsIdUnknown() {
        String known = createVpc();
        ec2("DescribeVpcs", "VpcId.1", known, "Filter.1.Name", "cidr-block",
                "Filter.1.Value.1", "10.96.0.0/16").statusCode(200)
                .body("DescribeVpcsResponse.vpcSet.item.size()", equalTo(0));
    }

    private String createVpc() {
        String id = ec2("CreateVpc", "CidrBlock", "10.95.0.0/16").statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
        createdVpcs.add(id);
        return id;
    }
}
