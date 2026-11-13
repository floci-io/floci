package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code GetParametersByPath} is checked against the parameter its {@code Path} names, and {@code GetParameters}
 * against each parameter its {@code Names} give, without the version or label selector a name may carry.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmParameterReadIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aGrantOnAPathAllowsGetParametersByPathOnItAndNoOtherPath() {
        String prefix = "/iam-path-read-" + UUID.randomUUID();
        String allowed = prefix + "/config";
        String other = prefix + "/secrets";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed + "/key").type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(other + "/key").type(ParameterType.STRING).value("v"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:GetParametersByPath",
                          "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(allowed));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    GetParametersByPathResponse listed = user.getParametersByPath(r -> r.path(allowed));
                    assertEquals(1, listed.parameters().size());
                    assertEquals(allowed + "/key", listed.parameters().getFirst().name());

                    SsmException denied = assertThrows(SsmException.class,
                            () -> user.getParametersByPath(r -> r.path(other)));
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                }
            } finally {
                root.deleteParameter(r -> r.name(allowed + "/key"));
                root.deleteParameter(r -> r.name(other + "/key"));
            }
        }
    }

    @Test
    void getParametersNeedsAGrantOnEveryParameterItNames() {
        String prefix = "/iam-names-read-" + UUID.randomUUID();
        String allowed = prefix + "/a";
        String other = prefix + "/b";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed).type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(other).type(ParameterType.STRING).value("v"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:GetParameters",
                          "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(allowed));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    GetParametersResponse read = user.getParameters(r -> r.names(allowed));
                    assertEquals(1, read.parameters().size());

                    SsmException denied = assertThrows(SsmException.class,
                            () -> user.getParameters(r -> r.names(allowed, other)));
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                }
            } finally {
                root.deleteParameter(r -> r.name(allowed));
                root.deleteParameter(r -> r.name(other));
            }
        }
    }

    @Test
    void aDenyOnAPathRefusesGetParametersByPathOnItWhateverNamesTheBodyCarries() {
        String prefix = "/iam-path-names-" + UUID.randomUUID();
        String allowed = prefix + "/allowed";
        String other = prefix + "/secrets";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowed).type(ParameterType.STRING).value("v"));
                root.putParameter(r -> r.name(other + "/key").type(ParameterType.STRING).value("v"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"ssm:GetParametersByPath",
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/*"},
                          {"Effect":"Deny","Action":"ssm:GetParametersByPath",
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(other));

                given()
                        .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                        .header("X-Amz-Target", "AmazonSSM.GetParametersByPath")
                        .contentType("application/x-amz-json-1.1")
                        .body("{\"Path\":\"" + other + "\",\"Names\":[\"" + allowed + "\"]}")
                .when().post("/")
                .then().statusCode(400).body(containsString("AccessDeniedException"));
            } finally {
                root.deleteParameter(r -> r.name(allowed));
                root.deleteParameter(r -> r.name(other + "/key"));
            }
        }
    }

    @Test
    void aGrantOnAParameterAllowsReadingItsVersionsAndLabels() {
        String name = "/iam-selector-read-" + UUID.randomUUID() + "/db";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(name).type(ParameterType.STRING).value("v1"));
                root.putParameter(r -> r.name(name).type(ParameterType.STRING).value("v2").overwrite(true));
                root.labelParameterVersion(r -> r.name(name).parameterVersion(1L).labels("stable"));
                String arn = root.getParameter(r -> r.name(name)).parameter().arn();
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":["ssm:GetParameter","ssm:GetParameters"],
                          "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(name));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertEquals("v1", user.getParameter(r -> r.name(name + ":1")).parameter().value());
                    assertEquals("v1", user.getParameter(r -> r.name(arn + ":stable")).parameter().value());
                    GetParametersResponse read = user.getParameters(r -> r.names(name + ":2", name + ":stable"));
                    assertEquals(2, read.parameters().size());
                }
            } finally {
                root.deleteParameter(r -> r.name(name));
            }
        }
    }

    @Test
    void aDenyOnAParameterRefusesReadingItsVersionsAndLabels() {
        String name = "/iam-selector-deny-" + UUID.randomUUID() + "/db";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(name).type(ParameterType.STRING).value("v1"));
                root.labelParameterVersion(r -> r.name(name).parameterVersion(1L).labels("stable"));
                String arn = root.getParameter(r -> r.name(name)).parameter().arn();
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":["ssm:GetParameter","ssm:GetParameters"],
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/*"},
                          {"Effect":"Deny","Action":["ssm:GetParameter","ssm:GetParameters"],
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(name));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    for (String selected : new String[] {name + ":1", name + ":stable", arn + ":stable"}) {
                        SsmException denied = assertThrows(SsmException.class,
                                () -> user.getParameter(r -> r.name(selected)));
                        assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                        SsmException batchDenied = assertThrows(SsmException.class,
                                () -> user.getParameters(r -> r.names(selected)));
                        assertEquals("AccessDeniedException", batchDenied.awsErrorDetails().errorCode());
                    }
                }
            } finally {
                root.deleteParameter(r -> r.name(name));
            }
        }
    }

    @Test
    void aDoubleSlashArnDoesNotReadTheParameterADenyCovers() {
        String name = "/iam-double-slash-" + UUID.randomUUID();
        String doubleSlashArn = "arn:aws:ssm:us-east-1:000000000000:parameter/" + name;
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(name).type(ParameterType.STRING).value("v1"));
                root.labelParameterVersion(r -> r.name(name).parameterVersion(1L).labels("stable"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":["ssm:GetParameter","ssm:GetParameters"],
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/*"},
                          {"Effect":"Deny","Action":["ssm:GetParameter","ssm:GetParameters"],
                           "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter%s"}]}""".formatted(name));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    for (String selected : new String[] {doubleSlashArn, doubleSlashArn + ":stable"}) {
                        SsmException refused = assertThrows(SsmException.class,
                                () -> user.getParameter(r -> r.name(selected)));
                        assertEquals("ParameterNotFound", refused.awsErrorDetails().errorCode());
                        GetParametersResponse read = user.getParameters(r -> r.names(selected));
                        assertEquals(0, read.parameters().size());
                        assertEquals(List.of(selected), read.invalidParameters());
                    }
                }
            } finally {
                root.deleteParameter(r -> r.name(name));
            }
        }
    }

    @Test
    void aGrantOnAParameterWithoutALeadingSlashAllowsReadingItsVersion() {
        String name = "iam-unslashed-read-" + UUID.randomUUID();
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(name).type(ParameterType.STRING).value("v1"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:GetParameter",
                          "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/%s"}]}""".formatted(name));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertEquals("v1", user.getParameter(r -> r.name(name + ":1")).parameter().value());
                    assertEquals("v1", user.getParameter(
                            r -> r.name("arn:aws:ssm:us-east-1:000000000000:parameter/" + name)).parameter().value());
                }
            } finally {
                root.deleteParameter(r -> r.name(name));
            }
        }
    }

    @Test
    void aGrantOnADoubleSlashArnAllowsOnlyThatArnThrough() {
        String name = "/iam-double-slash-grant-" + UUID.randomUUID();
        String doubleSlashArn = "arn:aws:ssm:us-east-1:000000000000:parameter/" + name;
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(name).type(ParameterType.STRING).value("v1"));
                EnforcementFixtures.UserCredentials credentials = user("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:GetParameter",
                          "Resource":"%s"}]}""".formatted(doubleSlashArn));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    SsmException missing = assertThrows(SsmException.class,
                            () -> user.getParameter(r -> r.name(doubleSlashArn)));
                    assertEquals("ParameterNotFound", missing.awsErrorDetails().errorCode());

                    SsmException denied = assertThrows(SsmException.class,
                            () -> user.getParameter(r -> r.name(name)));
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                }
            } finally {
                root.deleteParameter(r -> r.name(name));
            }
        }
    }

    @Test
    void aDenyOnTheRootPathRefusesGetParametersByPathOnItAndWithoutAPath() {
        EnforcementFixtures.UserCredentials credentials = user("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ssm:GetParametersByPath",
                   "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/*"},
                  {"Effect":"Deny","Action":"ssm:GetParametersByPath",
                   "Resource":"arn:aws:ssm:us-east-1:000000000000:parameter/"}]}""");
        try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                Region.US_EAST_1)) {
            SsmException denied = assertThrows(SsmException.class,
                    () -> user.getParametersByPath(r -> r.path("/").recursive(true)));
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
        }

        given()
                .header("Authorization", auth(credentials.accessKeyId(), "ssm"))
                .header("X-Amz-Target", "AmazonSSM.GetParametersByPath")
                .contentType("application/x-amz-json-1.1")
                .body("{\"Recursive\":true}")
        .when().post("/")
        .then().statusCode(400).body(containsString("AccessDeniedException"));
    }

    private EnforcementFixtures.UserCredentials user(String policy) {
        String userName = "ssm-read-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", policy);
        return credentials;
    }
}
