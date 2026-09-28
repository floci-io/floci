package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;

/**
 * The IAM instance profile association actions: associate by name or ARN, replace behind the
 * association id, disassociate, and the errors AWS answers for a second association and an unknown
 * association id. The profile a request names by {@code Name} must exist in IAM, as on AWS.
 */
@QuarkusTest
class Ec2IamInstanceProfileAssociationIntegrationTest {

    private static final String EC2_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String IAM_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";
    private static final String ASSOCIATIONS =
            "DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet";

    @Test
    void associateReplaceAndDisassociateAProfile() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String first = "assoc-" + suffix;
        String second = "assoc-v2-" + suffix;
        String firstArn = createProfile(first);
        String secondArn = createProfile(second);
        String instanceId = given().header("Authorization", EC2_AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            String associationId = given().header("Authorization", EC2_AUTH)
                    .formParam("Action", "AssociateIamInstanceProfile")
                    .formParam("InstanceId", instanceId)
                    .formParam("IamInstanceProfile.Name", first)
                    .post("/").then().statusCode(200)
                    .body("AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.instanceId",
                            equalTo(instanceId))
                    .body("AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.iamInstanceProfile.arn",
                            equalTo(firstArn))
                    .body("AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.iamInstanceProfile.id",
                            startsWith("AIPA"))
                    .body("AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.state",
                            equalTo("associating"))
                    .body("AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.timestamp",
                            matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"))
                    .extract().path("AssociateIamInstanceProfileResponse.iamInstanceProfileAssociation.associationId");

            // One profile per instance: a second association is refused, not stacked.
            given().header("Authorization", EC2_AUTH).formParam("Action", "AssociateIamInstanceProfile")
                    .formParam("InstanceId", instanceId).formParam("IamInstanceProfile.Arn", secondArn)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("IncorrectState"));
            assertAssociated(instanceId, associationId, firstArn);

            given().header("Authorization", EC2_AUTH).formParam("Action", "ReplaceIamInstanceProfileAssociation")
                    .formParam("AssociationId", associationId).formParam("IamInstanceProfile.Arn", secondArn)
                    .post("/").then().statusCode(200)
                    .body("ReplaceIamInstanceProfileAssociationResponse.iamInstanceProfileAssociation.associationId",
                            equalTo(associationId))
                    .body("ReplaceIamInstanceProfileAssociationResponse.iamInstanceProfileAssociation.iamInstanceProfile.arn",
                            equalTo(secondArn))
                    .body("ReplaceIamInstanceProfileAssociationResponse.iamInstanceProfileAssociation.state",
                            equalTo("associating"));
            assertAssociated(instanceId, associationId, secondArn);

            given().header("Authorization", EC2_AUTH).formParam("Action", "DisassociateIamInstanceProfile")
                    .formParam("AssociationId", associationId)
                    .post("/").then().statusCode(200)
                    .body("DisassociateIamInstanceProfileResponse.iamInstanceProfileAssociation.iamInstanceProfile.arn",
                            equalTo(secondArn))
                    .body("DisassociateIamInstanceProfileResponse.iamInstanceProfileAssociation.state",
                            equalTo("disassociating"));
            given().header("Authorization", EC2_AUTH).formParam("Action", "DescribeIamInstanceProfileAssociations")
                    .formParam("Filter.1.Name", "instance-id").formParam("Filter.1.Value.1", instanceId)
                    .post("/").then().statusCode(200)
                    .body(ASSOCIATIONS + ".item.size()", equalTo(0));
            given().header("Authorization", EC2_AUTH).formParam("Action", "DisassociateIamInstanceProfile")
                    .formParam("AssociationId", associationId)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidAssociationID.NotFound"));
        } finally {
            given().header("Authorization", EC2_AUTH).formParam("Action", "TerminateInstances")
                    .formParam("InstanceId.1", instanceId).post("/").then().statusCode(200);
            deleteProfile(first);
            deleteProfile(second);
        }
    }

    @Test
    void associatingAnUnknownProfileNameIsRefused() {
        String instanceId = given().header("Authorization", EC2_AUTH).formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            given().header("Authorization", EC2_AUTH).formParam("Action", "AssociateIamInstanceProfile")
                    .formParam("InstanceId", instanceId).formParam("IamInstanceProfile.Name", "no-such-profile")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        } finally {
            given().header("Authorization", EC2_AUTH).formParam("Action", "TerminateInstances")
                    .formParam("InstanceId.1", instanceId).post("/").then().statusCode(200);
        }
    }

    private static void assertAssociated(String instanceId, String associationId, String arn) {
        given().header("Authorization", EC2_AUTH).formParam("Action", "DescribeIamInstanceProfileAssociations")
                .formParam("Filter.1.Name", "instance-id").formParam("Filter.1.Value.1", instanceId)
                .post("/").then().statusCode(200)
                .body(ASSOCIATIONS + ".item.associationId", equalTo(associationId))
                .body(ASSOCIATIONS + ".item.iamInstanceProfile.arn", equalTo(arn))
                .body(ASSOCIATIONS + ".item.state", equalTo("associated"))
                .body(ASSOCIATIONS + ".item.timestamp",
                        matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    private static String createProfile(String name) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", "CreateInstanceProfile")
                .formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200)
                .extract().path("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
    }

    private static void deleteProfile(String name) {
        given().header("Authorization", IAM_AUTH).formParam("Action", "DeleteInstanceProfile")
                .formParam("InstanceProfileName", name).post("/").then().statusCode(200);
    }
}
