package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
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

    private static final String OWNER_AUTH = auth(OWNER);
    private static final String GRANTEE_AUTH = auth(GRANTEE);

    private static String auth(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261108/us-east-1/ec2/aws4_request";
    }

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
        String vpcId = given()
                .formParam("Action", "DescribeVpcs")
                .formParam("Filter.1.Name", "is-default").formParam("Filter.1.Value.1", "true")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(200)
                .extract().path("DescribeVpcsResponse.vpcSet.item[0].vpcId");
        String subnetId = given()
                .formParam("Action", "DescribeSubnets")
                .formParam("Filter.1.Name", "vpc-id").formParam("Filter.1.Value.1", vpcId)
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(200)
                .extract().path("DescribeSubnetsResponse.subnetSet.item[0].subnetId");
        return given()
                .formParam("Action", "CreateNetworkInterface")
                .formParam("SubnetId", subnetId)
                .formParam("Description", "ES permissions-domain")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
    }

    @Test
    void theOwnerGrantsAnotherAccountInstanceAttachAndSeesIt() {
        given()
                .formParam("Action", "CreateNetworkInterfacePermission")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", GRANTEE)
                .formParam("Permission", "INSTANCE-ATTACH")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(200)
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.awsAccountId", equalTo(GRANTEE));

        given()
                .formParam("Action", "DescribeNetworkInterfacePermissions")
                .formParam("Filter.1.Name", "network-interface-permission.network-interface-id")
                .formParam("Filter.1.Value.1", eni())
                .formParam("Filter.2.Name", "network-interface-permission.aws-account-id")
                .formParam("Filter.2.Value.1", GRANTEE)
                .header("Authorization", OWNER_AUTH)
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
        String first = given()
                .formParam("Action", "CreateNetworkInterfacePermission")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", SERVICE)
                .formParam("Permission", "INSTANCE-ATTACH")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(200)
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.networkInterfaceId",
                        equalTo(eni()))
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.awsAccountId", equalTo(SERVICE))
                .body("CreateNetworkInterfacePermissionResponse.interfacePermission.permissionState.state",
                        equalTo("granted"))
                .extract().path("CreateNetworkInterfacePermissionResponse.interfacePermission"
                        + ".networkInterfacePermissionId");
        String again = given()
                .formParam("Action", "CreateNetworkInterfacePermission")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", SERVICE)
                .formParam("Permission", "INSTANCE-ATTACH")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(200)
                .extract().path("CreateNetworkInterfacePermissionResponse.interfacePermission"
                        + ".networkInterfacePermissionId");
        assertEquals(first, again);

        given()
                .formParam("Action", "DescribeNetworkInterfacePermissions")
                .formParam("NetworkInterfacePermissionId.1", first)
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeNetworkInterfacePermissionsResponse.networkInterfacePermissions.item.awsAccountId",
                        equalTo(SERVICE));

        given()
                .formParam("Action", "CreateNetworkInterfacePermission")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", OTHER)
                .formParam("Permission", "INSTANCE-ATTACH")
                .header("Authorization", GRANTEE_AUTH)
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));
    }

    @Test
    void aPermissionRequestOutsideTheModelIsRefused() {
        given()
                .formParam("Action", "CreateNetworkInterfacePermission")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", GRANTEE)
                .formParam("Permission", "INSTANCE-DETACH")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        given()
                .formParam("Action", "CreateNetworkInterfacePermission")
                .formParam("NetworkInterfaceId", eni())
                .formParam("AwsAccountId", GRANTEE)
                .formParam("Permission", "EIP-ASSOCIATE")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
        given()
                .formParam("Action", "DescribeNetworkInterfacePermissions")
                .formParam("NetworkInterfacePermissionId.1", "eni-perm-0123456789abcdef0")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.NotFound"));
        given()
                .formParam("Action", "DescribeNetworkInterfacePermissions")
                .formParam("NetworkInterfacePermissionId.1", "perm-0123")
                .header("Authorization", OWNER_AUTH)
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.Malformed"));
    }
}
