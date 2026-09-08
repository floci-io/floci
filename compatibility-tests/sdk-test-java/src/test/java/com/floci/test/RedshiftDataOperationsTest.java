package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.DescribeStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.redshiftdata.model.GetStatementResultRequest;
import software.amazon.awssdk.services.redshiftdata.model.GetStatementResultResponse;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;

import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redshift Data API Operations")
class RedshiftDataOperationsTest {

    private static final Logger LOG = Logger.getLogger(RedshiftDataOperationsTest.class);

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password123";
    private static final String DATABASE = "dev";

    private static RedshiftClient redshift;
    private static RedshiftDataClient data;
    private static String clusterId;

    @BeforeAll
    static void setup() {
        redshift = TestFixtures.redshiftClient();
        data = TestFixtures.redshiftDataClient();
        clusterId = TestFixtures.uniqueName("rsdata-cluster");
        redshift.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .nodeType("dc2.large")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (redshift != null && clusterId != null) {
            try {
                redshift.deleteCluster(DeleteClusterRequest.builder().clusterIdentifier(clusterId).build());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to clean up Redshift cluster %s", clusterId);
            }
        }
        if (data != null) {
            data.close();
        }
        if (redshift != null) {
            redshift.close();
        }
    }

    @Test
    @DisplayName("execute, describe, and get-statement-result round trip")
    void executeDescribeGetResult() throws Exception {
        run("CREATE TABLE compat_t (id int, name varchar(20))");
        String insertId = run("INSERT INTO compat_t VALUES (1, 'a'), (2, 'b')");
        assertThat(describe(insertId).resultRows()).isEqualTo(2L);

        String selectId = run("SELECT id, name FROM compat_t ORDER BY id");
        GetStatementResultResponse result = data.getStatementResult(GetStatementResultRequest.builder()
                .id(selectId)
                .build());

        assertThat(result.totalNumRows()).isEqualTo(2L);
        assertThat(result.columnMetadata()).extracting("name").containsExactly("id", "name");
        assertThat(result.records().get(0).get(0).longValue()).isEqualTo(1L);
        assertThat(result.records().get(0).get(1).stringValue()).isEqualTo("a");
    }

    private String run(String sql) throws Exception {
        String id = data.executeStatement(ExecuteStatementRequest.builder()
                .clusterIdentifier(clusterId)
                .dbUser(USERNAME)
                .database(DATABASE)
                .sql(sql)
                .build())
                .id();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            DescribeStatementResponse describe = describe(id);
            StatusString status = describe.status();
            if (status == StatusString.FINISHED) {
                return id;
            }
            if (status == StatusString.FAILED || status == StatusString.ABORTED) {
                throw new IllegalStateException("Statement " + id + " ended " + status + ": " + describe.error());
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Statement " + id + " did not finish in time");
    }

    private DescribeStatementResponse describe(String id) {
        return data.describeStatement(DescribeStatementRequest.builder().id(id).build());
    }
}
