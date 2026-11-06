package io.floci.aws.services.lambdaweb;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LambdaWebOutputLinesTest {

    private static final int MAX = LambdaWebOutputLines.MAX_EVENT_BYTES;

    @Test
    void eachLineIsAnEventWithItsNewline() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();

        assertEquals(List.of("one\n", "two\n"), lines.append(bytes("one\ntwo\nthr")));
        assertEquals(List.of("three\n"), lines.append(bytes("ee\n")));
        assertEquals(List.of(), lines.flush());
    }

    @Test
    void theOutputAfterTheLastNewlineComesOutWhenTheStreamEnds() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();

        assertEquals(List.of(), lines.append(bytes("no newline")));
        assertEquals(List.of("no newline"), lines.flush());
    }

    @Test
    void aLongLineIsCutBeforeTheCharacterThatWouldPassTheLimit() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();

        assertEquals(List.of("y".repeat(MAX - 2), "日tail\n"), lines.append(bytes("y".repeat(MAX - 2) + "日tail\n")));
        assertEquals(List.of("z".repeat(MAX), "z\n"), lines.append(bytes("z".repeat(MAX + 1) + "\n")));
    }

    @Test
    void bytesThatAreNotUtf8BecomeReplacementCharactersCountedAtTheirEncodedSize() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();

        assertEquals(List.of("a�b��c\n"),
                lines.append(concat(bytes("a"), new byte[] {(byte) 0x80}, bytes("b"),
                        new byte[] {(byte) 0xFF, (byte) 0xFE}, bytes("c\n"))));
        assertEquals(List.of("cut � end\n"),
                lines.append(concat(bytes("cut "), new byte[] {(byte) 0xE6, (byte) 0x97}, bytes(" end\n"))));
        assertEquals(List.of("x".repeat(MAX - 1), "����tail\n"),
                lines.append(concat(bytes("x".repeat(MAX - 1)), new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80}, bytes("tail\n"))));
    }

    @Test
    void aCharacterSplitAcrossPayloadsStaysWhole() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();
        byte[] word = bytes("日本\n");

        assertEquals(List.of(), lines.append(new byte[] {word[0], word[1]}));
        assertEquals(List.of("日本\n"), lines.append(Arrays.copyOfRange(word, 2, word.length)));
    }

    @Test
    void anUnfinishedCharacterAtTheEndBecomesAReplacementCharacter() {
        LambdaWebOutputLines lines = new LambdaWebOutputLines();

        assertEquals(List.of(), lines.append(new byte[] {'a', (byte) 0xE6, (byte) 0x97}));
        assertEquals(List.of("a�"), lines.flush());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
