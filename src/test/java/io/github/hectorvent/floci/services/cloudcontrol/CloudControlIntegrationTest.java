package io.github.hectorvent.floci.services.cloudcontrol;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.TEXT;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class CloudControlIntegrationTest {

    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @BeforeAll
    static void registerAwsJsonParser() {
        RestAssured.registerParser("application/x-amz-json-1.1", Parser.JSON);
        RestAssured.registerParser("application/x-amz-json-1.0", Parser.JSON);
    }

    @Test
    void listResourcesReturnsCreatedS3Ec2AndIamResources() {
        String bucket = "cloudcontrol-test-bucket";
        given().when().put("/" + bucket).then().statusCode(200);

        String vpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.42.0.0/16")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");

        String subnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", vpcId)
                .formParam("CidrBlock", "10.42.1.0/24")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");

        String groupId = given()
                .formParam("Action", "CreateSecurityGroup")
                .formParam("GroupName", "cloudcontrol-sg")
                .formParam("GroupDescription", "cloudcontrol sg")
                .formParam("VpcId", vpcId)
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSecurityGroupResponse.groupId");

        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", "cloudcontrol-user")
                .header("Authorization", IAM_AUTH)
                .when().post("/")
                .then().statusCode(200);

        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", "CloudControlRole")
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .header("Authorization", IAM_AUTH)
                .when().post("/")
                .then().statusCode(200);

        assertListed("AWS::S3::Bucket", bucket, "BucketName");
        assertListed("AWS::EC2::VPC", vpcId, "CidrBlock");
        assertListed("AWS::EC2::Subnet", subnetId, "VpcId");
        assertListed("AWS::EC2::SecurityGroup", groupId, "GroupName");
        assertListed("AWS::EC2::SecurityGroup", groupId, "GroupName", "application/x-amz-json-1.0");
        assertListed("AWS::IAM::User", "cloudcontrol-user", "UserName");
        assertListed("AWS::IAM::Role", "CloudControlRole", "RoleName");
    }

    private void assertListed(String typeName, String identifier, String propertyName) {
        assertListed(typeName, identifier, propertyName, "application/x-amz-json-1.1");
    }

    private void assertListed(String typeName, String identifier, String propertyName, String contentType) {
        String body = given()
                .config(config().encoderConfig(
                        encoderConfig().encodeContentTypeAs(contentType, TEXT)))
                .contentType(contentType)
                .header("X-Amz-Target", "CloudApiService.ListResources")
                .body("{\"TypeName\":\"" + typeName + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().asString();

        assertThat(body, containsString("\"TypeName\":\"" + typeName + "\""));
        assertThat(body, containsString("\"Identifier\":\"" + identifier + "\""));
        assertThat(body, containsString(propertyName));
    }
}
