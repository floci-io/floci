package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.Cluster;
import software.amazon.awssdk.services.redshift.model.ClusterNotFoundException;
import software.amazon.awssdk.services.redshift.model.InvalidClusterStateException;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class RedshiftPauseResumeIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @InjectMock
    RedshiftContainerManager containers;

    @InjectMock
    RedshiftProxyManager proxies;

    @Test
    void sdkRoundTripsPauseResumeAndAwsErrors() {
        String identifier = "pause-" + UUID.randomUUID().toString().substring(0, 8);
        RedshiftContainerHandle handle = new RedshiftContainerHandle("original", identifier, "localhost", 5432);
        when(containers.start(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(handle);
        when(containers.resume(anyString(), anyString(), anyString(), anyString())).thenReturn(handle);
        try (RedshiftClient client = client()) {
            Cluster original = client.createCluster(request -> request.clusterIdentifier(identifier)
                    .nodeType("dc2.large").masterUsername("admin").masterUserPassword("Secret123")).cluster();
            try {
                assertThrows(InvalidClusterStateException.class,
                        () -> client.resumeCluster(request -> request.clusterIdentifier(identifier)));
                Cluster paused = client.pauseCluster(request -> request.clusterIdentifier(identifier)).cluster();
                assertEquals("paused", paused.clusterStatus());
                assertEquals(original.clusterIdentifier(), paused.clusterIdentifier());
                assertEquals(original.endpoint(), paused.endpoint());
                assertEquals("paused", client.describeClusters(request -> request.clusterIdentifier(identifier))
                        .clusters().getFirst().clusterStatus());
                assertThrows(InvalidClusterStateException.class,
                        () -> client.pauseCluster(request -> request.clusterIdentifier(identifier)));
                Cluster resumed = client.resumeCluster(request -> request.clusterIdentifier(identifier)).cluster();
                assertEquals("available", resumed.clusterStatus());
                assertEquals(original.endpoint(), resumed.endpoint());
                assertEquals(original.clusterNamespaceArn(), resumed.clusterNamespaceArn());
                client.pauseCluster(request -> request.clusterIdentifier(identifier));
            } finally {
                client.deleteCluster(request -> request.clusterIdentifier(identifier).skipFinalClusterSnapshot(true));
            }
            assertEquals(404, assertThrows(ClusterNotFoundException.class,
                    () -> client.pauseCluster(request -> request.clusterIdentifier(identifier))).statusCode());
            assertEquals(404, assertThrows(ClusterNotFoundException.class,
                    () -> client.resumeCluster(request -> request.clusterIdentifier(identifier))).statusCode());
        }
    }

    private RedshiftClient client() {
        return RedshiftClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
