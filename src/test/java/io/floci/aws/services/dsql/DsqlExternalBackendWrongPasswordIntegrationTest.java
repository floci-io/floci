package io.floci.aws.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.dsql.dataplane.DsqlEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * A superuser password the external server refuses fails each cluster's start, and nothing else:
 * the cluster is FAILED and Floci goes on answering. Deleting it finishes although the server
 * cannot be cleaned up, as AWS deletes a FAILED cluster.
 */
@QuarkusTest
@TestProfile(DsqlExternalBackendWrongPasswordProfile.class)
@Tag("docker")
class DsqlExternalBackendWrongPasswordIntegrationTest {

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @Test
    void wrongSuperuserPasswordFailsTheClusterOnly() {
        DsqlClusterFixture cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.createAndAwait(dockerClient, "FAILED");

        given().port(httpPort).get("/_floci/health").then().statusCode(200);
        cluster.listClusters();

        // GetCluster answers ResourceNotFound (404) once the deletion is done.
        cluster.delete();
    }
}
