package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.Ec2Requests.ec2;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Network interface permissions: the public CreateNetworkInterfacePermission an interface's owner
 * calls, and DescribeNetworkInterfacePermissions, which lists them to the owner.
 *
 * <p>Twelve-digit access key ids resolve to distinct accounts, as in the other cross-account tests.
 */
@QuarkusTest
class Ec2NetworkInterfacePermissionIntegrationTest {

    private static final String OWNER = "000000003511";
    private static final String GRANTEE = "000000003512";
    private static final String SERVICE = "000000003513";
    private static final String OTHER = "000000003514";

    /**
     * The owner's interface, created on first use: QuarkusTest points RestAssured at the application
     * only for a test method, so a {@code @BeforeAll} could not reach it.
     */
    private static String eniId;

    private static String eni() {
        if (eniId == null) {
            eniId = createTheOwnersInterface();
        }
        return eniId;
    }

    private static String createTheOwnersInterface() {
        String vpcId = ec2("DescribeVpcs", OWNER, "us-east-1")
                .formParam("Filter.1.Name", "is-default").formParam("Filter.1.Value.1", "true")
            .when().post("/")
            .then().statusCode(200)
                .extract().path("DescribeVpcsResponse.vpcSet.item[0].vpcId");
        String subnetId = ec2("DescribeSubnets", OWNER, "us-east-1")
                .formParam("Filter.1.Name", "vpc-id").formParam("Filter.1.Value.1", vpcId)
            .when().post("/")
            .then().statusCode(200)
                .extract().path("DescribeSubnetsResponse.subnetSet.item[0].subnetId");
        return ec2("CreateNetworkInterface", OWNER, "us-east-1")
                .formParam("SubnetId", subnetId)
                .formParam("Description", "ES permissions-domain")
            .when().post("/")
            .then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
    }

    @Test
    void theOwnerGrantsAnotherAccountInstanceAttachAndSeesIt() {
        ec2("CreateNetworkInterfacePermission", OWNER, "us-east-1")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", GRANTEE)
                .formParam("Permission", "INSTANCE-ATTACH")
            .when().post("/")
            .then().statusCode(200)
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.awsAccountId", equalTo(GRANTEE));

        ec2("DescribeNetworkInterfacePermissions", OWNER, "us-east-1")
                .formParam("Filter.1.Name", "network-interface-permission.network-interface-id")
                .formParam("Filter.1.Value.1", eni())
                .formParam("Filter.2.Name", "network-interface-permission.aws-account-id")
                .formParam("Filter.2.Value.1", GRANTEE)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeNetworkInterfacePermissionsResponse.networkInterfacePermissions.item.size()", equalTo(1))
                .body("DescribeNetworkInterfacePermissionsResponse.networkInterfacePermissions.item"
                        + ".networkInterfacePermissionId", startsWith("eni-perm-"))
                .body("DescribeNetworkInterfacePermissionsResponse.networkInterfacePermissions.item.permission",
                        equalTo("INSTANCE-ATTACH"))
                .body("DescribeNetworkInterfacePermissionsResponse.networkInterfacePermissions.item"
                        + ".permissionState.state", equalTo("granted"));
    }

    @Test
    void theOwnerGrantsAPermissionOnceAndOnlyTheOwnerCan() {
        String first = ec2("CreateNetworkInterfacePermission", OWNER, "us-east-1")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", SERVICE)
                .formParam("Permission", "INSTANCE-ATTACH")
            .when().post("/")
            .then().statusCode(200)
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.networkInterfaceId",
                        equalTo(eni()))
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.awsAccountId", equalTo(SERVICE))
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.permissionState.state",
                        equalTo("granted"))
                .extract().path("CreateNetworkInterfacePermissionResponse.interfacePermission"
                        + ".networkInterfacePermissionId");
        String again = ec2("CreateNetworkInterfacePermission", OWNER, "us-east-1")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", SERVICE)
                .formParam("Permission", "INSTANCE-ATTACH")
            .when().post("/")
            .then().statusCode(200)
                .extract().path("CreateNetworkInterfacePermissionResponse.interfacePermission"
                        + ".networkInterfacePermissionId");
        assertEquals(first, again);

        ec2("DescribeNetworkInterfacePermissions", OWNER, "us-east-1")
                .formParam("NetworkInterfacePermissionId.1", first)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeNetworkInterfacePermissionsResponse.networkInterfacePermissions.item.awsAccountId",
                        equalTo(SERVICE));

        ec2("CreateNetworkInterfacePermission", GRANTEE, "us-east-1")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", OTHER)
                .formParam("Permission", "INSTANCE-ATTACH")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));
    }

    @Test
    void aPermissionRequestOutsideTheModelIsRefused() {
        ec2("CreateNetworkInterfacePermission", OWNER, "us-east-1")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", GRANTEE)
                .formParam("Permission", "INSTANCE-DETACH")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        ec2("CreateNetworkInterfacePermission", OWNER, "us-east-1")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", GRANTEE)
                .formParam("Permission", "EIP-ASSOCIATE")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
        ec2("DescribeNetworkInterfacePermissions", OWNER, "us-east-1")
                .formParam("NetworkInterfacePermissionId.1", "eni-perm-0123456789abcdef0")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.NotFound"));
        ec2("DescribeNetworkInterfacePermissions", OWNER, "us-east-1")
                .formParam("NetworkInterfacePermissionId.1", "perm-0123")
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.Malformed"));
    }
}
