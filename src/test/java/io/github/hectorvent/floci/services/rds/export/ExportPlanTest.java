package io.github.hectorvent.floci.services.rds.export;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which tables an export's {@code ExportOnly} selects, and the entries it refuses. */
class ExportPlanTest {

    @Test
    void emptyExportOnlySelectsEverything() {
        ExportOnlySelection selection = ExportOnlySelection.of(List.of());

        assertTrue(selection.includesDatabase("appdb"));
        assertTrue(selection.includes("appdb", "public", "items"));
    }

    @Test
    void exportOnlySelectsByDatabaseSchemaOrTable() {
        ExportOnlySelection selection = ExportOnlySelection.of(
                List.of("whole", "appdb.audit", "appdb.public.items"));

        assertTrue(selection.includes("whole", "any", "table"));
        assertTrue(selection.includes("appdb", "audit", "events"));
        assertTrue(selection.includes("appdb", "public", "items"));
        assertFalse(selection.includes("appdb", "public", "orders"));
        assertFalse(selection.includesDatabase("other"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"appdb.public.items.extra", "app-db", "appdb..items", "appdb.public items"})
    void exportOnlyRefusesNamesAPartialExportCannotTake(String entry) {
        AwsException error = assertThrows(AwsException.class, () -> ExportOnlySelection.of(List.of(entry)));

        assertThat(error.getErrorCode(), equalTo("InvalidExportOnly"));
    }
}
