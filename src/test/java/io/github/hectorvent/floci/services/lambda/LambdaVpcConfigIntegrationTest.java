package io.github.hectorvent.floci.services.lambda;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * A function created with a VpcConfig has to report one back.
 *
 * <p>Floci stored the VpcConfig from the moment it was created but never projected it into the
 * configuration response, so GetFunctionConfiguration had no VpcConfig key at all and a reader
 * could not tell a VPC-attached function from a plain one.
 */
@QuarkusTest
class LambdaVpcConfigIntegrationTest {

    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void createReportsTheVpcConfigAndSoDoesEveryLaterRead() throws Exception {
        String vpcId = createVpc("10.20.0.0/16");
        String subnetId = createSubnet(vpcId, "10.20.1.0/24");
        String groupId = createSecurityGroup("lambda-vpc-" + System.nanoTime(), vpcId);
        String fn = "vpc-fn-" + System.nanoTime();

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": { "ZipFile": "%s" },
                    "VpcConfig": {
                        "SubnetIds": ["%s"],
                        "SecurityGroupIds": ["%s"]
                    }
                }
                """.formatted(fn, base64Zip(), subnetId, groupId))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("VpcConfig.SubnetIds", contains(subnetId))
            .body("VpcConfig.SecurityGroupIds", contains(groupId))
            // AWS fills the VpcId in from the subnets; a caller never sends one.
            .body("VpcConfig.VpcId", equalTo(vpcId))
            .body("VpcConfig.Ipv6AllowedForDualStack", equalTo(false));

        given()
        .when()
            .get("/2015-03-31/functions/" + fn + "/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig.SubnetIds", contains(subnetId))
            .body("VpcConfig.SecurityGroupIds", contains(groupId))
            .body("VpcConfig.VpcId", equalTo(vpcId));

        given()
        .when()
            .get("/2015-03-31/functions/" + fn)
        .then()
            .statusCode(200)
            .body("Configuration.VpcConfig.SubnetIds", contains(subnetId));
    }

    @Test
    void aFunctionWithNoVpcHasNoVpcConfigKey() throws Exception {
        String fn = "plain-fn-" + System.nanoTime();
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": { "ZipFile": "%s" }
                }
                """.formatted(fn, base64Zip()))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        given()
        .when()
            .get("/2015-03-31/functions/" + fn + "/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig", nullValue());
    }

    @Test
    void updatingTheConfigurationChangesTheReportedVpcConfig() throws Exception {
        String vpcId = createVpc("10.21.0.0/16");
        String first = createSubnet(vpcId, "10.21.1.0/24");
        String second = createSubnet(vpcId, "10.21.2.0/24");
        String groupId = createSecurityGroup("lambda-vpc-update-" + System.nanoTime(), vpcId);
        String fn = "vpc-update-fn-" + System.nanoTime();

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": { "ZipFile": "%s" },
                    "VpcConfig": { "SubnetIds": ["%s"], "SecurityGroupIds": ["%s"] }
                }
                """.formatted(fn, base64Zip(), first, groupId))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        given()
            .contentType("application/json")
            .body("""
                { "VpcConfig": { "SubnetIds": ["%s", "%s"], "SecurityGroupIds": ["%s"] } }
                """.formatted(first, second, groupId))
        .when()
            .put("/2015-03-31/functions/" + fn + "/configuration")
        .then()
            .statusCode(200)
            .body("VpcConfig.SubnetIds", contains(first, second))
            .body("VpcConfig.VpcId", equalTo(vpcId));
    }

    private static String base64Zip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.py"));
            zos.write("def handler(event, context): return {}".getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private String createVpc(String cidr) {
        return given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", cidr)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private String createSubnet(String vpcId, String cidr) {
        return given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", cidr)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    private String createSecurityGroup(String groupName, String vpcId) {
        return given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", groupName)
            .formParam("GroupDescription", groupName)
            .formParam("VpcId", vpcId)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");
    }
}
