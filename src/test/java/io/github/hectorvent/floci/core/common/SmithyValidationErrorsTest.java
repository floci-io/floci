package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmithyValidationErrorsTest {

    @Test
    void oneViolation_isSingular() {
        assertEquals("1 validation error detected: Value at 'a' failed to satisfy constraint: x",
                SmithyValidationErrors.message(List.of("Value at 'a' failed to satisfy constraint: x")));
    }

    @Test
    void severalViolations_arePluralAndJoinedInOrder() {
        assertEquals("2 validation errors detected: second; first",
                SmithyValidationErrors.message(List.of("second", "first")));
    }

    @Test
    void exception_carriesTheGivenCodeAsA400() {
        AwsException e = SmithyValidationErrors.exception("ValidationError", List.of("v"));
        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("1 validation error detected: v", e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void namedFactories_carryTheirProtocolsCode() {
        assertEquals("ValidationException", SmithyValidationErrors.validationException(List.of("v")).getErrorCode());
        assertEquals("ValidationError", SmithyValidationErrors.validationError(List.of("v")).getErrorCode());
        assertEquals("BadRequestException", SmithyValidationErrors.badRequest(List.of("v")).getErrorCode());
    }

    @Test
    void stringOverloads_reportOneViolation() {
        String expected = "1 validation error detected: v";
        assertEquals(expected, SmithyValidationErrors.badRequest("v").getMessage());
        assertEquals(expected, SmithyValidationErrors.validationError("v").getMessage());
        assertEquals(expected, SmithyValidationErrors.validationException("v").getMessage());
        assertEquals(expected, SmithyValidationErrors.exception("InvalidParameterException", "v").getMessage());
    }

    @Test
    void noViolations_isAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> SmithyValidationErrors.message(List.of()));
    }

    @Test
    void notNull_hasTwoForms() {
        assertEquals("Value null at 'a.b' failed to satisfy constraint: Member must not be null",
                SmithyValidationErrors.notNull("a.b"));
        assertEquals("Value at 'a.b' failed to satisfy constraint: Member must not be null",
                SmithyValidationErrors.notNullWithoutValue("a.b"));
    }

    @Test
    void lengthBoundsPreserveValuesAndIncludeEndpoints() {
        assertEquals("Value '' at 'name' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1",
                SmithyValidationErrors.lengthOutOfRange("name", "", 1, 3));
        assertEquals("Value 'abcd' at 'name' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 3",
                SmithyValidationErrors.lengthOutOfRange("name", "abcd", 1, 3));
        assertNull(SmithyValidationErrors.lengthOutOfRange("name", "a", 1, 3));
        assertNull(SmithyValidationErrors.lengthOutOfRange("name", "abc", 1, 3));
        assertNull(SmithyValidationErrors.lengthOutOfRange("name", " ", 1, 3));
        assertEquals("Value ' ' at 'name' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1",
                SmithyValidationErrors.lengthTooShort("name", " ", 1));
    }
    @Test
    void lengthBoundsCountSupplementaryCharactersOnce() {
        String value = "\uD83D\uDE00";
        assertNull(SmithyValidationErrors.lengthOutOfRange("name", value, 1, 1));
        assertEquals(SmithyValidationErrors.lengthTooShort("name", value, 2),
                SmithyValidationErrors.lengthOutOfRange("name", value, 2, 3));
        assertEquals("Value '" + value.repeat(2) + "' at 'name' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 1",
                SmithyValidationErrors.lengthOutOfRange("name", value.repeat(2), 0, 1));
    }

    /**
     * A service's own form decides whether the offending value is quoted beside the member, which
     * is the only thing the two differ in. The statics are the quoting form, so every service that
     * calls them keeps the message it answered.
     */
    @Test
    void aServicesFormDecidesWhetherTheValueIsQuoted() {
        SmithyValidationErrors.Violations quoted = SmithyValidationErrors.Violations.QUOTED;
        SmithyValidationErrors.Violations without = SmithyValidationErrors.Violations.WITHOUT_VALUES;

        assertEquals("Value 'ab' at 'userName' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 3",
                quoted.lengthOutOfRange("userName", "ab", 3, 10));
        assertEquals("Value at 'userName' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 3",
                without.lengthOutOfRange("userName", "ab", 3, 10));

        assertEquals(SmithyValidationErrors.lengthOutOfRange("userName", "ab", 3, 10),
                quoted.lengthOutOfRange("userName", "ab", 3, 10));
        assertEquals(SmithyValidationErrors.pattern("userName", "a b", "[a-z]+"),
                quoted.pattern("userName", "a b", "[a-z]+"));
        assertEquals(SmithyValidationErrors.notInEnum("Users", "filter", List.of("User")),
                quoted.notInEnum("Users", "filter", List.of("User")));
        assertEquals(SmithyValidationErrors.outOfRange("maxItems", 1001, 1, 1000),
                quoted.outOfRange("maxItems", 1001, 1, 1000));

        assertEquals("Value at 'filter' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [User]",
                without.notInEnum("Users", "filter", List.of("User")));
        assertEquals("Value at 'maxItems' failed to satisfy constraint: "
                        + "Member must have value less than or equal to 1000",
                without.outOfRange("maxItems", 1001, 1, 1000));

        // A value inside its bounds is no violation in either form.
        assertNull(without.lengthOutOfRange("userName", "abc", 3, 10));
        assertNull(without.outOfRange("maxItems", 1000, 1, 1000));
    }
}
