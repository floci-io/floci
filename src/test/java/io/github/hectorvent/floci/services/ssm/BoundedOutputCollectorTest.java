package io.github.hectorvent.floci.services.ssm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedOutputCollectorTest {

    @Test
    void feedingMoreThanCapYieldsExactlyFirstNChars() {
        BoundedOutputCollector collector = new BoundedOutputCollector(10);

        collector.write("abcde".getBytes(StandardCharsets.UTF_8));
        collector.write("fghijKLMNOP".getBytes(StandardCharsets.UTF_8));

        assertEquals("abcdefghij", collector.content());
    }

    @Test
    void additionalWritesAfterCapAreDiscardedWithoutGrowingContent() {
        BoundedOutputCollector collector = new BoundedOutputCollector(5);

        collector.write("abcde".getBytes(StandardCharsets.UTF_8));
        collector.write("this is a lot more text that should be fully discarded".getBytes(StandardCharsets.UTF_8));
        collector.write("even more after that".getBytes(StandardCharsets.UTF_8));

        assertEquals("abcde", collector.content());
    }

    @Test
    void multiByteUtf8CharacterSplitAcrossWritesDecodesCorrectly() {
        BoundedOutputCollector collector = new BoundedOutputCollector(10);
        byte[] euroSign = "€".getBytes(StandardCharsets.UTF_8); // 0xE2 0x82 0xAC

        collector.write(new byte[] {euroSign[0], euroSign[1]});
        collector.write(new byte[] {euroSign[2]});
        collector.write("X".getBytes(StandardCharsets.UTF_8));

        assertEquals("€X", collector.content());
    }

    @Test
    void multiByteCharacterCompletingExactlyAtCapIsIncludedWithoutCorruption() {
        BoundedOutputCollector collector = new BoundedOutputCollector(3);
        byte[] euroSign = "€".getBytes(StandardCharsets.UTF_8);

        collector.write("ab".getBytes(StandardCharsets.UTF_8));
        collector.write(new byte[] {euroSign[0], euroSign[1]});
        collector.write(new byte[] {euroSign[2]});

        assertEquals("ab€", collector.content());
    }

    @Test
    void capFallingInsideSurrogatePairKeepsOnlyWholeCharacters() {
        BoundedOutputCollector collector = new BoundedOutputCollector(3);

        collector.write("ab\uD83D\uDE00".getBytes(StandardCharsets.UTF_8));

        assertEquals("ab", collector.content());
    }

    @Test
    void outputEndingMidCharacterShowsReplacementCharacter() {
        BoundedOutputCollector collector = new BoundedOutputCollector(10);
        byte[] euroSign = "€".getBytes(StandardCharsets.UTF_8);

        collector.write("X".getBytes(StandardCharsets.UTF_8));
        collector.write(new byte[] {euroSign[0], euroSign[1]});

        assertEquals("X\uFFFD", collector.content());
    }
}
