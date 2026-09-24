package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpectrumLegacyCatalogRoutingTest {
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = ACCOUNT + ":cluster";
    private SpectrumCatalog catalog;
    private ExternalCatalogRegistry registry;
    private SpectrumCatalogResolver resolver;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("redshift"), eq("redshift-spectrum-schemas.json"), any(TypeReference.class)))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        when(factory.create(eq("redshift"), eq("redshift-spectrum-tables.json"), any(TypeReference.class)))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        catalog = new SpectrumCatalog(factory);
        registry = mock(ExternalCatalogRegistry.class);
        resolver = new SpectrumCatalogResolver(registry, catalog);
    }

    @Test
    void findsLegacySchemaByAccountAndNameWhenItsKeyUsesGlueDatabase() {
        SpectrumExternalSchema oldSchema = new SpectrumExternalSchema(
                ACCOUNT, "glue_lake", "analytics", "s3://warehouse/events/", null);
        SpectrumExternalTable oldTable = new SpectrumExternalTable(ACCOUNT, "dev", "analytics", "events",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER)), "s3://warehouse/events/",
                ',', '"', '\\', "\\N", 1);
        catalog.createSchema(oldSchema);
        catalog.createTable(oldTable);

        SpectrumCatalogResolver.Resolution result = resolver.resolve(ACCOUNT, CLUSTER, "dev", "analytics").orElseThrow();

        assertEquals(SpectrumCatalogResolver.Resolution.Kind.PHASE_ONE, result.kind());
        assertEquals(Optional.of(oldSchema), catalog.findLegacySchema(ACCOUNT, "analytics"));
        assertTrue(catalog.table(ACCOUNT, "dev", "analytics", "events").isPresent());
    }

    @Test
    void currentGlueBindingWinsOverSameNamedLegacySchema() {
        SpectrumExternalSchema oldSchema = new SpectrumExternalSchema(
                ACCOUNT, "glue_lake", "analytics", "s3://warehouse/events/", null);
        catalog.createSchema(oldSchema);
        ExternalSchemaBinding glueBinding = new ExternalSchemaBinding(
                ACCOUNT, CLUSTER, "dev", "analytics", "current_glue", "arn:aws:iam::000000000000:role/Spectrum");
        when(registry.find(ACCOUNT, CLUSTER, "dev", "analytics")).thenReturn(Optional.of(glueBinding));

        SpectrumCatalogResolver.Resolution result = resolver.resolve(ACCOUNT, CLUSTER, "dev", "analytics").orElseThrow();

        assertEquals(SpectrumCatalogResolver.Resolution.Kind.GLUE, result.kind());
        assertEquals(glueBinding, ((SpectrumCatalogResolver.Resolution.Glue) result).binding());
    }

    @Test
    void legacySchemaNamesRemainAccountScoped() {
        catalog.createSchema(new SpectrumExternalSchema(ACCOUNT, "glue_lake", "legacy", "s3://warehouse/legacy/", null));

        assertEquals(List.of("legacy"), catalog.legacySchemaNames(ACCOUNT));
        assertTrue(catalog.legacySchemaNames("111111111111").isEmpty());
    }

    @Test
    void supportedLegacySelectUsesPersistedPhaseOneTableDescriptor() {
        SpectrumExternalSchema oldSchema = new SpectrumExternalSchema(
                ACCOUNT, "glue_lake", "analytics", "s3://warehouse/events/", null);
        SpectrumExternalTable oldTable = new SpectrumExternalTable(ACCOUNT, "dev", "analytics", "events",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER)), "s3://warehouse/events/",
                ',', '"', '\\', "\\N", 1);
        catalog.createSchema(oldSchema);
        catalog.createTable(oldTable);
        SpectrumSession session = new SpectrumSession(ACCOUNT, CLUSTER, "dev", List.of(), false);
        ExternalSchemaService service = new ExternalSchemaService(registry,
                new SpectrumCatalogResolver(registry, catalog), mock(ExternalTableMaterializer.class),
                mock(ExternalMetadataWriter.class), mock(GlueService.class), mock(IamService.class));
        SpectrumMaterializer materializer = mock(SpectrumMaterializer.class);
        when(materializer.nextIdentifier()).thenReturn("legacy_tmp");
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().redshift().spectrumEnabled()).thenReturn(true);
        SpectrumInterceptor interceptor = new SpectrumInterceptor(new SpectrumStatementParser(),
                new ExternalStatementParser(), new SpectrumQueryClassifier(), new SpectrumGlueCsvAdapter(),
                materializer, mock(SpectrumS3Reader.class), service, config);

        SpectrumInterceptor.Plan result = interceptor.plan("SELECT * FROM analytics.events", session);

        assertTrue(result instanceof SpectrumInterceptor.Plan.PhaseOneQuery);
        SpectrumInterceptor.Plan.PhaseOneQuery query = (SpectrumInterceptor.Plan.PhaseOneQuery) result;
        assertEquals(oldSchema, query.table().schema());
        assertEquals(oldTable, query.table().table());
    }

    @Test
    void removingClusterBindingsLeavesOtherClustersAndAccountsUntouched() {
        StorageFactory factory = mock(StorageFactory.class);
        AccountAwareStorageBackend<ExternalSchemaBinding> bindings = AccountAwareStorageBackend.inMemory(ACCOUNT);
        when(factory.create(eq("redshift"), eq("redshift-external-schemas.json"), any(TypeReference.class)))
                .thenReturn(bindings);
        ExternalCatalogRegistry persistedRegistry = new ExternalCatalogRegistry(factory);
        ExternalSchemaBinding removed = binding(ACCOUNT, CLUSTER, "removed");
        ExternalSchemaBinding otherCluster = binding(ACCOUNT, ACCOUNT + ":other", "other_cluster");
        ExternalSchemaBinding otherAccount = binding("111111111111", CLUSTER, "other_account");
        persistedRegistry.bind(removed);
        persistedRegistry.bind(otherCluster);
        persistedRegistry.bind(otherAccount);

        persistedRegistry.removeCluster(ACCOUNT, CLUSTER);

        assertTrue(persistedRegistry.find(ACCOUNT, CLUSTER, "dev", "removed").isEmpty());
        assertEquals(Optional.of(otherCluster), persistedRegistry.find(ACCOUNT, otherCluster.clusterKey(), "dev", "other_cluster"));
        assertEquals(Optional.of(otherAccount), persistedRegistry.find("111111111111", CLUSTER, "dev", "other_account"));
    }

    private static ExternalSchemaBinding binding(String account, String cluster, String schema) {
        return new ExternalSchemaBinding(account, cluster, "dev", schema, "glue_db",
                "arn:aws:iam::" + account + ":role/Spectrum");
    }
}
