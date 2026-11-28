package io.floci.aws.testing;

import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.restassured.response.Response;
import org.awaitility.Awaitility;
import org.jboss.logging.Logger;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static io.floci.aws.testing.RdsRequests.createPostgresInstance;
import static io.floci.aws.testing.RdsRequests.deleteInstance;
import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * A Docker-backed RDS PostgreSQL source and Redshift Serverless target wired through an RDS zero-ETL integration,
 * with JDBC helpers and best-effort teardown.
 */
public final class RdsZeroEtlFixture implements AutoCloseable {

    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(60);

    private static final Logger LOG = Logger.getLogger(RdsZeroEtlFixture.class);
    private static final String SOURCE_DATABASE = "appdb";
    private static final String DESTINATION_DATABASE = "appdb_dw";
    private static final String MASTER_USER = "master";
    private static final String MASTER_PASSWORD = "Master123!";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "Secret123";
    private static final String REGION = "us-east-1";

    private final RedshiftServerlessService serverless;
    private final String parameterGroup;
    private final String dbId;
    private final String namespace;
    private final String workgroup;
    private final String integrationName;

    private boolean parameterGroupCreated;
    private boolean instanceCreated;
    private int sourcePort;
    private boolean namespaceCreated;
    private boolean workgroupCreated;
    private int workgroupPort;
    private String integrationArn;
    private String integrationId;

    private RdsZeroEtlFixture(RedshiftServerlessService serverless, String prefix, boolean logicalReplication) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        this.serverless = serverless;
        this.parameterGroup = logicalReplication ? prefix + "-pg-" + suffix : null;
        this.dbId = prefix + "-" + suffix;
        this.namespace = prefix + "-ns-" + suffix;
        this.workgroup = prefix + "-wg-" + suffix;
        this.integrationName = prefix + "-" + suffix;
    }

    /**
     * Starts a PostgreSQL source instance (with {@code rds.logical_replication = 1} when
     * {@code logicalReplication} is true) ready to be seeded before creating the integration.
     */
    public static RdsZeroEtlFixture create(RedshiftServerlessService serverless, String prefix,
                                           boolean logicalReplication) {
        RdsZeroEtlFixture fixture = new RdsZeroEtlFixture(serverless, prefix, logicalReplication);
        try {
            fixture.startSource();
            return fixture;
        } catch (RuntimeException e) {
            fixture.close();
            throw e;
        }
    }

    private void startSource() {
        if (parameterGroup != null) {
            Response groupResponse = rds("CreateDBParameterGroup")
                    .formParam("DBParameterGroupName", parameterGroup)
                    .formParam("DBParameterGroupFamily", "postgres16")
                    .formParam("Description", "zero-ETL source")
            .when().post("/");
            parameterGroupCreated = groupResponse.statusCode() < 300;
            groupResponse.then().statusCode(200);
            rds("ModifyDBParameterGroup")
                    .formParam("DBParameterGroupName", parameterGroup)
                    .formParam("Parameters.member.1.ParameterName", "rds.logical_replication")
                    .formParam("Parameters.member.1.ParameterValue", "1")
                    .formParam("Parameters.member.1.ApplyMethod", "pending-reboot")
            .when().post("/").then().statusCode(200);
        }
        sourcePort = createPostgresInstance(dbId, MASTER_USER, MASTER_PASSWORD, false, parameterGroup);
        instanceCreated = true;
    }

    /**
     * Creates the Redshift Serverless namespace and workgroup, creates the zero-ETL integration with
     * {@code dataFilter}, runs {@code CREATE DATABASE appdb_dw FROM INTEGRATION}, and waits for the
     * integration to become active.
     */
    public void createIntegrationAndAwaitActive(String dataFilter) throws SQLException {
        Response namespaceResponse = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateNamespace", """
                {"namespaceName":"%s","adminUsername":"%s","adminUserPassword":"%s"}
                """.formatted(namespace, ADMIN_USER, ADMIN_PASSWORD));
        namespaceCreated = namespaceResponse.statusCode() < 300;
        String namespaceArn = namespaceResponse.then().statusCode(200)
                .extract().path("namespace.namespaceArn");

        Response workgroupResponse = RestAssuredJsonUtils.awsAction("RedshiftServerless", "CreateWorkgroup", """
                {"workgroupName":"%s","namespaceName":"%s"}
                """.formatted(workgroup, namespace));
        workgroupCreated = workgroupResponse.statusCode() < 300;
        workgroupPort = workgroupResponse.then().statusCode(200)
                .extract().path("workgroup.endpoint.port");

        integrationArn = rds("CreateIntegration")
                .formParam("IntegrationName", integrationName)
                .formParam("SourceArn", "arn:aws:rds:us-east-1:000000000000:db:" + dbId)
                .formParam("TargetArn", namespaceArn)
                .formParam("DataFilter", dataFilter)
                .when().post("/").then().statusCode(200)
                .body(containsString("<Status>creating</Status>"))
                .extract().xmlPath().getString("CreateIntegrationResponse.CreateIntegrationResult.IntegrationArn");
        integrationId = integrationArn.substring(integrationArn.lastIndexOf(':') + 1);

        executeTarget("dev",
                "CREATE DATABASE " + DESTINATION_DATABASE + " FROM INTEGRATION '" + integrationId
                        + "' DATABASE '" + SOURCE_DATABASE + "'");

        String arn = integrationArn;
        Awaitility.await().atMost(DEFAULT_WAIT).pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    String body = rds("DescribeIntegrations").formParam("IntegrationIdentifier", arn)
                            .when().post("/").then().statusCode(200).extract().asString();
                    assertThat("the load failed: " + body, body,
                            not(containsString("<Status>failed</Status>")));
                    return body.contains("<Status>active</Status>");
                });
    }

    public String integrationId() {
        return integrationId;
    }

    public void executeSource(String... statements) throws SQLException {
        execute(sourcePort, SOURCE_DATABASE, MASTER_USER, MASTER_PASSWORD, statements);
    }

    public List<String> querySource(String sql) throws SQLException {
        return query(sourcePort, SOURCE_DATABASE, MASTER_USER, MASTER_PASSWORD, sql);
    }

    public void executeTarget(String database, String... statements) throws SQLException {
        execute(workgroupPort, database, ADMIN_USER, ADMIN_PASSWORD, statements);
    }

    public List<String> queryTarget(String sql) throws SQLException {
        return query(workgroupPort, DESTINATION_DATABASE, ADMIN_USER, ADMIN_PASSWORD, sql);
    }

    public void deleteIntegration() {
        String arnToDelete = integrationArn;
        if (arnToDelete == null) {
            return;
        }
        rds("DeleteIntegration").formParam("IntegrationIdentifier", arnToDelete).when().post("/")
                .then().statusCode(200);
        integrationArn = null;
    }

    @Override
    public void close() {
        String arnToDelete = integrationArn;
        if (arnToDelete != null) {
            integrationArn = null;
            cleanUpStep("DeleteIntegration " + arnToDelete, () -> rds("DeleteIntegration")
                    .formParam("IntegrationIdentifier", arnToDelete).when().post("/"));
        }
        if (workgroupCreated) {
            workgroupCreated = false;
            cleanUpStep("deleteWorkgroup " + workgroup, () -> serverless.deleteWorkgroup(workgroup, REGION));
        }
        if (namespaceCreated) {
            namespaceCreated = false;
            cleanUpStep("deleteNamespace " + namespace, () -> serverless.deleteNamespace(namespace, REGION));
        }
        if (instanceCreated) {
            instanceCreated = false;
            cleanUpStep("deleteInstance " + dbId, () -> deleteInstance(dbId));
        }
        if (parameterGroupCreated) {
            parameterGroupCreated = false;
            cleanUpStep("DeleteDBParameterGroup " + parameterGroup, () -> rds("DeleteDBParameterGroup")
                    .formParam("DBParameterGroupName", parameterGroup).when().post("/"));
        }
    }

    private static void cleanUpStep(String description, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Teardown step failed: {0}", description);
        }
    }

    private static Connection connect(int port, String database, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        // 127.0.0.1 rather than the advertised address, which CI sets to a name the test JVM cannot resolve;
        // the driver itself rather than DriverManager, which may not see it from a test profile's class loader.
        return new Driver().connect("jdbc:postgresql://127.0.0.1:" + port + "/" + database, properties);
    }

    private static void execute(int port, String database, String user, String password, String... statements)
            throws SQLException {
        try (Connection connection = connect(port, database, user, password);
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> query(int port, String database, String user, String password, String sql)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect(port, database, user, password);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
