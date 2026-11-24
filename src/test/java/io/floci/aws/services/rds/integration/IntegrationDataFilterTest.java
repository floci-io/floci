package io.floci.aws.services.rds.integration;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PostgreSQL data filter forms the RDS documentation shows, and the ones it refuses. */
class IntegrationDataFilterTest {

    @Test
    void parsesTheDocumentedPostgresExamples() {
        for (String filter : List.of(
                "include: books.*.*",
                "include: books.*.*, exclude: books.*.mystery",
                "include: books.mystery.stephen_king, include: employee.finance.benefits",
                "include: books.science_fiction.*, exclude: books.*./.*king.*/",
                "include: books.*.*, exclude: books./sci.*/.*",
                "include: books.*./table_.*/, exclude: books.*.table_stephen_king",
                "include: mydb.myschema./table_\\d+/")) {
            IntegrationDataFilter parsed = IntegrationDataFilter.parsePostgres(filter);
            assertFalse(parsed.rules().isEmpty(), filter);
        }
    }

    @Test
    void keepsEachRuleWithItsTypeAndParts() {
        IntegrationDataFilter parsed = IntegrationDataFilter.parsePostgres(
                "Include: books.*.*, EXCLUDE: books.*./.*king.*/, include: employee.finance.benefits");

        assertThat(parsed.rules(), hasSize(3));
        assertTrue(parsed.rules().get(0).include());
        assertFalse(parsed.rules().get(1).include());
        assertThat(parsed.rules().get(1).parts(), contains("books", "*", "/.*king.*/"));
        assertThat(parsed.includedDatabases(), contains("books", "employee"));
    }

    @Test
    void requiresAFilterThatIncludesADatabase() {
        assertInvalid(null);
        assertInvalid(" ");
        assertInvalid("include: *.*.*");
        assertInvalid("exclude: books.*.*");
    }

    @Test
    void refusesWhatIsNotAPostgresPattern() {
        assertInvalid("books.*.*");
        assertInvalid("keep: books.*.*");
        assertInvalid("include: books.*");
        assertInvalid("include: books.a.b.c");
        assertInvalid("include: /bo.*/.*.*");
        assertInvalid("include: books.*./unclosed");
        assertInvalid("include: books.*.{x}");
    }

    @Test
    void refusesMoreThanTheAllowedPatternsOrAPatternTooLong() {
        String many = String.join(", ", Collections.nCopies(100, "include: books.*.*"));
        AwsException tooMany = assertInvalid(many);
        assertThat(tooMany.getMessage(), containsString("99"));
        assertInvalid("include: books.*." + "t".repeat(260));
    }

    private static AwsException assertInvalid(String filter) {
        AwsException error = assertThrows(AwsException.class, () -> IntegrationDataFilter.parsePostgres(filter));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        return error;
    }
}
