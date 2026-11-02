package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
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

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SecretsManagerEventsResetIntegrationTest {
    private static final String ACCOUNT = "661100000007";
    private static final String REGION = "eu-west-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATTERN = "{\"source\":[\"aws.secretsmanager\"],"
            + "\"detail-type\":[\"AWS API Call via CloudTrail\"]}";

    @Inject
    CloudTrailService trails;

    @Inject
    SqsService sqs;

    @Test
    void actualResetDrainsBlockedTargetsDropsOldWritesAndDeliversNewSdkWrites() throws Exception {
        URI endpoint = URI.create("http://localhost:" + RestAssured.port);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"));
        CountDownLatch arrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService httpWorkers = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService resets = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpWorkers);
        server.createContext("/hook", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                arrived.countDown();
                boolean released = release.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(released ? 200 : 504, -1);
            } catch (InterruptedException expected) {
                // Teardown releases or interrupts every blocked test target.
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (SecretsManagerClient secrets = SecretsManagerClient.builder().endpointOverride(endpoint)
                     .region(Region.of(REGION)).httpClientBuilder(UrlConnectionHttpClient.builder())
                     .credentialsProvider(credentials).build();
             EventBridgeClient events = EventBridgeClient.builder().endpointOverride(endpoint)
                     .region(Region.of(REGION)).httpClientBuilder(UrlConnectionHttpClient.builder())
                     .credentialsProvider(credentials).build()) {
            String name = "reset-events-" + UUID.randomUUID();
            String oldArn = secrets.createSecret(r -> r.name(name).secretString("old-example")).arn();
            String connection = events.createConnection(r -> r.name(name)
                    .authorizationType(ConnectionAuthorizationType.API_KEY)
                    .authParameters(p -> p.apiKeyAuthParameters(k -> k.apiKeyName("example-key").apiKeyValue("example-value"))))
                    .connectionArn();
            String destination = events.createApiDestination(r -> r.name(name).connectionArn(connection)
                    .httpMethod(ApiDestinationHttpMethod.POST).invocationRateLimitPerSecond(100)
                    .invocationEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"))
                    .apiDestinationArn();
            rule(events, name, destination);
            startTrail(name);
            secrets.putSecretValue(r -> r.secretId(oldArn).secretString("old-first"));
            secrets.updateSecret(r -> r.secretId(oldArn).secretString("old-second"));
            assertTrue(arrived.await(3, TimeUnit.SECONDS), "both publisher workers must be occupied");
            secrets.putSecretValue(r -> r.secretId(oldArn).secretString("queued-before-reset"));

            Future<?> reset = resets.submit(() -> given().post("/_floci/state/reset").then().statusCode(200));
            assertThrows(TimeoutException.class, () -> reset.get(200, TimeUnit.MILLISECONDS),
                    "reset must drain blocked target deliveries before clearing their state");
            release.countDown();
            reset.get(15, TimeUnit.SECONDS);

            String freshArn = secrets.createSecret(r -> r.name(name).secretString("fresh-example")).arn();
            String queueUrl = RequestScopes.callAs(ACCOUNT, REGION,
                    () -> sqs.createQueue(name, Map.of(), REGION).getQueueUrl());
            String queueArn = RequestScopes.callAs(ACCOUNT, REGION,
                    () -> sqs.getQueueAttributes(queueUrl, List.of("QueueArn"), REGION).get("QueueArn"));
            rule(events, name, queueArn);
            startTrail(name);
            secrets.putSecretValue(r -> r.secretId(freshArn).secretString("fresh-write"));
            List<Message> messages = receive(queueUrl, 2);
            assertEquals(1, messages.size(), "new rules must receive only writes made after reset");
            JsonNode event = MAPPER.readTree(messages.getFirst().getBody());
            assertEquals(freshArn, event.path("detail").path("responseElements").path("arn").asText());
            assertEquals(ACCOUNT, event.path("account").asText());
            assertEquals(REGION, event.path("region").asText());
            assertTrue(receive(queueUrl, 1).isEmpty(),
                    "queued events from deleted secrets must not reach the recreated rule");
        } finally {
            release.countDown();
            resets.shutdownNow();
            assertTrue(resets.awaitTermination(5, TimeUnit.SECONDS));
            server.stop(0);
            httpWorkers.shutdownNow();
            given().post("/_floci/state/reset").then().statusCode(200);
        }
    }

    private void startTrail(String name) {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            trails.createTrail(REGION, name, "example-reset-audit-logs", null, null, false, false, false, false);
            trails.startLogging(REGION, name);
        });
    }

    private void rule(EventBridgeClient events, String name, String targetArn) {
        events.putRule(r -> r.name(name).eventPattern(PATTERN));
        events.putTargets(r -> r.rule(name).targets(Target.builder().id("target").arn(targetArn).build()));
    }

    private List<Message> receive(String queueUrl, int waitSeconds) {
        return RequestScopes.callAs(ACCOUNT, REGION, () -> sqs.receiveMessage(queueUrl, 10, 30, waitSeconds, REGION));
    }
}
