package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpectrumS3ReaderTest {

    @Test
    void readsCsvRowsWithHeadersNullsAndTypes() {
        S3Service s3 = mock(S3Service.class);
        S3Object data = object("events/data.csv", ""
                + "id,name,active,amount,date\n"
                + "1,alice,true,12.50,2026-01-02\n"
                + "2,\\N,false,3.00,2026-01-03\n");
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000))
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
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000))
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
        when(s3.listObjectsWithPrefixes("warehouse", "events/", "", 1000))
                .thenReturn(new S3Service.ListObjectsResult(List.of(data), List.of(), false, null));
        when(s3.getObject("warehouse", "events/data.csv")).thenReturn(data);
        SpectrumExternalTable table = new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("id", SpectrumColumn.Type.INTEGER)),
                "s3://warehouse/events/", ',', '"', '\\', "\\N", 0);

        assertThrows(SpectrumReadException.class, () -> new SpectrumS3Reader(s3, null).read(schema(), table).toList());
    }

    private static SpectrumExternalSchema schema() {
        return new SpectrumExternalSchema("000000000000", "dev", "analytics", "s3://warehouse/root/", null);
    }

    private static SpectrumExternalTable table(SpectrumColumn.Type type) {
        return new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("value", type)), "s3://warehouse/events/", ',', '"', '\\', "\\N", 0);
    }

    private static S3Object object(String key, String content) {
        return new S3Object("warehouse", key, content.getBytes(StandardCharsets.UTF_8), "text/csv");
    }
}
