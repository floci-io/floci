package io.floci.aws.services.ec2;

import io.floci.aws.testing.ServicesOffSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(ServicesOffSettingsProfile.class)
class Ec2VcpuAdmissionIntegrationTest {
    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260205/us-east-1/" + service + "/aws4_request";
    }

    @Test
    void directAndTemplateSpotDoNotConsumeOnDemandCapacityAndRequestOverridesTemplate() {
        String account = "687432101234";
        String header = auth(account, "ec2");
        given().header("Authorization", header).formParams("Action", "RunInstances",
                "ImageId", "ami-0abcdef1234567890", "InstanceType", "t3.micro", "MinCount", "1", "MaxCount", "1")
                .post("/").then().statusCode(200);
        given().header("Authorization", header).formParam("Action", "DescribeInstances")
                .post("/").then().statusCode(200).body(containsString("<name>running</name>"));
        try {
            given().header("Authorization", header).formParams("Action", "RunInstances",
                    "ImageId", "ami-0abcdef1234567890", "InstanceType", "t3.micro", "MinCount", "1", "MaxCount", "1",
                    "InstanceMarketOptions.MarketType", "spot")
                    .post("/").then().statusCode(200).body(containsString("<instanceLifecycle>spot</instanceLifecycle>"));
            String template = given().header("Authorization", header).formParams("Action", "CreateLaunchTemplate",
                    "LaunchTemplateName", "quota-market-" + UUID.randomUUID(),
                    "LaunchTemplateData.ImageId", "ami-0abcdef1234567890", "LaunchTemplateData.InstanceType", "t3.micro",
                    "LaunchTemplateData.InstanceMarketOptions.MarketType", "spot")
                    .post("/").then().statusCode(200)
                    .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
            try {
                given().header("Authorization", header).formParams("Action", "RunInstances",
                        "LaunchTemplate.LaunchTemplateId", template, "MinCount", "1", "MaxCount", "1")
                        .post("/").then().statusCode(200)
                        .body(containsString("<instanceLifecycle>spot</instanceLifecycle>"));
                given().header("Authorization", header).formParams("Action", "RunInstances",
                        "LaunchTemplate.LaunchTemplateId", template, "MinCount", "1", "MaxCount", "1",
                        "InstanceMarketOptions.MarketType", "on-demand")
                        .post("/").then().statusCode(400).body(containsString("VcpuLimitExceeded"));
                given().header("Authorization", header).formParam("Action", "DescribeInstances")
                        .post("/").then().statusCode(200)
                        .body(containsString("<instanceLifecycle>spot</instanceLifecycle>"));
            } finally {
                given().header("Authorization", header).formParams("Action", "DeleteLaunchTemplate",
                        "LaunchTemplateId", template).post("/").then().statusCode(200);
            }
        } finally {
            given().post("/_floci/state/reset").then().statusCode(200);
        }
    }

}
