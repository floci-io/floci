package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AthenaHiveDdlExecutorTest {

    @Test
    void dropTableIfExistsStaysANoOpWhenAnotherDropWinsTheRace() {
        GlueService glue = Mockito.mock(GlueService.class);
        Table table = new Table();
        table.setName("events");
        when(glue.getTable("lake", "events")).thenReturn(table);
        doThrow(new AwsException("EntityNotFoundException", "Table not found", 400)).when(glue).deleteTable("lake", "events");
        AthenaHiveDdlExecutor executor = new AthenaHiveDdlExecutor(glue, Mockito.mock(S3Service.class),
                Mockito.mock(AthenaIcebergStatements.class));

        assertDoesNotThrow(() -> executor.execute(AthenaHiveDdl.parse("DROP TABLE IF EXISTS events"), "lake"));
        AwsException e = assertThrows(AwsException.class,
                () -> executor.execute(AthenaHiveDdl.parse("DROP TABLE events"), "lake"));
        assertEquals("EntityNotFoundException", e.getErrorCode());
    }

    /**
     * In an S3 Tables context catalog, DROP TABLE deletes the bucket's table and OPTIMIZE is refused as VACUUM is; in
     * the default catalog, OPTIMIZE is not supported yet.
     */
    @Test
    void optimizeIsRefusedInAnS3TablesCatalogAndNotSupportedYetInTheDefaultOne() {
        AthenaIcebergStatements iceberg = Mockito.mock(AthenaIcebergStatements.class);
        AthenaHiveDdlExecutor executor = new AthenaHiveDdlExecutor(Mockito.mock(GlueService.class),
                Mockito.mock(S3Service.class), iceberg);

        executor.execute(AthenaHiveDdl.parse("DROP TABLE orders"), "s3tablescatalog/lake", "sales");
        verify(iceberg).dropS3Table("lake", "sales", "orders", false);
        AwsException optimize = assertThrows(AwsException.class, () -> executor.execute(
                AthenaHiveDdl.parse("OPTIMIZE orders REWRITE DATA USING BIN_PACK"), "s3tablescatalog/lake", "sales"));
        assertTrue(optimize.getMessage().contains("not supported on S3 Tables"), optimize.getMessage());

        AwsException unsupported = assertThrows(AwsException.class, () -> executor.execute(
                AthenaHiveDdl.parse("OPTIMIZE lake.events REWRITE DATA USING BIN_PACK"), "default"));
        assertEquals("OPTIMIZE is not supported yet", unsupported.getMessage());
    }

    @Test
    void aFolderWithAnInvalidEscapeIsNotAPartitionFolder() {
        Column region = new Column();
        region.setName("region");

        assertNull(AthenaHiveDdlExecutor.hivePartitionValues("region=bad%ZZ/part-0", List.of(region)));
        assertEquals(List.of("sa/west"), AthenaHiveDdlExecutor.hivePartitionValues("region=sa%2fwest/part-0", List.of(region)));
    }

    @Test
    void hiveEscapesTheCharactersItReservesInFolderNames() {
        assertEquals("sa%2Fwest%3Dx%25", AthenaHiveDdlExecutor.escapePathName("sa/west=x%"));
        assertEquals("2026-11-14 10.00", AthenaHiveDdlExecutor.escapePathName("2026-11-14 10.00"));
    }
}
