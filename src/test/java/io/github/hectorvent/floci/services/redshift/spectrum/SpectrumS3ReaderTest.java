package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftRoleAccess;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpectrumS3ReaderTest {

    @Test
    void readsCsvRowsWithHeadersNullsAndTypes() {
        S3Service s3 = mock(S3Service.class);
        S3Object data = object("events/data.csv", ""
                + "id,name,active,amount,date\n"
                + "1,alice,true,12.50,2026-01-02\n"
                + "2,\\N,false,3.00,2026-01-03\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(data), List.of(), false, null));
        when(s3.getObject("warehouse", "events/data.csv")).thenReturn(data);
        SpectrumExternalTable table = new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(
                        new SpectrumColumn("id", SpectrumColumn.Type.INTEGER),
                        new SpectrumColumn("name", SpectrumColumn.Type.VARCHAR),
                        new SpectrumColumn("active", SpectrumColumn.Type.BOOLEAN),
                        new SpectrumColumn("amount", SpectrumColumn.Type.DECIMAL),
                        new SpectrumColumn("date", SpectrumColumn.Type.DATE)),
                "s3://warehouse/events/", ',', '"', '\\', "\\N", 1);

        List<SpectrumRow> rows = new SpectrumS3Reader(s3, null).read(schema(), table).toList();

        assertEquals(List.of(
                new SpectrumRow(List.of("1", "alice", "true", "12.50", "2026-01-02")),
                new SpectrumRow(Arrays.asList("2", null, "false", "3.00", "2026-01-03"))), rows);
    }

    @Test
    void readsPrefixObjectsInSortedKeyOrder() {
        S3Service s3 = mock(S3Service.class);
        S3Object z = object("events/z.csv", "z\n");
        S3Object a = object("events/a.csv", "a\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(z, a), List.of(), false, null));
        when(s3.getObject("warehouse", "events/z.csv")).thenReturn(z);
        when(s3.getObject("warehouse", "events/a.csv")).thenReturn(a);
        SpectrumExternalTable table = table(SpectrumColumn.Type.VARCHAR);

        List<SpectrumRow> rows = new SpectrumS3Reader(s3, null).read(schema(), table).toList();

        assertEquals(List.of(new SpectrumRow(List.of("a")), new SpectrumRow(List.of("z"))), rows);
    }

    @Test
    void rejectsRowWidthAndTypeConversionErrors() {
        S3Service s3 = mock(S3Service.class);
        S3Object data = object("events/data.csv", "bad,extra\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(data), List.of(), false, null));
        when(s3.getObject("warehouse", "events/data.csv")).thenReturn(data);
        SpectrumExternalTable table = new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER)),
                "s3://warehouse/events/", ',', '"', '\\', "\\N", 0);

        assertThrows(SpectrumReadException.class, () -> new SpectrumS3Reader(s3, null).read(schema(), table).toList());
    }

    @Test
    void pagesThroughMoreThanOneThousandObjects() {
        S3Service s3 = mock(S3Service.class);
        S3Object first = object("events/000.csv", "1\n");
        S3Object second = object("events/001.csv", "2\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(first), List.of(), true, "page-2"));
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, "page-2", null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(second), List.of(), false, null));
        when(s3.getObject("warehouse", "events/000.csv")).thenReturn(first);
        when(s3.getObject("warehouse", "events/001.csv")).thenReturn(second);
        SpectrumExternalTable table = table(SpectrumColumn.Type.VARCHAR);

        List<SpectrumRow> rows = new SpectrumS3Reader(s3, null).read(schema(), table).toList();

        assertEquals(List.of(new SpectrumRow(List.of("1")), new SpectrumRow(List.of("2"))), rows);
    }

    @Test
    void mapsDeniedGetObjectToAuthorizationSqlState() {
        S3Service s3 = mock(S3Service.class);
        S3Object data = object("events/data.csv", "value\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(data), List.of(), false, null));
        doThrow(new AwsException("AccessDenied", "Access Denied", 403))
                .when(s3).authorizeAnonymousGetObject("warehouse", "events/data.csv");
        SpectrumExternalTable table = table(SpectrumColumn.Type.VARCHAR);

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> new SpectrumS3Reader(s3, null).read(schema(), table).toList());

        assertEquals("42501", exception.sqlState());
    }

    @Test
    void deniesRoleGetObjectBeforeFetchingTheObject() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(true);
        S3Object data = object("events/data.csv", "value\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(data), List.of(), false, null));
        IamService iam = mock(IamService.class);
        String role = "arn:aws:iam::000000000000:role/Reader";
        when(iam.resolvePrincipalContext(role)).thenReturn(CallerContext.of(List.of(policy(
                "s3:ListBucket", RedshiftRoleAccess.bucketArn(role, "warehouse")))));

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> new SpectrumS3Reader(s3, iam).read(session(role), schema(role), table(
                        SpectrumColumn.Type.VARCHAR)).toList());

        assertEquals("42501", exception.sqlState());
        verify(s3, never()).getObject("warehouse", "events/data.csv");
        verify(s3, never()).authorizeAnonymousGetObject("warehouse", "events/data.csv");
    }

    @Test
    void readsRowsWhenTheBoundRoleAllowsListingAndObjectReads() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(true);
        S3Object data = object("events/data.csv", "hello\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null))
                .thenReturn(new S3Service.ListObjectsResult(List.of(data), List.of(), false, null));
        when(s3.getObject("warehouse", "events/data.csv")).thenReturn(data);
        IamService iam = mock(IamService.class);
        String role = "arn:aws:iam::000000000000:role/Reader";
        when(iam.resolvePrincipalContext(role)).thenReturn(CallerContext.of(List.of(
                policy("s3:ListBucket", RedshiftRoleAccess.bucketArn(role, "warehouse")),
                policy("s3:GetObject", RedshiftRoleAccess.objectArn(role, "warehouse", "events/data.csv")))));

        List<SpectrumRow> rows = new SpectrumS3Reader(s3, iam)
                .read(session(role), schema(role), table(SpectrumColumn.Type.VARCHAR)).toList();

        assertEquals(List.of(new SpectrumRow(List.of("hello"))), rows);
        verify(s3, never()).authorizeAnonymousListBucket("warehouse");
        verify(s3, never()).authorizeAnonymousGetObject("warehouse", "events/data.csv");
    }

    @Test
    void deniesRoleListBucketBeforeListingObjects() {
        S3Service s3 = mock(S3Service.class);
        when(s3.isAuthEnforced()).thenReturn(true);
        IamService iam = mock(IamService.class);
        String role = "arn:aws:iam::000000000000:role/Reader";
        when(iam.resolvePrincipalContext(role)).thenReturn(CallerContext.of(List.of(policy(
                "s3:GetObject", RedshiftRoleAccess.objectArn(role, "warehouse", "events/data.csv")))));

        SpectrumReadException exception = assertThrows(SpectrumReadException.class,
                () -> new SpectrumS3Reader(s3, iam).read(session(role), schema(role), table(
                        SpectrumColumn.Type.VARCHAR)).toList());

        assertEquals("42501", exception.sqlState());
        verify(s3, never()).listObjectsWithPrefixes("warehouse", "events/", "", 1000, null, null);
    }

    private static SpectrumExternalSchema schema() {
        return new SpectrumExternalSchema("000000000000", "dev", "analytics", "s3://warehouse/root/", null);
    }

    private static SpectrumExternalSchema schema(String role) {
        return new SpectrumExternalSchema("000000000000", "dev", "analytics", "s3://warehouse/root/", role);
    }

    private static SpectrumSession session(String role) {
        return new SpectrumSession("000000000000", "cluster-1", "dev", List.of(role), false);
    }

    private static String policy(String action, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"" + resource + "\"}]}";
    }

    private static SpectrumExternalTable table(SpectrumColumn.Type type) {
        return new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("value", type)), "s3://warehouse/events/", ',', '"', '\\', "\\N", 0);
    }

    private static S3Object object(String key, String content) {
        return new S3Object("warehouse", key, content.getBytes(StandardCharsets.UTF_8), "text/csv");
    }
}
