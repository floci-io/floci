package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SSM references to Secrets Manager secrets")
class SsmSecretsManagerReferenceTest {

    private static final String PREFIX = "/aws/reference/secretsmanager/";

    private static SsmClient ssm;
    private static SecretsManagerClient secretsManager;
    private static CloudFormationClient cloudFormation;
    private static final List<String> secrets = new ArrayList<>();
    private static final List<String> stacks = new ArrayList<>();

    @BeforeAll
    static void setup() {
        ssm = TestFixtures.ssmClient();
        secretsManager = TestFixtures.secretsManagerClient();
        cloudFormation = TestFixtures.cloudFormationClient();
    }

    @AfterAll
    static void cleanup() {
        for (String stack : stacks) {
            cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stack).build());
        }
        for (String secret : secrets) {
            secretsManager.deleteSecret(DeleteSecretRequest.builder()
                    .secretId(secret).forceDeleteWithoutRecovery(true).build());
        }
        ssm.close();
        secretsManager.close();
        cloudFormation.close();
    }

    @Test
    @DisplayName("reads a secret created through Secrets Manager")
    void readsASecretCreatedThroughSecretsManager() {
        String name = TestFixtures.uniqueName("ssm-ref-sm");
        CreateSecretResponse created = secretsManager.createSecret(r -> r.name(name).secretString("s3cr3t"));
        secrets.add(name);

        Parameter parameter = ssm.getParameter(r -> r.name(PREFIX + name).withDecryption(true)).parameter();

        assertThat(parameter.name()).isEqualTo(PREFIX + name);
        assertThat(parameter.value()).isEqualTo("s3cr3t");
        assertThat(parameter.type()).isEqualTo(ParameterType.SECURE_STRING);
        assertThat(parameter.arn()).isEqualTo(created.arn());
        assertThat(parameter.sourceResult()).startsWith("{\"ARN\":\"" + created.arn() + "\"")
                .contains("\"secretString\":\"s3cr3t\"");
        assertRefusedWithoutDecryption(name);
    }

    @Test
    @DisplayName("reads a secret created through CloudFormation")
    void readsASecretCreatedThroughCloudFormation() {
        String name = TestFixtures.uniqueName("ssm-ref-cfn");
        String stack = TestFixtures.uniqueName("ssm-ref-cfn-stack");
        cloudFormation.createStack(CreateStackRequest.builder().stackName(stack).templateBody("""
                {"Resources": {"Key": {"Type": "AWS::SecretsManager::Secret", "Properties": {
                  "Name": "%s", "GenerateSecretString": {"PasswordLength": 40, "ExcludePunctuation": true}}}}}
                """.formatted(name)).build());
        stacks.add(stack);
        cloudFormation.waiter().waitUntilStackCreateComplete(r -> r.stackName(stack));

        String generated = secretsManager.getSecretValue(r -> r.secretId(name)).secretString();
        Parameter parameter = ssm.getParameter(r -> r.name(PREFIX + name).withDecryption(true)).parameter();

        assertThat(parameter.value()).hasSize(40).isEqualTo(generated);
        assertRefusedWithoutDecryption(name);
    }

    @Test
    @DisplayName("a missing secret is ParameterNotFound")
    void missingSecretIsParameterNotFound() {
        String name = TestFixtures.uniqueName("ssm-ref-missing");

        assertThatThrownBy(() -> ssm.getParameter(r -> r.name(PREFIX + name).withDecryption(true)))
                .isInstanceOf(ParameterNotFoundException.class);

        GetParametersResponse batch = ssm.getParameters(r -> r.names(PREFIX + name).withDecryption(true));
        assertThat(batch.parameters()).isEmpty();
        assertThat(batch.invalidParameters()).containsExactly(PREFIX + name);
    }

    private static void assertRefusedWithoutDecryption(String name) {
        assertThatThrownBy(() -> ssm.getParameter(r -> r.name(PREFIX + name).withDecryption(false)))
                .isInstanceOf(SsmException.class)
                .hasMessageContaining("WithDecryption flag must be True")
                .extracting(e -> ((SsmException) e).awsErrorDetails().errorCode())
                .isEqualTo("ValidationException");
        assertThatThrownBy(() -> ssm.getParameter(r -> r.name(PREFIX + name)))
                .isInstanceOf(SsmException.class)
                .hasMessageContaining("WithDecryption flag must be True");
    }
}
