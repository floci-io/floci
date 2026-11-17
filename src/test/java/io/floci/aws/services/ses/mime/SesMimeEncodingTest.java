package io.floci.aws.services.ses.mime;

import io.floci.aws.services.ses.mime.SesMimeEncoding.TransferEncoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected values marked "probed" are what SES returned from TestRenderTemplate on us-west-2 (2026-11-17);
// the other cases follow from the same rules.
class SesMimeEncodingTest {

    private static final String JAPANESE = "日本語のテスト";

    @ParameterizedTest(name = "{0}")
    @MethodSource("transferEncodingCases")
    void transferEncoding_followsTheShareOfAsciiOctets(String label, String body, String expected) {
        assertEquals(expected, TransferEncoding.of(body.getBytes(StandardCharsets.UTF_8)).headerValue());
    }

    static Stream<Arguments> transferEncodingCases() {
        return Stream.of(
                Arguments.of("empty", "", "7bit"),
                Arguments.of("probed: ASCII", "esc=plain raw=plain", "7bit"),
                Arguments.of("probed: ASCII with an equals sign and trailing spaces", "esc=a=b   raw=a=b  ", "7bit"),
                Arguments.of("probed: ASCII with a bare LF", "esc=line1\nline2", "7bit"),
                Arguments.of("probed: 609-octet ASCII line",
                        "esc=" + "abcdefghi ".repeat(30) + " raw=" + "abcdefghi ".repeat(30), "7bit"),
                Arguments.of("probed: 1000-octet ASCII line", "a".repeat(1000), "quoted-printable"),
                Arguments.of("probed: 17 ASCII octets against 16", "esc=café-日本 raw=café-日本", "quoted-printable"),
                Arguments.of("probed: emoji in ASCII text", "esc=ok 😀 done raw=ok 😀 done", "quoted-printable"),
                Arguments.of("probed: mostly non-ASCII", "esc=" + JAPANESE.repeat(20), "base64"),
                Arguments.of("probed: a 3:3 tie", "abcあ", "base64"),
                Arguments.of("probed: 4 ASCII octets against 3", "abcdあ", "quoted-printable"),
                Arguments.of("probed: a 30:30 tie", "a".repeat(30) + "あ".repeat(10), "base64"),
                Arguments.of("probed: 31 against 30", "a".repeat(31) + "あ".repeat(10), "quoted-printable"),
                Arguments.of("probed: 998-octet line", "a".repeat(998), "7bit"),
                Arguments.of("probed: 999-octet line", "a".repeat(999), "quoted-printable"),
                Arguments.of("probed: 998-octet lines", "a".repeat(998) + "\n" + "a".repeat(998), "7bit"),
                Arguments.of("probed: control character counts as non-ASCII", "a\u0001b", "quoted-printable"),
                Arguments.of("probed: control characters in the majority", "\u0001\u0001a", "base64"),
                Arguments.of("probed: DEL counts as non-ASCII", "ab\u007f", "quoted-printable"),
                Arguments.of("probed: tab and a bare CR are ASCII", "a\tb\rc", "7bit")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quotedPrintableCases")
    void quotedPrintable_encodes(String label, String body, String expected) {
        assertEquals(expected, SesMimeEncoding.quotedPrintable(body.getBytes(StandardCharsets.UTF_8)));
    }

    static Stream<Arguments> quotedPrintableCases() {
        return Stream.of(
                Arguments.of("probed: text part",
                        "esc=café-日本 raw=café-日本",
                        "esc=3Dcaf=C3=A9-=E6=97=A5=E6=9C=AC raw=3Dcaf=C3=A9-=E6=97=A5=E6=9C=AC"),
                Arguments.of("probed: soft break after 75 characters",
                        "<p>esc=café-日本 raw=café-日本</p>",
                        "<p>esc=3Dcaf=C3=A9-=E6=97=A5=E6=9C=AC raw=3Dcaf=C3=A9-=E6=97=A5=E6=9C=AC</p=\r\n>"),
                Arguments.of("probed: emoji",
                        "esc=ok 😀 done",
                        "esc=3Dok =F0=9F=98=80 done"),
                Arguments.of("probed: control character and DEL", "a\u0001b\u007f", "a=01b=7F"),
                Arguments.of("probed: space before a line break, tab, trailing space",
                        "café x \nb\tc and some more ascii text ",
                        "caf=C3=A9 x=20\r\nb=09c and some more ascii text=20"),
                Arguments.of("probed: long line breaks after 75 characters",
                        "a".repeat(160), "a".repeat(75) + "=\r\n" + "a".repeat(75) + "=\r\n" + "a".repeat(10)),
                Arguments.of("bare CR and LF become CRLF", "é\rb\nc\r\nd", "=C3=A9\r\nb\r\nc\r\nd")
        );
    }

    @Test
    void quotedPrintable_escapeNeverStraddlesASoftBreak() {
        String encoded = SesMimeEncoding.quotedPrintable(("a".repeat(74) + "é").getBytes(StandardCharsets.UTF_8));
        assertEquals("a".repeat(74) + "=\r\n=C3=A9", encoded);
    }

    @Test
    void base64_wrapsAt76Characters() {
        String body = "esc=" + JAPANESE.repeat(20);
        String encoded = TransferEncoding.BASE64.encode(body.getBytes(StandardCharsets.UTF_8));
        String[] lines = encoded.split("\r\n");
        // probed: the first line of the text part
        assertEquals("ZXNjPeaXpeacrOiqnuOBruODhuOCueODiOaXpeacrOiqnuOBruODhuOCueODiOaXpeacrOiqnuOB", lines[0]);
        for (int i = 0; i < lines.length - 1; i++) {
            assertEquals(76, lines[i].length());
        }
        assertFalse(encoded.endsWith("\r\n"));
        assertEquals(body, new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8));
    }

    // probed with 57 and 114 octets: a full last line keeps its CRLF
    @Test
    void base64_fullLastLineEndsInCrlf() {
        assertEquals("44GC".repeat(19) + "\r\n",
                SesMimeEncoding.base64Lines("あ".repeat(19).getBytes(StandardCharsets.UTF_8)));
        assertEquals(("44GC".repeat(19) + "\r\n").repeat(2),
                SesMimeEncoding.base64Lines("あ".repeat(38).getBytes(StandardCharsets.UTF_8)));
        assertEquals("44GC".repeat(19) + "\r\n44GC",
                SesMimeEncoding.base64Lines("あ".repeat(20).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sevenBit_isLeftAlone() {
        assertEquals("line1\nline2 a=b  ",
                TransferEncoding.SEVEN_BIT.encode("line1\nline2 a=b  ".getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjectCases")
    void encodeSubject_encodes(String label, String subject, String expected) {
        assertEquals(expected, SesMimeEncoding.encodeSubject(subject));
    }

    static Stream<Arguments> subjectCases() {
        return Stream.of(
                Arguments.of("probed: ASCII", "S plain", "S plain"),
                Arguments.of("probed: ASCII with markup", "S <b>&\"x\"'y'</b>", "S <b>&\"x\"'y'</b>"),
                Arguments.of("probed: ASCII with an equals sign, trailing spaces dropped", "S a=b  ", "S a=b"),
                Arguments.of("probed: mostly ASCII is a Q word",
                        "S café-日本", "=?UTF-8?Q?S_caf=C3=A9-=E6=97=A5=E6=9C=AC?="),
                Arguments.of("probed: emoji", "S ok 😀 done", "=?UTF-8?Q?S_ok_=F0=9F=98=80_done?="),
                Arguments.of("Q word escapes its own specials", "a=b_c?d é", "=?UTF-8?Q?a=3Db=5Fc=3Fd_=C3=A9?="),
                Arguments.of("probed: a 2:2 tie is a B word", "ab日本", "=?UTF-8?B?YWLml6XmnKw=?="),
                Arguments.of("probed: 3 ASCII characters against 2 is a Q word", "abc日本", "=?UTF-8?Q?abc=E6=97=A5=E6=9C=AC?="),
                Arguments.of("probed: a control character makes a Q word", "a\u0001b", "=?UTF-8?Q?a=01b?="),
                Arguments.of("probed: long ASCII folds at whitespace",
                        "S " + "abcdefghi ".repeat(30),
                        "S abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi\r\n"
                                + " abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi\r\n"
                                + " abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi\r\n"
                                + " abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi\r\n"
                                + " abcdefghi abcdefghi abcdefghi"),
                Arguments.of("probed: long ASCII without whitespace is not folded", "a".repeat(1000), "a".repeat(1000)),
                Arguments.of("probed: short neighbouring words share a line",
                        "日本語".repeat(4) + "abcdefghijkl".repeat(2) + "日本語".repeat(4),
                        "=?UTF-8?B?5pel5pys6Kqe5pel5pys6Kqe5pel5pys6Kqe5pel5pys6Kqe?=\r\n"
                                + " =?UTF-8?B?YWJjZGVmZ2hpamts?= =?UTF-8?B?YWJjZGVmZ2hpamts?=\r\n"
                                + " =?UTF-8?B?5pel5pys6Kqe5pel5pys6Kqe5pel5pys6Kqe5pel5pys6Kqe?="),
                Arguments.of("probed: a Q word keeps its trailing space",
                        "café x b c and some more ascii text ",
                        "=?UTF-8?Q?caf=C3=A9_x_b_c_and_some_more_ascii_text_?="),
                Arguments.of("probed: long non-ASCII is halved into B words",
                        "S " + JAPANESE.repeat(20),
                        "=?UTF-8?B?UyDml6XmnKzoqp7jga7jg4bjgrk=?=\r\n"
                                + " =?UTF-8?B?44OI5pel5pys6Kqe44Gu44OG44K544OI5pel?=\r\n"
                                + " =?UTF-8?B?5pys6Kqe44Gu44OG44K544OI5pel5pys6Kqe?=\r\n"
                                + " =?UTF-8?B?44Gu44OG44K544OI5pel5pys6Kqe44Gu44OG?=\r\n"
                                + " =?UTF-8?B?44K544OI5pel5pys6Kqe44Gu44OG44K544OI?=\r\n"
                                + " =?UTF-8?B?5pel5pys6Kqe44Gu44OG44K544OI5pel5pys?=\r\n"
                                + " =?UTF-8?B?6Kqe44Gu44OG44K544OI5pel5pys6Kqe44Gu?=\r\n"
                                + " =?UTF-8?B?44OG44K544OI5pel5pys6Kqe44Gu44OG44K5?=\r\n"
                                + " =?UTF-8?B?44OI5pel5pys6Kqe44Gu44OG44K544OI?=\r\n"
                                + " =?UTF-8?B?5pel5pys6Kqe44Gu44OG44K544OI5pel5pys?=\r\n"
                                + " =?UTF-8?B?6Kqe44Gu44OG44K544OI5pel5pys6Kqe44Gu?=\r\n"
                                + " =?UTF-8?B?44OG44K544OI5pel5pys6Kqe44Gu44OG44K5?=\r\n"
                                + " =?UTF-8?B?44OI5pel5pys6Kqe44Gu44OG44K544OI5pel?=\r\n"
                                + " =?UTF-8?B?5pys6Kqe44Gu44OG44K544OI5pel5pys6Kqe?=\r\n"
                                + " =?UTF-8?B?44Gu44OG44K544OI5pel5pys6Kqe44Gu44OG?=\r\n"
                                + " =?UTF-8?B?44K544OI5pel5pys6Kqe44Gu44OG44K544OI?=")
        );
    }

    @Test
    void encodeSubject_neverSplitsASurrogatePair() {
        String encoded = SesMimeEncoding.encodeSubject("😀".repeat(40));
        for (String word : encoded.split("\r\n | ")) {
            String payload = word.substring("=?UTF-8?B?".length(), word.length() - 2);
            String decoded = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
            assertEquals("😀".repeat(decoded.length() / 2), decoded);
            assertTrue(word.length() <= 75);
        }
    }
}
