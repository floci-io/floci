package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.spectrum.SpectrumQueryPreparation;
import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.RedshiftTestCluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runs COPY JOB statements through the Data API against a cluster's PostgreSQL, so it needs a Docker daemon. */
@QuarkusTest
class RedshiftDataCopyJobIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    RedshiftService redshift;

    private final ObjectMapper om = new ObjectMapper();
    private RedshiftTestCluster cluster;
    private RedshiftDataExecutor executor;

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for Redshift copy job tests");
    }

    @AfterEach
    void cleanUp() {
        if (executor != null) {
            executor.close();
        }
        if (cluster != null) {
            cluster.close();
        }
    }

    private ObjectNode req(String sql) {
        ObjectNode request = om.createObjectNode().put("WaitTimeSeconds", 5);
        request.put("Sql", sql);
        request.put("ClusterIdentifier", "wh");
        request.put("DbUser", RedshiftTestCluster.MASTER_USER);
        request.put("Database", "dev");
        return request;
    }

    private ObjectNode idOf(String id) {
        return om.createObjectNode().put("Id", id);
    }

    @Test
    void copyJobLifecycleAndTransactionRestrictionsThroughTheDataApi() throws Exception {
        cluster = RedshiftTestCluster.create(redshift, "data-api-job");
        try (Connection connection = cluster.connect(RedshiftTestCluster.MASTER_USER);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.events (id integer)");
        }

        RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift:us-east-1:000000000000:cluster:wh", "127.0.0.1", 5439, "dev",
                RedshiftTestCluster.MASTER_USER, "x",
                new SpectrumSession("000000000000", "000000000000:wh", "dev", List.of(), false)));
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        when(factory.open(any())).thenAnswer(inv -> cluster.connect(RedshiftTestCluster.MASTER_USER));
        executor = new RedshiftDataExecutor(2, 100, 1);
        RedshiftDataService service = new RedshiftDataService(resolver, factory,
                new RedshiftDataStatementStore(24, Clock.systemUTC()), om, mock(SpectrumQueryPreparation.class),
                executor, null);

        String copy = "COPY public.events FROM 's3://bucket/prefix' IAM_ROLE 'load-role' JOB CREATE data_api_job";
        for (String sql : List.of(copy, "COPY JOB SHOW data_api_job", "COPY JOB ALTER data_api_job AUTO OFF")) {
            String id = service.executeStatement(req(sql), REGION).path("Id").asText();
            ObjectNode description = service.describeStatement(idOf(id));
            assertEquals("FINISHED", description.path("Status").asText(), description.toString());
        }
        ObjectNode request = req("unused");
        request.putArray("Sqls").add("COPY JOB DROP data_api_job");
        String batchId = service.batchExecuteStatement(request, REGION).path("Id").asText();
        ObjectNode batch = service.describeStatement(idOf(batchId));
        assertEquals("FAILED", batch.path("Status").asText());
        assertTrue(batch.path("Error").asText().contains("transaction block"), batch.toString());

        ObjectNode begin = req("BEGIN").put("SessionKeepAliveSeconds", 60);
        String sessionId = service.executeStatement(begin, REGION).path("SessionId").asText();
        String dropId = service.executeStatement(req("COPY JOB DROP data_api_job").put("SessionId", sessionId), REGION)
                .path("Id").asText();
        ObjectNode drop = service.describeStatement(idOf(dropId));
        assertEquals("FAILED", drop.path("Status").asText());
        assertTrue(drop.path("Error").asText().contains("transaction block"), drop.toString());
        service.executeStatement(req("ROLLBACK").put("SessionId", sessionId), REGION);
        String id = service.executeStatement(req("COPY JOB DROP data_api_job"), REGION).path("Id").asText();
        assertEquals("FINISHED", service.describeStatement(idOf(id)).path("Status").asText());
    }
}
