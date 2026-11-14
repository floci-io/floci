package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import software.amazon.awssdk.services.secretsmanager.model.Tag;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SecretsManagerResourceTagsIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"eu-west-1,aws", "us-gov-west-1,aws", "cn-north-1,aws",
            "eu-west-1,secretsmanager", "us-gov-west-1,secretsmanager", "cn-north-1,secretsmanager"})
    void readsAndWritesUseCurrentStoredTagsForNamesAndArns(String region, String prefix) {
        String name = "secret-tag-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth(ACCOUNT, "iam"), name);
        try (SecretsManagerClient admin = AwsTestClients.create(SecretsManagerClient.builder(), ACCOUNT,
                Region.of(region), endpoint);
             SecretsManagerClient client = AwsTestClients.create(SecretsManagerClient.builder(),
                     credentials.accessKeyId(), credentials.secretAccessKey(), Region.of(region), endpoint)) {
            String arn = admin.createSecret(r -> r.name(name).secretString("initial")
                    .tags(Tag.builder().key("Access").value("allowed").build())).arn();
            try {
                policy(name, """
                        {"Effect":"Allow","Action":["secretsmanager:GetSecretValue",
                        "secretsmanager:DescribeSecret","secretsmanager:PutSecretValue"],"Resource":"%s",
                        "Condition":{"StringEquals":{"%s:ResourceTag/Access":"allowed"}}}
                        """.formatted(arn, prefix));
                for (String reference : new String[]{name, arn, arn.substring(0, arn.length() - 7)}) {
                    assertEquals("initial", client.getSecretValue(r -> r.secretId(reference)).secretString());
                    assertEquals(arn, client.describeSecret(r -> r.secretId(reference)).arn());
                }
                client.putSecretValue(r -> r.secretId(name).secretString("updated"));
                admin.tagResource(r -> r.secretId(arn).tags(Tag.builder().key("Access").value("other").build()));
                for (String reference : new String[]{name, arn, arn.substring(0, arn.length() - 7)}) {
                    denied(() -> client.getSecretValue(r -> r.secretId(reference)));
                    denied(() -> client.describeSecret(r -> r.secretId(reference)));
                    denied(() -> client.putSecretValue(r -> r.secretId(reference).secretString("must-not-write")));
                }
                assertEquals("updated", admin.getSecretValue(r -> r.secretId(arn)).secretString());
                admin.untagResource(r -> r.secretId(arn).tagKeys("Access"));
                denied(() -> client.getSecretValue(r -> r.secretId(name)));
                admin.tagResource(r -> r.secretId(arn).tags(Tag.builder().key("Access").value("allowed").build()));
                assertEquals("updated", client.getSecretValue(r -> r.secretId(name)).secretString());
                policy(name, """
                        {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s"},
                        {"Effect":"Deny","Action":"secretsmanager:GetSecretValue","Resource":"%s",
                        "Condition":{"StringEquals":{"%s:ResourceTag/Access":"allowed"}}}
                        """.formatted(arn, arn, prefix));
                denied(() -> client.getSecretValue(r -> r.secretId(arn)));
            } finally {
                admin.deleteSecret(r -> r.secretId(arn).forceDeleteWithoutRecovery(true));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"aws,false", "aws,true", "secretsmanager,false", "secretsmanager,true"})
    void caseVariantTagsCannotHideAnExplicitDeny(String prefix, boolean reverse) {
        String name = "secret-tag-case-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth(ACCOUNT, "iam"), name);
        try (SecretsManagerClient admin = AwsTestClients.create(SecretsManagerClient.builder(), ACCOUNT,
                Region.EU_WEST_1, endpoint);
             SecretsManagerClient client = AwsTestClients.create(SecretsManagerClient.builder(),
                     credentials.accessKeyId(), credentials.secretAccessKey(), Region.EU_WEST_1, endpoint)) {
            Tag dev = Tag.builder().key("environment").value("dev").build();
            Tag prod = Tag.builder().key("Environment").value("prod").build();
            String arn = admin.createSecret(r -> r.name(name).secretString("value")
                    .tags(reverse ? prod : dev, reverse ? dev : prod)).arn();
            try {
                policy(name, """
                        {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s"},
                        {"Effect":"Deny","Action":"secretsmanager:GetSecretValue","Resource":"%s",
                        "Condition":{"ForAnyValue:StringEquals":{"%s:ResourceTag/Environment":"prod"}}}
                        """.formatted(arn, arn, prefix));
                denied(() -> client.getSecretValue(r -> r.secretId(name)));
                denied(() -> client.getSecretValue(r -> r.secretId(arn)));
                admin.untagResource(r -> r.secretId(arn).tagKeys("Environment"));
                assertEquals("value", client.getSecretValue(r -> r.secretId(name)).secretString());
            } finally {
                admin.deleteSecret(r -> r.secretId(arn).forceDeleteWithoutRecovery(true));
            }
        }
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "tags",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static void denied(Runnable call) {
        SecretsManagerException error = assertThrows(SecretsManagerException.class, call::run);
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }
}
