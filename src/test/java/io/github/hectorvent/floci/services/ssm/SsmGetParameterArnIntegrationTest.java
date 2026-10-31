package io.github.hectorvent.floci.services.ssm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class SsmGetParameterArnIntegrationTest {

    private static final String ACCOUNT = "741661400001";

    @ParameterizedTest
    @CsvSource({"us-east-1", "eu-west-2", "cn-north-1", "us-gov-west-1"})
    void sdkReadsReturnedArnsWithVersionAndLabelSelectors(String region) {
        List<String> names = new ArrayList<>();
        try (SsmClient client = client(ACCOUNT, region)) {
            try {
                String prefix = "read-arn-" + UUID.randomUUID();
                for (String name : List.of(prefix, "/" + prefix + "/config")) {
                    createVersions(client, name, names);
                    String arn = client.getParameter(request -> request.name(name)).parameter().arn();
                    for (String selector : List.of("", ":1", ":stable")) {
                        for (boolean decrypt : List.of(false, true)) {
                            Parameter expected = client.getParameter(request -> request.name(name + selector)
                                    .withDecryption(decrypt)).parameter();
                            Parameter actual = assertDoesNotThrow(() -> client.getParameter(request -> request
                                    .name(arn + selector).withDecryption(decrypt)).parameter());
                            assertEquals(expected, actual);
                        }
                    }
                }
            } finally {
                names.forEach(name -> client.deleteParameter(request -> request.name(name)));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1", "eu-west-2", "cn-north-1", "us-gov-west-1"})
    void batchReadsMixNamesArnsAndSelectorsWithoutMarkingResolvedArnsInvalid(String region) {
        List<String> names = new ArrayList<>();
        try (SsmClient client = client(ACCOUNT, region)) {
            try {
                String prefix = "batch-arn-" + UUID.randomUUID();
                createVersions(client, prefix, names);
                createVersions(client, "/" + prefix + "/config", names);
                String topArn = client.getParameter(request -> request.name(prefix)).parameter().arn();
                String pathArn = client.getParameter(request -> request.name(names.get(1))).parameter().arn();
                List<String> invalid = List.of(topArn + "-missing", pathArn + ":999");
                GetParametersResponse result = client.getParameters(request -> request.withDecryption(true)
                        .names(topArn, pathArn + ":stable", prefix + ":1", invalid.get(0), invalid.get(1)));
                assertEquals(invalid, result.invalidParameters());
                assertEquals(3, result.parameters().size());
                assertEquals(List.of("v1", "v1", "v2"), result.parameters().stream()
                        .map(Parameter::value).sorted().toList());
            } finally {
                names.forEach(name -> client.deleteParameter(request -> request.name(name)));
            }
        }
    }

    @Test
    void foreignAndMalformedArnsCannotResolveACallerLocalParameter() {
        String region = "us-east-1";
        List<String> names = new ArrayList<>();
        try (SsmClient client = client(ACCOUNT, region)) {
            try {
                String name = "scope-arn-" + UUID.randomUUID();
                createVersions(client, name, names);
                String arn = client.getParameter(request -> request.name(name)).parameter().arn();
                List<String> invalid = List.of(arn.replace(ACCOUNT, "741661400002"),
                        arn.replace(region, "eu-west-2"), arn.replace("arn:aws:", "arn:aws-cn:"),
                        arn.replace(":ssm:", ":secretsmanager:"),
                        arn.replace(":parameter/", ":parameter"), "arn:malformed");
                for (String reference : invalid) {
                    SsmException error = assertThrows(SsmException.class,
                            () -> client.getParameter(request -> request.name(reference)));
                    assertEquals("ParameterNotFound", error.awsErrorDetails().errorCode());
                }
                assertEquals(invalid, client.getParameters(request -> request.names(invalid)).invalidParameters());
                for (String otherAccount : List.of(ACCOUNT, "741661400002")) {
                    String otherRegion = otherAccount.equals(ACCOUNT) ? "eu-west-2" : region;
                    try (SsmClient other = client(otherAccount, otherRegion)) {
                        try {
                            other.putParameter(request -> request.name(name).type("String").value("other-scope"));
                            SsmException error = assertThrows(SsmException.class,
                                    () -> other.getParameter(request -> request.name(arn)));
                            assertEquals("ParameterNotFound", error.awsErrorDetails().errorCode());
                        } finally {
                            other.deleteParameter(request -> request.name(name));
                        }
                    }
                }
            } finally {
                names.forEach(name -> client.deleteParameter(request -> request.name(name)));
            }
        }
    }

    @Test
    void parameterArnsCannotBecomeVirtualSecretsManagerReferences() {
        String name = "reference-arn-" + UUID.randomUUID();
        String reference = "/aws/reference/secretsmanager/" + name;
        String arn = "arn:aws:ssm:us-east-1:" + ACCOUNT + ":parameter" + reference;
        try (SecretsManagerClient secrets = SecretsManagerClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"))).build();
                SsmClient client = client(ACCOUNT, "us-east-1")) {
            try {
                secrets.createSecret(request -> request.name(name).secretString("secret-value"));
                assertEquals("secret-value", client.getParameter(request -> request.name(reference)
                        .withDecryption(true)).parameter().value());
                SsmException error = assertThrows(SsmException.class,
                        () -> client.getParameter(request -> request.name(arn).withDecryption(true)));
                assertEquals("ParameterNotFound", error.awsErrorDetails().errorCode());
                GetParametersResponse batch = client.getParameters(request -> request.names(reference, arn)
                        .withDecryption(true));
                assertEquals(List.of(arn), batch.invalidParameters());
                assertEquals(1, batch.parameters().size());
            } finally {
                secrets.deleteSecret(request -> request.secretId(name).forceDeleteWithoutRecovery(true));
            }
        }
    }

    @Test
    void missingArnSelectorsKeepTheVersionNotFoundError() {
        List<String> names = new ArrayList<>();
        try (SsmClient client = client(ACCOUNT, "us-east-1")) {
            try {
                String name = "/selector-arn-" + UUID.randomUUID();
                createVersions(client, name, names);
                String arn = client.getParameter(request -> request.name(name)).parameter().arn();
                for (String selector : List.of(":999", ":missing-label")) {
                    SsmException error = assertThrows(SsmException.class,
                            () -> client.getParameter(request -> request.name(arn + selector)));
                    assertEquals("ParameterVersionNotFound", error.awsErrorDetails().errorCode());
                }
            } finally {
                names.forEach(name -> client.deleteParameter(request -> request.name(name)));
            }
        }
    }

    private void createVersions(SsmClient client, String name, List<String> names) {
        client.putParameter(request -> request.name(name).type("SecureString").value("v1"));
        names.add(name);
        client.putParameter(request -> request.name(name).type("SecureString").value("v2").overwrite(true));
        client.labelParameterVersion(request -> request.name(name).parameterVersion(1L).labels("stable"));
    }

    private SsmClient client(String account, String region) {
        return SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }
}
