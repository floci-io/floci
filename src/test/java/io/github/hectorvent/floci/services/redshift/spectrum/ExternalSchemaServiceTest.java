package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.ColumnDefinition;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.CreateSchema;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.CreateTable;
import io.github.hectorvent.floci.services.redshift.spectrum.ExternalStatement.TableFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalSchemaServiceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = ACCOUNT + ":cluster";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/SpectrumRole";
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    private final List<String> statements = new ArrayList<>();
    private ExternalCatalogRegistry registry;
    private ExternalMetadataWriter metadata;
    private GlueService glue;
    private ExternalTableMaterializer tableMaterializer;
    private SpectrumCatalog legacyCatalog;
    private ExternalSchemaService service;
    private SpectrumSession session;

    @BeforeEach
    void setUp() {
        registry = mock(ExternalCatalogRegistry.class);
        metadata = mock(ExternalMetadataWriter.class);
        glue = mock(GlueService.class);
        IamService iam = mock(IamService.class);
        IamRole role = mock(IamRole.class);
        when(role.getAssumeRolePolicyDocument()).thenReturn(TRUST_POLICY);
        when(iam.findRole(ACCOUNT, "SpectrumRole")).thenReturn(Optional.of(role));
        tableMaterializer = mock(ExternalTableMaterializer.class);
        legacyCatalog = mock(SpectrumCatalog.class);
        service = new ExternalSchemaService(registry, new SpectrumCatalogResolver(registry, legacyCatalog),
                tableMaterializer, metadata, glue, iam);
        session = new SpectrumSession(ACCOUNT, CLUSTER, "dev", List.of(ROLE_ARN), false);
        statements.clear();
    }

    @Test
    void createSchemaValidatesRoleAndGlueDatabaseThenBinds() {
        when(glue.getDatabase("lake")).thenReturn(new Database("lake"));

        Optional<String> result = service.execute(new CreateSchema("analytics", "lake", ROLE_ARN, false),
                session, backend());

        assertThat(result, equalTo(Optional.of("CREATE SCHEMA")));
        assertThat(statements, contains("CREATE SCHEMA \"analytics\""));
        verify(registry).bind(new ExternalSchemaBinding(ACCOUNT, CLUSTER, "dev", "analytics", "lake", ROLE_ARN));
        verify(metadata).refresh(any(BackendSql.class), eq(ACCOUNT), any(ExternalSchemaBinding.class));
    }

    @Test
    void catalogViewDetectionIgnoresCommentsAndStringLiterals() {
        assertFalse(service.touchesCatalogViews("SELECT 'svv_external_tables'"));
        assertFalse(service.touchesCatalogViews("SELECT 1 /* svv_external_tables */"));
        assertTrue(service.touchesCatalogViews("SELECT * FROM svv_external_tables"));
    }

    @Test
    void missingGlueDatabaseFailsWithoutBinding() {
        when(glue.getDatabase("lake")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> service.execute(
                new CreateSchema("analytics", "lake", ROLE_ARN, false), session, backend()));

        assertThat(error.sqlState(), equalTo("3D000"));
        assertThat(error.getMessage(), containsString("Glue database \"lake\" not found"));
        verify(registry, never()).bind(any(ExternalSchemaBinding.class));
    }

    @Test
    void createTableWritesGlueMetadataForBoundSchema() {
        ExternalSchemaBinding binding = new ExternalSchemaBinding(ACCOUNT, CLUSTER, "dev", "analytics", "lake", ROLE_ARN);
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(binding));
        CreateTable create = new CreateTable("analytics", "events", List.of(new ColumnDefinition("id", "int")),
                List.of(), TableFormat.PARQUET, "s3://bucket/events/", null, null, Map.of());

        Optional<String> result = service.execute(create, session, backend());

        assertThat(result, equalTo(Optional.of("CREATE TABLE")));
        verify(glue).createTable(eq("lake"), any(Table.class));
        verify(metadata).refresh(any(BackendSql.class), eq(ACCOUNT), eq(binding));
    }

    @Test
    void missingGlueTableFailsClosedInsteadOfForwardingToNativeTable() {
        ExternalSchemaBinding binding = new ExternalSchemaBinding(ACCOUNT, CLUSTER, "dev", "analytics", "lake", ROLE_ARN);
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(binding));
        when(glue.getTable("lake", "events")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> service.resolveGlueTable(
                new ExternalReferenceScanner.Reference("analytics", "events"), session));

        assertThat(error.sqlState(), equalTo("42P01"));
    }

    @Test
    void materializerNotExternalOutcomeFailsClosedInsteadOfForwardingToNativeTable() {
        ExternalSchemaBinding binding = new ExternalSchemaBinding(ACCOUNT, CLUSTER, "dev", "analytics", "lake", ROLE_ARN);
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(binding));
        when(tableMaterializer.ensureCurrent(any(), eq(session), eq(binding), eq("events")))
                .thenReturn(ExternalTableMaterializer.Outcome.NOT_EXTERNAL);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> service.loadReferences(
                List.of(new ExternalReferenceScanner.Reference("analytics", "events")), session, backend()));

        assertThat(error.sqlState(), equalTo("42P01"));
    }

    @Test
    void deletingClusterRemovesOnlyItsGlueBindingsAndMaterializerFingerprints() {
        service.forgetCluster(ACCOUNT, CLUSTER);

        verify(registry).removeCluster(ACCOUNT, CLUSTER);
        verify(tableMaterializer).forgetCluster(CLUSTER);
    }

    private BackendSql backend() {
        return new BackendSql() {
            @Override
            public void execute(String sql) {
                statements.add(sql);
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };
    }

    private static ExternalSchemaBinding analyticsBinding() {
        return new ExternalSchemaBinding(ACCOUNT, CLUSTER, "dev", "analytics", "lake", ROLE_ARN);
    }

    @Test
    void dropSchemaWithoutCascadeIsRestrictedAndDropsOnlyTablesFlociManages() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));
        Table events = new Table();
        events.setName("events");
        when(glue.getTables("lake")).thenReturn(List.of(events));
        when(tableMaterializer.loadedTables(CLUSTER, "dev", "analytics")).thenReturn(Set.of("stale"));

        Optional<String> result = service.execute(new ExternalStatement.DropSchema("analytics", false, false),
                session, backend());

        assertThat(result, equalTo(Optional.of("DROP SCHEMA")));
        assertThat(statements, hasSize(1));
        assertThat(statements.get(0), containsString("DROP TABLE IF EXISTS \"analytics\".\"events\";"));
        assertThat(statements.get(0), containsString("DROP TABLE IF EXISTS \"analytics\".\"stale\";"));
        assertThat(statements.get(0), endsWith("DROP SCHEMA IF EXISTS \"analytics\""));
        assertThat(statements.get(0), not(containsString("CASCADE")));
        verify(registry).unbind(ACCOUNT, CLUSTER, "dev", "analytics");
        verify(tableMaterializer).forgetSchema(CLUSTER, "dev", "analytics");
    }

    @Test
    void dropSchemaCascadeIsExecutedOnlyWhenTheStatementAskedForIt() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));

        service.execute(new ExternalStatement.DropSchema("analytics", false, true), session, backend());

        assertThat(statements, contains("DROP SCHEMA IF EXISTS \"analytics\" CASCADE"));
        verify(tableMaterializer).forgetSchema(CLUSTER, "dev", "analytics");
    }

    @Test
    void refusedRestrictedDropKeepsTheBindingAndTheFingerprints() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));
        BackendSql refusing = new BackendSql() {
            @Override
            public void execute(String sql) {
                throw new SpectrumReadException("2BP01", "cannot drop table because other objects depend on it");
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };

        SpectrumReadException error = assertThrows(SpectrumReadException.class, () -> service.execute(
                new ExternalStatement.DropSchema("analytics", false, false), session, refusing));

        assertThat(error.sqlState(), equalTo("2BP01"));
        verify(registry, never()).unbind(any(), any(), any(), any());
        verify(tableMaterializer, never()).forgetSchema(any(), any(), any());
    }

    @Test
    void dropTableCascadeIsExecutedOnlyWhenAskedFor() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));

        service.execute(new ExternalStatement.DropTable("analytics", "events", false, false), session, backend());
        service.execute(new ExternalStatement.DropTable("analytics", "events", false, true), session, backend());

        assertThat(dropStatements(), contains("DROP TABLE IF EXISTS \"analytics\".\"events\"",
                "DROP TABLE IF EXISTS \"analytics\".\"events\" CASCADE"));
    }

    @Test
    void refusedDropTableLeavesTheGlueTableInPlace() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));
        BackendSql refusing = new BackendSql() {
            @Override
            public void execute(String sql) {
                throw new SpectrumReadException("2BP01", "cannot drop table because other objects depend on it");
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };

        assertThrows(SpectrumReadException.class, () -> service.execute(
                new ExternalStatement.DropTable("analytics", "events", false, false), session, refusing));

        verify(glue, never()).deleteTable(any(), any());
        verify(tableMaterializer, never()).forget(any(), any(), any(), any());
    }

    @Test
    void dropTableDeletesTheGlueTableAfterPostgresAcceptsTheDrop() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));

        service.execute(new ExternalStatement.DropTable("analytics", "events", false, false), session, backend());

        assertThat(dropStatements(), contains("DROP TABLE IF EXISTS \"analytics\".\"events\""));
        verify(glue).deleteTable("lake", "events");
    }

    @Test
    void dropTableChecksTheSchemaPrivilegeBeforeTouchingGlue() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));
        BackendSql denying = new BackendSql() {
            @Override
            public void execute(String sql) {
                throw new SpectrumReadException("42501", "permission denied for schema analytics");
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };

        assertThrows(SpectrumReadException.class, () -> service.execute(
                new ExternalStatement.DropTable("analytics", "events", false, false), session, denying));

        verify(glue, never()).deleteTable(any(), any());
    }

    @Test
    void createSchemaAcceptsAGlueDatabaseCreatedByAnotherSessionInTheMeantime() {
        when(glue.getDatabase("lake")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));
        doThrow(new AwsException("AlreadyExistsException", "exists", 400)).when(glue).createDatabase(any(Database.class));

        Optional<String> result = service.execute(new CreateSchema("analytics", "lake", ROLE_ARN, true), session, backend());

        assertThat(result, equalTo(Optional.of("CREATE SCHEMA")));
        verify(registry).bind(any(ExternalSchemaBinding.class));
    }

    private List<String> dropStatements() {
        return statements.stream().filter(sql -> sql.startsWith("DROP ")).toList();
    }

    @Test
    void createSchemaDoesNotCreateAGlueDatabaseWhenPostgresRefusesTheSchema() {
        when(glue.getDatabase("lake")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));
        BackendSql refusing = new BackendSql() {
            @Override
            public void execute(String sql) {
                throw new SpectrumReadException("42P06", "schema already exists");
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };

        assertThrows(SpectrumReadException.class, () -> service.execute(
                new CreateSchema("analytics", "lake", ROLE_ARN, true), session, refusing));

        verify(glue, never()).createDatabase(any(Database.class));
        verify(registry, never()).bind(any(ExternalSchemaBinding.class));
    }

    @Test
    void createSchemaRollsTheSchemaBackWhenGlueDatabaseCreationFails() {
        when(glue.getDatabase("lake")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));
        doThrow(new AwsException("InternalServiceException", "boom", 500)).when(glue).createDatabase(any(Database.class));

        assertThrows(AwsException.class, () -> service.execute(
                new CreateSchema("analytics", "lake", ROLE_ARN, true), session, backend()));

        assertThat(statements, contains("CREATE SCHEMA \"analytics\"", "DROP SCHEMA IF EXISTS \"analytics\""));
        verify(registry, never()).bind(any(ExternalSchemaBinding.class));
    }

    @Test
    void externalDdlIsRefusedInsideATransactionBlock() {
        SpectrumSession inTransaction = new SpectrumSession(ACCOUNT, CLUSTER, "dev", List.of(ROLE_ARN), true);

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> service.execute(
                new CreateSchema("analytics", "lake", ROLE_ARN, false), inTransaction, backend()));

        assertThat(error.sqlState(), equalTo("25001"));
        assertThat(statements, hasSize(0));
        verify(registry, never()).bind(any(ExternalSchemaBinding.class));
    }

    @Test
    void createTableChecksTheSchemaPrivilegeBeforeWritingGlue() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));
        BackendSql denying = new BackendSql() {
            @Override
            public void execute(String sql) {
                throw new SpectrumReadException("42501", "permission denied for schema analytics");
            }

            @Override
            public long copyIn(String copySql, InputStream data) {
                return 0;
            }
        };
        CreateTable create = new CreateTable("analytics", "events", List.of(new ColumnDefinition("id", "int")),
                List.of(), TableFormat.PARQUET, "s3://bucket/events/", null, null, Map.of());

        SpectrumReadException error = assertThrows(SpectrumReadException.class,
                () -> service.execute(create, session, denying));

        assertThat(error.sqlState(), equalTo("42501"));
        verify(glue, never()).createTable(any(), any());
    }

    @Test
    void createTableOnALegacySchemaExplainsWhyItIsRefused() {
        when(registry.find(ACCOUNT, CLUSTER, "dev", "old_schema")).thenReturn(Optional.empty());
        when(legacyCatalog.legacySchemaNames(ACCOUNT, "dev")).thenReturn(List.of("old_schema"));
        CreateTable create = new CreateTable("old_schema", "events", List.of(new ColumnDefinition("id", "int")),
                List.of(), TableFormat.PARQUET, "s3://bucket/events/", null, null, Map.of());

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> service.execute(create, session, backend()));

        assertThat(error.sqlState(), equalTo("0A000"));
        assertThat(error.getMessage(), containsString("legacy"));
    }

    @Test
    void metadataRefreshSkipsASchemaWhoseGlueDatabaseIsGone() {
        ExternalSchemaBinding broken = new ExternalSchemaBinding(ACCOUNT, CLUSTER, "dev", "broken", "gone", ROLE_ARN);
        ExternalSchemaBinding healthy = analyticsBinding();
        when(registry.list(ACCOUNT, CLUSTER, "dev")).thenReturn(List.of(broken, healthy));
        BackendSql backend = backend();
        doThrow(new AwsException("EntityNotFoundException", "gone", 400)).when(metadata)
                .refresh(backend, ACCOUNT, broken);

        service.refreshMetadata(session, backend);

        verify(metadata).refresh(backend, ACCOUNT, healthy);
    }

    @Test
    void onlyBoundSchemasOwnDropAndAlterStatements() {
        assertFalse(service.handles(new ExternalStatement.DropTable("public", "t", false, false), session));
        assertFalse(service.handles(new ExternalStatement.DropSchema("local_schema", false, false), session));
        assertTrue(service.handles(new CreateSchema("analytics", "lake", ROLE_ARN, false), session));

        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(analyticsBinding()));

        assertTrue(service.handles(new ExternalStatement.DropTable("analytics", "events", false, false), session));
        assertTrue(service.handles(new ExternalStatement.DropSchema("analytics", false, false), session));
    }

    @Test
    void newSchemaForgetsFingerprintsAnEarlierSchemaOfTheSameNameLeftBehind() {
        when(glue.getDatabase("lake")).thenReturn(new Database("lake"));

        service.execute(new CreateSchema("analytics", "lake", ROLE_ARN, false), session, backend());

        verify(tableMaterializer).forgetSchema(CLUSTER, "dev", "analytics");
    }
}
