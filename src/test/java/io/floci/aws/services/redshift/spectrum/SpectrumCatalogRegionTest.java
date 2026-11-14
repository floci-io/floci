package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.glue.GlueService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redshift Spectrum reads the Glue catalog of its cluster's Region, as on AWS, whatever Region the call runs
 * in: over a JDBC connection that is no request's. Runs under Quarkus, where a scoped call sets the Region.
 */
@QuarkusTest
class SpectrumCatalogRegionTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/spectrum";
    private static final SpectrumSession SESSION = new SpectrumSession("000000000000", "000000000000:analytics",
            "dev", List.of(ROLE), false);

    @Inject
    RegionResolver regionResolver;

    @Test
    void anExternalSchemaReadsTheCatalogOfTheClustersRegion() {
        assertEquals("eu-west-1", regionOfTheCatalogRead(
                (accountId, cluster) -> "analytics".equals(cluster) ? "eu-west-1" : null));
    }

    @Test
    void aClusterFlociDoesNotKnowKeepsTheRegionTheCallRunsIn() {
        assertEquals(regionResolver.getDefaultRegion(), regionOfTheCatalogRead((accountId, cluster) -> null));
    }

    /** The Region current when Spectrum looked the external schema's Glue database up. */
    private String regionOfTheCatalogRead(BiFunction<String, String, String> clusterRegion) {
        GlueService glue = mock(GlueService.class);
        AtomicReference<String> region = new AtomicReference<>();
        when(glue.getDatabase("sales")).thenAnswer(invocation -> {
            region.set(regionResolver.getRegion());
            throw new AwsException("EntityNotFoundException", "Database not found: sales", 400);
        });
        SpectrumQueryPreparation preparation = new SpectrumQueryPreparation(mock(ExternalCatalogRegistry.class),
                mock(ExternalTableMaterializer.class), new ExternalStatementParser(), glue,
                mock(ExternalMetadataWriter.class), new ExternalCatalogLocks(), clusterRegion);

        preparation.prepare("CREATE EXTERNAL SCHEMA lake FROM DATA CATALOG DATABASE 'sales' IAM_ROLE '" + ROLE + "'",
                SESSION, mock(BackendSql.class));

        return region.get();
    }
}
