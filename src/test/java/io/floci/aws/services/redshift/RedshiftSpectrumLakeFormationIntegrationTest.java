package io.floci.aws.services.redshift;

import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.lakeformation.LakeFormationService;
import io.floci.aws.services.lakeformation.model.DataLakePrincipal;
import io.floci.aws.services.lakeformation.model.DataLakeSettings;
import io.floci.aws.services.lakeformation.model.GrantPermissionsRequest;
import io.floci.aws.services.lakeformation.model.PutDataLakeSettingsRequest;
import io.floci.aws.services.lakeformation.model.Resource;
import io.floci.aws.services.lakeformation.model.RevokePermissionsRequest;
import io.floci.aws.services.lakeformation.model.TableResource;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.spectrum.ExternalStatement;
import io.floci.aws.services.redshift.spectrum.ExternalStatementParser;
import io.floci.aws.services.redshift.spectrum.GlueTableBuilder;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.SidecarContainersProfile;
import io.floci.aws.testing.SidecarSpectrumLakeFormationProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static io.floci.aws.services.redshift.RedshiftConnections.waitForConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Spectrum query is refused until the external schema's role holds a Lake Formation {@code SELECT}
 * grant on the table, and a revoke stops the next query even though the table is already cached.
 */
@QuarkusTest
@TestProfile(SidecarSpectrumLakeFormationProfile.class)
class RedshiftSpectrumLakeFormationIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE_NAME = "SpectrumLfItRole";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/" + ROLE_NAME;
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @Inject
    RedshiftService redshiftService;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @Inject
    GlueService glueService;

    @Inject
    LakeFormationService lakeFormationService;

    private String clusterId;
    private String bucket;
    private String glueDatabase;

    @BeforeAll
    static void requireDocker() throws ClassNotFoundException {
        SidecarContainersProfile.requireDocker();
        Class.forName("org.postgresql.Driver");
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
        if (bucket != null) {
            s3Service.deleteObject(bucket, "events/part-1.csv");
            s3Service.deleteBucket(bucket);
        }
        if (glueDatabase != null) {
            glueService.deleteDatabase(glueDatabase);
        }
        iamService.deleteRolePolicy(ROLE_NAME, "AllowS3");
        iamService.deleteRole(ROLE_NAME);
    }

    @Test
    @Timeout(180)
    void aGrantOpensTheTableAndARevokeClosesItAgainOnceCached() throws Exception {
        iamService.createRole(ROLE_NAME, "/", TRUST_POLICY, null, 0, null);
        iamService.putRolePolicy(ROLE_NAME, "AllowS3", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}]}""");
        bucket = "spectrum-lf-" + System.nanoTime();
        s3Service.createBucket(bucket, REGION);
        s3Service.putObject(bucket, "events/part-1.csv", "1,10\n2,7\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        glueDatabase = "spectrum_lf_" + System.nanoTime();
        Database database = new Database();
        database.setName(glueDatabase);
        glueService.createDatabase(database);
        clusterId = "it-lf-" + System.nanoTime();
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123",
                null, List.of(), List.of(ROLE_ARN));
        try (Connection connection = waitForConnection(cluster);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE '" + glueDatabase
                    + "' IAM_ROLE '" + ROLE_ARN + "'");
            String externalDdl = "CREATE EXTERNAL TABLE lake.events (id INTEGER, amount INTEGER) "
                    + "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE LOCATION 's3://"
                    + bucket + "/events/'";
            ExternalStatement.CreateTable external = (ExternalStatement.CreateTable)
                    new ExternalStatementParser().parse(externalDdl).orElseThrow();
            glueService.createTable(glueDatabase, GlueTableBuilder.toGlueTable(external));

            DataLakeSettings settings = new DataLakeSettings();
            settings.setCreateTableDefaultPermissions(List.of());
            PutDataLakeSettingsRequest managed = new PutDataLakeSettingsRequest();
            managed.setDataLakeSettings(settings);
            lakeFormationService.putDataLakeSettings(REGION, managed);

            SQLException denied = assertThrows(SQLException.class, () -> sumAmount(statement));
            assertEquals("42501", denied.getSQLState());

            lakeFormationService.grantPermissions(REGION, grant());
            assertEquals(17L, sumAmount(statement));
            assertEquals(17L, sumAmount(statement));

            lakeFormationService.revokePermissions(REGION, revoke());
            SQLException revoked = assertThrows(SQLException.class, () -> sumAmount(statement));
            assertEquals("42501", revoked.getSQLState());
        }
    }

    private GrantPermissionsRequest grant() {
        GrantPermissionsRequest request = new GrantPermissionsRequest();
        request.setPrincipal(principal());
        request.setResource(tableResource());
        request.setPermissions(List.of("SELECT"));
        return request;
    }

    private RevokePermissionsRequest revoke() {
        RevokePermissionsRequest request = new RevokePermissionsRequest();
        request.setPrincipal(principal());
        request.setResource(tableResource());
        request.setPermissions(List.of("SELECT"));
        return request;
    }

    private static DataLakePrincipal principal() {
        DataLakePrincipal principal = new DataLakePrincipal();
        principal.setDataLakePrincipalIdentifier(ROLE_ARN);
        return principal;
    }

    private Resource tableResource() {
        TableResource table = new TableResource();
        table.setDatabaseName(glueDatabase);
        table.setName("events");
        Resource resource = new Resource();
        resource.setTable(table);
        return resource;
    }

    private static long sumAmount(Statement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT SUM(amount) FROM lake.events")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
