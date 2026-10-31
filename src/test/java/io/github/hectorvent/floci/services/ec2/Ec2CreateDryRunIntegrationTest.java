package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.Ec2Requests.ec2;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;

/**
 * DryRun on CreateVpc, CreateSubnet and CreateSecurityGroup is answered with DryRunOperation and HTTP 412,
 * and nothing is created, so the real create that follows can still take the same CIDR block or group
 * name. A subnet or group dry run first needs the VPC it names to exist; Floci then answers it before the
 * CIDR block, tenancy, overlap and group name are checked, an order AWS does not document.
 */
@QuarkusTest
class Ec2CreateDryRunIntegrationTest {

    private static final String MISSING_VPC = "vpc-0123456789abcdef0";

    @Test
    void aCreateVpcDryRunCreatesNoVpc() {
        String name = "dryrun-" + UUID.randomUUID();
        try {
            assertDryRun(tagged(createVpc("10.91.0.0/16"), "vpc", name).formParam("DryRun", "true"));

            ec2("DescribeVpcs", "test", "us-east-1")
                    .formParam("Filter.1.Name", "tag:Name")
                    .formParam("Filter.1.Value.1", name)
                    .post("/").then().statusCode(200)
                    .body("DescribeVpcsResponse.vpcSet", emptyOrNullString());

            tagged(createVpc("10.91.0.0/16"), "vpc", name).formParam("DryRun", "false")
                    .post("/").then().statusCode(200)
                    .body("CreateVpcResponse.vpc.cidrBlock", equalTo("10.91.0.0/16"));
        } finally {
            deleteVpcsNamed(name);
        }
    }

    @Test
    void aCreateSubnetDryRunCreatesNoSubnetAndLeavesItsRangeFree() {
        String vpcId = createVpcId("10.92.0.0/16");
        try {
            assertDryRun(createSubnet(vpcId, "10.92.1.0/24").formParam("DryRun", "true"));

            ec2("DescribeSubnets", "test", "us-east-1")
                    .formParam("Filter.1.Name", "vpc-id")
                    .formParam("Filter.1.Value.1", vpcId)
                    .post("/").then().statusCode(200)
                    .body("DescribeSubnetsResponse.subnetSet", emptyOrNullString());

            createSubnet(vpcId, "10.92.1.0/24")
                    .post("/").then().statusCode(200)
                    .body("CreateSubnetResponse.subnet.cidrBlock", equalTo("10.92.1.0/24"));
        } finally {
            deleteVpcAndItsContents(vpcId);
        }
    }

    @Test
    void aCreateSecurityGroupDryRunCreatesNoGroupAndLeavesItsNameFree() {
        String vpcId = createVpcId("10.93.0.0/16");
        try {
            assertDryRun(createSecurityGroup(vpcId, "dryrun-group").formParam("DryRun", "true"));

            ec2("DescribeSecurityGroups", "test", "us-east-1")
                    .formParam("Filter.1.Name", "vpc-id")
                    .formParam("Filter.1.Value.1", vpcId)
                    .post("/").then().statusCode(200)
                    .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.groupName", equalTo("default"));

            createSecurityGroup(vpcId, "dryrun-group")
                    .post("/").then().statusCode(200)
                    .body("CreateSecurityGroupResponse.return", equalTo("true"));
        } finally {
            deleteVpcAndItsContents(vpcId);
        }
    }

    /** A group dry run with no VpcId, or an empty one, is answered for the region's default VPC. */
    @ParameterizedTest
    @ValueSource(strings = {"absent", "empty"})
    void aGroupDryRunWithoutAVpcIdIsAnsweredForTheDefaultVpc(String vpcId) {
        String name = "dryrun-default-" + UUID.randomUUID();
        try {
            RequestSpecification dryRun = ec2("CreateSecurityGroup", "test", "us-east-1")
                    .formParam("GroupName", name)
                    .formParam("GroupDescription", "dry run test group")
                    .formParam("DryRun", "true");
            if ("empty".equals(vpcId)) {
                dryRun.formParam("VpcId", "");
            }
            assertDryRun(dryRun);

            groupsNamed(name).body("DescribeSecurityGroupsResponse.securityGroupInfo", emptyOrNullString());
        } finally {
            for (String groupId : groupsNamed(name).extract().xmlPath()
                    .getList("DescribeSecurityGroupsResponse.securityGroupInfo.item.groupId", String.class)) {
                ec2("DeleteSecurityGroup", "test", "us-east-1").formParam("GroupId", groupId).post("/").then().statusCode(200);
            }
        }
    }

    /**
     * A dry run whose real create would fail one of those checks is answered with DryRunOperation too,
     * and the same request without DryRun still fails with its own error.
     */
    @ParameterizedTest
    @CsvSource({
            "vpc-prefix-out-of-range, InvalidVpc.Range",
            "vpc-reserved-range, InvalidVpc.Range",
            "vpc-host-tenancy, InvalidParameterValue",
            "subnet-range-in-use, InvalidSubnet.Conflict",
            "subnet-outside-the-vpc, InvalidSubnet.Range",
            "group-name-taken, InvalidGroup.Duplicate"
    })
    void aDryRunIsAnsweredBeforeTheChecksThatFailTheRealCreate(String request, String errorCode) {
        String name = "dryrun-" + UUID.randomUUID();
        String vpcId = createVpcId("10.94.0.0/16");
        try {
            createSubnet(vpcId, "10.94.1.0/24").post("/").then().statusCode(200);
            createSecurityGroup(vpcId, "taken-group").post("/").then().statusCode(200);

            assertDryRun(failingCreate(request, vpcId, name).formParam("DryRun", "true"));

            failingCreate(request, vpcId, name)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo(errorCode));
        } finally {
            try {
                deleteVpcAndItsContents(vpcId);
            } finally {
                deleteVpcsNamed(name);
            }
        }
    }

    /**
     * A subnet or group dry run naming a VPC that does not exist, or a subnet dry run naming none, keeps the
     * error the real create gives, as a RunInstances dry run keeps the error of a missing subnet.
     */
    @ParameterizedTest
    @CsvSource({
            "subnet-in-missing-vpc, InvalidVpcID.NotFound",
            "subnet-without-vpc-id, MissingParameter",
            "group-in-missing-vpc, InvalidVpcID.NotFound"
    })
    void aDryRunKeepsTheErrorOfAMissingVpc(String request, String errorCode) {
        missingVpcCreate(request).formParam("DryRun", "true")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));

        missingVpcCreate(request)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));
    }

    private static RequestSpecification missingVpcCreate(String request) {
        return switch (request) {
            case "subnet-in-missing-vpc" -> createSubnet(MISSING_VPC, "10.97.1.0/24");
            case "subnet-without-vpc-id" -> ec2("CreateSubnet", "test", "us-east-1").formParam("CidrBlock", "10.97.1.0/24");
            case "group-in-missing-vpc" -> createSecurityGroup(MISSING_VPC, "missing-vpc-group");
            default -> throw new IllegalArgumentException(request);
        };
    }

    /** The failing request named by {@code request}; a VPC it might create carries the tag {@code name}. */
    private static RequestSpecification failingCreate(String request, String vpcId, String name) {
        return switch (request) {
            case "vpc-prefix-out-of-range" -> tagged(createVpc("10.0.0.0/8"), "vpc", name);
            case "vpc-reserved-range" -> tagged(createVpc("127.0.0.0/16"), "vpc", name);
            case "vpc-host-tenancy" -> tagged(createVpc("10.95.0.0/16"), "vpc", name)
                    .formParam("InstanceTenancy", "host");
            case "subnet-range-in-use" -> createSubnet(vpcId, "10.94.1.0/24");
            case "subnet-outside-the-vpc" -> createSubnet(vpcId, "10.96.1.0/24");
            case "group-name-taken" -> createSecurityGroup(vpcId, "taken-group");
            default -> throw new IllegalArgumentException(request);
        };
    }

    private static void assertDryRun(RequestSpecification request) {
        request.post("/").then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                .body("Response.Errors.Error.Message",
                        equalTo("Request would have succeeded, but DryRun flag is set."));
    }

    private static ValidatableResponse groupsNamed(String name) {
        return ec2("DescribeSecurityGroups", "test", "us-east-1")
                .formParam("Filter.1.Name", "group-name")
                .formParam("Filter.1.Value.1", name)
                .post("/").then().statusCode(200);
    }

    private static RequestSpecification tagged(RequestSpecification request, String resourceType, String name) {
        return request
                .formParam("TagSpecification.1.ResourceType", resourceType)
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", name);
    }

    private static RequestSpecification createVpc(String cidrBlock) {
        return ec2("CreateVpc", "test", "us-east-1").formParam("CidrBlock", cidrBlock);
    }

    private static RequestSpecification createSubnet(String vpcId, String cidrBlock) {
        return ec2("CreateSubnet", "test", "us-east-1").formParam("VpcId", vpcId).formParam("CidrBlock", cidrBlock);
    }

    private static RequestSpecification createSecurityGroup(String vpcId, String groupName) {
        return ec2("CreateSecurityGroup", "test", "us-east-1")
                .formParam("VpcId", vpcId)
                .formParam("GroupName", groupName)
                .formParam("GroupDescription", "dry run test group");
    }

    private static String createVpcId(String cidrBlock) {
        return createVpc(cidrBlock).post("/").then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private static void deleteVpcsNamed(String name) {
        XmlPath vpcs = ec2("DescribeVpcs", "test", "us-east-1")
                .formParam("Filter.1.Name", "tag:Name")
                .formParam("Filter.1.Value.1", name)
                .post("/").then().statusCode(200).extract().xmlPath();
        for (String vpcId : vpcs.getList("DescribeVpcsResponse.vpcSet.item.vpcId", String.class)) {
            deleteVpcAndItsContents(vpcId);
        }
    }

    /** Deletes the VPC with every subnet and non-default group in it, including any a dry run left. */
    private static void deleteVpcAndItsContents(String vpcId) {
        XmlPath subnets = ec2("DescribeSubnets", "test", "us-east-1")
                .formParam("Filter.1.Name", "vpc-id")
                .formParam("Filter.1.Value.1", vpcId)
                .post("/").then().statusCode(200).extract().xmlPath();
        for (String subnetId : subnets.getList("DescribeSubnetsResponse.subnetSet.item.subnetId", String.class)) {
            ec2("DeleteSubnet", "test", "us-east-1").formParam("SubnetId", subnetId).post("/").then().statusCode(200);
        }
        XmlPath groups = ec2("DescribeSecurityGroups", "test", "us-east-1")
                .formParam("Filter.1.Name", "vpc-id")
                .formParam("Filter.1.Value.1", vpcId)
                .post("/").then().statusCode(200).extract().xmlPath();
        for (String groupId : groups.getList("DescribeSecurityGroupsResponse.securityGroupInfo.item"
                + ".findAll { it.groupName != 'default' }.groupId", String.class)) {
            ec2("DeleteSecurityGroup", "test", "us-east-1").formParam("GroupId", groupId).post("/").then().statusCode(200);
        }
        ec2("DeleteVpc", "test", "us-east-1").formParam("VpcId", vpcId).post("/").then().statusCode(200);
    }
}
