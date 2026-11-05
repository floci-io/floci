package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    void forwardsRoleSessionCredentialsToTheSidecar() throws Exception {
        S3Service s3 = mock(S3Service.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        when(duck.query(anyString(), anyString(), eq("access"), eq("secret"), eq("token")))
                .thenReturn(List.of(Map.of("filename", "s3://floci-internal-redshift-unload-staging/output/data_0.parquet")));
        when(s3.openObjectStream(anyString(), anyString(), isNull()))
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        RedshiftRoleAccess.RoleSession session = new RedshiftRoleAccess.RoleSession("access", "secret", "token");
        writer(s3, duck).convert(new byte[0], List.of(new RedshiftParquetWriter.Column("id", "INTEGER")),
                "123456789012", session, 8192);
        verify(duck).query(anyString(), anyString(), eq("access"), eq("secret"), eq("token"));
    }

    @Test
    void registersAndRevokesStagingScopeEvenWhenConversionFails() throws Exception {
        S3Service s3 = mock(S3Service.class);
        IamService iam = mock(IamService.class);
        FlociDuckClient duck = mock(FlociDuckClient.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(duck.query(anyString(), anyString(), eq("access"), eq("secret"), eq("token")))
                .thenThrow(new IllegalStateException("sidecar failed"));
        RedshiftParquetWriter writer = new RedshiftParquetWriter(duck, s3, iam, config);
        assertThrows(IllegalStateException.class, () -> writer.convert(new byte[0],
                List.of(new RedshiftParquetWriter.Column("id", "INTEGER")), "123456789012",
                new RedshiftRoleAccess.RoleSession("access", "secret", "token"), 8192));
        ArgumentCaptor<String> scopes = ArgumentCaptor.forClass(String.class);
        verify(iam).registerScratchResource(eq("access"), scopes.capture());
        verify(iam).clearScratchResource("access");
        assertTrue(scopes.getAllValues().get(0).matches(
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

    private RedshiftParquetWriter writer(S3Service s3, FlociDuckClient duck) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRegion()).thenReturn("us-east-1");
        return new RedshiftParquetWriter(duck, s3, mock(IamService.class), config);
    }
}
