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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        service = new ExternalSchemaService(registry, mock(ExternalTableMaterializer.class), metadata, glue, iam);
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
    void missingGlueDatabaseFailsWithoutBinding() {
        when(glue.getDatabase("lake")).thenThrow(new AwsException("EntityNotFoundException", "missing", 400));

        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> service.execute(
                new CreateSchema("analytics", "lake", ROLE_ARN, false), session, backend()));

        assertThat(error.sqlState(), equalTo("XX000"));
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
}
