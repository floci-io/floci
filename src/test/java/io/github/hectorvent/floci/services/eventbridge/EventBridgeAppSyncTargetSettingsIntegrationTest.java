package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.RuleState;
import software.amazon.awssdk.services.eventbridge.model.Target;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class EventBridgeAppSyncTargetSettingsIntegrationTest {

    private static final String ACCOUNT = "725018800001";
    private static final String ENDPOINT = "arn:aws:appsync:us-east-1:" + ACCOUNT + ":endpoints/graphql-api/example";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/events-appsync";
    private static final String OPERATION = "mutation Publish($id: ID!) { publish(id: $id) { id } }";
    private EventBridgeClient client;
    private String rule;

    @BeforeEach
    void createRule() {
        client = client(ACCOUNT);
        rule = "appsync-settings-" + UUID.randomUUID();
        client.putRule(request -> request.name(rule).eventPattern("{}").state(RuleState.DISABLED));
    }

    @AfterEach
    void deleteRule() {
        try {
            client.removeTargets(request -> request.rule(rule).ids("appsync"));
            client.deleteRule(request -> request.name(rule));
        } finally {
            client.close();
        }
    }

    @Test
    void sdkRoundTripsTheGraphqlOperationAndTargetRole() {
        putTarget(OPERATION);
        Target target = listedTarget();
        assertNotNull(target.appSyncParameters());
        assertEquals(OPERATION, target.appSyncParameters().graphQLOperation());
        assertEquals(ROLE, target.roleArn());
        assertEquals(ENDPOINT, target.arn());
    }

    @Test
    void replacementUpdatesOrRemovesAppSyncParameters() {
        putTarget(OPERATION);
        String replacement = "mutation { publish(id: \"replacement\") { id } }";
        putTarget(replacement);
        assertNotNull(listedTarget().appSyncParameters());
        assertEquals(replacement, listedTarget().appSyncParameters().graphQLOperation());
        client.putTargets(request -> request.rule(rule).targets(Target.builder().id("appsync")
                .arn(ENDPOINT).roleArn(ROLE).build()));
        assertNull(listedTarget().appSyncParameters());
    }

    @Test
    void identicalRuleNamesInAnotherAccountCannotReplaceTheOperation() {
        putTarget(OPERATION);
        try (EventBridgeClient other = client("725018800002")) {
            other.putRule(request -> request.name(rule).eventPattern("{}").state(RuleState.DISABLED));
            try {
                other.putTargets(request -> request.rule(rule).targets(Target.builder().id("appsync")
                        .arn(ENDPOINT).appSyncParameters(p -> p.graphQLOperation("mutation { other }")).build()));
                assertNotNull(listedTarget().appSyncParameters());
                assertEquals(OPERATION, listedTarget().appSyncParameters().graphQLOperation());
                assertEquals("mutation { other }", other.listTargetsByRule(request -> request.rule(rule))
                        .targets().getFirst().appSyncParameters().graphQLOperation());
            } finally {
                other.removeTargets(request -> request.rule(rule).ids("appsync"));
                other.deleteRule(request -> request.name(rule));
            }
        }
    }

    @Test
    void storedTargetSerializationRetainsTheExactOperation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String stored = "{\"id\":\"appsync\",\"arn\":\"" + ENDPOINT
                + "\",\"appSyncParameters\":{\"GraphQLOperation\":\"mutation { publish { id } }\"}}";
        // The persisted model and the SDK wire model both have the name Target.
        io.github.hectorvent.floci.services.eventbridge.model.Target target = mapper.readValue(stored,
                io.github.hectorvent.floci.services.eventbridge.model.Target.class);
        JsonNode restored = mapper.readTree(mapper.writeValueAsBytes(target));
        assertEquals("mutation { publish { id } }", restored.path("appSyncParameters").path("GraphQLOperation").asText());
    }

    private void putTarget(String operation) {
        assertEquals(0, client.putTargets(request -> request.rule(rule).targets(Target.builder().id("appsync")
                .arn(ENDPOINT).roleArn(ROLE).appSyncParameters(p -> p.graphQLOperation(operation)).build())).failedEntryCount());
    }

    private Target listedTarget() {
        return client.listTargetsByRule(request -> request.rule(rule)).targets().getFirst();
    }

    private static EventBridgeClient client(String account) {
        return EventBridgeClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }
}
