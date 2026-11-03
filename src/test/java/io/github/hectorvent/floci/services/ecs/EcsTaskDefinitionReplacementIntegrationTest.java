package io.github.hectorvent.floci.services.ecs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class EcsTaskDefinitionReplacementIntegrationTest {

    @ParameterizedTest
    @CsvSource({"us-east-1,771661800001,false", "eu-west-2,771661800002,true",
            "cn-north-1,771661800003,false", "us-gov-west-1,771661800004,true"})
    void deregisteringOldRevisionKeepsReplacementActive(String region, String account, boolean shortReference)
            throws Exception {
        String family = "replacement-" + UUID.randomUUID();
        String otherFamily = family + "-other";
        try (EcsClient client = EcsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String cluster = client.createCluster(request -> request.clusterName(family)).cluster().clusterArn();
            String service = "replacement-service";
            // Resources close in reverse order, each only if it was created. A failure while
            // closing one doesn't stop the rest and is suppressed onto the test's own failure.
            try (AutoCloseable _ = () -> client.deleteCluster(request -> request.cluster(cluster));
                    Registered old = register(client, family, 8080);
                    Registered replacement = register(client, family, 8081);
                    Registered unrelated = register(client, otherFamily, 8082)) {
                assertEquals(1, old.definition().revision());
                assertEquals(2, replacement.definition().revision());
                client.createService(request -> request.cluster(cluster).serviceName(service)
                        .taskDefinition(old.arn()).desiredCount(0));
                try (AutoCloseable _ = () -> client.deleteService(request -> request
                        .cluster(cluster).service(service).force(true))) {
                    client.updateService(request -> request.cluster(cluster).service(service)
                            .taskDefinition(replacement.arn()));

                    String oldReference = shortReference ? family + ":1" : old.arn();
                    assertEquals("INACTIVE", client.deregisterTaskDefinition(request -> request
                            .taskDefinition(oldReference)).taskDefinition().statusAsString());
                    assertStatus(client, old.arn(), "INACTIVE");
                    assertStatus(client, replacement.arn(), "ACTIVE");
                    assertStatus(client, family + ":2", "ACTIVE");
                    assertStatus(client, unrelated.arn(), "ACTIVE");
                    assertEquals(replacement.arn(), client.describeServices(request -> request
                            .cluster(cluster).services(service)).services().getFirst().taskDefinition());
                    assertEquals(8081, client.describeTaskDefinition(request -> request
                            .taskDefinition(replacement.arn())).taskDefinition()
                            .containerDefinitions().getFirst().portMappings().getFirst().hostPort());

                    // Repeated cleanup of the old resource must not drift to the latest revision.
                    client.deregisterTaskDefinition(request -> request.taskDefinition(oldReference));
                    assertStatus(client, replacement.arn(), "ACTIVE");
                    client.deregisterTaskDefinition(request -> request.taskDefinition(replacement.arn()));
                    assertStatus(client, replacement.arn(), "INACTIVE");
                    assertStatus(client, unrelated.arn(), "ACTIVE");
                }
            }
        }
    }

    private static Registered register(EcsClient client, String family, int port) {
        return new Registered(client, client.registerTaskDefinition(request -> request.family(family)
                .containerDefinitions(ContainerDefinition.builder().name("app").image("example:test").memory(128)
                        .portMappings(PortMapping.builder().containerPort(port).hostPort(port).build()).build()))
                .taskDefinition());
    }

    private static void assertStatus(EcsClient client, String reference, String expected) {
        assertEquals(expected, client.describeTaskDefinition(request -> request.taskDefinition(reference))
                .taskDefinition().statusAsString(), reference);
    }

    private record Registered(EcsClient client, TaskDefinition definition) implements AutoCloseable {

        String arn() {
            return definition.taskDefinitionArn();
        }

        @Override
        public void close() {
            client.deregisterTaskDefinition(request -> request.taskDefinition(arn()));
            client.deleteTaskDefinitions(request -> request.taskDefinitions(arn()));
        }
    }
}
