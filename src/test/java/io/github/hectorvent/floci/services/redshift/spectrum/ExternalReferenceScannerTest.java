package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.services.redshift.spectrum.ExternalReferenceScanner.Reference;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

class ExternalReferenceScannerTest {
    private static final Set<String> SCHEMAS = Set.of("analytics", "Mixed");

    @Test
    void findsQualifiedReferencesAndIgnoresOtherSchemas() {
        assertThat(ExternalReferenceScanner.scan("SELECT * FROM analytics.events e JOIN public.users u ON true", SCHEMAS),
                contains(new Reference("analytics", "events")));
    }

    @Test
    void keepsQuotedCaseAndSupportsDatabaseQualifiedNames() {
        assertThat(ExternalReferenceScanner.scan("SELECT * FROM dev.analytics.events, \"Mixed\".\"Order\"", SCHEMAS),
                contains(new Reference("analytics", "events"), new Reference("Mixed", "Order")));
    }

    @Test
    void findsQualifiedReferencesWithWhitespaceOrCommentsAroundDots() {
        assertThat(ExternalReferenceScanner.scan(
                "SELECT * FROM analytics /* schema */ .\n events", SCHEMAS),
                contains(new Reference("analytics", "events")));
    }

    @Test
    void ignoresStringsAndComments() {
        assertThat(ExternalReferenceScanner.scan("SELECT 'analytics.events' -- analytics.other\n /* analytics.x */", SCHEMAS), empty());
    }

    @Test
    void detectsOnlyWritesTargetingExternalTables() {
        assertThat(ExternalReferenceScanner.writeTarget("INSERT INTO analytics.events VALUES (1)", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
        assertThat(ExternalReferenceScanner.writeTarget("INSERT INTO native_copy SELECT * FROM analytics.events", SCHEMAS), equalTo(Optional.empty()));
        assertThat(ExternalReferenceScanner.writeTarget("WITH values_to_write AS (SELECT 1) UPDATE analytics.events SET id = 2", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
    }

    @Test
    void doesNotTreatColumnQualifiersOrTableAliasesAsTableReferences() {
        assertThat(ExternalReferenceScanner.scan("SELECT analytics.id FROM public.owners AS analytics", SCHEMAS), empty());
        assertThat(ExternalReferenceScanner.scan("SELECT * FROM public.owners analytics WHERE analytics.id = 1", SCHEMAS), empty());
        assertThat(ExternalReferenceScanner.scan(
                "SELECT * FROM public.owners o JOIN public.teams t ON t.id = analytics.id", SCHEMAS), empty());
    }

    @Test
    void findsCommaJoinedAndSubqueryReferencesAndRestoresContextAfterTheSubquery() {
        assertThat(ExternalReferenceScanner.scan("SELECT * FROM public.a, analytics.events", SCHEMAS),
                contains(new Reference("analytics", "events")));
        assertThat(ExternalReferenceScanner.scan(
                "SELECT * FROM public.t WHERE id IN (SELECT id FROM analytics.events)", SCHEMAS),
                contains(new Reference("analytics", "events")));
        assertThat(ExternalReferenceScanner.scan(
                "SELECT * FROM public.t WHERE id IN (SELECT id FROM public.u) AND analytics.id = 1", SCHEMAS), empty());
    }

    @Test
    void checksEveryStatementOfABatchForWrites() {
        assertThat(ExternalReferenceScanner.writeTarget(
                "UPDATE public.owners SET x = 1; DELETE FROM analytics.events", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
    }

    @Test
    void detectsWritesInsideDataModifyingCtesTruncateAndMerge() {
        assertThat(ExternalReferenceScanner.writeTarget(
                "WITH gone AS (DELETE FROM analytics.events RETURNING id) SELECT * FROM gone", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
        assertThat(ExternalReferenceScanner.writeTarget("TRUNCATE TABLE analytics.events", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
        assertThat(ExternalReferenceScanner.writeTarget(
                "TRUNCATE TABLE public.local_table, analytics.events", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
        assertThat(ExternalReferenceScanner.writeTarget("MERGE INTO ONLY analytics.events USING public.t ON true", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
        assertThat(ExternalReferenceScanner.writeTarget(
                "MERGE INTO analytics.events USING public.t ON true WHEN MATCHED THEN DELETE", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
    }

    @Test
    void detectsDdlThatWouldChangeAnExternalTableBehindFlocisBack() {
        Optional<Reference> events = Optional.of(new Reference("analytics", "events"));
        assertThat(ExternalReferenceScanner.writeTarget("DROP TABLE public.a, analytics.events", SCHEMAS), equalTo(events));
        assertThat(ExternalReferenceScanner.writeTarget("SELECT 1; DROP TABLE IF EXISTS analytics.events", SCHEMAS), equalTo(events));
        assertThat(ExternalReferenceScanner.writeTarget("ALTER TABLE analytics.events RENAME TO x", SCHEMAS), equalTo(events));
        assertThat(ExternalReferenceScanner.writeTarget("ALTER TABLE IF EXISTS ONLY analytics.events ADD COLUMN c int", SCHEMAS), equalTo(events));
        assertThat(ExternalReferenceScanner.writeTarget("COPY analytics.events FROM 's3://b/k'", SCHEMAS), equalTo(events));
        assertThat(ExternalReferenceScanner.writeTarget("CREATE TABLE analytics.events (id int)", SCHEMAS), equalTo(events));
        assertThat(ExternalReferenceScanner.writeTarget("CREATE TEMP TABLE IF NOT EXISTS analytics.events (id int)", SCHEMAS), equalTo(events));
    }

    @Test
    void detectsDroppingAnExternalSchemaFromAList() {
        assertThat(ExternalReferenceScanner.writeTarget("DROP SCHEMA local_schema, analytics CASCADE", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", ""))));
    }

    @Test
    void ddlOnNativeObjectsIsNotAnExternalWrite() {
        assertThat(ExternalReferenceScanner.writeTarget("DROP TABLE public.a, public.b", SCHEMAS), equalTo(Optional.empty()));
        assertThat(ExternalReferenceScanner.writeTarget("DROP SCHEMA local_schema", SCHEMAS), equalTo(Optional.empty()));
        assertThat(ExternalReferenceScanner.writeTarget("ALTER TABLE public.t ADD COLUMN c int", SCHEMAS), equalTo(Optional.empty()));
        assertThat(ExternalReferenceScanner.writeTarget("CREATE TABLE public.t AS SELECT * FROM analytics.events", SCHEMAS), equalTo(Optional.empty()));
        assertThat(ExternalReferenceScanner.writeTarget("COPY public.t FROM 's3://b/k'", SCHEMAS), equalTo(Optional.empty()));
    }

    @Test
    void detectsNativeTablesRenamedOrMovedIntoAnExternalSchema() {
        assertThat(ExternalReferenceScanner.writeTarget("ALTER TABLE public.t RENAME TO analytics.events", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", "events"))));
        assertThat(ExternalReferenceScanner.writeTarget("ALTER TABLE public.t SET SCHEMA analytics", SCHEMAS),
                equalTo(Optional.of(new Reference("analytics", ""))));
    }

    @Test
    void writesToNativeTablesAreNotExternalWrites() {
        assertThat(ExternalReferenceScanner.writeTarget("UPDATE public.owners SET x = 1", SCHEMAS), equalTo(Optional.empty()));
        assertThat(ExternalReferenceScanner.writeTarget(
                "INSERT INTO public.t VALUES (1) ON CONFLICT DO UPDATE SET x = 1", SCHEMAS), equalTo(Optional.empty()));
    }
}
