package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.RedshiftException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class RedshiftSnapshotCfnIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261024/us-east-1/cloudformation/aws4_request";
    private static final String DUMP = "CREATE TABLE preserved (id integer); INSERT INTO preserved VALUES (42);";

    @InjectMock
    RedshiftContainerManager containers;

    @InjectMock
    RedshiftProxyManager proxies;

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void prepareRuntime() {
        when(containers.start(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> new RedshiftContainerHandle("mock-container",
                        invocation.getArgument(1), "localhost", 5432));
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(3, Path.class), DUMP);
            return null;
        }).when(containers).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
    }

    @Test
    void snapshotStackRestoresDumpAndUnchangedUpdateKeepsCluster() {
        String stack = "rs-snapshot-cfn-it";
        String source = "rs-snapshot-source";
        String target = "rs-snapshot-target";
        String snapshot = "rs-snapshot-backup";
        AtomicReference<String> restoredDump = new AtomicReference<>();
        doAnswer(invocation -> {
            restoredDump.set(Files.readString(invocation.getArgument(3, Path.class)));
            return null;
        }).when(containers).restoreSnapshot(anyString(), eq(target), eq("admin"), any(Path.class));

        try (RedshiftClient client = client()) {
            client.createCluster(request -> request.clusterIdentifier(source).nodeType("ra3.large")
                    .masterUsername("admin").masterUserPassword("Original123"));
            try {
                client.createClusterSnapshot(request -> request.clusterIdentifier(source).snapshotIdentifier(snapshot));
                client.deleteCluster(request -> request.clusterIdentifier(source).skipFinalClusterSnapshot(true));
                createOrUpdateStack(stack, "CreateStack", template(target, snapshot, "ra3.large"));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());

                Cluster cluster = client.describeClusters(request -> request.clusterIdentifier(target)).clusters().getFirst();
                String xml = given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                        .formParam("Action", "DescribeStacks").formParam("StackName", stack)
                        .post("/").then().statusCode(200).extract().asString();
                Map<String, String> outputs = XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
                assertEquals(target, outputs.get("Ref"));
                assertEquals(cluster.endpoint().address(), outputs.get("Address"));
                assertEquals(String.valueOf(cluster.endpoint().port()), outputs.get("Port"));
                assertTrue(outputs.get("Namespace").contains(":namespace:"));
                assertEquals(DUMP, restoredDump.get());
                assertEquals("admin", cluster.masterUsername());

                createOrUpdateStack(stack, "UpdateStack", template(target, snapshot, "ra3.xlplus"));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
                assertEquals(cluster.endpoint(), client.describeClusters(request -> request.clusterIdentifier(target))
                        .clusters().getFirst().endpoint());
                verify(containers).restoreSnapshot(anyString(), eq(target), eq("admin"), any(Path.class));
            } finally {
                given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                        .formParam("Action", "DeleteStack").formParam("StackName", stack).post("/")
                        .then().statusCode(200);
                CfnStackWaits.awaitStackDeleted(stack);
                deleteIfPresent(client, source);
                deleteIfPresent(client, target);
                client.deleteClusterSnapshot(request -> request.snapshotIdentifier(snapshot));
            }
        }
    }

    private RedshiftClient client() {
        return RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }

    private static void deleteIfPresent(RedshiftClient client, String identifier) {
        try {
            client.deleteCluster(request -> request.clusterIdentifier(identifier).skipFinalClusterSnapshot(true));
        } catch (RedshiftException expected) {
            // Stack cleanup may already have deleted the cluster.
            if (!"ClusterNotFound".equals(expected.awsErrorDetails().errorCode())) {
                throw expected;
            }
        }
    }

    private static void createOrUpdateStack(String stack, String action, String template) {
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action).formParam("StackName", stack).formParam("TemplateBody", template)
                .post("/").then().statusCode(200);
    }

    private static String template(String identifier, String snapshot, String nodeType) {
        return """
                {"Resources":{"Warehouse":{"Type":"AWS::Redshift::Cluster","Properties":{
                  "ClusterIdentifier":"%s","NodeType":"%s","DBName":"dev","ClusterType":"single-node",
                  "MasterUsername":"admin","SnapshotIdentifier":"%s"}}},
                 "Outputs":{
                   "Ref":{"Value":{"Ref":"Warehouse"}},
                   "Address":{"Value":{"Fn::GetAtt":["Warehouse","Endpoint.Address"]}},
                   "Port":{"Value":{"Fn::GetAtt":["Warehouse","Endpoint.Port"]}},
                   "Namespace":{"Value":{"Fn::GetAtt":["Warehouse","ClusterNamespaceArn"]}}}}
                """.formatted(identifier, nodeType, snapshot);
    }
}
