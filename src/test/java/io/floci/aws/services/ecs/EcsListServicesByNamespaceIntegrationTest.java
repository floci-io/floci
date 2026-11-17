package io.floci.aws.services.ecs;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.EcsException;
import software.amazon.awssdk.services.ecs.model.ListServicesByNamespaceResponse;
import software.amazon.awssdk.services.ecs.model.ServiceConnectConfiguration;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.NamespaceSummary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code ListServicesByNamespace} lists the services whose Service Connect configuration joined the namespace. */
@QuarkusTest
class EcsListServicesByNamespaceIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @Test
    void theServicesThatJoinedANamespaceAreListedByItsNameOrArnOnePageAtATime() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespace = "orders-" + suffix;
        String cluster = "ns-cluster-" + suffix;
        String defaultsCluster = "ns-defaults-" + suffix;
        try (EcsClient ecs = AwsTestClients.create(EcsClient.builder(), ACCOUNT, Region.US_EAST_1);
             ServiceDiscoveryClient cloudMap = AwsTestClients.create(ServiceDiscoveryClient.builder(), ACCOUNT,
                     Region.US_EAST_1)) {
            cloudMap.createHttpNamespace(r -> r.name(namespace));
            String namespaceArn = namespaceArn(cloudMap, namespace);
            ecs.createCluster(r -> r.clusterName(cluster));
            ecs.createCluster(r -> r.clusterName(defaultsCluster).serviceConnectDefaults(d -> d.namespace(namespace)));
            ecs.registerTaskDefinition(r -> r.family("ns-web-" + suffix)
                    .containerDefinitions(c -> c.name("web").image("nginx").memory(128)));
            String byName = createService(ecs, cluster, "by-name", suffix,
                    ServiceConnectConfiguration.builder().enabled(true).namespace(namespace).build());
            String byArn = createService(ecs, cluster, "by-arn", suffix,
                    ServiceConnectConfiguration.builder().enabled(true).namespace(namespaceArn).build());
            String byDefault = createService(ecs, defaultsCluster, "by-default", suffix,
                    ServiceConnectConfiguration.builder().enabled(true).build());
            createService(ecs, cluster, "disabled", suffix,
                    ServiceConnectConfiguration.builder().enabled(false).namespace(namespace).build());
            createService(ecs, cluster, "plain", suffix, null);

            List<String> expected = List.of(byName, byArn, byDefault);
            assertEquals(expected.stream().sorted().toList(), listAll(ecs, namespace).stream().sorted().toList());
            assertEquals(expected.stream().sorted().toList(), listAll(ecs, namespaceArn).stream().sorted().toList());
        }
    }

    @Test
    void aMissingBlankOrMalformedNamespaceIsRefused() {
        try (EcsClient ecs = AwsTestClients.create(EcsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            EcsException missing = assertThrows(EcsException.class,
                    () -> ecs.listServicesByNamespace(r -> r.namespace("does-not-exist")));
            assertEquals("NamespaceNotFoundException", missing.awsErrorDetails().errorCode());
            assertEquals("Failed to retrieve namespace for does-not-exist", missing.awsErrorDetails().errorMessage());

            EcsException blank = assertThrows(EcsException.class,
                    () -> ecs.listServicesByNamespace(r -> r.namespace(" ")));
            assertEquals("InvalidParameterException", blank.awsErrorDetails().errorCode());
            assertEquals("namespace can not be blank.", blank.awsErrorDetails().errorMessage());

            EcsException notNamespace = assertThrows(EcsException.class, () -> ecs.listServicesByNamespace(
                    r -> r.namespace("arn:aws:ecs:us-east-1:" + ACCOUNT + ":cluster/default")));
            assertEquals("InvalidParameterException", notNamespace.awsErrorDetails().errorCode());
            assertEquals("Namespace is not a valid namespace ARN.", notNamespace.awsErrorDetails().errorMessage());
        }
    }

    @Test
    void aServiceKeepsTheNamespaceItJoinedWhenItsClusterDefaultChanges() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String joined = "joined-" + suffix;
        String later = "later-" + suffix;
        String cluster = "ns-moved-" + suffix;
        try (EcsClient ecs = AwsTestClients.create(EcsClient.builder(), ACCOUNT, Region.US_EAST_1);
             ServiceDiscoveryClient cloudMap = AwsTestClients.create(ServiceDiscoveryClient.builder(), ACCOUNT,
                     Region.US_EAST_1)) {
            cloudMap.createHttpNamespace(r -> r.name(joined));
            cloudMap.createHttpNamespace(r -> r.name(later));
            ecs.createCluster(r -> r.clusterName(cluster).serviceConnectDefaults(d -> d.namespace(joined)));
            ecs.registerTaskDefinition(r -> r.family("ns-web-" + suffix)
                    .containerDefinitions(c -> c.name("web").image("nginx").memory(128)));
            String service = createService(ecs, cluster, "inherits", suffix,
                    ServiceConnectConfiguration.builder().enabled(true).build());

            ecs.updateCluster(r -> r.cluster(cluster).serviceConnectDefaults(d -> d.namespace(later)));

            assertEquals(List.of(service), listAll(ecs, joined));
            assertEquals(List.of(), listAll(ecs, later));
        }
    }

    @Test
    void aPageTokenForANegativeOffsetIsRefused() {
        String negative = Base64.getUrlEncoder().withoutPadding().encodeToString("-1".getBytes(StandardCharsets.UTF_8));
        String namespace = "token-" + UUID.randomUUID().toString().substring(0, 8);
        try (EcsClient ecs = AwsTestClients.create(EcsClient.builder(), ACCOUNT, Region.US_EAST_1);
             ServiceDiscoveryClient cloudMap = AwsTestClients.create(ServiceDiscoveryClient.builder(), ACCOUNT,
                     Region.US_EAST_1)) {
            cloudMap.createHttpNamespace(r -> r.name(namespace));

            EcsException invalid = assertThrows(EcsException.class,
                    () -> ecs.listServicesByNamespace(r -> r.namespace(namespace).nextToken(negative)));

            assertEquals("InvalidParameterException", invalid.awsErrorDetails().errorCode());
        }
    }

    private static List<String> listAll(EcsClient ecs, String namespace) {
        List<String> arns = new ArrayList<>();
        String token = null;
        do {
            String next = token;
            ListServicesByNamespaceResponse page = ecs.listServicesByNamespace(
                    r -> r.namespace(namespace).maxResults(1).nextToken(next));
            assertEquals(true, page.serviceArns().size() <= 1, page.toString());
            arns.addAll(page.serviceArns());
            token = page.nextToken();
        } while (token != null);
        return arns;
    }

    private static String createService(EcsClient ecs, String cluster, String name, String suffix,
                                        ServiceConnectConfiguration serviceConnect) {
        return ecs.createService(r -> r.cluster(cluster).serviceName(name + "-" + suffix)
                .taskDefinition("ns-web-" + suffix).desiredCount(0)
                .serviceConnectConfiguration(serviceConnect)).service().serviceArn();
    }

    private static String namespaceArn(ServiceDiscoveryClient cloudMap, String name) {
        return cloudMap.listNamespaces(r -> r.maxResults(100)).namespaces().stream()
                .filter(n -> name.equals(n.name())).map(NamespaceSummary::arn).findFirst().orElseThrow();
    }
}
