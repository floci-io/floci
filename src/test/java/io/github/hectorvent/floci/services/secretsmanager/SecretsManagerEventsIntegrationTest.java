package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ApiDestinationHttpMethod;
import software.amazon.awssdk.services.eventbridge.model.ConnectionAuthorizationType;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SecretsManagerEventsIntegrationTest {
    private static final String ACCOUNT = "661100000001";
    private static final String REGION = "eu-west-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    CloudTrailService trails;

    @Inject
    SqsService sqs;

    private SecretsManagerClient secrets;
    private EventBridgeClient events;
    private String name;
    private String arn;
    private String queueUrl;

    @BeforeEach
    void createFixture() {
        URI endpoint = URI.create("http://localhost:" + RestAssured.port);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"));
        secrets = SecretsManagerClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        events = EventBridgeClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        name = "secret-events-" + UUID.randomUUID();
        arn = secrets.createSecret(r -> r.name(name).secretString("initial-example-value")).arn();
        String queueArn = RequestScopes.callAs(ACCOUNT, REGION, () -> {
            Queue queue = sqs.createQueue(name, Map.of(), REGION);
            queueUrl = queue.getQueueUrl();
            return sqs.getQueueAttributes(queueUrl, List.of("QueueArn"), REGION).get("QueueArn");
        });
        events.putRule(r -> r.name(name).eventPattern("{\"source\":[\"aws.secretsmanager\"],"
                + "\"detail-type\":[\"AWS API Call via CloudTrail\"],"
                + "\"detail\":{\"responseElements\":{\"arn\":[\"" + arn + "\"]}}}"));
        events.putTargets(r -> r.rule(name).targets(Target.builder().id("queue").arn(queueArn).build()));
        RequestScopes.runAs(ACCOUNT, REGION, () -> trails.createTrail(REGION, name, "example-audit-logs",
                null, null, false, false, false, false));
    }

    @AfterEach
    void deleteFixture() {
        try {
            events.removeTargets(r -> r.rule(name).ids("queue"));
            events.deleteRule(r -> r.name(name));
            secrets.deleteSecret(r -> r.secretId(arn).forceDeleteWithoutRecovery(true));
            RequestScopes.runAs(ACCOUNT, REGION, () -> {
                trails.deleteTrail(REGION, name);
                sqs.deleteQueue(queueUrl, REGION);
            });
        } finally {
            events.close();
            secrets.close();
        }
    }

    private List<Message> receive(int waitSeconds) {
        return RequestScopes.callAs(ACCOUNT, REGION, () -> sqs.receiveMessage(queueUrl, 10, 30, waitSeconds, REGION));
    }

    @Test
    void sdkWritesReachTheDefaultBusWithoutInjectedEventsAndExcludeSecretValues() throws Exception {
        RequestScopes.runAs(ACCOUNT, REGION, () -> trails.startLogging(REGION, name));
        secrets.putSecretValue(r -> r.secretId(arn).secretString("sensitive-put-example"));
        secrets.updateSecret(r -> r.secretId(arn).secretString("sensitive-update-example"));
        List<Message> messages = new ArrayList<>();
        for (int attempt = 0; attempt < 5 && messages.size() < 2; attempt++) {
            messages.addAll(receive(1));
        }
        assertEquals(2, messages.size(), "both SDK writes must reach the rule through service-originated events");
        for (Message message : messages) {
            JsonNode event = MAPPER.readTree(message.getBody());
            assertEquals("aws.secretsmanager", event.path("source").asText());
            assertEquals("AWS API Call via CloudTrail", event.path("detail-type").asText());
            assertEquals(ACCOUNT, event.path("account").asText());
            assertEquals(REGION, event.path("region").asText());
            assertEquals(arn, event.path("detail").path("responseElements").path("arn").asText());
            assertFalse(message.getBody().contains("sensitive-"));
            assertFalse(message.getBody().contains("SecretString"));
            assertFalse(message.getBody().contains("secretString"));
        }
    }

    @Test
    void metadataOnlyUpdatesPublishButReadsUnrelatedSecretsAndFailedWritesDoNot() throws Exception {
        RequestScopes.runAs(ACCOUNT, REGION, () -> trails.startLogging(REGION, name));
        String other = secrets.createSecret(r -> r.name(name + "-other").secretString("other-example")).arn();
        try {
            secrets.putSecretValue(r -> r.secretId(other).secretString("unrelated-example"));
            secrets.getSecretValue(r -> r.secretId(arn));
            secrets.describeSecret(r -> r.secretId(arn));
            assertThrows(ResourceNotFoundException.class,
                    () -> secrets.putSecretValue(r -> r.secretId(name + "-missing").secretString("failed-example")));
            assertTrue(receive(0).isEmpty());
            secrets.updateSecret(r -> r.secretId(arn).description("metadata only"));
            List<Message> messages = receive(2);
            assertEquals(1, messages.size());
            JsonNode detail = MAPPER.readTree(messages.getFirst().getBody()).path("detail");
            assertEquals("UpdateSecret", detail.path("eventName").asText());
            assertFalse(detail.path("responseElements").has("versionId"));
        } finally {
            secrets.deleteSecret(r -> r.secretId(other).forceDeleteWithoutRecovery(true));
        }
    }

    @Test
    void slowHttpTargetsDoNotDelaySdkWriteResponsesAndKeepRequestScope() throws Exception {
        CountDownLatch arrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<String> payloads = new CopyOnWriteArrayList<>();
        ExecutorService httpWorkers = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpWorkers);
        server.createContext("/hook", exchange -> {
            try {
                payloads.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                arrived.countDown();
                boolean released = release.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(released ? 200 : 504, -1);
            } catch (InterruptedException expected) {
                // Test teardown interrupts any handler still waiting for release.
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String connection = events.createConnection(r -> r.name(name)
                    .authorizationType(ConnectionAuthorizationType.API_KEY)
                    .authParameters(p -> p.apiKeyAuthParameters(k -> k.apiKeyName("example-key").apiKeyValue("example-value"))))
                    .connectionArn();
            String destination = events.createApiDestination(r -> r.name(name).connectionArn(connection)
                    .httpMethod(ApiDestinationHttpMethod.POST).invocationRateLimitPerSecond(100)
                    .invocationEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"))
                    .apiDestinationArn();
            events.putTargets(r -> r.rule(name).targets(Target.builder().id("blocked-http").arn(destination).build()));
            RequestScopes.runAs(ACCOUNT, REGION, () -> trails.startLogging(REGION, name));

            Future<?> put = callers.submit(() -> secrets.putSecretValue(r -> r.secretId(arn)
                    .secretString("sensitive-async-put")));
            Future<?> update = callers.submit(() -> secrets.updateSecret(r -> r.secretId(arn)
                    .secretString("sensitive-async-update")));
            put.get(2, TimeUnit.SECONDS);
            update.get(2, TimeUnit.SECONDS);
            assertTrue(arrived.await(3, TimeUnit.SECONDS), "both target deliveries must run after the SDK writes return");
            assertEquals(1, release.getCount(), "HTTP targets are still waiting for a response");
            for (String payload : payloads) {
                JsonNode event = MAPPER.readTree(payload);
                assertEquals(ACCOUNT, event.path("account").asText());
                assertEquals(REGION, event.path("region").asText());
                assertEquals(arn, event.path("detail").path("responseElements").path("arn").asText());
                assertFalse(payload.contains("sensitive-"));
            }
        } finally {
            release.countDown();
            callers.shutdownNow();
            callers.awaitTermination(5, TimeUnit.SECONDS);
            server.stop(0);
            httpWorkers.shutdownNow();
            events.removeTargets(r -> r.rule(name).ids("blocked-http"));
            events.deleteApiDestination(r -> r.name(name));
            events.deleteConnection(r -> r.name(name));
        }
    }

    @Test
    void stoppedTrailsAndReadOnlySelectorsDoNotPublishWrites() {
        secrets.putSecretValue(r -> r.secretId(arn).secretString("not-logged"));
        assertTrue(receive(0).isEmpty());
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            trails.putEventSelectors(REGION, name, List.of(new EventSelector("ReadOnly", true, List.of(), List.of())));
            trails.startLogging(REGION, name);
        });
        secrets.updateSecret(r -> r.secretId(arn).description("metadata only"));
        assertTrue(receive(0).isEmpty());
    }
}
