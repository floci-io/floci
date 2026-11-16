package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.ses.SesConfigurationSetService;
import io.floci.aws.services.ses.model.EventDestination;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSesEventDestinationIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private CloudFormationClient cfn;
    private String stackName;
    private boolean deleted;

    @Inject
    SesConfigurationSetService configurationSets;

    @BeforeEach
    void setUp() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        stackName = "cfn-ses-ed-" + Long.toString(System.nanoTime(), 36);
        deleted = false;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (!deleted) {
                cfn.deleteStack(request -> request.stackName(stackName));
                await().atMost(Duration.ofSeconds(15)).until(this::stackIsGone);
            }
        } finally {
            cfn.close();
        }
    }

    @Test
    void eventDestinationFollowsConfigurationSetCreateUpdateAndRemoval() {
        String setName = stackName + "-set";
        String destinationName = stackName + "-events";
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template(setName, destinationName, "SEND", "initial")));
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
        assertEquals(destinationName, cfn.describeStacks(request -> request.stackName(stackName))
                .stacks().getFirst().outputs().stream()
                .filter(output -> "DestinationId".equals(output.outputKey()))
                .findFirst().orElseThrow().outputValue());

        List<EventDestination> destinations = configurationSets.getEventDestinations(setName, "us-east-1");
        assertEquals(1, destinations.size());
        assertEquals(destinationName, destinations.getFirst().getName());
        assertEquals(List.of("SEND"), destinations.getFirst().getMatchingEventTypes());
        assertEquals("initial", destinations.getFirst().getCloudWatchDestination()
                .getDimensionConfigurations().getFirst().getDefaultDimensionValue());

        cfn.updateStack(request -> request.stackName(stackName)
                .templateBody(template(setName, destinationName, "BOUNCE", "updated")));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");

        destinations = configurationSets.getEventDestinations(setName, "us-east-1");
        assertEquals(1, destinations.size());
        assertEquals(destinationName, destinations.getFirst().getName());
        assertEquals(List.of("BOUNCE"), destinations.getFirst().getMatchingEventTypes());
        assertEquals("updated", destinations.getFirst().getCloudWatchDestination()
                .getDimensionConfigurations().getFirst().getDefaultDimensionValue());

        String replacementSet = stackName + "-new-set";
        cfn.updateStack(request -> request.stackName(stackName)
                .templateBody(template(replacementSet, destinationName, "BOUNCE", "updated")));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
        assertTrue(configurationSets.find(setName, "us-east-1").isEmpty());
        assertEquals(destinationName, configurationSets.getEventDestinations(replacementSet, "us-east-1")
                .getFirst().getName());

        cfn.updateStack(request -> request.stackName(stackName).templateBody("""
                {"Resources": {"ConfigurationSet": {
                  "Type": "AWS::SES::ConfigurationSet", "Properties": {"Name": "%s"}
                }}}
                """.formatted(replacementSet)));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
        assertTrue(configurationSets.getEventDestinations(replacementSet, "us-east-1").isEmpty());

        cfn.deleteStack(request -> request.stackName(stackName));
        await().atMost(Duration.ofSeconds(15)).until(this::stackIsGone);
        deleted = true;
    }

    @Test
    void changingTheDestinationNamePreviewsAndPerformsReplacement() {
        String setName = stackName + "-set";
        String oldName = stackName + "-old";
        String newName = stackName + "-new";
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template(setName, oldName, "SEND", "initial")));
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

        CfnStackTestSupport.createUpdateChangeSet(cfn, stackName, "rename",
                template(setName, newName, "SEND", "initial"));
        assertEquals(List.of("True"), CfnStackTestSupport.replacement(
                cfn, stackName, "rename", "EventDestination"));

        CfnStackTestSupport.executeAndAwaitUpdate(cfn, stackName, "rename");
        assertEquals(List.of(newName), configurationSets.getEventDestinations(setName, "us-east-1")
                .stream().map(EventDestination::getName).toList());
    }

    @Test
    void changedDynamicDestinationNamePreviewsReplacement() {
        String setName = stackName + "-set";
        String oldName = stackName + "-old";
        String newName = stackName + "-new";
        String parameter = "/ses/event-destination/" + stackName;
        String reference = "{{resolve:ssm:" + parameter + "}}";
        try (SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "test", Region.US_EAST_1)) {
            try {
                ssm.putParameter(request -> request.name(parameter).type("String").value(oldName));
                cfn.createStack(request -> request.stackName(stackName)
                        .templateBody(template(setName, reference, "SEND", "initial")));
                CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

                ssm.putParameter(request -> request.name(parameter).type("String").value(newName)
                        .overwrite(true));
                CfnStackTestSupport.createUpdateChangeSet(cfn, stackName, "rename-dynamic",
                        template(setName, reference, "BOUNCE", "initial"));
                assertEquals(List.of("True"), CfnStackTestSupport.replacement(
                        cfn, stackName, "rename-dynamic", "EventDestination"));

                CfnStackTestSupport.executeAndAwaitUpdate(cfn, stackName, "rename-dynamic");
                assertEquals(List.of(newName), configurationSets.getEventDestinations(setName, "us-east-1")
                        .stream().map(EventDestination::getName).toList());
            } catch (RuntimeException | Error failure) {
                try {
                    ssm.deleteParameter(request -> request.name(parameter));
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            ssm.deleteParameter(request -> request.name(parameter));
        }
    }

    @Test
    void dependentFailureRestoresAnInPlaceUpdate() throws JsonProcessingException {
        String setName = stackName + "-set";
        String destinationName = stackName + "-events";
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template(setName, destinationName, "SEND", "initial")));
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

        String update = withBadDependent(template(setName, destinationName, "BOUNCE", "updated"));
        cfn.updateStack(request -> request.stackName(stackName).templateBody(update));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");

        List<EventDestination> destinations = configurationSets.getEventDestinations(setName, "us-east-1");
        assertEquals(1, destinations.size());
        assertEquals(destinationName, destinations.getFirst().getName());
        assertEquals(List.of("SEND"), destinations.getFirst().getMatchingEventTypes());
        assertEquals("initial", destinations.getFirst().getCloudWatchDestination()
                .getDimensionConfigurations().getFirst().getDefaultDimensionValue());
    }

    @Test
    void dependentFailureRemovesAReplacementAndKeepsThePriorDestination() throws JsonProcessingException {
        String setName = stackName + "-set";
        String destinationName = stackName + "-events";
        cfn.createStack(request -> request.stackName(stackName)
                .templateBody(template(setName, destinationName, "SEND", "initial")));
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

        String update = withBadDependent(template(setName, destinationName + "-new", "BOUNCE", "updated"));
        cfn.updateStack(request -> request.stackName(stackName).templateBody(update));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");

        List<EventDestination> destinations = configurationSets.getEventDestinations(setName, "us-east-1");
        assertEquals(1, destinations.size());
        assertEquals(destinationName, destinations.getFirst().getName());
        assertEquals(List.of("SEND"), destinations.getFirst().getMatchingEventTypes());
    }

    @Test
    void cloudFormationArnPropertiesMapToSesDestinationsAndEnabledDefaultsToFalse() {
        String setName = stackName + "-set";
        String roleArn = "arn:aws:iam::000000000000:role/ses-firehose";
        String streamArn = "arn:aws:firehose:us-east-1:000000000000:deliverystream/ses-events";
        try (SnsClient sns = AwsTestClients.create(SnsClient.builder(), "test", Region.US_EAST_1)) {
            String topicArn = sns.createTopic(request -> request.name(stackName + "-topic")).topicArn();
            try {
                String template = """
                        {
                          "Resources": {
                            "ConfigurationSet": {
                              "Type": "AWS::SES::ConfigurationSet", "Properties": {"Name": "%s"}
                            },
                            "SnsEvents": {
                              "Type": "AWS::SES::ConfigurationSetEventDestination",
                              "Properties": {
                                "ConfigurationSetName": {"Ref": "ConfigurationSet"},
                                "EventDestination": {
                                  "Name": "sns-events",
                                  "MatchingEventTypes": ["SEND"],
                                  "SnsDestination": {"TopicARN": "%s"}
                                }
                              }
                            },
                            "FirehoseEvents": {
                              "Type": "AWS::SES::ConfigurationSetEventDestination",
                              "Properties": {
                                "ConfigurationSetName": {"Ref": "ConfigurationSet"},
                                "EventDestination": {
                                  "Name": "firehose-events",
                                  "MatchingEventTypes": ["SEND"],
                                  "KinesisFirehoseDestination": {
                                    "IAMRoleARN": "%s", "DeliveryStreamARN": "%s"
                                  }
                                }
                              }
                            }
                          }
                        }
                        """.formatted(setName, topicArn, roleArn, streamArn);
                cfn.createStack(request -> request.stackName(stackName).templateBody(template));
                CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

                List<EventDestination> destinations = configurationSets.getEventDestinations(setName, "us-east-1");
                assertEquals(2, destinations.size());
                EventDestination snsDestination = destinations.stream()
                        .filter(destination -> "sns-events".equals(destination.getName()))
                        .findFirst().orElseThrow();
                EventDestination firehoseDestination = destinations.stream()
                        .filter(destination -> "firehose-events".equals(destination.getName()))
                        .findFirst().orElseThrow();
                assertFalse(snsDestination.isEnabled());
                assertEquals(topicArn, snsDestination.getSnsDestination().getTopicArn());
                assertEquals(roleArn, firehoseDestination.getKinesisFirehoseDestination().getIamRoleArn());
                assertEquals(streamArn, firehoseDestination.getKinesisFirehoseDestination().getDeliveryStreamArn());
            } finally {
                sns.deleteTopic(request -> request.topicArn(topicArn));
            }
        }
    }

    private String withBadDependent(String template) throws JsonProcessingException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(template);
        ObjectNode badSecret = ((ObjectNode) root.path("Resources")).putObject("BadSecret");
        badSecret.put("Type", "AWS::SecretsManager::Secret");
        badSecret.put("DependsOn", "EventDestination");
        ObjectNode properties = badSecret.putObject("Properties");
        properties.put("SecretString", "conflicting");
        properties.putObject("GenerateSecretString").put("PasswordLength", 8);
        return root.toString();
    }

    private String template(String setName, String destinationName, String eventType, String defaultValue) {
        return """
                {
                  "Resources": {
                    "ConfigurationSet": {
                      "Type": "AWS::SES::ConfigurationSet",
                      "Properties": {"Name": "%s"}
                    },
                    "EventDestination": {
                      "Type": "AWS::SES::ConfigurationSetEventDestination",
                      "Properties": {
                        "ConfigurationSetName": {"Ref": "ConfigurationSet"},
                        "EventDestination": {
                          "Name": "%s",
                          "Enabled": true,
                          "MatchingEventTypes": ["%s"],
                          "CloudWatchDestination": {
                            "DimensionConfigurations": [{
                              "DimensionName": "campaign",
                              "DimensionValueSource": "messageTag",
                              "DefaultDimensionValue": "%s"
                            }]
                          }
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "DestinationId": {"Value": {"Fn::GetAtt": ["EventDestination", "Id"]}}
                  }
                }
                """.formatted(setName, destinationName, eventType, defaultValue);
    }

    private boolean stackIsGone() {
        try {
            return cfn.describeStacks(request -> request.stackName(stackName)).stacks().isEmpty();
        } catch (CloudFormationException expected) {
            return expected.statusCode() == 400;
        }
    }
}
