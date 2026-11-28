package io.floci.aws.services.redshift.proxy;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(ParallelLocks.REDSHIFT_COPY_LIMITS)
class RedshiftParquetWriterTest {
    @Test
    void mapsDeclaredNumericTypesAndRejectsLossyConversions() {
        assertEquals("DECIMAL(18,4)", RedshiftParquetWriter.type("amount", 1700, (18 << 16) + 4 + 4));
        assertEquals("BIGINT", RedshiftParquetWriter.type("amount", 20, -1));
        assertEquals("DATE", RedshiftParquetWriter.type("amount", 1082, -1));
        assertEquals("TIMESTAMP", RedshiftParquetWriter.type("amount", 1114, -1));
        assertThrows(S3CopySimulator.S3TransferException.class, () -> RedshiftParquetWriter.type("amount", 1700, -1));
        assertThrows(S3CopySimulator.S3TransferException.class, () -> RedshiftParquetWriter.type("amount", 17, -1));
    }

    @Test
    void unsupportedErrorsIdentifyTheColumnTypeAndSuggestedCast() {
        for (int oid : List.of(17, 114, 2950, 3802, 1007, 99999)) {
            S3CopySimulator.S3TransferException error = assertThrows(S3CopySimulator.S3TransferException.class,
                    () -> RedshiftParquetWriter.type("payload", oid, -1));
            assertEquals("0A000", error.sqlState());
            assertTrue(error.getMessage().contains("column \"payload\""));
            assertTrue(error.getMessage().contains("OID " + oid));
            assertTrue(error.getMessage().contains("Cast it explicitly"));
        }
        for (int modifier : List.of(-1, (40 << 16) + 2 + 4, (18 << 16) + 20 + 4,
                (18 << 16) + 0x7ff + 4)) {
            S3CopySimulator.S3TransferException error = assertThrows(S3CopySimulator.S3TransferException.class,
                    () -> RedshiftParquetWriter.type("total", 1700, modifier));
            assertEquals("0A000", error.sqlState());
            assertTrue(error.getMessage().contains("column \"total\""));
            assertTrue(error.getMessage().contains("NUMERIC"));
            assertTrue(error.getMessage().contains("DECIMAL(p,s)"));
        }
    }

    @Test
    void delegatesSizingToDuckDbAndCleansStagingOnSuccessAndFailure() throws Exception {
        for (boolean failure : List.of(false, true)) {
            S3Service s3 = mock(S3Service.class);
            FlociDuckClient duck = mock(FlociDuckClient.class);
            byte[] parquet = "PAR1payloadPAR1".getBytes(StandardCharsets.UTF_8);
            when(s3.openObjectStream(eq(RedshiftParquetWriter.STAGING_BUCKET), anyString(), isNull()))
                    .thenReturn(new ByteArrayInputStream(parquet), new ByteArrayInputStream(parquet));
            when(duck.query(anyString(), anyString(), eq("123456789012")))
                    .thenReturn(List.of(Map.of("filename", "s3://floci-internal-redshift-unload-staging/output/data_0.parquet"),
                            Map.of("filename", "s3://floci-internal-redshift-unload-staging/output/data_1.parquet")));
            if (failure) {
                when(duck.query(anyString(), anyString(), eq("123456789012")))
                        .thenThrow(new IllegalStateException("sidecar unavailable"));
            }
            List<RedshiftParquetWriter.Column> columns = List.of(
                    new RedshiftParquetWriter.Column("a\"b", "BIGINT"),
                    new RedshiftParquetWriter.Column("amount", "DECIMAL(18,4)"));
            byte[] csv = "1,123.4500\n".getBytes(StandardCharsets.UTF_8);
            RedshiftParquetWriter writer = writer(s3, duck);
            if (failure) {
                assertThrows(IllegalStateException.class, () -> writer.convert(csv, columns, "123456789012", null, 8192));
            } else {
                List<byte[]> files = writer.convert(csv, columns, "123456789012", null, 8192);
                assertEquals(2, files.size());
                assertArrayEquals(parquet, files.get(0));
                assertArrayEquals(parquet, files.get(1));
            }
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(duck).query(sql.capture(), contains("SET threads = 1"), eq("123456789012"));
            assertTrue(sql.getValue().contains("columns = {'c0': 'BIGINT', 'c1': 'DECIMAL(18,4)'}"));
            assertTrue(sql.getValue().contains("c0 AS \"a\"\"b\""));
            assertTrue(sql.getValue().contains("COMPRESSION SNAPPY"));
            assertTrue(sql.getValue().contains("FILE_SIZE_BYTES 8192, RETURN_STATS true"));
            verify(s3).deleteObjectsWithPrefix(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"), any());
        }
    }

    @Test
    void emptyResultRetainsSchemaWithoutInferringFromCsv() throws Exception {
        S3Service s3 = mock(S3Service.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        when(duck.query(anyString(), anyString(), anyString()))
                .thenReturn(List.of(Map.of("filename", "s3://floci-internal-redshift-unload-staging/output/data_0.parquet")));
        when(s3.openObjectStream(anyString(), anyString(), isNull()))
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        writer(s3, duck).convert(new byte[0], List.of(new RedshiftParquetWriter.Column("id", "INTEGER")),
                "123456789012", null, 8192);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(duck).query(sql.capture(), anyString(), eq("123456789012"));
        assertTrue(sql.getValue().contains("SELECT CAST(NULL AS INTEGER) AS \"id\" WHERE false"));
        assertFalse(sql.getValue().contains("read_csv"));
    }

    @Test
    void mintsDedicatedStagingSessionPerTransferInsteadOfReusingCallerKey() throws Exception {
        S3Service s3 = mock(S3Service.class);
        IamService iam = mock(IamService.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("123456789012");
        when(duck.query(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(Map.of("filename", "s3://floci-internal-redshift-unload-staging/output/data_0.parquet")));
        when(s3.openObjectStream(anyString(), anyString(), isNull()))
                .thenReturn(new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0]));
        RedshiftRoleAccess.RoleSession callerKey = new RedshiftRoleAccess.RoleSession("AKIAUSERKEY", "secret", null);
        RedshiftParquetWriter writer = new RedshiftParquetWriter(duck, s3, iam, config);

        writer.convert(new byte[0], List.of(new RedshiftParquetWriter.Column("id", "INTEGER")),
                "123456789012", callerKey, 8192);
        writer.convert(new byte[0], List.of(new RedshiftParquetWriter.Column("id", "INTEGER")),
                "123456789012", callerKey, 8192);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(duck, times(2)).query(anyString(), anyString(), keys.capture(), anyString(), anyString());
        String firstKey = keys.getAllValues().get(0);
        String secondKey = keys.getAllValues().get(1);
        assertTrue(firstKey.startsWith("ASIA"));
        assertTrue(secondKey.startsWith("ASIA"));
        assertNotEquals("AKIAUSERKEY", firstKey);
        assertNotEquals(firstKey, secondKey);
        verify(iam).registerSessionForAccount(eq("123456789012"), eq(firstKey), anyString(), anyString(),
                eq("arn:aws:iam::123456789012:role/floci-internal-redshift-staging"), any(), isNull());
        verify(iam).registerSessionForAccount(eq("123456789012"), eq(secondKey), anyString(), anyString(),
                eq("arn:aws:iam::123456789012:role/floci-internal-redshift-staging"), any(), isNull());
        verify(iam).clearScratchResource(firstKey);
        verify(iam).clearScratchResource(secondKey);
        verify(iam).unregisterSession("123456789012", firstKey);
        verify(iam).unregisterSession("123456789012", secondKey);
    }

    @Test
    void registersAndRevokesStagingScopeEvenWhenConversionFails() throws Exception {
        S3Service s3 = mock(S3Service.class);
        IamService iam = mock(IamService.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("123456789012");
        when(duck.query(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("sidecar failed"));
        RedshiftParquetWriter writer = new RedshiftParquetWriter(duck, s3, iam, config);
        assertThrows(IllegalStateException.class, () -> writer.convert(new byte[0],
                List.of(new RedshiftParquetWriter.Column("id", "INTEGER")), "123456789012",
                new RedshiftRoleAccess.RoleSession("access", "secret", "token"), 8192));
        ArgumentCaptor<String> stagingKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scopes = ArgumentCaptor.forClass(String.class);
        verify(iam).registerScratchResource(stagingKey.capture(), scopes.capture());
        verify(iam).clearScratchResource(stagingKey.getValue());
        verify(iam).unregisterSession("123456789012", stagingKey.getValue());
        assertNotEquals("access", stagingKey.getValue());
        assertTrue(scopes.getValue().matches(
                "arn:aws:s3:::floci-internal-redshift-unload-staging/unload/[0-9a-f-]{36}/"));
        verify(s3).deleteObjectsWithPrefix(eq(RedshiftParquetWriter.STAGING_BUCKET), anyString(), any());
    }

    @Test
    void refusesToUseAnExistingUntaggedStagingBucket() {
        S3Service s3 = mock(S3Service.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        when(s3.createInternalBucket(anyString(), anyString())).thenThrow(
                new AwsException("BucketAlreadyOwnedByYou", "exists", 409));
        assertThrows(IOException.class, () -> writer(s3, duck).convert(new byte[0],
                List.of(new RedshiftParquetWriter.Column("id", "INTEGER")), "123456789012", null, 8192));
        verifyNoInteractions(duck);
        verify(s3, never()).putBucketTagging(anyString(), any());
        verify(s3, never()).putObject(anyString(), anyString(), any(byte[].class), anyString(), any());
    }

    @Test
    void readToCsvStagesParquetDisablesHivePartitioningAndReservesHeapUntilClosed() throws Exception {
        S3Service s3 = mock(S3Service.class);
        IamService iam = mock(IamService.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("123456789012");
        byte[] parquet = "PAR1payloadPAR1".getBytes(StandardCharsets.UTF_8);
        byte[] csv = "1,alice\n2,bob\n".getBytes(StandardCharsets.UTF_8);
        when(s3.objectExists(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"))).thenReturn(true);
        when(s3.openObjectStream(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"), isNull()))
                .thenReturn(new ByteArrayInputStream(csv));
        RedshiftRoleAccess.RoleSession session = new RedshiftRoleAccess.RoleSession("AKIAUSER", "secret", null);
        RedshiftParquetWriter writer = new RedshiftParquetWriter(duck, s3, iam, config);
        int permitsBefore = S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits();

        try (RedshiftParquetWriter.StagedCsv result = writer.readToCsv(parquet, "123456789012", session)) {
            assertArrayEquals(csv, result.bytes());
            assertTrue(S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits() < permitsBefore);
        }
        assertEquals(permitsBefore, S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stagingKey = ArgumentCaptor.forClass(String.class);
        verify(duck).query(sql.capture(), contains("SET threads = 1"), stagingKey.capture(), anyString(), anyString());
        assertNotEquals("AKIAUSER", stagingKey.getValue());
        assertTrue(sql.getValue().contains("read_parquet('s3://floci-internal-redshift-unload-staging/unload/"));
        assertTrue(sql.getValue().contains("/input.parquet', hive_partitioning = false)"));
        assertTrue(sql.getValue().contains("FORMAT CSV, HEADER false"));
        verify(s3).putObject(eq(RedshiftParquetWriter.STAGING_BUCKET), endsWith("/input.parquet"),
                eq(parquet), eq("application/octet-stream"), isNull());
        verify(s3).registerScratchWriteLimit(eq(RedshiftParquetWriter.STAGING_BUCKET), endsWith("/output.csv"),
                eq(S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES));
        verify(s3).clearScratchWriteLimit(eq(RedshiftParquetWriter.STAGING_BUCKET), endsWith("/output.csv"));
        verify(iam).registerScratchResource(eq(stagingKey.getValue()),
                startsWith("arn:aws:s3:::floci-internal-redshift-unload-staging/unload/"));
        verify(iam).clearScratchResource(stagingKey.getValue());
        verify(iam).unregisterSession("123456789012", stagingKey.getValue());
        verify(s3).deleteObjectsWithPrefix(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"), any());
    }

    @Test
    void readToCsvRejectsOversizedSourceAndStagedOutputExceedingLimit() throws Exception {
        long previous = S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES;
        int permitsBefore = S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits();
        S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES = 8;
        try {
            S3Service s3 = mock(S3Service.class);
            FlociDuckClient duck = mock(FlociDuckClient.class);

            S3CopySimulator.S3TransferException oversizedInput = assertThrows(S3CopySimulator.S3TransferException.class,
                    () -> writer(s3, duck).readToCsv("0123456789".getBytes(StandardCharsets.UTF_8), "123456789012", null));
            assertEquals("54000", oversizedInput.sqlState());
            verifyNoInteractions(duck);

            when(duck.query(anyString(), anyString(), eq("123456789012")))
                    .thenThrow(new RuntimeException("HTTP 400 EntityTooLarge: upload exceeds limit"));
            S3CopySimulator.S3TransferException cappedWrite = assertThrows(S3CopySimulator.S3TransferException.class,
                    () -> writer(s3, duck).readToCsv("PAR1".getBytes(StandardCharsets.UTF_8), "123456789012", null));
            assertEquals("54000", cappedWrite.sqlState());

            reset(duck);
            when(s3.objectExists(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"))).thenReturn(true);
            when(s3.openObjectStream(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"), isNull()))
                    .thenReturn(new ByteArrayInputStream("1,exceeds-eight-bytes\n".getBytes(StandardCharsets.UTF_8)));

            S3CopySimulator.S3TransferException error = assertThrows(S3CopySimulator.S3TransferException.class,
                    () -> writer(s3, duck).readToCsv("PAR1".getBytes(StandardCharsets.UTF_8), "123456789012", null));
            assertEquals("54000", error.sqlState());
            assertEquals(permitsBefore, S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits());
            verify(s3, atLeastOnce()).deleteObjectsWithPrefix(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"), any());
        } finally {
            S3CopySimulator.COPY_TRANSFORM_MAX_OBJECT_BYTES = previous;
        }
    }

    @Test
    void readToCsvFailsWhenSharedHeapBudgetIsExhausted() throws Exception {
        int availableBefore = S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits();
        S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.acquireUninterruptibly(availableBefore);
        try {
            S3Service s3 = mock(S3Service.class);
            FlociDuckClient duck = mock(FlociDuckClient.class);
            when(s3.objectExists(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"))).thenReturn(true);
            when(s3.openObjectStream(eq(RedshiftParquetWriter.STAGING_BUCKET), startsWith("unload/"), isNull()))
                    .thenReturn(new ByteArrayInputStream("1,alice\n".getBytes(StandardCharsets.UTF_8)));

            S3CopySimulator.S3TransferException error = assertThrows(S3CopySimulator.S3TransferException.class,
                    () -> writer(s3, duck).readToCsv("PAR1".getBytes(StandardCharsets.UTF_8), "123456789012", null));
            assertEquals("53400", error.sqlState());
        } finally {
            S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.release(availableBefore);
        }
        assertEquals(availableBefore, S3CopySimulator.COPY_TRANSFORM_HEAP_MIB.availablePermits());
    }

    private RedshiftParquetWriter writer(S3Service s3, FlociDuckClient duck) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRegion()).thenReturn("us-east-1");
        return new RedshiftParquetWriter(duck, s3, mock(IamService.class), config);
    }
}
