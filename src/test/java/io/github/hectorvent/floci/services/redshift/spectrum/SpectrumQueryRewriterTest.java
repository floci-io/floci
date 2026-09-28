package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

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
}
