package io.floci.aws.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class KinesisStreamingViewParserTest {
    private final ExternalStatementParser parser = new ExternalStatementParser();

    @Test
    void parsesAViewOverAStreamWithManualRefresh() {
        ExternalStatement.CreateStreamingView view = (ExternalStatement.CreateStreamingView) parser.parse(
                "CREATE MATERIALIZED VIEW my_view AUTO REFRESH NO AS SELECT * FROM kds.my_stream;").orElseThrow();

        assertThat(view.viewSchema(), nullValue());
        assertThat(view.viewName(), equalTo("my_view"));
        assertThat(view.autoRefresh(), is(false));
        assertThat(view.select(), equalTo("SELECT * FROM kds.my_stream"));
    }

    @Test
    void manualRefreshIsTheDefaultAndAutoRefreshYesIsRecorded() {
        ExternalStatement.CreateStreamingView manual = (ExternalStatement.CreateStreamingView) parser.parse(
                "create materialized view s.v as select 1 from kds.t").orElseThrow();
        ExternalStatement.CreateStreamingView auto = (ExternalStatement.CreateStreamingView) parser.parse(
                "CREATE MATERIALIZED VIEW \"S\".\"V\" AUTO REFRESH YES AS SELECT 1 FROM kds.t").orElseThrow();

        assertThat(manual.viewSchema(), equalTo("s"));
        assertThat(manual.viewName(), equalTo("v"));
        assertThat(manual.autoRefresh(), is(false));
        assertThat(auto.viewSchema(), equalTo("S"));
        assertThat(auto.viewName(), equalTo("V"));
        assertThat(auto.autoRefresh(), is(true));
    }

    @Test
    void parsesRefreshAndDrop() {
        ExternalStatement.RefreshView refresh = (ExternalStatement.RefreshView) parser.parse(
                "REFRESH MATERIALIZED VIEW public.my_view;").orElseThrow();
        ExternalStatement.DropView drop = (ExternalStatement.DropView) parser.parse(
                "DROP MATERIALIZED VIEW IF EXISTS my_view CASCADE").orElseThrow();

        assertThat(refresh.viewSchema(), equalTo("public"));
        assertThat(refresh.viewName(), equalTo("my_view"));
        assertThat(drop.viewSchema(), nullValue());
        assertThat(drop.viewName(), equalTo("my_view"));
        assertThat(drop.ifExists(), is(true));
        assertThat(drop.cascade(), is(true));
    }

    @Test
    void leavesOtherStatementsAlone() {
        assertThat(parser.parse("CREATE MATERIALIZED VIEW v DISTSTYLE ALL AS SELECT 1").isPresent(), is(false));
        assertThat(parser.parse("REFRESH MATERIALIZED VIEW").isPresent(), is(false));
        assertThat(parser.parse("DROP VIEW v").isPresent(), is(false));
    }
}
