package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpectrumQueryRewriterTest {

    @Test
    void rewritesOnlyTheSourceTableAndQuotesGeneratedIdentifier() {
        SpectrumQuery query = new SpectrumQuery("analytics", "events", "id, name", "id >= 2 AND name <> 'x'", false);

        assertEquals("SELECT id, name FROM \"spectrum_tmp_abc\" WHERE id >= 2 AND name <> 'x'",
                SpectrumQueryRewriter.rewrite(query, "spectrum_tmp_abc"));
        assertEquals("SELECT * FROM \"tmp\"\"unsafe\"", SpectrumQueryRewriter.rewrite(
                new SpectrumQuery("analytics", "events", "*", null, true), "tmp\"unsafe"));
    }

    @Test
    void resolvesQuotedProjectionNamesContainingDotsCommasAndEscapedQuotes() {
        SpectrumExternalTable table = new SpectrumExternalTable("000000000000", "dev", "analytics", "events",
                List.of(new SpectrumColumn("user.name", SpectrumColumn.Type.VARCHAR),
                        new SpectrumColumn("first,last", SpectrumColumn.Type.VARCHAR),
                        new SpectrumColumn("quote\"name", SpectrumColumn.Type.VARCHAR)),
                "s3://bucket/events/", ',', '"', '\\', "\\N", 0);
        SpectrumQuery query = new SpectrumQuery("analytics", "events",
                "\"user.name\", \"first,last\", \"quote\"\"name\"", null, false);

        assertEquals(table.columns(), SpectrumQueryRewriter.outputColumns(query, table));
    }
}
