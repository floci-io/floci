package io.floci.aws.services.cloudformation;

import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a stack update does with the {@code Description} of an {@code AWS::SecretsManager::Secret}. */
@QuarkusTest
class CloudFormationSecretsManagerSecretIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String REGION = "us-east-1";

    @Inject
    SecretsManagerService secrets;

    @Test
    void updateStackThatDropsTheSecretDescriptionClearsItAndAReAddSetsItAgain() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-secret-desc-drop-" + suffix;
        String name = "cfn-secret-desc-drop/" + suffix;
        deploy("CreateStack", stackName, secretTemplate(name, "v1", ", \"Description\": \"orig\""), "CREATE_COMPLETE");
        assertEquals("orig", secrets.describeSecret(name, REGION).getDescription());

        deploy("UpdateStack", stackName, secretTemplate(name, "v1", ""), "UPDATE_COMPLETE");
        assertNull(secrets.describeSecret(name, REGION).getDescription());

        deploy("UpdateStack", stackName, secretTemplate(name, "v1", ", \"Description\": \"back\""), "UPDATE_COMPLETE");
        assertEquals("back", secrets.describeSecret(name, REGION).getDescription());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void updateStackWhoseSecretDescriptionBecomesNoValueClearsIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-secret-desc-novalue-" + suffix;
        String name = "cfn-secret-desc-novalue/" + suffix;
        deploy("CreateStack", stackName, secretTemplate(name, "v1", ", \"Description\": \"orig\""), "CREATE_COMPLETE");

        deploy("UpdateStack", stackName, secretTemplate(name, "v1",
                ", \"Description\": {\"Fn::If\": [\"Off\", {\"Ref\": \"AWS::NoValue\"}, \"orig\"]}"), "UPDATE_COMPLETE");

        assertNull(secrets.describeSecret(name, REGION).getDescription());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void changedSecretDescriptionAppliesAndAnEmptyOneIsStoredAsGivenUntilALaterDropClearsIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-secret-desc-empty-" + suffix;
        String name = "cfn-secret-desc-empty/" + suffix;
        deploy("CreateStack", stackName, secretTemplate(name, "v1", ", \"Description\": \"orig\""), "CREATE_COMPLETE");

        deploy("UpdateStack", stackName, secretTemplate(name, "v1", ", \"Description\": \"changed\""),
                "UPDATE_COMPLETE");
        assertEquals("changed", secrets.describeSecret(name, REGION).getDescription());

        deploy("UpdateStack", stackName, secretTemplate(name, "v1", ", \"Description\": \"\""), "UPDATE_COMPLETE");
        assertEquals("", secrets.describeSecret(name, REGION).getDescription());

        deploy("UpdateStack", stackName, secretTemplate(name, "v1", ""), "UPDATE_COMPLETE");
        assertNull(secrets.describeSecret(name, REGION).getDescription());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void secretDescriptionSetOutsideTheStackSurvivesAnUpdateThatNeverDeclaredIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-secret-desc-outside-" + suffix;
        String name = "cfn-secret-desc-outside/" + suffix;
        deploy("CreateStack", stackName, secretTemplate(name, "v1", ""), "CREATE_COMPLETE");
        secrets.updateSecret(name, "outside", null, REGION);

        deploy("UpdateStack", stackName, secretTemplate(name, "v2", ""), "UPDATE_COMPLETE");

        assertEquals("outside", secrets.describeSecret(name, REGION).getDescription());
        assertEquals("v2", secrets.getSecretValue(name, null, null, REGION).getSecretString());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void droppingTheSecretDescriptionLeavesTheSecretValueAlone() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-secret-desc-others-" + suffix;
        String name = "cfn-secret-desc-others/" + suffix;
        deploy("CreateStack", stackName, secretTemplate(name, "v1", ", \"Description\": \"orig\""), "CREATE_COMPLETE");

        deploy("UpdateStack", stackName, secretTemplate(name, "v1", ""), "UPDATE_COMPLETE");

        Secret stored = secrets.describeSecret(name, REGION);
        assertEquals(name, stored.getName());
        assertEquals("v1", secrets.getSecretValue(name, null, null, REGION).getSecretString());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    private static void deploy(String action, String stackName, String template, String expectedStatus) {
        CfnRequests.deploy(action, stackName, template, CFN_AUTH, expectedStatus);
    }

    /** A named secret; {@code extraProperties} is appended to its properties. */
    private static String secretTemplate(String name, String value, String extraProperties) {
        return """
                {
                  "Conditions": {"Off": {"Fn::Equals": ["a", "a"]}},
                  "Resources": {
                    "Secret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {"Name": "%s", "SecretString": "%s"%s}
                    }
                  }
                }
                """.formatted(name, value, extraProperties);
    }
}
