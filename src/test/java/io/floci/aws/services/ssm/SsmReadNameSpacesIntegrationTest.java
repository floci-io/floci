package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmReadNameSpacesIntegrationTest {

    private static final String ACCOUNT = "741661401501";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "eu-west-2", "cn-north-1", "us-gov-west-1"})
    void surroundingSpacesResolveCanonicalNames(String region) {
        List<String> created = new ArrayList<>();
        try (SsmClient client = client(region)) {
            try {
                String prefix = "read-spaces-" + UUID.randomUUID();
                for (String name : List.of(prefix, "/" + prefix + "/value")) {
                    create(client, name, created);
                    Parameter expected = client.getParameter(request -> request.name(name)).parameter();
                    assertAll(
                            () -> assertEquals(expected, client.getParameter(request -> request
                                    .name(" " + name)).parameter()),
                            () -> assertEquals(expected, client.getParameter(request -> request
                                    .name(name + "  ")).parameter()),
                            () -> assertEquals(expected, client.getParameter(request -> request
                                    .name("  " + name + " ")).parameter()));
                }
            } finally {
                remove(client, created);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ":1", ":stable"})
    void surroundingSpacesResolveArnsAndSelectors(String selector) {
        List<String> created = new ArrayList<>();
        try (SsmClient client = client("us-east-1")) {
            try {
                String name = "/read-selector-" + UUID.randomUUID();
                create(client, name, created);
                client.putParameter(request -> request.name(name).type("String").value("v2").overwrite(true));
                client.labelParameterVersion(request -> request.name(name).parameterVersion(1L).labels("stable"));
                String arn = client.getParameter(request -> request.name(name)).parameter().arn();
                Parameter expected = client.getParameter(request -> request.name(name + selector)).parameter();
                assertAll(
                        () -> assertEquals(expected, client.getParameter(request -> request
                                .name(" " + name + selector + " ")).parameter()),
                        () -> assertEquals(expected, client.getParameter(request -> request
                                .name(" " + arn + selector + " ")).parameter()));
            } finally {
                remove(client, created);
            }
        }
    }

    @Test
    void batchTrimsNamesAndReportsCanonicalMissingName() {
        List<String> created = new ArrayList<>();
        try (SsmClient client = client("us-east-1")) {
            try {
                String prefix = "batch-spaces-" + UUID.randomUUID();
                String name = "/" + prefix;
                String missing = "/" + prefix + "-missing";
                create(client, name, created);
                Parameter expected = client.getParameter(request -> request.name(name)).parameter();
                GetParametersResponse actual = client.getParameters(request -> request
                        .names(" " + name + " ", " " + missing + " "));
                assertAll(
                        () -> assertEquals(List.of(expected), actual.parameters()),
                        () -> assertEquals(List.of(missing), actual.invalidParameters()));
            } finally {
                remove(client, created);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetParameter", "GetParameters"})
    void internalSpacesFailTheWholeRead(String operation) {
        List<String> created = new ArrayList<>();
        try (SsmClient client = client("us-east-1")) {
            try {
                String name = "/internal-spaces-" + UUID.randomUUID();
                create(client, name, created);
                assertAwsError("ValidationException", 400, () -> {
                    if ("GetParameter".equals(operation)) {
                        client.getParameter(request -> request.name(" " + name + " suffix "));
                    } else {
                        client.getParameters(request -> request.names(name, " " + name + " suffix "));
                    }
                });
                assertEquals("v1", client.getParameter(request -> request.name(name)).parameter().value());
            } finally {
                remove(client, created);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "region", "partition"})
    void paddedForeignArnsRetainScopeRejection(String dimension) {
        List<String> created = new ArrayList<>();
        try (SsmClient client = client("us-east-1")) {
            try {
                String name = "/foreign-spaces-" + UUID.randomUUID();
                create(client, name, created);
                String arn = client.getParameter(request -> request.name(name)).parameter().arn();
                String foreign = switch (dimension) {
                    case "account" -> arn.replace(ACCOUNT, "741661401502");
                    case "region" -> arn.replace("us-east-1", "eu-west-2");
                    case "partition" -> arn.replace("arn:aws:", "arn:aws-cn:");
                    default -> throw new AssertionError(dimension);
                };
                SsmException error = assertThrows(SsmException.class,
                        () -> client.getParameter(request -> request.name(" " + foreign + " ")));
                assertEquals("ParameterNotFound", error.awsErrorDetails().errorCode());
                assertEquals("v1", client.getParameter(request -> request.name(name)).parameter().value());
            } finally {
                remove(client, created);
            }
        }
    }

    @Test
    void canonicalMissingNameRetainsItsErrorAndBatchEntry() {
        try (SsmClient client = client("us-east-1")) {
            String name = "/missing-spaces-" + UUID.randomUUID();
            SsmException error = assertThrows(SsmException.class,
                    () -> client.getParameter(request -> request.name(name)));
            assertEquals("ParameterNotFound", error.awsErrorDetails().errorCode());
            GetParametersResponse batch = client.getParameters(request -> request.names(name));
            assertTrue(batch.parameters().isEmpty());
            assertEquals(List.of(name), batch.invalidParameters());
        }
    }

    private void create(SsmClient client, String name, List<String> created) {
        assertAwsError("ParameterNotFound", 400, () -> client.getParameter(request -> request.name(name)));
        client.putParameter(request -> request.name(name).type("String").value("v1").overwrite(false));
        created.add(name);
    }

    private void remove(SsmClient client, List<String> created) {
        for (String name : created) {
            assertEquals(name, client.getParameter(request -> request.name(name)).parameter().name());
            client.deleteParameter(request -> request.name(name));
            assertAwsError("ParameterNotFound", 400, () -> client.getParameter(request -> request.name(name)));
        }
    }

    private SsmClient client(String region) {
        return AwsTestClients.create(SsmClient.builder()
                        .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
                                .apiCallAttemptTimeout(Duration.ofSeconds(12)).retryPolicy(RetryPolicy.none())),
                ACCOUNT, "test", Region.of(region), UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(10)),
                URI.create("http://localhost:" + RestAssured.port));
    }
}
