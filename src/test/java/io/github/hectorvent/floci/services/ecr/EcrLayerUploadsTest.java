package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcrLayerUploadsTest {

    private static final String SCOPE = "us-east-1::000000000000::app";
    private static final long MIB = 1024 * 1024;

    @Test
    void aPartMustStartAtTheNextByteAndMatchItsRange() {
        EcrLayerUploads uploads = new EcrLayerUploads(location -> { });
        EcrLayerUploads.Session session = uploads.require(SCOPE, uploads.open(SCOPE, "http://r/u1"), "app");

        assertDoesNotThrow(() -> check(session, 0, 9, 10));
        session.accept(10, "http://r/u2", Instant.now());

        AwsException gap = assertThrows(AwsException.class, () -> check(session, 11, 20, 10));
        assertEquals("InvalidLayerPartException", gap.getErrorCode());
        assertEquals(9L, gap.getExtendedData().get("lastValidByteReceived"));
        assertEquals("app", gap.getExtendedData().get("repositoryName"));

        assertEquals("InvalidLayerPartException",
                assertThrows(AwsException.class, () -> check(session, 0, 9, 10)).getErrorCode());
        assertEquals("InvalidLayerPartException",
                assertThrows(AwsException.class, () -> check(session, 10, 19, 9)).getErrorCode());
        assertEquals("InvalidLayerPartException",
                assertThrows(AwsException.class, () -> check(session, 10, 9, 0)).getErrorCode());
        assertDoesNotThrow(() -> check(session, 10, 19, 10));
    }

    @Test
    void aPartOver20MiBIsRefused() {
        EcrLayerUploads uploads = new EcrLayerUploads(location -> { });
        EcrLayerUploads.Session session = uploads.require(SCOPE, uploads.open(SCOPE, "http://r/u"), "app");
        int tooBig = (int) (EcrLayerUploads.MAX_PART_SIZE + 1);

        assertEquals("InvalidLayerPartException",
                assertThrows(AwsException.class, () -> check(session, 0, tooBig - 1, tooBig)).getErrorCode());
        int largest = (int) EcrLayerUploads.MAX_PART_SIZE;
        assertDoesNotThrow(() -> check(session, 0, largest - 1, largest));
    }

    @Test
    void completionNeedsAPartAndEveryPartButTheLastAtLeast5MiB() {
        EcrLayerUploads uploads = new EcrLayerUploads(location -> { });
        EcrLayerUploads.Session empty = uploads.require(SCOPE, uploads.open(SCOPE, "http://r/a"), "app");
        assertEquals("EmptyUploadException",
                assertThrows(AwsException.class, () -> EcrLayerUploads.checkComplete(empty)).getErrorCode());

        EcrLayerUploads.Session small = uploads.require(SCOPE, uploads.open(SCOPE, "http://r/b"), "app");
        small.accept(5 * MIB - 1, "http://r/b2", Instant.now());
        small.accept(10, "http://r/b3", Instant.now());
        assertEquals("LayerPartTooSmallException",
                assertThrows(AwsException.class, () -> EcrLayerUploads.checkComplete(small)).getErrorCode());

        EcrLayerUploads.Session ok = uploads.require(SCOPE, uploads.open(SCOPE, "http://r/c"), "app");
        ok.accept(5 * MIB, "http://r/c2", Instant.now());
        ok.accept(10, "http://r/c3", Instant.now());
        assertDoesNotThrow(() -> EcrLayerUploads.checkComplete(ok));

        EcrLayerUploads.Session single = uploads.require(SCOPE, uploads.open(SCOPE, "http://r/d"), "app");
        single.accept(10, "http://r/d2", Instant.now());
        assertDoesNotThrow(() -> EcrLayerUploads.checkComplete(single));
    }

    @Test
    void anUploadBelongsToItsRepositoryAndEndsWhenClosed() {
        EcrLayerUploads uploads = new EcrLayerUploads(location -> { });
        String id = uploads.open(SCOPE, "http://r/u");

        assertEquals("UploadNotFoundException", assertThrows(AwsException.class,
                () -> uploads.require("us-east-1::000000000000::other", id, "other")).getErrorCode());
        assertEquals("UploadNotFoundException", assertThrows(AwsException.class,
                () -> uploads.require(SCOPE, "6a1f2b3c-0000-4000-8000-000000000000", "app")).getErrorCode());
        assertEquals("http://r/u", uploads.require(SCOPE, id, "app").location());

        uploads.close(id);
        assertEquals("UploadNotFoundException",
                assertThrows(AwsException.class, () -> uploads.require(SCOPE, id, "app")).getErrorCode());
    }

    @Test
    void anUploadIdleForADayIsDroppedWhenTheNextOneOpens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
        List<String> discarded = new ArrayList<>();
        EcrLayerUploads uploads = new EcrLayerUploads(clock, discarded::add);
        String stale = uploads.open(SCOPE, "http://r/stale");
        String touched = uploads.open(SCOPE, "http://r/touched");

        clock.advance(Duration.ofHours(23));
        uploads.require(SCOPE, touched, "app").accept(10, "http://r/touched2", clock.instant());
        clock.advance(Duration.ofHours(2));
        uploads.open(SCOPE, "http://r/new");

        assertEquals("UploadNotFoundException",
                assertThrows(AwsException.class, () -> uploads.require(SCOPE, stale, "app")).getErrorCode());
        assertEquals("http://r/touched2", uploads.require(SCOPE, touched, "app").location());
        assertEquals(List.of("http://r/stale"), discarded);
    }

    @Test
    void anUploadIdleForADayIsRefusedEvenWhenNoOtherOpens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
        List<String> discarded = new ArrayList<>();
        EcrLayerUploads uploads = new EcrLayerUploads(clock, discarded::add);
        String id = uploads.open(SCOPE, "http://r/idle");

        clock.advance(Duration.ofHours(25));

        assertEquals("UploadNotFoundException",
                assertThrows(AwsException.class, () -> uploads.require(SCOPE, id, "app")).getErrorCode());
        assertEquals(List.of("http://r/idle"), discarded);
    }

    private static void check(EcrLayerUploads.Session session, long first, long last, int length) {
        EcrLayerUploads.checkPart(session, first, last, length, "000000000000", "app", "upload");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
