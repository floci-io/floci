package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SqsResourceTagsIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"eu-west-1", "us-gov-west-1", "cn-north-1"})
    void queueAttributesAndDeletionUseCurrentStoredTags(String region) {
        String name = "sqs-tags-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        try (SqsClient admin = admin(ACCOUNT, region); SqsClient client = client(credentials, region)) {
            String url = admin.createQueue(r -> r.queueName(name).tags(Map.of("Owner", "fixture"))).queueUrl();
            try {
                String arn = admin.getQueueAttributes(r -> r.queueUrl(url).attributeNames(QueueAttributeName.QUEUE_ARN))
                        .attributes().get(QueueAttributeName.QUEUE_ARN);
                policy(name, """
                        {"Effect":"Allow","Action":["sqs:SetQueueAttributes","sqs:GetQueueAttributes",
                        "sqs:ListQueueTags","sqs:DeleteQueue"],"Resource":"%s",
                        "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"fixture"}}}
                        """.formatted(arn));
                client.setQueueAttributes(r -> r.queueUrl(url).attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "45")));
                assertEquals("45", client.getQueueAttributes(r -> r.queueUrl(url)
                        .attributeNames(QueueAttributeName.VISIBILITY_TIMEOUT)).attributes().get(QueueAttributeName.VISIBILITY_TIMEOUT));
                String queuePolicy = """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"AWS":"arn:%s:iam::%s:root"},"Action":"sqs:SendMessage","Resource":"%s"}]}
                        """.formatted(AwsRegions.partitionFor(region), ACCOUNT, arn);
                client.setQueueAttributes(r -> r.queueUrl(url).attributes(Map.of(QueueAttributeName.POLICY, queuePolicy)));
                assertEquals(queuePolicy, admin.getQueueAttributes(r -> r.queueUrl(url)
                        .attributeNames(QueueAttributeName.POLICY)).attributes().get(QueueAttributeName.POLICY));
                assertEquals("fixture", client.listQueueTags(r -> r.queueUrl(url)).tags().get("Owner"));
                admin.tagQueue(r -> r.queueUrl(url).tags(Map.of("Owner", "other")));
                denied(() -> client.setQueueAttributes(r -> r.queueUrl(url)
                        .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "60"))));
                denied(() -> client.deleteQueue(r -> r.queueUrl(url)));
                assertEquals("45", admin.getQueueAttributes(r -> r.queueUrl(url)
                        .attributeNames(QueueAttributeName.VISIBILITY_TIMEOUT)).attributes().get(QueueAttributeName.VISIBILITY_TIMEOUT));
                admin.untagQueue(r -> r.queueUrl(url).tagKeys("Owner"));
                denied(() -> client.listQueueTags(r -> r.queueUrl(url)));
                admin.tagQueue(r -> r.queueUrl(url).tags(Map.of("Owner", "fixture")));
                client.deleteQueue(r -> r.queueUrl(url));
            } finally {
                try {
                    admin.deleteQueue(r -> r.queueUrl(url));
                } catch (SqsException absent) {
                    assertEquals("AWS.SimpleQueueService.NonExistentQueue", absent.awsErrorDetails().errorCode());
                }
            }
        }
    }

    @Test
    void resourceTagsComeFromTheQueuesAccountAndRegion() {
        String name = "sqs-coordinates-" + UUID.randomUUID();
        String otherAccount = "111111111111";
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        try (SqsClient admin = admin(ACCOUNT, "eu-west-1");
             SqsClient foreign = admin(otherAccount, "eu-west-1");
             SqsClient elsewhere = admin(ACCOUNT, "us-east-1");
             SqsClient client = client(credentials, "eu-west-1");
             SqsClient remote = client(credentials, "us-east-1")) {
            String local = admin.createQueue(r -> r.queueName(name).tags(Map.of("Owner", "fixture"))).queueUrl();
            String other = foreign.createQueue(r -> r.queueName(name).tags(Map.of("Owner", "other"))).queueUrl();
            String regional = elsewhere.createQueue(r -> r.queueName(name).tags(Map.of("Owner", "other"))).queueUrl();
            try {
                policy(name, """
                        {"Effect":"Allow","Action":["sqs:GetQueueAttributes","sqs:SendMessage"],"Resource":"arn:aws:sqs:*:*:%s",
                        "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"fixture"}}}
                        """.formatted(name));
                String grant = """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"AWS":"arn:aws:iam::%s:user/%s"},"Action":"sqs:SendMessage",
                        "Resource":"arn:aws:sqs:eu-west-1:%s:%s"}]}
                        """.formatted(ACCOUNT, name, otherAccount, name);
                foreign.setQueueAttributes(r -> r.queueUrl(other).attributes(Map.of(QueueAttributeName.POLICY, grant)));
                client.getQueueAttributes(r -> r.queueUrl(local).attributeNames(QueueAttributeName.QUEUE_ARN));
                denied(() -> client.sendMessage(r -> r.queueUrl(other).messageBody("tag-boundary")));
                denied(() -> remote.getQueueAttributes(r -> r.queueUrl(regional).attributeNames(QueueAttributeName.QUEUE_ARN)));
                foreign.tagQueue(r -> r.queueUrl(other).tags(Map.of("Owner", "fixture")));
                client.sendMessage(r -> r.queueUrl(other).messageBody("tag-boundary"));
                foreign.setQueueAttributes(r -> r.queueUrl(other).attributes(Map.of(QueueAttributeName.POLICY, "{}")));
                denied(() -> client.sendMessage(r -> r.queueUrl(other).messageBody("grant-required")));
                foreign.setQueueAttributes(r -> r.queueUrl(other).attributes(Map.of(QueueAttributeName.POLICY, grant)));
                policy(name, """
                        {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*"},
                        {"Effect":"Deny","Action":"sqs:SendMessage","Resource":"*",
                        "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"fixture"}}}
                        """);
                denied(() -> client.sendMessage(r -> r.queueUrl(other).messageBody("tag-deny")));
            } finally {
                admin.deleteQueue(r -> r.queueUrl(local));
                foreign.deleteQueue(r -> r.queueUrl(other));
                elsewhere.deleteQueue(r -> r.queueUrl(regional));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void caseVariantTagsCannotHideAnExplicitDeny(boolean reverse) {
        String name = "sqs-case-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(reverse ? "Environment" : "environment", reverse ? "prod" : "dev");
        tags.put(reverse ? "environment" : "Environment", reverse ? "dev" : "prod");
        try (SqsClient admin = admin(ACCOUNT, "eu-west-1"); SqsClient client = client(credentials, "eu-west-1")) {
            String url = admin.createQueue(r -> r.queueName(name).tags(tags)).queueUrl();
            try {
                policy(name, """
                        {"Effect":"Allow","Action":"sqs:SetQueueAttributes","Resource":"*"},
                        {"Effect":"Deny","Action":"sqs:SetQueueAttributes","Resource":"*",
                        "Condition":{"ForAnyValue:StringEquals":{"aws:ResourceTag/Environment":"prod"}}}
                        """);
                denied(() -> client.setQueueAttributes(r -> r.queueUrl(url)
                        .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "45"))));
                admin.untagQueue(r -> r.queueUrl(url).tagKeys("Environment"));
                client.setQueueAttributes(r -> r.queueUrl(url).attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "45")));
            } finally {
                admin.deleteQueue(r -> r.queueUrl(url));
            }
        }
    }

    private SqsClient admin(String account, String region) {
        return AwsTestClients.create(SqsClient.builder(), account, Region.of(region), endpoint);
    }

    private SqsClient client(UserCredentials credentials, String region) {
        return AwsTestClients.create(SqsClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(),
                Region.of(region), endpoint);
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "tags",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static void denied(Runnable call) {
        SqsException error = assertThrows(SqsException.class, call::run);
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }
}
