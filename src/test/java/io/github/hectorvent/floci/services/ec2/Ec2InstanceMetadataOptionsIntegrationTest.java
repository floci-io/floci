package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

/**
 * Instance metadata options on the Query wire. A launch that names MetadataOptions.* is
 * honoured per instance, DescribeInstances reads the stored values back, and
 * ModifyInstanceMetadataOptions changes only what it names. terraform-aws-modules'
 * ec2-instance module defaults to http_tokens = required, so a hardcoded "optional" on read
 * produced a permanent second plan.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2InstanceMetadataOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String INSTANCE = "RunInstancesResponse.instancesSet.item.";
    private static final String DESCRIBED =
            "DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.";

    private static String instanceId;

    @Test
    @Order(1)
    void aLaunchWithNoMetadataOptionsGetsAwsDefaults() {
        instanceId = given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(INSTANCE + "metadataOptions.state", equalTo("applied"))
                .body(INSTANCE + "metadataOptions.httpTokens", equalTo("optional"))
                .body(INSTANCE + "metadataOptions.httpPutResponseHopLimit", equalTo("1"))
                .body(INSTANCE + "metadataOptions.httpEndpoint", equalTo("enabled"))
                .body(INSTANCE + "metadataOptions.httpProtocolIpv6", equalTo("disabled"))
                .body(INSTANCE + "metadataOptions.instanceMetadataTags", equalTo("disabled"))
                .extract().path(INSTANCE + "instanceId");
    }

    @Test
    @Order(2)
    void anExplicitLaunchRequestIsHonouredAndReadBack() {
        String id = given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .formParam("MetadataOptions.HttpTokens", "required")
                .formParam("MetadataOptions.HttpPutResponseHopLimit", "2")
                .formParam("MetadataOptions.InstanceMetadataTags", "enabled")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(INSTANCE + "metadataOptions.httpTokens", equalTo("required"))
                .body(INSTANCE + "metadataOptions.httpPutResponseHopLimit", equalTo("2"))
                .body(INSTANCE + "metadataOptions.instanceMetadataTags", equalTo("enabled"))
                // Fields the launch left out keep the AWS default.
                .body(INSTANCE + "metadataOptions.httpEndpoint", equalTo("enabled"))
                .extract().path(INSTANCE + "instanceId");

        given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", id)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(DESCRIBED + "httpTokens", equalTo("required"))
                .body(DESCRIBED + "httpPutResponseHopLimit", equalTo("2"))
                .body(DESCRIBED + "instanceMetadataTags", equalTo("enabled"));
    }

    @Test
    @Order(3)
    void modifyInstanceMetadataOptionsChangesOnlyWhatItNames() {
        String response = "ModifyInstanceMetadataOptionsResponse.";
        given()
                .formParam("Action", "ModifyInstanceMetadataOptions")
                .formParam("InstanceId", instanceId)
                .formParam("HttpTokens", "required")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(response + "instanceId", equalTo(instanceId))
                .body(response + "instanceMetadataOptions.state", equalTo("applied"))
                .body(response + "instanceMetadataOptions.httpTokens", equalTo("required"))
                // Not named in the call, so the launch values stay.
                .body(response + "instanceMetadataOptions.httpPutResponseHopLimit", equalTo("1"))
                .body(response + "instanceMetadataOptions.httpEndpoint", equalTo("enabled"));

        given()
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(DESCRIBED + "httpTokens", equalTo("required"))
                .body(DESCRIBED + "httpPutResponseHopLimit", equalTo("1"));
    }

    @Test
    @Order(4)
    void modifyInstanceMetadataOptionsRejectsAValueOutsideTheEnumeration() {
        given()
                .formParam("Action", "ModifyInstanceMetadataOptions")
                .formParam("InstanceId", instanceId)
                .formParam("HttpEndpoint", "sometimes")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(5)
    void aLaunchTemplateSuppliesTheOptionsAndTheLaunchOverridesThem() {
        String templateName = "imds-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", templateName)
                .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
                .formParam("LaunchTemplateData.MetadataOptions.HttpPutResponseHopLimit", "3")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "RunInstances")
                .formParam("LaunchTemplate.LaunchTemplateName", templateName)
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .formParam("MetadataOptions.HttpPutResponseHopLimit", "5")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(INSTANCE + "metadataOptions.httpTokens", equalTo("required"))
                .body(INSTANCE + "metadataOptions.httpPutResponseHopLimit", equalTo("5"))
                .body(INSTANCE + "metadataOptions.httpEndpoint", equalTo("enabled"));
    }
}
