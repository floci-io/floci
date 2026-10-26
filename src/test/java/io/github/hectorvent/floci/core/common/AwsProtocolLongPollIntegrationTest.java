package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.FourWorkerThreadsProfile;
import io.github.hectorvent.floci.testing.HeldWorkerProbes;
import io.github.hectorvent.floci.testing.HeldWorkerProbes.Probe;
import io.github.hectorvent.floci.testing.HeldWorkerProbes.Scope;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.HeldWorkerProbes.SCOPES;
import static io.github.hectorvent.floci.testing.HeldWorkerProbes.served;
import static io.github.hectorvent.floci.testing.HeldWorkerProbes.signed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

/**
 * A ReceiveMessage long poll waits without holding one of the shared request workers, on each of
 * the five entry points SQS is served through: AWS JSON 1.0, Query POST and GET, and CBOR by
 * X-Amz-Target and by Smithy RPC v2 path. The profile leaves four workers, and four receives that
 * wait twenty seconds would otherwise queue every other request behind them, including the health
 * check and SSM, which still run on the worker pool.
 */
@QuarkusTest
@TestProfile(FourWorkerThreadsProfile.class)
class AwsProtocolLongPollIntegrationTest {

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String CBOR_1_1 = "application/x-amz-cbor-1.1";
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @TestHTTPResource("/")
    URI endpoint;

    @InjectSpy
    SqsService sqsService;

    @Inject
    RequestContext context;

    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"json", "query-post", "query-get", "cbor-target", "cbor-rpc"})
    void waitingReceivesLeaveOtherRequestsServed(String protocol) throws Exception {
        String prefix = "long-poll-" + UUID.randomUUID();
        Map<Scope, String> queues = new LinkedHashMap<>();
        Map<String, Scope> receiveScopes = new ConcurrentHashMap<>();
        CountDownLatch receiving = new CountDownLatch(SCOPES.size());
        try (HttpClient client = HttpClient.newHttpClient()) {
            try {
                for (Scope scope : SCOPES) {
                    HttpResponse<byte[]> created = served(sqs(client, scope, "query-post", "CreateQueue",
                            Map.of("QueueName", prefix + "-" + scope.identity())));
                    assertEquals(200, created.statusCode());
                    queues.put(scope, xml(created).getString("CreateQueueResponse.CreateQueueResult.QueueUrl"));
                }
                doAnswer(call -> {
                    Scope current = new Scope(context.getAccountId(), context.getRegion());
                    if (receiveScopes.putIfAbsent(call.getArgument(0), current) == null) {
                        receiving.countDown();
                    }
                    return call.callRealMethod();
                }).when(sqsService).receiveMessage(anyString(), anyInt(), anyInt(), nullable(Integer.class),
                        anyString());

                List<CompletableFuture<HttpResponse<byte[]>>> receives = new ArrayList<>();
                for (Scope scope : SCOPES) {
                    receives.add(sqs(client, scope, protocol, "ReceiveMessage",
                            Map.of("QueueUrl", queues.get(scope), "WaitTimeSeconds", HeldWorkerProbes.HOLD_SECONDS)));
                }
                assertTrue(receiving.await(HeldWorkerProbes.ARRIVAL_SECONDS, TimeUnit.SECONDS),
                        "every receive reaches SqsService");
                for (Scope scope : SCOPES) {
                    assertEquals(scope, receiveScopes.get(queues.get(scope)),
                            "a receive runs in the account and Region it was signed for");
                }

                List<Probe> probes = new ArrayList<>(HeldWorkerProbes.sharedProbes(client, endpoint));
                for (Scope scope : SCOPES) {
                    probes.add(new Probe("ListQueues as " + scope.identity(), sqs(client, scope, protocol,
                            "ListQueues", Map.of("QueueNamePrefix", prefix)), listed -> {
                                assertEquals(200, listed.statusCode());
                                assertEquals(List.of(queues.get(scope)), queueUrls(listed, protocol));
                                assertProtocolHeaders(listed, protocol);
                            }));
                }
                probes.add(new Probe("ReceiveMessage with WaitTimeSeconds 21", sqs(client, SCOPES.getFirst(), protocol,
                        "ReceiveMessage", Map.of("QueueUrl", queues.get(SCOPES.getFirst()), "WaitTimeSeconds", 21)),
                        invalid -> {
                            assertEquals(400, invalid.statusCode());
                            assertEquals("InvalidParameterValue", errorCode(invalid, protocol));
                        }));
                HeldWorkerProbes.assertServed(probes);
                for (CompletableFuture<HttpResponse<byte[]>> receive : receives) {
                    assertFalse(receive.isDone(), "the receives are still waiting for a message");
                }

                for (int i = 0; i < SCOPES.size(); i++) {
                    Scope scope = SCOPES.get(i);
                    RequestScopes.runAs(scope.account(), scope.region(),
                            () -> sqsService.sendMessage(queues.get(scope), scope.identity(), 0, scope.region()));
                    HttpResponse<byte[]> delivered = receives.get(i).get(10, TimeUnit.SECONDS);
                    assertEquals(200, delivered.statusCode());
                    assertEquals(List.of(scope.identity()), messageBodies(delivered, protocol));
                }
            } finally {
                // Deleting a queue also ends a receive still waiting on it.
                for (Map.Entry<Scope, String> queue : queues.entrySet()) {
                    Scope scope = queue.getKey();
                    RequestScopes.runAs(scope.account(), scope.region(),
                            () -> sqsService.deleteQueue(queue.getValue(), scope.region()));
                }
            }
        }
    }

    private CompletableFuture<HttpResponse<byte[]>> sqs(HttpClient client, Scope scope, String protocol,
                                                        String action, Map<String, Object> parameters)
            throws Exception {
        if (protocol.startsWith("query")) {
            Map<String, Object> query = new LinkedHashMap<>(parameters);
            query.put("Action", action);
            query.put("Version", "2012-11-05");
            List<String> pairs = new ArrayList<>();
            query.forEach((key, value) -> pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)));
            String encoded = String.join("&", pairs);
            HttpRequest.Builder request = "query-get".equals(protocol)
                    ? signed(URI.create(endpoint + "?" + encoded), scope, "sqs").GET()
                    : signed(endpoint, scope, "sqs")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(encoded));
            return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        if ("json".equals(protocol)) {
            return client.sendAsync(signed(endpoint, scope, "sqs")
                    .header("Content-Type", JSON_1_0)
                    .header("X-Amz-Target", "AmazonSQS." + action)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(parameters)))
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        HttpRequest.Builder request = "cbor-rpc".equals(protocol)
                ? signed(endpoint.resolve("service/SQS/operation/" + action), scope, "sqs")
                        .header("smithy-protocol", "rpc-v2-cbor")
                : signed(endpoint, scope, "sqs").header("X-Amz-Target", "AmazonSQS." + action);
        return client.sendAsync(request
                .header("Content-Type", CBOR_1_1)
                .POST(HttpRequest.BodyPublishers.ofByteArray(CBOR.writeValueAsBytes(parameters)))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private List<String> queueUrls(HttpResponse<byte[]> response, String protocol) throws Exception {
        if (protocol.startsWith("query")) {
            return xml(response).getList("ListQueuesResponse.ListQueuesResult.QueueUrl", String.class);
        }
        List<String> urls = new ArrayList<>();
        tree(response, protocol).path("QueueUrls").forEach(url -> urls.add(url.asText()));
        return urls;
    }

    private List<String> messageBodies(HttpResponse<byte[]> response, String protocol) throws Exception {
        if (protocol.startsWith("query")) {
            return xml(response).getList("ReceiveMessageResponse.ReceiveMessageResult.Message.Body", String.class);
        }
        List<String> bodies = new ArrayList<>();
        tree(response, protocol).path("Messages").forEach(message -> bodies.add(message.path("Body").asText()));
        return bodies;
    }

    private String errorCode(HttpResponse<byte[]> response, String protocol) throws Exception {
        if (protocol.startsWith("query")) {
            return xml(response).getString("ErrorResponse.Error.Code");
        }
        return tree(response, protocol).path("__type").asText();
    }

    private static void assertProtocolHeaders(HttpResponse<byte[]> response, String protocol) {
        if (protocol.startsWith("query")) {
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/xml"));
        } else if (protocol.startsWith("cbor")) {
            assertEquals("rpc-v2-cbor", response.headers().firstValue("smithy-protocol").orElseThrow());
        } else {
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith(JSON_1_0));
        }
    }

    private JsonNode tree(HttpResponse<byte[]> response, String protocol) throws Exception {
        return ("json".equals(protocol) ? mapper : CBOR).readTree(response.body());
    }

    private static XmlPath xml(HttpResponse<byte[]> response) {
        return new XmlPath(new String(response.body(), StandardCharsets.UTF_8));
    }
}
