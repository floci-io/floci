package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidShapesTest {

    @Test
    void lowerCaseMatchesBoth() {
        String value = "0f8fad5b-d9cb-469f-a165-70867728950e";

        assertTrue(UuidShapes.matchesEitherCase(value));
        assertTrue(UuidShapes.matchesLowerCase(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0F8FAD5B-D9CB-469F-A165-70867728950E",
        "0f8fad5b-D9CB-469f-a165-70867728950e",
    })
    void upperCaseDigitsMatchOnlyTheEitherCaseCheck(String value) {
        assertTrue(UuidShapes.matchesEitherCase(value));
        assertFalse(UuidShapes.matchesLowerCase(value));
    }

    @Test
    void versionAndVariantAreNotRead() {
        String value = "00000000-0000-0000-0000-000000000000";

        assertTrue(UuidShapes.matchesEitherCase(value));
        assertTrue(UuidShapes.matchesLowerCase(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not-a-uuid",
        "0f8fad5bd9cb469fa16570867728950e",
        "0f8fad5-d9cb-469f-a165-70867728950e",
        "0f8fad5b-d9cb-469f-a165-70867728950",
        "0f8fad5b-d9cb-469f-a165-70867728950ef",
        "0f8fad5b-d9cb-469f-a165-70867728950g",
        "0f8fad5b_d9cb_469f_a165_70867728950e",
        " 0f8fad5b-d9cb-469f-a165-70867728950e",
        "0f8fad5b-d9cb-469f-a165-70867728950e\n",
        "x0f8fad5b-d9cb-469f-a165-70867728950e",
        "{0f8fad5b-d9cb-469f-a165-70867728950e}",
        "1-2-3-4-5",
        "\uFF10f8fad5b-d9cb-469f-a165-70867728950e",
    })
    void anythingElseMatchesNeither(String value) {
        assertFalse(UuidShapes.matchesEitherCase(value));
        assertFalse(UuidShapes.matchesLowerCase(value));
    }

    @Test
    void nullMatchesNeither() {
        assertFalse(UuidShapes.matchesEitherCase(null));
        assertFalse(UuidShapes.matchesLowerCase(null));
    }
}
