package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedQuerySessionTest {

    @Test
    void bindMakesAnInterceptedStatementVisibleThroughItsPortal() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");

        ExtendedQuerySession.Mutation parse = session.stageParse("copy-s", copy);
        ExtendedQuerySession.Mutation bind = session.stageBind("copy-p", "copy-s");

        assertSame(copy, session.statement("copy-s").orElseThrow());
        assertSame(copy, session.portal("copy-p").orElseThrow());
        session.confirm(parse);
        session.confirm(bind);
    }

    @Test
    void ordinaryUnnamedParseAndBindReplaceInterceptedUnnamedMappings() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("", copy));
        session.confirm(session.stageBind("", ""));

        session.confirm(session.stageParse("", null));
        session.confirm(session.stageBind("", ""));

        assertTrue(session.statement("").isEmpty());
        assertTrue(session.portal("").isEmpty());
    }

    @Test
    void rejectingAParseRollsBackItAndEveryLaterPipelinedMutation() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        ExtendedQuerySession.Mutation parse = session.stageParse("s", copy);
        session.stageBind("p", "s");

        session.rejectFrom(parse);

        assertTrue(session.statement("s").isEmpty());
        assertTrue(session.portal("p").isEmpty());
    }

    @Test
    void namedStatementsCoexist() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement first = CopyStatementParser.parse("COPY first FROM 's3://b/one'");
        CopyStatementParser.S3Statement second = CopyStatementParser.parse("COPY second FROM 's3://b/two'");

        session.confirm(session.stageParse("first", first));
        session.confirm(session.stageParse("second", second));

        assertSame(first, session.statement("first").orElseThrow());
        assertSame(second, session.statement("second").orElseThrow());
    }

    @Test
    void unnamedPortalRebindingReplacesItsStatement() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement first = CopyStatementParser.parse("COPY first FROM 's3://b/one'");
        CopyStatementParser.S3Statement second = CopyStatementParser.parse("COPY second FROM 's3://b/two'");
        session.confirm(session.stageParse("first", first));
        session.confirm(session.stageParse("second", second));
        session.confirm(session.stageBind("", "first"));

        session.confirm(session.stageBind("", "second"));

        assertSame(second, session.portal("").orElseThrow());
    }

    @Test
    void twoPortalsCanReferenceOneStatement() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));

        session.confirm(session.stageBind("first", "s"));
        session.confirm(session.stageBind("second", "s"));

        assertSame(copy, session.portal("first").orElseThrow());
        assertSame(copy, session.portal("second").orElseThrow());
    }

    @Test
    void closingAStatementAlsoClosesItsPortals() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("first", "s"));
        session.confirm(session.stageBind("second", "s"));

        session.confirm(session.stageClose('S', "s"));

        assertTrue(session.statement("s").isEmpty());
        assertTrue(session.portal("first").isEmpty());
        assertTrue(session.portal("second").isEmpty());
    }

    @Test
    void closingAPortalLeavesItsStatementAndOtherPortals() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("first", "s"));
        session.confirm(session.stageBind("second", "s"));

        session.confirm(session.stageClose('P', "first"));

        assertSame(copy, session.statement("s").orElseThrow());
        assertTrue(session.portal("first").isEmpty());
        assertSame(copy, session.portal("second").orElseThrow());
    }

    @Test
    void rejectingAStatementCloseRestoresTheStatementAndItsPortals() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("first", "s"));
        session.confirm(session.stageBind("second", "s"));

        ExtendedQuerySession.Mutation close = session.stageClose('S', "s");
        assertTrue(session.statement("s").isEmpty());
        assertTrue(session.portal("first").isEmpty());
        assertTrue(session.portal("second").isEmpty());

        session.rejectFrom(close);

        assertSame(copy, session.statement("s").orElseThrow());
        assertSame(copy, session.portal("first").orElseThrow());
        assertSame(copy, session.portal("second").orElseThrow());
    }

    @Test
    void rejectingAPortalCloseRestoresOnlyThatPortal() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("p", "s"));

        ExtendedQuerySession.Mutation close = session.stageClose('P', "p");
        session.rejectFrom(close);

        assertSame(copy, session.statement("s").orElseThrow());
        assertSame(copy, session.portal("p").orElseThrow());
    }

    @Test
    void repeatedLookupDoesNotChangeRollbackState() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        ExtendedQuerySession.Mutation parse = session.stageParse("s", copy);

        assertSame(copy, session.statement("s").orElseThrow());
        assertSame(copy, session.statement("s").orElseThrow());
        session.rejectFrom(parse);

        assertTrue(session.statement("s").isEmpty());
    }

    @Test
    void transactionEndClearsPortalsButRetainsStatements() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("p", "s"));

        session.transactionEnded();

        assertSame(copy, session.statement("s").orElseThrow());
        assertTrue(session.portal("p").isEmpty());
    }

    @Test
    void clearPortalsRetainsStatements() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("p", "s"));

        session.clearPortals();

        assertSame(copy, session.statement("s").orElseThrow());
        assertTrue(session.portal("p").isEmpty());
    }

    @Test
    void clearRemovesStatementsAndPortals() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("p", "s"));

        session.clear();

        assertTrue(session.statement("s").isEmpty());
        assertTrue(session.portal("p").isEmpty());
    }

    @Test
    void closeRejectsAnInvalidTargetType() {
        ExtendedQuerySession session = new ExtendedQuerySession();

        assertThrows(IllegalArgumentException.class, () -> session.stageClose('X', "name"));
    }
}
