package io.floci.aws.services.ses;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SesQueryHandlerTest {

    @Test
    void spellOutControlCharacters_leavesAMessageWithoutThemAlone() {
        String message = "Template name <né 😀 \uFFFD>: not allowed.";

        assertSame(message, SesQueryHandler.spellOutControlCharacters(message));
        assertNull(SesQueryHandler.spellOutControlCharacters(null));
    }

    @Test
    void spellOutControlCharacters_writesAControlCharacterAsHex() {
        assertEquals("a0x0009b0x000ac0x000dd0x0001",
                SesQueryHandler.spellOutControlCharacters("a\tb\nc\rd\u0001"));
    }

    @Test
    void spellOutControlCharacters_writesTheNonCharactersAsHex() {
        assertEquals("a0xfffeb0xffff", SesQueryHandler.spellOutControlCharacters("a\uFFFEb\uFFFF"));
    }

    // a surrogate pair is one character XML allows; half of one is not
    @Test
    void spellOutControlCharacters_writesASurrogateWithoutItsPairAsHex() {
        assertEquals("0xde00a0xde010xd83d😀0xd83d",
                SesQueryHandler.spellOutControlCharacters("\uDE00a\uDE01\uD83D😀\uD83D"));
    }
}
