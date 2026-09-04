package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Drives the package-private static {@code LambdaService.parseFilterCriteria} directly: the create/update
 * validation is a pure function of the request map, so it is unit-testable without constructing the service
 * (whose full wiring needs the Quarkus runtime, unavailable in this sandbox).
 */
class LambdaEsmFilterCriteriaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, Object> reqWithCriteria(Object filterCriteria) {
        Map<String, Object> req = new HashMap<>();
        req.put("FilterCriteria", filterCriteria);
        return req;
    }

    private static Map<String, Object> filtersReq(String... patterns) {
        List<Object> filters = new ArrayList<>();
        for (String p : patterns) {
            Map<String, Object> f = new HashMap<>();
            f.put("Pattern", p);
            filters.add(f);
        }
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("Filters", filters);
        return reqWithCriteria(criteria);
    }

    private static void assertRejected(Map<String, Object> request) {
        AwsException ex = assertThrows(AwsException.class,
                () -> LambdaService.parseFilterCriteria(request, MAPPER));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    // ──────────── accepted ────────────

    @Test
    void singleValidFilterParses() {
        EventSourceMapping.FilterCriteria fc =
                LambdaService.parseFilterCriteria(filtersReq("{\"data\":{\"type\":[\"buy\"]}}"), MAPPER);
        assertNotNull(fc);
        assertEquals(1, fc.getFilters().size());
        assertEquals("{\"data\":{\"type\":[\"buy\"]}}", fc.getFilters().get(0).getPattern());
    }

    @Test
    void fiveFiltersOk_nestedObjectMatchArrayAndEmptyObjectValuesAccepted() {
        EventSourceMapping.FilterCriteria fc = LambdaService.parseFilterCriteria(filtersReq(
                "{\"eventName\":[\"INSERT\"]}",
                "{\"dynamodb\":{\"NewImage\":{\"s\":{\"S\":[\"x\"]}}}}",
                "{\"body\":{\"status\":[\"active\"]}}",
                "{\"data\":{\"n\":[{\"numeric\":[\">\",0]}]}}",
                "{}"), MAPPER);
        assertNotNull(fc);
        assertEquals(5, fc.getFilters().size());
    }

    @Test
    void absentEmptyObjectEmptyFiltersAndExplicitNullAllClear() {
        assertNull(LambdaService.parseFilterCriteria(new HashMap<>(), MAPPER));               // absent
        assertNull(LambdaService.parseFilterCriteria(reqWithCriteria(new HashMap<>()), MAPPER)); // {}
        Map<String, Object> emptyFilters = new HashMap<>();
        emptyFilters.put("Filters", new ArrayList<>());
        assertNull(LambdaService.parseFilterCriteria(reqWithCriteria(emptyFilters), MAPPER)); // {"Filters":[]}
        assertNull(LambdaService.parseFilterCriteria(reqWithCriteria(null), MAPPER));         // explicit null clears
    }

    // ──────────── rejected ────────────

    @Test
    void nonObjectCriteriaRejected() {
        assertRejected(reqWithCriteria("not-an-object"));
    }

    @Test
    void nonArrayFiltersRejected() {
        Map<String, Object> c = new HashMap<>();
        c.put("Filters", "nope");
        assertRejected(reqWithCriteria(c));
    }

    @Test
    void nonObjectFilterEntryRejected() {
        Map<String, Object> c = new HashMap<>();
        c.put("Filters", List.of("nope"));
        assertRejected(reqWithCriteria(c));
    }

    @Test
    void missingBlankAndNonStringPatternRejected() {
        Map<String, Object> c1 = new HashMap<>();
        c1.put("Filters", List.of(new HashMap<>())); // entry has no Pattern
        assertRejected(reqWithCriteria(c1));
        assertRejected(filtersReq("   "));           // blank Pattern
        Map<String, Object> intPattern = new HashMap<>();
        intPattern.put("Pattern", 5);
        Map<String, Object> c2 = new HashMap<>();
        c2.put("Filters", List.of(intPattern));
        assertRejected(reqWithCriteria(c2));         // non-string Pattern
    }

    @Test
    void tooManyFiltersRejected() {
        assertRejected(filtersReq(
                "{\"a\":[1]}", "{\"a\":[1]}", "{\"a\":[1]}", "{\"a\":[1]}", "{\"a\":[1]}", "{\"a\":[1]}"));
    }

    @Test
    void oversizedPatternRejectedButBoundaryOk() {
        // {"a":["<filler>"]}: fixed chars = 10, so filler 4086 → total exactly 4096.
        String ok4096 = "{\"a\":[\"" + "x".repeat(4086) + "\"]}";
        assertEquals(4096, ok4096.length());
        assertNotNull(LambdaService.parseFilterCriteria(filtersReq(ok4096), MAPPER));
        String over4097 = "{\"a\":[\"" + "x".repeat(4087) + "\"]}";
        assertEquals(4097, over4097.length());
        assertRejected(filtersReq(over4097));
    }

    @Test
    void malformedJsonPatternRejected() {
        assertRejected(filtersReq("{oops"));
    }

    @Test
    void nonObjectRootPatternRejected() {
        assertRejected(filtersReq("[1,2]"));
        assertRejected(filtersReq("\"x\""));
        assertRejected(filtersReq("5"));
    }

    @Test
    void scalarValuePatternRejected() {
        // Object root but a scalar value: the matcher can never satisfy this, so with enforcement
        // active it would silently drop every record, so reject at create/update instead.
        assertRejected(filtersReq("{\"eventName\":\"INSERT\"}"));
    }

    @Test
    void emptyArrayValuePatternRejected() {
        assertRejected(filtersReq("{\"a\":[]}"));
    }
}
