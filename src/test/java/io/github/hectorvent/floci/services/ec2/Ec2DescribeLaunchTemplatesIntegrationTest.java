package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2DescribeLaunchTemplatesIntegrationTest {

    private RequestSpecification request(String action, String region) {
        return given().formParam("Action", action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260908/"
                        + region + "/ec2/aws4_request");
    }

    private String create(String name) {
        return request("CreateLaunchTemplate", "us-east-1")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                .post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
    }

    @Test
    void missingNameReturnsAwsNotFound() {
        request("DescribeLaunchTemplates", "us-east-1")
                .formParam("LaunchTemplateName.1", "missing-" + UUID.randomUUID())
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateName.NotFoundException"));
    }

    @Test
    void missingIdReturnsAwsNotFound() {
        request("DescribeLaunchTemplates", "us-east-1")
                .formParam("LaunchTemplateId.1", "lt-00000000000000000")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateId.NotFound"));
    }

    @Test
    void existingTemplatesRoundTripAndMixedRequestsFail() {
        String name = "describe-" + UUID.randomUUID();
        String id = create(name);
        try {
            request("DescribeLaunchTemplates", "us-east-1")
                    .formParam("LaunchTemplateName.1", name)
                    .post("/").then().statusCode(200)
                    .body("DescribeLaunchTemplatesResponse.launchTemplates.item.launchTemplateId", equalTo(id));
            request("DescribeLaunchTemplates", "us-east-1")
                    .formParam("LaunchTemplateId.1", id)
                    .post("/").then().statusCode(200)
                    .body("DescribeLaunchTemplatesResponse.launchTemplates.item.launchTemplateName", equalTo(name));
            request("DescribeLaunchTemplates", "us-east-1")
                    .formParam("LaunchTemplateName.1", name)
                    .formParam("LaunchTemplateName.2", name + "-missing")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateName.NotFoundException"));
            request("DescribeLaunchTemplates", "us-east-1")
                    .formParam("LaunchTemplateId.1", id)
                    .formParam("LaunchTemplateId.2", "lt-00000000000000000")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateId.NotFound"));
            request("DescribeLaunchTemplates", "us-west-2")
                    .formParam("LaunchTemplateName.1", name)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateName.NotFoundException"));
            request("DescribeLaunchTemplates", "us-west-2")
                    .formParam("LaunchTemplateId.1", id)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateId.NotFound"));
        } finally {
            request("DeleteLaunchTemplate", "us-east-1").formParam("LaunchTemplateId", id)
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    void filtersDoNotTurnExistingResourcesIntoNotFoundErrors() {
        String name = "filter-" + UUID.randomUUID();
        String id = create(name);
        try {
            request("DescribeLaunchTemplates", "us-east-1")
                    .post("/").then().statusCode(200)
                    .body("DescribeLaunchTemplatesResponse.launchTemplates.item.find { it.launchTemplateId == '"
                            + id + "' }.launchTemplateId", equalTo(id));
            request("DescribeLaunchTemplates", "us-east-1")
                    .formParam("Filter.1.Name", "launch-template-name")
                    .formParam("Filter.1.Value.1", name + "-missing")
                    .post("/").then().statusCode(200)
                    .body("DescribeLaunchTemplatesResponse.launchTemplates.item.size()", equalTo(0));
            request("DescribeLaunchTemplates", "us-east-1")
                    .formParam("LaunchTemplateName.1", name)
                    .formParam("Filter.1.Name", "launch-template-name")
                    .formParam("Filter.1.Value.1", name + "-missing")
                    .post("/").then().statusCode(200)
                    .body("DescribeLaunchTemplatesResponse.launchTemplates.item.size()", equalTo(0));
            request("DescribeLaunchTemplates", "us-east-1")
                    .formParam("LaunchTemplateId.1", id)
                    .formParam("Filter.1.Name", "launch-template-name")
                    .formParam("Filter.1.Value.1", name + "-missing")
                    .post("/").then().statusCode(200)
                    .body("DescribeLaunchTemplatesResponse.launchTemplates.item.size()", equalTo(0));
        } finally {
            request("DeleteLaunchTemplate", "us-east-1").formParam("LaunchTemplateId", id)
                    .post("/").then().statusCode(200);
        }
    }
}
