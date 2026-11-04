package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3ServiceSequencerTest {

    @Test
    void toSequencerMatchesFormattedValue() {
        long[] values = {0, 1, 15, 16, 255, 0xABCDEF, System.currentTimeMillis() * 1000, Long.MAX_VALUE, -1,
                Long.MIN_VALUE};
        for (long value : values) {
            assertEquals(String.format(Locale.ROOT, "%018X", value), S3Service.toSequencer(value));
        }
    }
}
