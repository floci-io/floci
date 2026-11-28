package io.floci.aws.services.rds;

import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import io.floci.aws.testing.RdsZeroEtlFixture;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static io.floci.aws.testing.RdsRequests.assumeDocker;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The initial load of an RDS zero-ETL integration end to end: a PostgreSQL instance as the source, a
 * Redshift Serverless workgroup as the target, and the destination database created over the
 * workgroup's PostgreSQL endpoint with {@code CREATE DATABASE ... FROM INTEGRATION}. Needs Docker.
 */
@QuarkusTest
class RdsZeroEtlInitialLoadIntegrationTest {

    @Inject
    RedshiftServerlessService serverless;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeDocker();
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theIncludedTablesWithAPrimaryKeyAreCopiedIntoTheDestinationDatabase() throws Exception {
        try (RdsZeroEtlFixture fixture = RdsZeroEtlFixture.create(serverless, "zetl-src", false)) {
            fixture.executeSource(
                    "CREATE SCHEMA sales",
                    "CREATE TABLE sales.orders (id int8, line int4, note text, total numeric(10,2), "
                            + "attrs jsonb, PRIMARY KEY (id, line))",
                    "INSERT INTO sales.orders VALUES (1, 1, 'first', 12.30, '{\"rush\": true}'), "
                            + "(1, 2, NULL, 0.50, NULL)",
                    "CREATE TABLE customers (id serial, name text, payload text, PRIMARY KEY (id) INCLUDE (payload))",
                    "INSERT INTO customers (name, payload) VALUES ('ada', 'vip'), ('grace', NULL)",
                    "CREATE TABLE no_key (id int)",
                    "CREATE TABLE left_out (id int PRIMARY KEY)");

            fixture.createIntegrationAndAwaitActive("include: appdb.*.*, exclude: appdb.public.left_out");

            assertThat(fixture.queryTarget(
                            "SELECT id || ':' || line || ':' || COALESCE(note, '-') || ':' || total || ':' "
                                    + "|| COALESCE(attrs::text, '-') FROM sales.orders ORDER BY id, line"),
                    contains("1:1:first:12.30:{\"rush\": true}", "1:2:-:0.50:-"));
            assertThat(fixture.queryTarget(
                            "SELECT string_agg(table_schema || '.' || table_name, ',' ORDER BY table_name) "
                                    + "FROM information_schema.tables WHERE table_schema IN ('public', 'sales')"),
                    contains("public.customers,sales.orders"));
            assertThat(fixture.queryTarget(
                            "SELECT name || ':' || COALESCE(payload, '-') FROM public.customers ORDER BY id"),
                    contains("ada:vip", "grace:-"));
            assertThat(fixture.queryTarget(
                            "SELECT k.table_name || ':' || string_agg(k.column_name, ',' ORDER BY k.ordinal_position) "
                                    + "FROM information_schema.table_constraints c "
                                    + "JOIN information_schema.key_column_usage k "
                                    + "ON k.constraint_name = c.constraint_name AND k.table_schema = c.table_schema "
                                    + "WHERE c.constraint_type = 'PRIMARY KEY' "
                                    + "AND c.table_schema IN ('public', 'sales') "
                                    + "GROUP BY k.table_name ORDER BY k.table_name"),
                    contains("customers:id", "orders:id,line"));
            assertThat(fixture.queryTarget(
                            "SELECT data_type FROM information_schema.columns "
                                    + "WHERE table_name = 'orders' AND column_name = 'note'"),
                    contains("character varying"));

            SQLException again = assertThrows(SQLException.class,
                    () -> fixture.executeTarget("dev",
                            "CREATE DATABASE appdb_dw2 FROM INTEGRATION '" + fixture.integrationId()
                                    + "' DATABASE 'appdb'"));
            assertThat(again.getSQLState(), equalTo("42P04"));
        }
    }
}
