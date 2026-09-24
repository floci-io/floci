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
}
