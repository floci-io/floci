package io.floci.aws.services.glue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcIncludePathTest {

    private static JdbcIncludePath path(String path, String... exclusions) {
        return JdbcIncludePath.parse(path, List.of(exclusions));
    }

    @Test
    void namesTheDatabase() {
        assertEquals("dev", path("dev/public/%").database());
    }

    @Test
    void percentAsTheTableSelectsEveryTableOfTheSchema() {
        JdbcIncludePath path = path("dev/public/%");

        assertTrue(path.selects("public", "orders"));
        assertFalse(path.selects("sales", "orders"));
    }

    @Test
    void percentAsTheSchemaSelectsEveryTableOfEverySchema() {
        JdbcIncludePath path = path("dev/%");

        assertTrue(path.selects("public", "orders"));
        assertTrue(path.selects("sales", "invoices"));
    }

    @Test
    void aSchemaWithoutATableSelectsEveryTableOfThatSchema() {
        JdbcIncludePath path = path("dev/public");

        assertTrue(path.selects("public", "orders"));
        assertFalse(path.selects("sales", "orders"));
    }

    @Test
    void aFullPathSelectsOneTableAndIsCaseSensitive() {
        JdbcIncludePath path = path("dev/public/orders");

        assertTrue(path.selects("public", "orders"));
        assertFalse(path.selects("public", "Orders"));
        assertFalse(path.selects("public", "orders_archive"));
    }

    @Test
    void percentInsideASegmentMatchesAnyRunOfCharacters() {
        JdbcIncludePath path = path("dev/public/order%");

        assertTrue(path.selects("public", "orders"));
        assertTrue(path.selects("public", "order_items"));
        assertFalse(path.selects("public", "customers"));
    }

    @Test
    void underscoreInAPathIsALiteralCharacter() {
        JdbcIncludePath path = path("dev/public/order_items");

        assertFalse(path.selects("public", "orderXitems"));
    }

    @Test
    void refusesAPercentDatabase() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> path("%/public/%"));

        assertTrue(e.getMessage().contains("%/public/%"), e.getMessage());
    }

    @Test
    void refusesAPathWithoutASchemaOrWithTooManySegments() {
        assertThrows(IllegalArgumentException.class, () -> path("dev"));
        assertThrows(IllegalArgumentException.class, () -> path("dev/public/orders/extra"));
        assertThrows(IllegalArgumentException.class, () -> path(""));
        assertThrows(IllegalArgumentException.class, () -> JdbcIncludePath.parse(null, List.of()));
    }

    @Test
    void exclusionsMatchTableNamesRelativeToASchemaPath() {
        JdbcIncludePath path = path("dev/public/%", "HR*", "Market_*", "**_Table");

        assertFalse(path.selects("public", "HR_staff"));
        assertFalse(path.selects("public", "Market_share"));
        assertFalse(path.selects("public", "Sales_Table"));
        assertTrue(path.selects("public", "orders"));
    }

    @Test
    void exclusionsMatchSchemaAndTableRelativeToADatabasePath() {
        JdbcIncludePath path = path("dev/%", "audit/*");

        assertFalse(path.selects("audit", "events"));
        assertTrue(path.selects("public", "events"));
    }

    @Test
    void singleStarDoesNotCrossASchemaBoundaryButDoubleStarDoes() {
        assertTrue(path("dev/%", "*").selects("public", "orders"));
        assertFalse(path("dev/%", "**").selects("public", "orders"));
        assertFalse(path("dev/%", "**orders").selects("public", "orders"));
    }

    @Test
    void exclusionsSupportQuestionMarkBracketsAndGroups() {
        JdbcIncludePath path = path("dev/public/%", "tmp?", "log[0-9]", "stage[!x]", "{a,b}_copy");

        assertFalse(path.selects("public", "tmp1"));
        assertTrue(path.selects("public", "tmp12"));
        assertFalse(path.selects("public", "log7"));
        assertFalse(path.selects("public", "stagey"));
        assertTrue(path.selects("public", "stagex"));
        assertFalse(path.selects("public", "a_copy"));
        assertFalse(path.selects("public", "b_copy"));
        assertTrue(path.selects("public", "c_copy"));
    }

    @Test
    void anEscapedCharacterInAnExclusionIsLiteral() {
        JdbcIncludePath path = path("dev/public/%", "tmp\\*");

        assertFalse(path.selects("public", "tmp*"));
        assertTrue(path.selects("public", "tmp1"));
    }
}
