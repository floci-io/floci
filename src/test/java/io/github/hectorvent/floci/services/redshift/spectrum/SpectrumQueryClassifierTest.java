package io.github.hectorvent.floci.services.redshift.spectrum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectrumQueryClassifierTest {

    private final SpectrumQueryClassifier classifier = new SpectrumQueryClassifier();

    @Test
    void classifiesProjectionAndSimplePredicates() {
        SpectrumQuery query = classifier.classify(
                "SELECT id, name FROM analytics.events WHERE id >= 2 AND name <> 'x'", 0).orElseThrow();

        assertEquals("analytics", query.schemaName());
        assertEquals("events", query.tableName());
        assertEquals("id, name", query.projectionSql());
        assertEquals("id >= 2 AND name <> 'x'", query.predicateSql());
    }

    @Test
    void classifiesSelectStarAndReturnsEmptyForNonSelectSql() {
        SpectrumQuery query = classifier.classify("SELECT * FROM events", 0).orElseThrow();
        assertTrue(query.selectStar());
        assertTrue(classifier.classify("SHOW TABLES", 0).isEmpty());
    }

    @Test
    void rejectsParametersAndUnsupportedExternalShapes() {
        assertThrows(SpectrumSqlException.class, () -> classifier.classify("SELECT * FROM analytics.events", 1));
        assertThrows(SpectrumSqlException.class, () -> classifier.classify(
                "SELECT * FROM analytics.events JOIN analytics.users ON events.id = users.id", 0));
        assertThrows(SpectrumSqlException.class, () -> classifier.classify(
                "SELECT count(*) FROM analytics.events", 0));
        assertThrows(SpectrumSqlException.class, () -> classifier.classify(
                "SELECT * FROM analytics.events; SELECT 1", 0));
    }
}
