package io.floci.aws.services.cloudformation;

import io.floci.aws.services.ses.SesConfigurationSetService;
import io.floci.aws.services.ses.SesIdentityService;
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
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class CloudFormationSesConfigurationSetIntegrationTest {
    private CloudFormationClient cfn;
    private String stackName;
    private boolean deleted;

    @Inject
    SesConfigurationSetService configurationSets;

    @Inject
    SesIdentityService identities;

    @BeforeEach
    void setUp() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        stackName = "cfn-ses-set-" + Long.toString(System.nanoTime(), 36);
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
    void configurationSetRefCreatesTheSetBeforeItsEmailIdentityAndDeletesBoth() {
        String setName = stackName + "-delivery";
        String email = stackName + "@example.com";
        String template = """
                {
                  "AWSTemplateFormatVersion": "2010-09-09",
                  "Resources": {
                    "ConfigurationSet": {
                      "Type": "AWS::SES::ConfigurationSet",
                      "Properties": {"Name": "%s"}
                    },
                    "Identity": {
                      "Type": "AWS::SES::EmailIdentity",
                      "Properties": {
                        "EmailIdentity": "%s",
                        "ConfigurationSetAttributes": {
                          "ConfigurationSetName": {"Ref": "ConfigurationSet"}
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "ConfigurationSetRef": {"Value": {"Ref": "ConfigurationSet"}}
                  }
                }
                """.formatted(setName, email);

        cfn.createStack(request -> request.stackName(stackName).templateBody(template));
        Stack stack = CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

        assertEquals(setName, stack.outputs().getFirst().outputValue());
        assertEquals(setName, configurationSets.get(setName, "us-east-1").getName());
        assertEquals(setName, identities.getIdentityVerificationAttributes(email, "us-east-1")
                .getConfigurationSetName());

        cfn.deleteStack(request -> request.stackName(stackName));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertNull(configurationSets.find(setName, "us-east-1").orElse(null));
            assertNull(identities.getIdentityVerificationAttributes(email, "us-east-1"));
        });
        await().atMost(Duration.ofSeconds(15)).until(this::stackIsGone);
        deleted = true;
    }

    @Test
    void addingTheExistingGeneratedNameRollsBackAfterADependentResourceFails() {
        String generated = createUnnamedConfigurationSet();
        updateWithDependentFailure(generated);

        Stack rolledBack = CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(generated, rolledBack.outputs().getFirst().outputValue());
        assertEquals(generated, configurationSets.get(generated, "us-east-1").getName());
    }

    @Test
    void replacingTheGeneratedNameRollsBackAfterADependentResourceFails() {
        String generated = createUnnamedConfigurationSet();
        String replacement = stackName + "-named";
        updateWithDependentFailure(replacement);

        Stack rolledBack = CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(generated, rolledBack.outputs().getFirst().outputValue());
        assertEquals(generated, configurationSets.get(generated, "us-east-1").getName());
        assertNull(configurationSets.find(replacement, "us-east-1").orElse(null));
    }

    private String createUnnamedConfigurationSet() {
        String initialTemplate = """
                {
                  "Resources": {
                    "ConfigurationSet": {"Type": "AWS::SES::ConfigurationSet", "Properties": {}}
                  },
                  "Outputs": {"ConfigurationSetRef": {"Value": {"Ref": "ConfigurationSet"}}}
                }
                """;
        cfn.createStack(request -> request.stackName(stackName).templateBody(initialTemplate));
        return CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE")
                .outputs().getFirst().outputValue();
    }

    private void updateWithDependentFailure(String name) {
        String updatedTemplate = """
                {
                  "Resources": {
                    "ConfigurationSet": {
                      "Type": "AWS::SES::ConfigurationSet",
                      "Properties": {"Name": "%s"}
                    },
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {
                        "SecretString": {"Ref": "ConfigurationSet"},
                        "GenerateSecretString": {"PasswordLength": 8}
                      }
                    }
                  },
                  "Outputs": {"ConfigurationSetRef": {"Value": {"Ref": "ConfigurationSet"}}}
                }
                """.formatted(name);
        cfn.updateStack(request -> request.stackName(stackName).templateBody(updatedTemplate));
    }

    private boolean stackIsGone() {
        try {
            return cfn.describeStacks(request -> request.stackName(stackName)).stacks().isEmpty();
        } catch (CloudFormationException expected) {
            return expected.statusCode() == 400;
        }
    }
}
