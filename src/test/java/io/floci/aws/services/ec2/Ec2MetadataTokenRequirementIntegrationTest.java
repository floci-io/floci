package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * The HttpTokens an instance is launched with, and any later ModifyInstanceMetadataOptions, decide
 * whether IMDS still answers a request without a token: on AWS a required token turns IMDSv1 reads
 * into 401. The stored instance is registered with a metadata server of its own, as a launched
 * container would be.
 */
@QuarkusTest
class Ec2MetadataTokenRequirementIntegrationTest {
    private static final String ACCOUNT = "357924681357";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE_ID = "/latest/meta-data/instance-id";

    @Inject
    Ec2Service ec2;

    @Test
    void launchAndModifiedTokenRequirementsApplyToTheRunningInstance() throws Exception {
        String instanceId = request("RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("MetadataOptions.HttpTokens", "required")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            Instance stored = ec2.findInstanceForAccount(ACCOUNT, REGION, instanceId).orElseThrow();
            fixture.start(null, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), stored);

            assertEquals(401, fixture.get(INSTANCE_ID, null).statusCode());
            HttpResponse<String> withToken = fixture.get(INSTANCE_ID, fixture.putToken("60").body());
            assertEquals(200, withToken.statusCode());
            assertEquals(instanceId, withToken.body());

            modifyHttpTokens(instanceId, "optional");
            HttpResponse<String> withoutToken = fixture.get(INSTANCE_ID, null);
            assertEquals(200, withoutToken.statusCode());
            assertEquals(instanceId, withoutToken.body());

            modifyHttpTokens(instanceId, "required");
            assertEquals(401, fixture.get(INSTANCE_ID, null).statusCode());
        } finally {
            request("TerminateInstances").formParam("InstanceId.1", instanceId)
                    .post("/").then().statusCode(200);
        }
    }

    private static void modifyHttpTokens(String instanceId, String httpTokens) {
        request("ModifyInstanceMetadataOptions").formParam("InstanceId", instanceId)
                .formParam("HttpTokens", httpTokens)
                .post("/").then().statusCode(200)
                .body("ModifyInstanceMetadataOptionsResponse.instanceMetadataOptions.httpTokens",
                        equalTo(httpTokens));
    }

    private static RequestSpecification request(String action) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, REGION, "ec2"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
    }
}
