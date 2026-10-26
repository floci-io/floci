package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.RedshiftException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftDefaultIamRoleIntegrationTest {

    @InjectMock
    RedshiftContainerManager containerManager;

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"", "arn:aws:iam::000000000000:role/first"})
    void sdkCreatesChangesAndRemovesDefaultRole(String initialDefault) {
        String clusterId = "sdk-default-role";
        String first = "arn:aws:iam::000000000000:role/first";
        String second = "arn:aws:iam::000000000000:role/second";
        when(containerManager.start(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new RedshiftContainerHandle("mock-container", clusterId, "localhost", 5432));
        try (RedshiftClient client = RedshiftClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            Cluster created = client.createCluster(request -> request.clusterIdentifier(clusterId)
                    .nodeType("dc2.large").masterUsername("admin").masterUserPassword("Secret123")
                    .iamRoles(first).defaultIamRoleArn(initialDefault)).cluster();
            try {
                assertEquals(initialDefault.isBlank() ? null : initialDefault, created.defaultIamRoleArn());
                Cluster addedWithoutDefault = client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .addIamRoles(second).defaultIamRoleArn("")).cluster();
                assertNull(addedWithoutDefault.defaultIamRoleArn());
                assertEquals(2, addedWithoutDefault.iamRoles().size());
                Cluster removedWithoutDefault = client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .removeIamRoles(second).defaultIamRoleArn("")).cluster();
                assertNull(removedWithoutDefault.defaultIamRoleArn());
                assertEquals(first, removedWithoutDefault.iamRoles().getFirst().iamRoleArn());
                assertEquals(first, client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .defaultIamRoleArn(first)).cluster().defaultIamRoleArn());
                assertEquals(first, client.describeClusters(request -> request.clusterIdentifier(clusterId))
                        .clusters().getFirst().defaultIamRoleArn());

                RedshiftException error = assertThrows(RedshiftException.class,
                        () -> client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                                .defaultIamRoleArn(second)));
                assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());

                Cluster updated = client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .addIamRoles(second).defaultIamRoleArn(second)).cluster();
                assertEquals(second, updated.defaultIamRoleArn());

                Cluster cleared = client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .defaultIamRoleArn("")).cluster();
                assertNull(cleared.defaultIamRoleArn());
                assertEquals(2, cleared.iamRoles().size());
                client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId).defaultIamRoleArn(second));

                Cluster removed = client.modifyClusterIamRoles(request -> request.clusterIdentifier(clusterId)
                        .removeIamRoles(second)).cluster();
                assertNull(removed.defaultIamRoleArn());
                assertEquals(first, removed.iamRoles().getFirst().iamRoleArn());
                assertNull(client.describeClusters(request -> request.clusterIdentifier(clusterId))
                        .clusters().getFirst().defaultIamRoleArn());
            } finally {
                client.deleteCluster(request -> request.clusterIdentifier(clusterId).skipFinalClusterSnapshot(true));
            }
        }
    }
}
