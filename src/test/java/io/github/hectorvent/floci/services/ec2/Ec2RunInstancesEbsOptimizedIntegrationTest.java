package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** RunInstances dropped EbsOptimized, so an instance launched with EbsOptimized=true read back false. */
@QuarkusTest
class Ec2RunInstancesEbsOptimizedIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void requestValueIsReadBack() {
        String id = runInstances(launch().formParam("EbsOptimized", "true"));
        try {
            assertEbsOptimized(id, "true");
        } finally {
            terminate(id);
        }
    }

    @Test
    void absentReadsBackFalse() {
        String id = runInstances(launch());
        try {
            assertEbsOptimized(id, "false");
        } finally {
            terminate(id);
        }
    }

    @Test
    void launchTemplateValueAppliesAndTheRequestOverridesIt() {
        String templateId = given().header("Authorization", AUTH).formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", "ebs-optimized-" + UUID.randomUUID())
                .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.EbsOptimized", "true")
                .post("/").then().statusCode(200).extract()
                .xmlPath().getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        try {
            String fromTemplate = runInstances(fromTemplate(templateId));
            try {
                assertEbsOptimized(fromTemplate, "true");
                String overridden = runInstances(fromTemplate(templateId).formParam("EbsOptimized", "false"));
                try {
                    assertEbsOptimized(overridden, "false");
                } finally {
                    terminate(overridden);
                }
            } finally {
                terminate(fromTemplate);
            }
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateId", templateId).post("/").then().statusCode(200);
        }
    }

    private static RequestSpecification launch() {
        return given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1");
    }

    private static RequestSpecification fromTemplate(String templateId) {
        return given().header("Authorization", AUTH).formParam("Action", "RunInstances")
                .formParam("LaunchTemplate.LaunchTemplateId", templateId)
                .formParam("MinCount", "1").formParam("MaxCount", "1");
    }

    private static String runInstances(RequestSpecification request) {
        return request.post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    private static void assertEbsOptimized(String id, String expected) {
        given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceAttribute")
                .formParam("InstanceId", id).formParam("Attribute", "ebsOptimized")
                .post("/").then().statusCode(200)
                .body("DescribeInstanceAttributeResponse.ebsOptimized.value.text()", equalTo(expected));
        given().header("Authorization", AUTH).formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", id).post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.ebsOptimized.text()",
                        equalTo(expected));
    }

    private static void terminate(String id) {
        given().header("Authorization", AUTH).formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", id).post("/").then().statusCode(200);
    }
}
