package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XmlBuilderTest {

    private static String forbiddenIndexes(String s) {
        return IntStream.range(0, s.length())
                .filter(i -> XmlBuilder.isForbiddenAt(s, i))
                .mapToObj(Integer::toString)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    @Test
    void isForbiddenAt_allowsTabLineFeedAndCarriageReturn() {
        assertEquals("", forbiddenIndexes("a\tb\nc\rd né \uFFFD"));
    }

    @Test
    void isForbiddenAt_flagsOtherControlCharactersAndTheTwoNoncharacters() {
        assertEquals("1,3,5,7", forbiddenIndexes("a\u0000b\u001bc\uFFFEd\uFFFF"));
    }

    @Test
    void isForbiddenAt_flagsOnlyASurrogateWithoutItsPair() {
        assertEquals("0,2,5", forbiddenIndexes("\uDE00a\uD83D😀\uD83D"));
    }
}
