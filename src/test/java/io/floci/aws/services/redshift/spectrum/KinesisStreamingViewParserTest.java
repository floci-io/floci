package io.floci.aws.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void aViewQueryMayStartWithACte() {
        ExternalStatement.CreateStreamingView view = (ExternalStatement.CreateStreamingView) parser.parse(
                "CREATE MATERIALIZED VIEW v AS WITH s AS (SELECT * FROM kds.t) SELECT * FROM s").orElseThrow();

        assertThat(view.select(), equalTo("WITH s AS (SELECT * FROM kds.t) SELECT * FROM s"));
    }

    @Test
    void commentsAreSeparatorsAndDoNotReachTheStoredQuery() {
        ExternalStatement.RefreshView refresh = (ExternalStatement.RefreshView) parser.parse(
                "REFRESH /* a */ MATERIALIZED VIEW v /* note */ -- tail").orElseThrow();
        ExternalStatement.CreateStreamingView view = (ExternalStatement.CreateStreamingView) parser.parse(
                "CREATE MATERIALIZED VIEW v AS SELECT 'a--b' AS x FROM kds.t -- trailing").orElseThrow();

        assertThat(refresh.viewName(), equalTo("v"));
        assertThat(view.select(), equalTo("SELECT 'a--b' AS x FROM kds.t"));
    }

    @Test
    void aSecondStatementInTheViewQueryIsRefused() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE MATERIALIZED VIEW v AS SELECT 1 FROM kds.t; DROP TABLE public.x"));

        assertThat(error.sqlState(), equalTo("42601"));
        assertThat(parser.parse("CREATE MATERIALIZED VIEW v AS SELECT ';' FROM kds.t").isPresent(), is(true));
    }

    @Test
    void anEscapedQuoteInAnEscapeStringDoesNotHideASecondStatement() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> parser.parse(
                "CREATE MATERIALIZED VIEW v AS SELECT E'\\'' FROM kds.t; DROP TABLE public.x"));
        ExternalStatement.CreateStreamingView view = (ExternalStatement.CreateStreamingView) parser.parse(
                "CREATE MATERIALIZED VIEW v AS SELECT E'\\';' AS x FROM kds.t;").orElseThrow();

        assertThat(error.sqlState(), equalTo("42601"));
        assertThat(view.select(), equalTo("SELECT E'\\';' AS x FROM kds.t"));
    }

    @Test
    void aCommentMarkerAfterAnEscapedQuoteIsStillAComment() {
        ExternalStatement.CreateStreamingView view = (ExternalStatement.CreateStreamingView) parser.parse(
                "CREATE MATERIALIZED VIEW v AS SELECT E'\\'' AS x FROM kds.t -- ; DROP TABLE public.x").orElseThrow();

        assertThat(view.select(), equalTo("SELECT E'\\'' AS x FROM kds.t"));
    }

    @Test
    void leavesOtherStatementsAlone() {
        assertThat(parser.parse("CREATE MATERIALIZED VIEW v DISTSTYLE ALL AS SELECT 1").isPresent(), is(false));
        assertThat(parser.parse("REFRESH MATERIALIZED VIEW").isPresent(), is(false));
        assertThat(parser.parse("DROP VIEW v").isPresent(), is(false));
    }
}
