package io.github.hectorvent.floci.services.ses;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the v1 Query handler's XML 1.0 sanitization, which keeps the TestRenderTemplate
 * response parseable by SDK XML parsers whatever the rendered template contains.
 */
class SesQueryHandlerXmlSanitizationTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("stripXml10InvalidCharsCases")
    void stripXml10InvalidChars_returnsExpected(String label, String input, String expected) {
        assertEquals(expected, SesQueryHandler.stripXml10InvalidChars(input));
    }

    static Stream<Arguments> stripXml10InvalidCharsCases() {
        // U+1F600 GRINNING FACE encoded as surrogate pair D83D DE00
        String emoji = "\uD83D\uDE00";
        return Stream.of(
                Arguments.of("keeps tab/LF/CR",          "a\tb\nc\rd",        "a\tb\nc\rd"),
                Arguments.of("removes C0 SOH/US",        "a\u0001b\u001fc",   "abc"),
                Arguments.of("removes BS",               "a\u0008b",            "ab"),
                Arguments.of("removes VT",               "a\u000bb",            "ab"),
                Arguments.of("removes FF",               "a\u000cb",            "ab"),
                Arguments.of("preserves Unicode",        "件名 太郎",            "件名 太郎"),
                Arguments.of("removes noncharacter FFFE","a\ufffeb",            "ab"),
                Arguments.of("removes noncharacter FFFF","a\uffffb",            "ab"),
                Arguments.of("removes lone high surrogate", "a\ud800b",         "ab"),
                Arguments.of("removes lone low surrogate",  "a\udc00b",         "ab"),
                Arguments.of("preserves paired surrogate (emoji)", "a" + emoji + "b", "a" + emoji + "b")
        );
    }
}
