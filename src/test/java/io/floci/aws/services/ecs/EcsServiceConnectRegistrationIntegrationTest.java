package io.floci.aws.services.ecs;

import io.floci.aws.testing.AwsSdkErrors;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.ServiceConnectConfiguration;
import software.amazon.awssdk.services.ecs.model.ServiceConnectService;
import software.amazon.awssdk.services.ecs.model.ServiceConnectServiceResource;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.FilterCondition;
import software.amazon.awssdk.services.servicediscovery.model.NamespaceSummary;
import software.amazon.awssdk.services.servicediscovery.model.ServiceFilterName;
import software.amazon.awssdk.services.servicediscovery.model.ServiceSummary;
import software.amazon.awssdk.services.servicediscovery.model.Tag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Service Connect configuration's {@code services[]} entries are endpoints, and ECS creates a
 * Cloud Map service for each in the service's namespace, named {@code discoveryName} or, without
 * one, {@code portName}, and reports them as the deployment's {@code serviceConnectResources}
 * (the ECS Developer Guide's "Configuring Service Connect with the AWS CLI"). A client
 * configuration, with no entries, gets one Cloud Map service for the client. Each is described as managed by
 * the ECS service and tagged {@code AmazonECSManaged}. Discovery names follow a naming rule and are unique in a
 * namespace; CreateService refuses one with {@code ClientException} and UpdateService with
 * {@code InvalidParameterException}, with ECS's messages. All of it as measured on AWS (us-east-1, 2026-11-19).
 */
@QuarkusTest
class EcsServiceConnectRegistrationIntegrationTest {

    private static final Region REGION = Region.US_EAST_1;
    private static final String CLUSTER = "shop-cluster";
    private static final String FAMILY = "shop";
    private static final String INVALID_NAME = "The discovery name that you provided is invalid. This must be "
            + "unique within the AWS Cloud Map namespace. Up to 64 characters are allowed. The characters can "
            + "include lowercase letters, numbers, underscores (_), and hyphens (-). A hyphen can\u2019t be the "
            + "first character.";
    /** Cloud Map's refusal of ECS's CreateService call, as ECS passes it on, its request id varying. */
    private static final String CLOUD_MAP_SERVICE_EXISTS = "Service already exists\\. \\(Service: ServiceDiscovery, "
            + "Status Code: 400, Request ID: [0-9a-f-]{36}\\) \\(SDK Attempt Count: 1\\)";

    /** Every test works in an account of its own, so a namespace holds only what the test created. */
    private final String account = TestAccounts.randomId();
    private final Deque<Runnable> cleanups = new ArrayDeque<>();
    private EcsClient ecs;
    private ServiceDiscoveryClient cloudMap;
    private NamespaceSummary namespace;

    @BeforeEach
    void setUp() {
        ecs = AwsTestClients.create(EcsClient.builder(), account, REGION);
        cloudMap = AwsTestClients.create(ServiceDiscoveryClient.builder(), account, REGION);
        cloudMap.createHttpNamespace(r -> r.name("shop"));
        namespace = cloudMap.listNamespaces(r -> { }).namespaces().getFirst();
        cleanups.push(() -> cloudMap.deleteNamespace(r -> r.id(namespace.id())));
        ecs.createCluster(r -> r.clusterName(CLUSTER));
        cleanups.push(() -> ecs.deleteCluster(r -> r.cluster(CLUSTER)));
        String taskDefinition = ecs.registerTaskDefinition(r -> r.family(FAMILY).containerDefinitions(
                ContainerDefinition.builder().name("app").image("nginx").memory(128).portMappings(
                        PortMapping.builder().name("http").containerPort(8080).build(),
                        PortMapping.builder().name("admin").containerPort(9000).build()).build()))
                .taskDefinition().taskDefinitionArn();
        cleanups.push(() -> ecs.deregisterTaskDefinition(r -> r.taskDefinition(taskDefinition)));
    }

    @AfterEach
    void cleanUp() {
        try {
            while (!cleanups.isEmpty()) {
                try {
                    cleanups.pop().run();
                } catch (AwsServiceException e) {
                    // What a test deleted itself is gone already.
                    if (e.statusCode() != 404 && !e.awsErrorDetails().errorCode().contains("NotFound")) {
                        throw e;
                    }
                }
            }
        } finally {
            ecs.close();
            cloudMap.close();
        }
    }

    @Test
    void createServiceCreatesACloudMapServicePerEndpoint() {
        Service created = createService("web-svc", endpoint("http", "web", 80), endpoint("admin", null, 9000));

        Map<String, ServiceSummary> inNamespace = cloudMapServices();
        assertEquals(List.of("admin", "web"), List.copyOf(inNamespace.keySet()));
        inNamespace.values().forEach(service -> assertEquals("HTTP", service.typeAsString()));
        List<ServiceConnectServiceResource> expected = List.of(
                resource("web", inNamespace.get("web").arn()),
                resource("admin", inNamespace.get("admin").arn()));
        assertEquals(expected, created.deployments().getFirst().serviceConnectResources());
        assertEquals(expected, deployment("web-svc").serviceConnectResources());
        for (ServiceSummary service : inNamespace.values()) {
            assertManagedBy(created.serviceArn(), service);
        }
    }

    /** A configuration that names no namespace joins its cluster's default, which then holds its endpoints. */
    @Test
    void aConfigurationNamingNoNamespaceCreatesItsEndpointsInTheClusterDefault() {
        ecs.createCluster(r -> r.clusterName("default-cluster")
                .serviceConnectDefaults(defaults -> defaults.namespace(namespace.name())));
        cleanups.push(() -> ecs.deleteCluster(r -> r.cluster("default-cluster")));

        Service created = ecs.createService(r -> r.cluster("default-cluster").serviceName("web-svc")
                .taskDefinition(FAMILY).desiredCount(0).serviceConnectConfiguration(ServiceConnectConfiguration
                        .builder().enabled(true).services(endpoint("http", "web", 80)).build())).service();
        cleanups.push(() -> ecs.deleteService(r -> r.cluster("default-cluster").service("web-svc").force(true)));

        assertEquals(List.of("web"), List.copyOf(cloudMapServices().keySet()));
        assertEquals(List.of(resource("web", cloudMapServices().get("web").arn())),
                created.deployments().getFirst().serviceConnectResources());
    }

    @Test
    void aNamespaceNamedByArnHoldsTheEndpoints() {
        Service created = ecs.createService(r -> r.cluster(CLUSTER).serviceName("web-svc").taskDefinition(FAMILY)
                .desiredCount(0).serviceConnectConfiguration(ServiceConnectConfiguration.builder().enabled(true)
                        .namespace(namespace.arn()).services(endpoint("http", "web", 80)).build())).service();
        cleanups.push(() -> ecs.deleteService(r -> r.cluster(CLUSTER).service("web-svc").force(true)));

        assertEquals(List.of("web"), List.copyOf(cloudMapServices().keySet()));
        assertEquals(List.of(resource("web", cloudMapServices().get("web").arn())),
                created.deployments().getFirst().serviceConnectResources());
    }

    /** A client, which offers no endpoint, gets one Cloud Map service, aws-ecs-sc.client.<UUID>.<service name>. */
    @Test
    void aClientServiceGetsOneCloudMapServiceForTheClient() {
        Service created = createService("client-svc");

        Map<String, ServiceSummary> inNamespace = cloudMapServices();
        assertEquals(1, inNamespace.size());
        String name = inNamespace.keySet().iterator().next();
        assertTrue(name.matches("aws-ecs-sc\\.client\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                + "\\.client-svc"), name);
        assertEquals(List.of(resource(name, inNamespace.get(name).arn())),
                created.deployments().getFirst().serviceConnectResources());
        assertEquals("HTTP", inNamespace.get(name).typeAsString());
        assertManagedBy(created.serviceArn(), inNamespace.get(name));

        update("client-svc", connect(endpoint("http", "web", 80)));
        assertEquals(List.of("web"), List.copyOf(cloudMapServices().keySet()), "an endpoint replaces the client's");
    }

    @Test
    void updateServiceCreatesTheNewEndpointsAndDeletesTheDroppedOnes() {
        createService("web-svc", endpoint("http", "web", 80));
        String web = cloudMapServices().get("web").arn();

        update("web-svc", connect(endpoint("http", "web", 80), endpoint("admin", null, 9000)));
        String admin = cloudMapServices().get("admin").arn();
        assertEquals(List.of(resource("web", web), resource("admin", admin)),
                deployment("web-svc").serviceConnectResources(), "the kept endpoint keeps its Cloud Map service");

        update("web-svc", connect(endpoint("admin", null, 9000)));
        assertEquals(List.of("admin"), List.copyOf(cloudMapServices().keySet()));
        assertEquals(List.of(resource("admin", admin)), deployment("web-svc").serviceConnectResources());

        update("web-svc", ServiceConnectConfiguration.builder().enabled(false).build());
        assertEquals(Map.of(), cloudMapServices());
        assertFalse(deployment("web-svc").hasServiceConnectResources());
    }

    @Test
    void deleteServiceDeletesItsCloudMapServicesSoTheNamespaceCanBeDeleted() {
        createService("web-svc", endpoint("http", "web", 80));
        assertEquals(List.of("web"), List.copyOf(cloudMapServices().keySet()));

        ecs.deleteService(r -> r.cluster(CLUSTER).service("web-svc").force(true));

        assertEquals(Map.of(), cloudMapServices());
        cloudMap.deleteNamespace(r -> r.id(namespace.id()));
        assertEquals(List.of(), cloudMap.listNamespaces(r -> { }).namespaces());
    }

    /**
     * A discovery name another ECS service's endpoint holds is refused naming the namespace, one a Cloud Map
     * service made outside ECS holds with Cloud Map's refusal, and one a configuration gives twice by naming
     * it. CreateService answers {@code ClientException} and creates nothing; UpdateService answers
     * {@code InvalidParameterException}, with the same messages, and changes nothing.
     */
    @Test
    void aDiscoveryNameTheNamespaceAlreadyHasIsRefused() {
        String byHand = cloudMap.createService(r -> r.name("web").namespaceId(namespace.id())).service().arn();
        cleanups.push(() -> cloudMap.deleteService(r -> r.id(byHand.substring(byHand.lastIndexOf('/') + 1))));

        assertTrue(AwsSdkErrors.assertAwsError("ClientException", 400, () -> ecs.createService(r -> r
                .cluster(CLUSTER).serviceName("web-svc").taskDefinition(FAMILY).desiredCount(0)
                .serviceConnectConfiguration(connect(endpoint("http", "web", 80))))).awsErrorDetails()
                .errorMessage().matches(CLOUD_MAP_SERVICE_EXISTS));
        assertEquals(List.of(), ecs.listServices(r -> r.cluster(CLUSTER)).serviceArns(), "nothing was created");

        createService("admin-svc", endpoint("admin", null, 9000));
        createService("api-svc", endpoint("http", "api", 80));
        assertRefused("ClientException", "SC service is already used by " + namespace.arn(), () -> ecs
                .createService(r -> r.cluster(CLUSTER).serviceName("other-svc").taskDefinition(FAMILY)
                        .desiredCount(0).serviceConnectConfiguration(connect(endpoint("admin", null, 9001)))));
        assertRefused("ClientException", "The following discoveryNames are defined more than once: [dup].",
                () -> ecs.createService(r -> r.cluster(CLUSTER).serviceName("twice-svc").taskDefinition(FAMILY)
                        .desiredCount(0).serviceConnectConfiguration(connect(endpoint("http", "dup", 80),
                                endpoint("admin", "dup", 81)))));

        assertRefused("InvalidParameterException", "SC service is already used by " + namespace.arn(), () -> ecs
                .updateService(r -> r.cluster(CLUSTER).service("admin-svc").desiredCount(3)
                        .serviceConnectConfiguration(connect(endpoint("http", "api", 80)))));
        assertTrue(AwsSdkErrors.assertAwsError("InvalidParameterException", 400, () -> ecs.updateService(r -> r
                .cluster(CLUSTER).service("admin-svc").desiredCount(3)
                .serviceConnectConfiguration(connect(endpoint("http", "web", 80))))).awsErrorDetails()
                .errorMessage().matches(CLOUD_MAP_SERVICE_EXISTS));
        assertRefused("InvalidParameterException", "The following discoveryNames are defined more than once: [dup].",
                () -> ecs.updateService(r -> r.cluster(CLUSTER).service("admin-svc").serviceConnectConfiguration(
                        connect(endpoint("http", "dup", 80), endpoint("admin", "dup", 81)))));
        Service unchanged = ecs.describeServices(r -> r.cluster(CLUSTER).services("admin-svc")).services().getFirst();
        assertEquals(0, unchanged.desiredCount(), "a refused update changes nothing");
        assertEquals(List.of(resource("admin", cloudMapServices().get("admin").arn())),
                unchanged.deployments().getFirst().serviceConnectResources());
        assertEquals(List.of("admin", "api", "web"), List.copyOf(cloudMapServices().keySet()));
    }

    /**
     * A discovery name is up to 64 lowercase letters, numbers, underscores and hyphens, not beginning with a
     * hyphen; any other is refused before the namespace is looked at, by CreateService and by UpdateService.
     */
    @Test
    void aDiscoveryNameOutsideTheNamingRuleIsRefused() {
        for (String name : List.of("API", "-api", "a".repeat(65))) {
            assertRefused("ClientException", INVALID_NAME, () -> ecs.createService(r -> r.cluster(CLUSTER)
                    .serviceName("bad-svc").taskDefinition(FAMILY).desiredCount(0)
                    .serviceConnectConfiguration(connect(endpoint("http", name, 80)))));
        }
        assertEquals(List.of(), ecs.listServices(r -> r.cluster(CLUSTER)).serviceArns(), "nothing was created");
        assertEquals(Map.of(), cloudMapServices());

        createService("web-svc", endpoint("http", "api_v2", 80));
        assertEquals(List.of("api_v2"), List.copyOf(cloudMapServices().keySet()));
        assertRefused("InvalidParameterException", INVALID_NAME, () -> ecs.updateService(r -> r.cluster(CLUSTER)
                .service("web-svc").serviceConnectConfiguration(connect(endpoint("http", "API", 80)))));
        assertEquals(List.of("api_v2"), List.copyOf(cloudMapServices().keySet()));
    }

    /**
     * A DNS namespace's Cloud Map service names are DNS labels, so Cloud Map holds {@code Web} and {@code web}
     * as one name, and a Cloud Map service made outside ECS as {@code Web} keeps an endpoint from {@code web}.
     */
    @Test
    void aDnsNamespaceComparesDiscoveryNamesAsCloudMapDoes() {
        cloudMap.createPublicDnsNamespace(r -> r.name("shop.example"));
        String dnsNamespace = cloudMap.listNamespaces(r -> { }).namespaces().stream()
                .filter(candidate -> "shop.example".equals(candidate.name()))
                .findFirst().orElseThrow().id();
        cleanups.push(() -> cloudMap.deleteNamespace(r -> r.id(dnsNamespace)));
        String byHand = cloudMap.createService(r -> r.name("Web").namespaceId(dnsNamespace)).service().arn();
        cleanups.push(() -> cloudMap.deleteService(r -> r.id(byHand.substring(byHand.lastIndexOf('/') + 1))));

        assertTrue(AwsSdkErrors.assertAwsError("ClientException", 400, () -> ecs.createService(r -> r
                .cluster(CLUSTER).serviceName("case-svc").taskDefinition(FAMILY).desiredCount(0)
                .serviceConnectConfiguration(ServiceConnectConfiguration.builder().enabled(true)
                        .namespace("shop.example").services(endpoint("http", "web", 80)).build())))
                .awsErrorDetails().errorMessage().matches(CLOUD_MAP_SERVICE_EXISTS));
        assertEquals(List.of("Web"), List.copyOf(cloudMapServices(dnsNamespace).keySet()));
    }

    private Service createService(String name, ServiceConnectService... endpoints) {
        Service created = ecs.createService(r -> r.cluster(CLUSTER).serviceName(name).taskDefinition(FAMILY)
                .desiredCount(0).serviceConnectConfiguration(connect(endpoints))).service();
        cleanups.push(() -> ecs.deleteService(r -> r.cluster(CLUSTER).service(name).force(true)));
        return created;
    }

    private void update(String name, ServiceConnectConfiguration configuration) {
        ecs.updateService(r -> r.cluster(CLUSTER).service(name).serviceConnectConfiguration(configuration));
    }

    private Deployment deployment(String name) {
        return ecs.describeServices(r -> r.cluster(CLUSTER).services(name)).services().getFirst()
                .deployments().getFirst();
    }

    /** The namespace's Cloud Map services by name. */
    private Map<String, ServiceSummary> cloudMapServices() {
        return cloudMapServices(namespace.id());
    }

    private Map<String, ServiceSummary> cloudMapServices(String namespaceId) {
        Map<String, ServiceSummary> byName = new TreeMap<>();
        cloudMap.listServicesPaginator(r -> r.filters(f -> f.name(ServiceFilterName.NAMESPACE_ID)
                        .condition(FilterCondition.EQ).values(namespaceId)))
                .stream().flatMap(page -> page.services().stream())
                .forEach(service -> byName.put(service.name(), service));
        return byName;
    }

    private ServiceConnectConfiguration connect(ServiceConnectService... endpoints) {
        return ServiceConnectConfiguration.builder().enabled(true).namespace(namespace.name())
                .services(endpoints).build();
    }

    private static ServiceConnectService endpoint(String portName, String discoveryName, int port) {
        return ServiceConnectService.builder().portName(portName).discoveryName(discoveryName)
                .clientAliases(alias -> alias.port(port)).build();
    }

    private static ServiceConnectServiceResource resource(String discoveryName, String discoveryArn) {
        return ServiceConnectServiceResource.builder().discoveryName(discoveryName).discoveryArn(discoveryArn).build();
    }

    private static void assertRefused(String code, String message, Executable call) {
        assertEquals(message, AwsSdkErrors.assertAwsError(code, 400, call).awsErrorDetails().errorMessage());
    }

    /** Describes the Cloud Map service as ECS does the ones it creates, and checks its tag. */
    private void assertManagedBy(String serviceArn, ServiceSummary service) {
        String id = service.arn().substring(service.arn().lastIndexOf('/') + 1);
        assertEquals("Managed by " + serviceArn, cloudMap.getService(r -> r.id(id)).service().description());
        assertEquals(Map.of("AmazonECSManaged", "true"), cloudMap.listTagsForResource(r -> r.resourceARN(
                service.arn())).tags().stream().collect(Collectors.toMap(Tag::key, Tag::value)));
    }
}
