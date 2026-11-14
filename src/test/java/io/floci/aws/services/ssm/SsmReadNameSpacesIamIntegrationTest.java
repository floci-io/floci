package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmReadNameSpacesIamIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void paddedNamesAndArnsKeepExactParameterPermissions() {
        String prefix = "/iam-spaces-" + UUID.randomUUID();
        String allowed = prefix + "/allowed";
        String denied = prefix + "/denied";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            root.putParameter(request -> request.name(allowed).type("String").value("allowed-value"));
            try {
                root.putParameter(request -> request.name(denied).type("String").value("denied-value"));
                try {
                    String allowedArn = root.getParameter(request -> request.name(allowed)).parameter().arn();
                    String deniedArn = root.getParameter(request -> request.name(denied)).parameter().arn();
                    EnforcementFixtures.UserCredentials credentials = user("""
                            {
                              "Version": "2012-10-17",
                              "Statement": [
                                {
                                  "Effect": "Allow",
                                  "Action": "ssm:GetParameter",
                                  "Resource": "%s"
                                }
                              ]
                            }
                            """.formatted(allowedArn));
                    try (SsmClient caller = caller(credentials)) {
                        for (String reference : List.of(allowed, allowedArn)) {
                            assertEquals("allowed-value", caller.getParameter(request -> request
                                    .name(" " + reference + " ")).parameter().value());
                        }
                        for (String reference : List.of(denied, deniedArn)) {
                            SsmException error = assertThrows(SsmException.class,
                                    () -> caller.getParameter(request -> request.name(" " + reference + " ")));
                            assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                        }
                    }
                } finally {
                    root.deleteParameter(request -> request.name(denied));
                }
            } finally {
                root.deleteParameter(request -> request.name(allowed));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetParameterHistory", "LabelParameterVersion", "GetParametersByPath"})
    void paddedHistoryLabelsAndPathsKeepExactPermissions(String operation) {
        String prefix = "/iam-extra-spaces-" + UUID.randomUUID();
        String allowedPath = prefix + "/allowed";
        String deniedPath = prefix + "/denied";
        String allowed = allowedPath + "/value";
        String denied = deniedPath + "/value";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            root.putParameter(request -> request.name(allowed).type("String").value("allowed-value"));
            try {
                root.putParameter(request -> request.name(denied).type("String").value("denied-value"));
                try {
                    String allowedArn = root.getParameter(request -> request.name(allowed)).parameter().arn();
                    String resource = "GetParametersByPath".equals(operation)
                            ? allowedArn.substring(0, allowedArn.lastIndexOf('/')) : allowedArn;
                    EnforcementFixtures.UserCredentials credentials = user("""
                            {
                              "Version": "2012-10-17",
                              "Statement": [
                                {
                                  "Effect": "Allow",
                                  "Action": "ssm:%s",
                                  "Resource": "%s"
                                }
                              ]
                            }
                            """.formatted(operation, resource));
                    try (SsmClient caller = caller(credentials)) {
                        switch (operation) {
                            case "GetParameterHistory" -> assertEquals("allowed-value",
                                    caller.getParameterHistory(request -> request.name(" " + allowed + " "))
                                            .parameters().getFirst().value());
                            case "LabelParameterVersion" -> assertEquals(1L,
                                    caller.labelParameterVersion(request -> request.name(" " + allowed + " ")
                                            .parameterVersion(1L).labels("stable")).parameterVersion());
                            case "GetParametersByPath" -> assertEquals(List.of(allowed),
                                    caller.getParametersByPath(request -> request.path(" " + allowedPath + " "))
                                            .parameters().stream().map(Parameter::name).toList());
                            default -> throw new AssertionError(operation);
                        }
                        SsmException error = assertThrows(SsmException.class, () -> {
                            switch (operation) {
                                case "GetParameterHistory" -> caller.getParameterHistory(request -> request
                                        .name(" " + denied + " "));
                                case "LabelParameterVersion" -> caller.labelParameterVersion(request -> request
                                        .name(" " + denied + " ").parameterVersion(1L).labels("stable"));
                                case "GetParametersByPath" -> caller.getParametersByPath(request -> request
                                        .path(" " + deniedPath + " "));
                                default -> throw new AssertionError(operation);
                            }
                        });
                        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                    }
                    assertTrue(root.getParameterHistory(request -> request.name(denied))
                            .parameters().getFirst().labels().isEmpty());
                    assertEquals("denied-value", root.getParameter(request -> request.name(denied))
                            .parameter().value());
                } finally {
                    root.deleteParameter(request -> request.name(denied));
                }
            } finally {
                root.deleteParameter(request -> request.name(allowed));
            }
        }
    }

    @Test
    void paddedSecretReferencesRequireTheCallersSecretPermission() {
        String name = "secret-spaces-denied-" + UUID.randomUUID();
        try (SecretsManagerClient secrets = AwsTestClients.create(
                SecretsManagerClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            secrets.createSecret(request -> request.name(name).secretString("secret-value"));
            try {
                EnforcementFixtures.UserCredentials credentials = user("""
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Action": ["ssm:GetParameter", "ssm:GetParameters"],
                              "Resource": "*"
                            }
                          ]
                        }
                        """);
                try (SsmClient caller = caller(credentials)) {
                    for (String reference : List.of(name, name + "-missing")) {
                        String padded = " /aws/reference/secretsmanager/" + reference + " ";
                        SsmException single = assertThrows(SsmException.class,
                                () -> caller.getParameter(request -> request.name(padded).withDecryption(true)));
                        assertDependencyRefusal(single);
                        SsmException batch = assertThrows(SsmException.class,
                                () -> caller.getParameters(request -> request.names(padded).withDecryption(true)));
                        assertDependencyRefusal(batch);
                    }
                }
                assertEquals("secret-value", secrets.getSecretValue(request -> request.secretId(name)).secretString());
            } finally {
                secrets.deleteSecret(request -> request.secretId(name).forceDeleteWithoutRecovery(true));
            }
        }
    }

    @Test
    void paddedSecretReferencesResolveWithExactSecretPermission() {
        String name = "secret-spaces-allowed-" + UUID.randomUUID();
        try (SecretsManagerClient secrets = AwsTestClients.create(
                SecretsManagerClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String arn = secrets.createSecret(request -> request.name(name).secretString("secret-value")).arn();
            try {
                EnforcementFixtures.UserCredentials credentials = user("""
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Action": ["ssm:GetParameter", "ssm:GetParameters"],
                              "Resource": "*"
                            },
                            {
                              "Effect": "Allow",
                              "Action": "secretsmanager:GetSecretValue",
                              "Resource": "%s"
                            }
                          ]
                        }
                        """.formatted(arn));
                try (SsmClient caller = caller(credentials)) {
                    String reference = "/aws/reference/secretsmanager/" + name;
                    Parameter expected = caller.getParameter(request -> request
                            .name(reference).withDecryption(true)).parameter();
                    assertEquals(expected, caller.getParameter(request -> request
                            .name(" " + reference + " ").withDecryption(true)).parameter());
                    assertEquals(List.of(expected), caller.getParameters(request -> request
                            .names(" " + reference + " ").withDecryption(true)).parameters());
                }
            } finally {
                secrets.deleteSecret(request -> request.secretId(name).forceDeleteWithoutRecovery(true));
            }
        }
    }

    private void assertDependencyRefusal(SsmException error) {
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertEquals("An error occurred while calling one AWS dependency service.",
                error.awsErrorDetails().errorMessage());
        assertEquals(400, error.statusCode());
    }

    private EnforcementFixtures.UserCredentials user(String policy) {
        String userName = "read-spaces-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "read", policy);
        return credentials;
    }

    private SsmClient caller(EnforcementFixtures.UserCredentials credentials) {
        return AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1);
    }
}
