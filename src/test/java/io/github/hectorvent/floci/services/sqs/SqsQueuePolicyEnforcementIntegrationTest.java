package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SqsTestQueue;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SqsQueuePolicyEnforcementIntegrationTest {
    private static final String OWNER = "000000000451";
    private static final String OTHER = "000000000452";
    private static final String REGION = "us-east-1";
    private static final String SEND = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*"}]}
            """;
    private static final String READ_ONLY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sqs:GetQueueUrl","Resource":"*"}]}
            """;

    @TestHTTPResource
    URI baseUri;

    @Inject
    SqsService sqsService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anotherAccountNeedsTheQueueToGrantIt() {
        SqsTestQueue queue = queue();
        String accessKey = user(OTHER, SEND);

        send(accessKey, queue).statusCode(403);

        queue.addPermission("from-other", OTHER, "SendMessage");
        send(accessKey, queue).statusCode(200);
        assertEquals("hello", queue.receive().getFirst().getBody());
    }

    @Test
    void aQueueGrantDoesNotReplaceTheOtherAccountsOwnPolicy() {
        SqsTestQueue queue = queue();
        queue.addPermission("from-other", OTHER, "SendMessage");

        send(user(OTHER, READ_ONLY), queue).statusCode(403);
    }

    @Test
    void aQueuePolicyAloneAllowsAUserOfItsOwnAccount() {
        SqsTestQueue queue = queue();
        String name = "sqs-policy-" + UUID.randomUUID();
        String accessKey = user(OWNER, name, READ_ONLY);

        send(accessKey, queue).statusCode(403);

        queue.setPolicy(policy("Allow", "arn:aws:iam::" + OWNER + ":user/" + name, queue.arn()));
        send(accessKey, queue).statusCode(200);
    }

    @Test
    void aQueuePolicyDenyRefusesAUserAllowedTheAction() {
        SqsTestQueue queue = queue();
        String name = "sqs-policy-" + UUID.randomUUID();
        String accessKey = user(OWNER, name, SEND);

        send(accessKey, queue).statusCode(200);

        queue.setPolicy(policy("Deny", "arn:aws:iam::" + OWNER + ":user/" + name, queue.arn()));
        send(accessKey, queue).statusCode(403);
    }

    private static String policy(String effect, String principal, String queueArn) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","Principal":{"AWS":"%s"},
                 "Action":"sqs:SendMessage","Resource":"%s"}]}
                """.formatted(effect, principal, queueArn);
    }

    private SqsTestQueue queue() {
        SqsTestQueue queue = SqsTestQueue.create(sqsService, OWNER, REGION, "sqs-policy-");
        cleanup.register(queue::delete);
        return queue;
    }

    private String user(String account, String policy) {
        return user(account, "sqs-policy-" + UUID.randomUUID(), policy);
    }

    private String user(String account, String name, String policy) {
        String authorization = auth(account, "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), authorization, name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), authorization, name, "p", policy);
        return credentials.accessKeyId();
    }

    private ValidatableResponse send(String accessKey, SqsTestQueue queue) {
        return given().port(baseUri.getPort()).header("Authorization", auth(accessKey, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage").formParam("Version", "2012-11-05")
                .formParam("QueueUrl", queue.url()).formParam("MessageBody", "hello")
                .post("/").then();
    }
}
