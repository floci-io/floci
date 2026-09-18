package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2InstanceProfilePathIntegrationTest {
    @Inject IamService iam;
    @Inject Ec2Service ec2;

    @ParameterizedTest
    @ValueSource(strings = {"direct", "template", "fleet"})
    void resolvesProfileInCallerAccountAndPreservesItsPath(String launch) {
        String name = "profile-path-" + UUID.randomUUID().toString().substring(0, 8);
        String account = "246813579012";
        String other = "135792468013";
        String arn = "arn:aws:iam::" + account + ":instance-profile/workers/nested/" + name;
        String roleArn = "arn:aws:iam::" + account + ":role/" + name;
        String template = null;
        String instanceId = null;
        request(account, "iam", "CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", "{}").post("/").then().statusCode(200);
        request(account, "iam", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .formParam("Path", "/workers/nested/").post("/").then().statusCode(200);
        request(other, "iam", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .formParam("Path", "/other/").post("/").then().statusCode(200);
        request(account, "iam", "AddRoleToInstanceProfile").formParam("InstanceProfileName", name)
                .formParam("RoleName", name).post("/").then().statusCode(200);
        Ec2InstanceCredentials credentials = new Ec2InstanceCredentials(iam);
        try {
            RequestSpecification run = request(account, "ec2", "RunInstances")
                    .formParam("MinCount", "1").formParam("MaxCount", "1");
            if (launch.equals("direct")) {
                run.formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                        .formParam("IamInstanceProfile.Name", name);
            } else {
                template = request(account, "ec2", "CreateLaunchTemplate")
                        .formParam("LaunchTemplateName", name)
                        .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                        .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                        .formParam("LaunchTemplateData.IamInstanceProfile.Name", name)
                        .post("/").then().statusCode(200)
                        .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
                run.formParam("LaunchTemplate.LaunchTemplateId", template)
                        .formParam("LaunchTemplate.Version", "1");
            }
            if (launch.equals("fleet")) {
                instanceId = request(account, "ec2", "CreateFleet").formParam("Type", "instant")
                        .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                        .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                        .formParam("TargetCapacitySpecification.TotalTargetCapacity", "1")
                        .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                        .post("/").then().statusCode(200)
                        .extract().path("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item");
            } else {
                instanceId = run.post("/").then().statusCode(200)
                        .body("RunInstancesResponse.instancesSet.item.iamInstanceProfile.arn", equalTo(arn))
                        .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
            }
            request(account, "ec2", "DescribeInstances").formParam("InstanceId.1", instanceId)
                    .post("/").then().statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.arn",
                            equalTo(arn));
            Instance instance = ec2.findInstanceForAccount(account, "us-east-1", instanceId).orElseThrow();
            credentials.register(instance);
            assertEquals(roleArn, credentials.role(instance).orElseThrow().getArn());
            assertTrue(credentials.get(instance, name, Instant.now()).isPresent());
        } finally {
            credentials.clear();
            if (instanceId != null) {
                request(account, "ec2", "TerminateInstances").formParam("InstanceId.1", instanceId)
                        .post("/").then().statusCode(200);
            }
            if (template != null) {
                request(account, "ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", template)
                        .post("/").then().statusCode(200);
            }
            request(account, "iam", "RemoveRoleFromInstanceProfile").formParam("InstanceProfileName", name)
                    .formParam("RoleName", name).post("/").then().statusCode(200);
            for (String owner : new String[]{account, other}) {
                request(owner, "iam", "DeleteInstanceProfile").formParam("InstanceProfileName", name)
                        .post("/").then().statusCode(200);
            }
            request(account, "iam", "DeleteRole").formParam("RoleName", name).post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNamesMissingFromCallerAccount(boolean existsInOtherAccount) {
        String name = "missing-profile-" + UUID.randomUUID().toString().substring(0, 8);
        String other = "135792468013";
        if (existsInOtherAccount) {
            request(other, "iam", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                    .post("/").then().statusCode(200);
        }
        try {
            request("246813579012", "ec2", "RunInstances")
                    .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                    .formParam("MinCount", "1").formParam("MaxCount", "1")
                    .formParam("IamInstanceProfile.Name", name).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        } finally {
            if (existsInOtherAccount) {
                request(other, "iam", "DeleteInstanceProfile").formParam("InstanceProfileName", name)
                        .post("/").then().statusCode(200);
            }
        }
    }

    private static RequestSpecification request(String account, String service, String action) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260918/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", action);
    }
}
