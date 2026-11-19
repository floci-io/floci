package io.floci.aws.services.ses;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SesNamesTest {

    @Test
    void hasOnlyAllowedCharacters_acceptsAsciiLettersDigitsUnderscoreAndHyphen() {
        assertTrue(SesNames.hasOnlyAllowedCharacters("Name_probe-09"));
        assertFalse(SesNames.hasOnlyAllowedCharacters("name probe"));
        assertFalse(SesNames.hasOnlyAllowedCharacters("name.probe"));
        assertFalse(SesNames.hasOnlyAllowedCharacters("name-probé"));
        assertFalse(SesNames.hasOnlyAllowedCharacters(""));
    }

    @Test
    void isValid_appliesTheLengthAndTheCharacters() {
        assertTrue(SesNames.isValid("a".repeat(64)));
        assertFalse(SesNames.isValid("a".repeat(65)));
        assertFalse(SesNames.isValid("a b"));
        assertFalse(SesNames.isValid(""));
    }

    @Test
    void requireMaxLength_refusesTheSixtyFifthCharacterWithTheCallersWording() {
        assertDoesNotThrow(() -> SesNames.requireMaxLength("a".repeat(64), "BadRequestException", "TenantName"));

        AwsException e = assertThrows(AwsException.class,
                () -> SesNames.requireMaxLength("a".repeat(65), "BadRequestException", "TenantName"));
        assertEquals("BadRequestException", e.getErrorCode());
        assertEquals("TenantName cannot exceed 64 characters.", e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void requireAllowedCharacters_quotesTheNameAfterTheCallersWording() {
        assertDoesNotThrow(
                () -> SesNames.requireAllowedCharacters("a_b-1", "InvalidParameterValue", "Template name"));

        AwsException e = assertThrows(AwsException.class,
                () -> SesNames.requireAllowedCharacters("a b", "InvalidParameterValue", "Template name"));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("Template name <a b>: only alphanumeric ASCII characters, '_', and '-' are allowed.",
                e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }
}
