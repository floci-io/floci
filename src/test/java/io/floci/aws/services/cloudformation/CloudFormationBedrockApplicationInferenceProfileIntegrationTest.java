package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.GetInferenceProfileResponse;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileType;
import software.amazon.awssdk.services.bedrock.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrock.model.Tag;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationBedrockApplicationInferenceProfileIntegrationTest {

    @Inject
    CloudFormationService cloudFormationService;

    @Inject
    CfnResourceDispatcher dispatcher;

    private static final String ACCOUNT = "000000000000";
    private static final Region REGION = Region.US_EAST_1;
    private static final String STACK = "cfn-bedrock-application-profile-it";
    private static final String PROFILE_NAME = "cfn-bedrock-application-profile";
    private static final String SOURCE_ARN =
            "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-micro-v1:0";
    private static final String OTHER_SOURCE_ARN =
            "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0";

    private static String template(String profileName, String tagValue) {
        return template(profileName, tagValue, SOURCE_ARN);
    }

    private static String template(String profileName, String tagValue, String sourceArn) {
        return """
            {
              "Resources": {
                "Profile": {
                  "Type": "AWS::Bedrock::ApplicationInferenceProfile",
                  "Properties": {
                    "InferenceProfileName": "%s",
                    "ModelSource": {"CopyFrom": "%s"},
                    "Tags": [{"Key": "team", "Value": "%s"}]
                  }
                }
              },
              "Outputs": {
                "ProfileRef": {"Value": {"Ref": "Profile"}},
                "ProfileId": {"Value": {"Fn::GetAtt": ["Profile", "InferenceProfileId"]}},
                "ProfileArn": {"Value": {"Fn::GetAtt": ["Profile", "InferenceProfileArn"]}},
                "ProfileIdentifier": {"Value": {"Fn::GetAtt": ["Profile", "InferenceProfileIdentifier"]}},
                "ProfileType": {"Value": {"Fn::GetAtt": ["Profile", "Type"]}},
                "ProfileStatus": {"Value": {"Fn::GetAtt": ["Profile", "Status"]}},
                "ProfileCreatedAt": {"Value": {"Fn::GetAtt": ["Profile", "CreatedAt"]}},
                "ProfileUpdatedAt": {"Value": {"Fn::GetAtt": ["Profile", "UpdatedAt"]}}
              }
            }
            """.formatted(profileName, sourceArn, tagValue);
    }

    @Test
    void stackCreatesAnApplicationProfileAndExposesItsIdAndArn() {
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, REGION);
             BedrockClient bedrock = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, REGION)) {
            Stack stack = CfnStackWaits.createStack(cfn, STACK, template(PROFILE_NAME, "infra"), "CREATE_COMPLETE");
            try {
                List<InferenceProfileSummary> profiles = bedrock.listInferenceProfiles(request -> request
                                .typeEquals(InferenceProfileType.APPLICATION))
                        .inferenceProfileSummaries().stream()
                        .filter(profile -> PROFILE_NAME.equals(profile.inferenceProfileName()))
                        .toList();
                assertEquals(1, profiles.size(), "CloudFormation must create a backing Bedrock application profile");

                InferenceProfileSummary profile = profiles.getFirst();
                String id = profile.inferenceProfileId();
                String arn = "arn:aws:bedrock:us-east-1:" + ACCOUNT + ":application-inference-profile/" + id;
                assertEquals(arn, profile.inferenceProfileArn());
                assertEquals(id, CfnStackWaits.output(stack, "ProfileRef"));
                assertEquals(id, CfnStackWaits.output(stack, "ProfileId"));
                assertEquals(arn, CfnStackWaits.output(stack, "ProfileArn"));
                assertEquals(id, CfnStackWaits.output(stack, "ProfileIdentifier"));
                assertEquals("APPLICATION", CfnStackWaits.output(stack, "ProfileType"));
                assertEquals("ACTIVE", CfnStackWaits.output(stack, "ProfileStatus"));

                GetInferenceProfileResponse fetched = bedrock.getInferenceProfile(request -> request
                        .inferenceProfileIdentifier(id));
                assertEquals(PROFILE_NAME, fetched.inferenceProfileName());
                assertEquals(id, fetched.inferenceProfileId());
                assertEquals(arn, fetched.inferenceProfileArn());
                assertEquals(InferenceProfileType.APPLICATION, fetched.type());
                assertEquals(fetched.createdAt(), Instant.parse(CfnStackWaits.output(stack, "ProfileCreatedAt")));
                assertEquals(fetched.updatedAt(), Instant.parse(CfnStackWaits.output(stack, "ProfileUpdatedAt")));
                assertEquals(List.of(SOURCE_ARN), fetched.models().stream().map(model -> model.modelArn()).toList());
                StackResource resource = cloudFormationService.describeStackResources(STACK, REGION.id()).stream()
                        .filter(item -> "Profile".equals(item.getLogicalId()))
                        .findFirst().orElseThrow();
                JsonNode models = dispatcher.attributeValue(resource.getResourceType(), "Models",
                        resource.getAttributes().get("Models"));
                assertTrue(models.isArray(), "Models GetAtt must be an array, not a JSON string");
                assertEquals(SOURCE_ARN, models.get(0).path("ModelArn").asText());
                assertEquals(List.of(Tag.builder().key("team").value("infra").build()),
                        bedrock.listTagsForResource(request -> request.resourceARN(arn)).tags());

                Stack tagUpdate = CfnStackWaits.updateStack(cfn, STACK,
                        template(PROFILE_NAME, "platform"), "UPDATE_COMPLETE");
                assertEquals(id, CfnStackWaits.output(tagUpdate, "ProfileRef"));
                assertEquals(List.of(Tag.builder().key("team").value("platform").build()),
                        bedrock.listTagsForResource(request -> request.resourceARN(arn)).tags());

                String replacementName = PROFILE_NAME + "-replacement";
                Stack replacement = CfnStackWaits.updateStack(cfn, STACK,
                        template(replacementName, "platform"), "UPDATE_COMPLETE");
                String replacementId = CfnStackWaits.output(replacement, "ProfileRef");
                assertNotEquals(id, replacementId);
                assertEquals(replacementName, bedrock.getInferenceProfile(request -> request
                        .inferenceProfileIdentifier(replacementId)).inferenceProfileName());
                assertThrows(ResourceNotFoundException.class,
                        () -> bedrock.getInferenceProfile(request -> request.inferenceProfileIdentifier(id)));
            } finally {
                CfnStackWaits.deleteStack(cfn, STACK);
                List<InferenceProfileSummary> remaining = bedrock.listInferenceProfiles(request -> request
                                .typeEquals(InferenceProfileType.APPLICATION))
                        .inferenceProfileSummaries().stream()
                        .filter(profile -> profile.inferenceProfileName().startsWith(PROFILE_NAME))
                        .toList();
                assertTrue(remaining.isEmpty(), "CloudFormation must delete its backing Bedrock profile");
            }
        }
    }

    @Test
    void missingModelSourceFailsWithoutCreatingAnApplicationProfile() {
        String name = PROFILE_NAME + "-no-source";
        String stackName = STACK + "-no-source";
        String template = """
                {"Resources":{"Profile":{"Type":"AWS::Bedrock::ApplicationInferenceProfile",
                "Properties":{"InferenceProfileName":"%s"}}}}
                """.formatted(name);
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, REGION);
             BedrockClient bedrock = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, REGION)) {
            try {
                CfnStackWaits.createStack(cfn, stackName, template, "ROLLBACK_COMPLETE");
                assertTrue(bedrock.listInferenceProfiles(request -> request
                                .typeEquals(InferenceProfileType.APPLICATION))
                        .inferenceProfileSummaries().stream()
                        .noneMatch(profile -> name.equals(profile.inferenceProfileName())));
            } finally {
                CfnStackWaits.deleteStack(cfn, stackName);
            }
        }
    }

    @Test
    void wholePropertyConditionsResolveModelSourceAndTagsBeforeProvisioning() {
        String stackName = STACK + "-conditional";
        String name = PROFILE_NAME + "-conditional";
        String template = """
                {"Conditions":{"UsePrimary":{"Fn::Equals":["primary","primary"]}},
                 "Resources":{"Profile":{"Type":"AWS::Bedrock::ApplicationInferenceProfile",
                   "Properties":{"InferenceProfileName":"%s",
                     "ModelSource":{"Fn::If":["UsePrimary",{"CopyFrom":"%s"},{"CopyFrom":"%s"}]},
                     "Tags":{"Fn::If":["UsePrimary",[{"Key":"team","Value":"chosen"}],
                       [{"Key":"team","Value":"unused"}]]}}}},
                 "Outputs":{"ProfileRef":{"Value":{"Ref":"Profile"}}}}
                """.formatted(name, SOURCE_ARN, OTHER_SOURCE_ARN);
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, REGION);
             BedrockClient bedrock = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, REGION)) {
            Stack created = CfnStackWaits.createStack(cfn, stackName, template, "CREATE_COMPLETE");
            try {
                String id = CfnStackWaits.output(created, "ProfileRef");
                GetInferenceProfileResponse profile = bedrock.getInferenceProfile(request -> request
                        .inferenceProfileIdentifier(id));
                assertEquals(List.of(SOURCE_ARN), profile.models().stream()
                        .map(model -> model.modelArn()).toList());
                assertEquals(List.of(Tag.builder().key("team").value("chosen").build()),
                        bedrock.listTagsForResource(request -> request.resourceARN(profile.inferenceProfileArn()))
                                .tags());
            } finally {
                CfnStackWaits.deleteStack(cfn, stackName);
            }
        }
    }

    @Test
    void externallyDeletedPriorProfileBlocksReplacementCreation() {
        String stackName = STACK + "-deleted-prior";
        String name = PROFILE_NAME + "-deleted-prior";
        String replacementName = name + "-replacement";
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, REGION);
             BedrockClient bedrock = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, REGION)) {
            Stack created = CfnStackWaits.createStack(cfn, stackName, template(name, "original"), "CREATE_COMPLETE");
            try {
                String id = CfnStackWaits.output(created, "ProfileRef");
                bedrock.deleteInferenceProfile(request -> request.inferenceProfileIdentifier(id));

                CfnStackWaits.updateStack(cfn, stackName,
                        template(replacementName, "changed"), "UPDATE_ROLLBACK_COMPLETE");

                assertTrue(bedrock.listInferenceProfiles(request -> request
                                .typeEquals(InferenceProfileType.APPLICATION)).inferenceProfileSummaries().stream()
                        .noneMatch(profile -> replacementName.equals(profile.inferenceProfileName())));
            } finally {
                CfnStackWaits.deleteStack(cfn, stackName);
            }
        }
    }

    @Test
    void failedUpdateRestoresApplicationProfileTags() {
        String stackName = STACK + "-tag-rollback";
        String name = PROFILE_NAME + "-tag-rollback";
        String failedTemplate = """
                {"Resources":{
                  "Profile":{"Type":"AWS::Bedrock::ApplicationInferenceProfile",
                    "Properties":{"InferenceProfileName":"%s",
                      "ModelSource":{"CopyFrom":"%s"},
                      "Tags":[{"Key":"team","Value":"changed"}]}},
                  "Later":{"Type":"AWS::EC2::Instance","DependsOn":"Profile",
                    "Properties":{"LaunchTemplate":{
                      "LaunchTemplateId":"lt-0123456789abcdef0","Version":"1"}}}
                }}
                """.formatted(name, SOURCE_ARN);
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, REGION);
             BedrockClient bedrock = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, REGION)) {
            Stack created = CfnStackWaits.createStack(cfn, stackName, template(name, "original"), "CREATE_COMPLETE");
            try {
                String id = CfnStackWaits.output(created, "ProfileRef");
                CfnStackWaits.updateStack(cfn, stackName, failedTemplate, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(id, CfnStackWaits.output(cfn.describeStacks(request -> request.stackName(stackName))
                        .stacks().getFirst(), "ProfileRef"));
                String arn = bedrock.getInferenceProfile(request -> request.inferenceProfileIdentifier(id))
                        .inferenceProfileArn();
                assertEquals(List.of(Tag.builder().key("team").value("original").build()),
                        bedrock.listTagsForResource(request -> request.resourceARN(arn)).tags());
            } finally {
                CfnStackWaits.deleteStack(cfn, stackName);
            }
        }
    }

    @Test
    void updatePreservesTagsAddedOutsideTheTemplate() {
        String stackName = STACK + "-external-tag";
        String name = PROFILE_NAME + "-external-tag";
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, REGION);
             BedrockClient bedrock = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, REGION)) {
            Stack created = CfnStackWaits.createStack(cfn, stackName, template(name, "original"), "CREATE_COMPLETE");
            try {
                String id = CfnStackWaits.output(created, "ProfileRef");
                String arn = bedrock.getInferenceProfile(request -> request.inferenceProfileIdentifier(id))
                        .inferenceProfileArn();
                Tag external = Tag.builder().key("external").value("keep").build();
                bedrock.tagResource(request -> request.resourceARN(arn).tags(external));

                CfnStackWaits.updateStack(cfn, stackName, template(name, "updated"), "UPDATE_COMPLETE");

                List<Tag> tags = bedrock.listTagsForResource(request -> request.resourceARN(arn)).tags();
                assertEquals(2, tags.size());
                assertTrue(tags.contains(Tag.builder().key("team").value("updated").build()));
                assertTrue(tags.contains(external));
            } finally {
                CfnStackWaits.deleteStack(cfn, stackName);
            }
        }
    }

    @Test
    void sourceReplacementWithTheSameNameKeepsThePriorProfile() {
        String stackName = STACK + "-source-rollback";
        String name = PROFILE_NAME + "-source-rollback";
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, REGION);
             BedrockClient bedrock = AwsTestClients.create(BedrockClient.builder(), ACCOUNT, REGION)) {
            Stack created = CfnStackWaits.createStack(cfn, stackName, template(name, "original"), "CREATE_COMPLETE");
            try {
                String id = CfnStackWaits.output(created, "ProfileRef");
                CfnStackWaits.updateStack(cfn, stackName,
                        template(name, "original", OTHER_SOURCE_ARN), "UPDATE_ROLLBACK_COMPLETE");
                Stack rolledBack = cfn.describeStacks(request -> request.stackName(stackName)).stacks().getFirst();
                assertEquals(id, CfnStackWaits.output(rolledBack, "ProfileRef"));
                assertEquals(List.of(SOURCE_ARN), bedrock.getInferenceProfile(request -> request
                        .inferenceProfileIdentifier(id)).models().stream().map(model -> model.modelArn()).toList());
                assertEquals(1, bedrock.listInferenceProfiles(request -> request.typeEquals(
                                InferenceProfileType.APPLICATION)).inferenceProfileSummaries().stream()
                        .filter(profile -> name.equals(profile.inferenceProfileName())).count());
            } finally {
                CfnStackWaits.deleteStack(cfn, stackName);
            }
        }
    }
}
