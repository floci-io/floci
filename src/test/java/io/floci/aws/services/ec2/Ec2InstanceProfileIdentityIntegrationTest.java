package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class Ec2InstanceProfileIdentityIntegrationTest {
    private static final String ACCOUNT = "246813579012";
    private static final String OTHER = "135792468013";
    private static final String LAUNCH_TAG = "floci-test-launch";
    @Inject Ec2Service ec2;
    @Inject ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"Name,false", "Arn,false", "Name,true", "Arn,true"})
    void launchUsesTheSameIamIdentityForEveryInstance(String field, boolean template) {
        String name = "profile-identity-" + UUID.randomUUID().toString().substring(0, 8);
        Profile profile = createProfile(ACCOUNT, name);
        List<String> instances = new ArrayList<>();
        String templateId = null;
        try {
            RequestSpecification run = tagged(request(ACCOUNT, "ec2", "RunInstances")
                    .formParam("MinCount", "2").formParam("MaxCount", "2"), name);
            String value = field.equals("Name") ? name : profile.arn();
            if (template) {
                templateId = request(ACCOUNT, "ec2", "CreateLaunchTemplate")
                        .formParam("LaunchTemplateName", name)
                        .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                        .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                        .formParam("LaunchTemplateData.IamInstanceProfile." + field, value)
                        .post("/").then().statusCode(200)
                        .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
                run.formParam("LaunchTemplate.LaunchTemplateId", templateId);
            } else {
                run.formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                        .formParam("IamInstanceProfile." + field, value);
            }
            XmlPath response = run.post("/").then().statusCode(200).extract().xmlPath();
            instances.addAll(response.getList("RunInstancesResponse.instancesSet.item.instanceId"));
            assertEquals(List.of(profile.id(), profile.id()),
                    response.getList("RunInstancesResponse.instancesSet.item.iamInstanceProfile.id"));
            assertEquals(instances.stream().sorted().toList(), launchedWith(name).stream().sorted().toList());
            for (String id : instances) {
                assertProfile(id, profile);
            }
        } finally {
            for (String id : instances) {
                terminate(id);
            }
            if (templateId != null) {
                request(ACCOUNT, "ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", templateId)
                        .post("/").then().statusCode(200);
            }
            deleteProfile(ACCOUNT, name);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidArnsCannotCreateInstances(boolean template) {
        String name = "profile-invalid-" + UUID.randomUUID().toString().substring(0, 8);
        Profile own = createProfile(ACCOUNT, name);
        Profile other = createProfile(OTHER, name);
        try {
            for (String arn : List.of(own.arn() + "-absent", other.arn(),
                    own.arn().replace("/workers/", "/other/"),
                    own.arn().replace(":instance-profile/", ":role/"),
                    own.arn().replace("arn:aws:", "arn:aws-cn:"))) {
                String launchTag = UUID.randomUUID().toString();
                String templateId = null;
                try {
                    RequestSpecification run = tagged(request(ACCOUNT, "ec2", "RunInstances")
                            .formParam("MinCount", "1").formParam("MaxCount", "1"), launchTag);
                    if (template) {
                        ValidatableResponse created = request(ACCOUNT, "ec2", "CreateLaunchTemplate")
                                .formParam("LaunchTemplateName", "invalid-" + launchTag)
                                .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                                .formParam("LaunchTemplateData.IamInstanceProfile.Arn", arn)
                                .post("/").then();
                        if (arn.contains(":role/") || arn.startsWith("arn:aws-cn:")) {
                            created.statusCode(400).body("Response.Errors.Error.Code",
                                    equalTo("InvalidIamInstanceProfileArn.Malformed"));
                            continue;
                        }
                        templateId = created.statusCode(200)
                                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
                        run.formParam("LaunchTemplate.LaunchTemplateId", templateId);
                    } else {
                        run.formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                                .formParam("IamInstanceProfile.Arn", arn);
                    }
                    run.post("/").then().statusCode(400)
                            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
                    assertEquals(List.of(), launchedWith(launchTag));
                } finally {
                    if (templateId != null) {
                        request(ACCOUNT, "ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", templateId)
                                .post("/").then().statusCode(200);
                    }
                }
            }
        } finally {
            deleteProfile(ACCOUNT, name);
            deleteProfile(OTHER, name);
        }
    }

    @Test
    void attachmentIdentityIsPersistedAndUnknownLegacyIdsAreOmitted() throws Exception {
        String name = "profile-stored-" + UUID.randomUUID().toString().substring(0, 8);
        Profile profile = createProfile(ACCOUNT, name);
        String id = request(ACCOUNT, "ec2", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("IamInstanceProfile.Name", name).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            Instance stored = ec2.findInstanceForAccount(ACCOUNT, "us-east-1", id).orElseThrow();
            Instance roundTrip = mapper.readValue(mapper.writeValueAsBytes(stored), Instance.class);
            assertEquals(profile.id(), roundTrip.getIamInstanceProfileId());
            roundTrip.setIamInstanceProfileId(null);
            assertNull(mapper.readValue(mapper.writeValueAsBytes(roundTrip), Instance.class)
                    .getIamInstanceProfileId());
            stored.setIamInstanceProfileId(null);
            request(ACCOUNT, "ec2", "DescribeInstances").formParam("InstanceId.1", id)
                    .post("/").then().statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.id.size()",
                            equalTo(0));
        } finally {
            terminate(id);
            deleteProfile(ACCOUNT, name);
        }
    }

    @Test
    void replacementUsesArnWhenBothSelectorsAreProvided() {
        String name = "profile-replace-" + UUID.randomUUID().toString().substring(0, 8);
        Profile first = createProfile(ACCOUNT, name);
        Profile second = createProfile(ACCOUNT, name + "-second");
        String id = request(ACCOUNT, "ec2", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("IamInstanceProfile.Name", name).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            String association = Ec2Service.iamInstanceProfileAssociationId(id);
            request(ACCOUNT, "ec2", "ReplaceIamInstanceProfileAssociation")
                    .formParam("AssociationId", association).formParam("IamInstanceProfile.Name", name)
                    .formParam("IamInstanceProfile.Arn", second.arn()).post("/").then().statusCode(200)
                    .body("ReplaceIamInstanceProfileAssociationResponse.iamInstanceProfileAssociation"
                            + ".iamInstanceProfile.id", equalTo(second.id()));
            assertProfile(id, second);
            request(ACCOUNT, "ec2", "DisassociateIamInstanceProfile").formParam("AssociationId", association)
                    .post("/").then().statusCode(200)
                    .body("DisassociateIamInstanceProfileResponse.iamInstanceProfileAssociation"
                            + ".iamInstanceProfile.id", equalTo(second.id()));
        } finally {
            terminate(id);
            deleteProfile(ACCOUNT, name);
            deleteProfile(ACCOUNT, name + "-second");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void launchRejectsBothProfileSelectors(boolean template) {
        String name = "profile-selectors-" + UUID.randomUUID().toString().substring(0, 8);
        Profile profile = createProfile(ACCOUNT, name);
        String launchTag = UUID.randomUUID().toString();
        String templateId = null;
        try {
            RequestSpecification run = tagged(request(ACCOUNT, "ec2", "RunInstances"), launchTag);
            if (template) {
                // As on AWS, a template is not fully validated when it is created: the pair is
                // stored, and the launch from it is what fails.
                templateId = request(ACCOUNT, "ec2", "CreateLaunchTemplate")
                        .formParam("LaunchTemplateName", name)
                        .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                        .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                        .formParam("LaunchTemplateData.IamInstanceProfile.Name", name)
                        .formParam("LaunchTemplateData.IamInstanceProfile.Arn", profile.arn())
                        .post("/").then().statusCode(200)
                        .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
                run.formParam("LaunchTemplate.LaunchTemplateId", templateId);
            } else {
                run.formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                        .formParam("IamInstanceProfile.Name", name)
                        .formParam("IamInstanceProfile.Arn", profile.arn());
            }
            run.post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
            assertEquals(List.of(), launchedWith(launchTag));
        } finally {
            if (templateId != null) {
                request(ACCOUNT, "ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", templateId)
                        .post("/").then().statusCode(200);
            }
            deleteProfile(ACCOUNT, name);
        }
    }

    private static void assertProfile(String instanceId, Profile profile) {
        request(ACCOUNT, "ec2", "DescribeInstances").formParam("InstanceId.1", instanceId)
                .post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.arn",
                        equalTo(profile.arn()))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.id",
                        equalTo(profile.id()));
        request(ACCOUNT, "ec2", "DescribeIamInstanceProfileAssociations")
                .formParam("Filter.1.Name", "instance-id").formParam("Filter.1.Value.1", instanceId)
                .post("/").then().statusCode(200)
                .body("DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet"
                        + ".item.iamInstanceProfile.id", equalTo(profile.id()));
    }

    private static Profile createProfile(String account, String name) {
        String prefix = "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.";
        XmlPath value = request(account, "iam", "CreateInstanceProfile")
                .formParam("InstanceProfileName", name).formParam("Path", "/workers/").post("/")
                .then().statusCode(200).extract().xmlPath();
        return new Profile(value.getString(prefix + "Arn"), value.getString(prefix + "InstanceProfileId"));
    }

    private static void deleteProfile(String account, String name) {
        request(account, "iam", "DeleteInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200);
    }

    /** Tags the launch's instances with a value unique to this test run, so a lookup finds only them. */
    private static RequestSpecification tagged(RequestSpecification run, String value) {
        return run.formParam("TagSpecification.1.ResourceType", "instance")
                .formParam("TagSpecification.1.Tag.1.Key", LAUNCH_TAG)
                .formParam("TagSpecification.1.Tag.1.Value", value);
    }

    private static List<String> launchedWith(String value) {
        return request(ACCOUNT, "ec2", "DescribeInstances").formParam("Filter.1.Name", "tag:" + LAUNCH_TAG)
                .formParam("Filter.1.Value.1", value).post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId");
    }

    private static void terminate(String instanceId) {
        request(ACCOUNT, "ec2", "TerminateInstances").formParam("InstanceId.1", instanceId)
                .post("/").then().statusCode(200);
    }

    private static RequestSpecification request(String account, String service, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(account, service))
                .formParam("Action", action);
    }

    private record Profile(String arn, String id) {}
}
