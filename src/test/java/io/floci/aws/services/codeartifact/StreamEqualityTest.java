package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamEqualityTest {

    @Test
    void contentsMatchIsTrueForByteIdenticalStreams() throws IOException {
        byte[] content = new byte[64 * 1024 + 37];
        new Random(7).nextBytes(content);

        boolean matches = StreamEquality.contentsMatch(new ByteArrayInputStream(content),
                new ByteArrayInputStream(content.clone()));

        assertTrue(matches);
    }

    @Test
    void contentsMatchIsFalseWhenOneStreamIsShorter() throws IOException {
        byte[] longer = new byte[1000];
        byte[] shorter = new byte[900];

        boolean matches = StreamEquality.contentsMatch(new ByteArrayInputStream(longer),
                new ByteArrayInputStream(shorter));

        assertFalse(matches);
    }

    @Test
    void contentsMatchIsFalseOnASingleByteDifferenceFarPastTheFirstBuffer() throws IOException {
        byte[] a = new byte[200_000];
        byte[] b = a.clone();
        b[150_000] = (byte) (b[150_000] + 1);

        boolean matches = StreamEquality.contentsMatch(new ByteArrayInputStream(a), new ByteArrayInputStream(b));

        assertFalse(matches);
    }

    @Test
    void contentsMatchIsTrueForTwoEmptyStreams() throws IOException {
        boolean matches = StreamEquality.contentsMatch(new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0]));

        assertTrue(matches);
    }
}
