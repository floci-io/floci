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
    void deregisteringOldRevisionKeepsReplacementActive(String region, String account, boolean shortReference) {
        String family = "replacement-" + UUID.randomUUID();
        String otherFamily = family + "-other";
        try (EcsClient client = EcsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String cluster = client.createCluster(request -> request.clusterName(family)).cluster().clusterArn();
            TaskDefinition old = register(client, family, 8080);
            TaskDefinition replacement = register(client, family, 8081);
            TaskDefinition unrelated = register(client, otherFamily, 8082);
            String service = "replacement-service";
            try {
                assertEquals(1, old.revision());
                assertEquals(2, replacement.revision());
                client.createService(request -> request.cluster(cluster).serviceName(service)
                        .taskDefinition(old.taskDefinitionArn()).desiredCount(0));
                client.updateService(request -> request.cluster(cluster).service(service)
                        .taskDefinition(replacement.taskDefinitionArn()));

                String oldReference = shortReference ? family + ":1" : old.taskDefinitionArn();
                assertEquals("INACTIVE", client.deregisterTaskDefinition(request -> request
                        .taskDefinition(oldReference)).taskDefinition().statusAsString());
                assertStatus(client, old.taskDefinitionArn(), "INACTIVE");
                assertStatus(client, replacement.taskDefinitionArn(), "ACTIVE");
                assertStatus(client, family + ":2", "ACTIVE");
                assertStatus(client, unrelated.taskDefinitionArn(), "ACTIVE");
                assertEquals(replacement.taskDefinitionArn(), client.describeServices(request -> request
                        .cluster(cluster).services(service)).services().getFirst().taskDefinition());
                assertEquals(8081, client.describeTaskDefinition(request -> request
                        .taskDefinition(replacement.taskDefinitionArn())).taskDefinition()
                        .containerDefinitions().getFirst().portMappings().getFirst().hostPort());

                // Repeated cleanup of the old resource must not drift to the latest revision.
                client.deregisterTaskDefinition(request -> request.taskDefinition(oldReference));
                assertStatus(client, replacement.taskDefinitionArn(), "ACTIVE");
                client.deregisterTaskDefinition(request -> request.taskDefinition(replacement.taskDefinitionArn()));
                assertStatus(client, replacement.taskDefinitionArn(), "INACTIVE");
                assertStatus(client, unrelated.taskDefinitionArn(), "ACTIVE");
            } finally {
                client.deleteService(request -> request.cluster(cluster).service(service).force(true));
                for (TaskDefinition definition : new TaskDefinition[] {old, replacement, unrelated}) {
                    client.deregisterTaskDefinition(request -> request.taskDefinition(definition.taskDefinitionArn()));
                    client.deleteTaskDefinitions(request -> request.taskDefinitions(definition.taskDefinitionArn()));
                }
                client.deleteCluster(request -> request.cluster(cluster));
            }
        }
    }

    private static TaskDefinition register(EcsClient client, String family, int port) {
        return client.registerTaskDefinition(request -> request.family(family).containerDefinitions(
                ContainerDefinition.builder().name("app").image("example:test").memory(128)
                        .portMappings(PortMapping.builder().containerPort(port).hostPort(port).build()).build()))
                .taskDefinition();
    }

    private static void assertStatus(EcsClient client, String reference, String expected) {
        assertEquals(expected, client.describeTaskDefinition(request -> request.taskDefinition(reference))
                .taskDefinition().statusAsString(), reference);
    }
}
