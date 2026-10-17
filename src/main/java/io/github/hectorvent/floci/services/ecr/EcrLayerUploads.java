package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The layer uploads opened by {@code InitiateLayerUpload}, each tied to the {@code registry:2}
 * upload session that holds its bytes. Sessions live in memory, as the registry's own do, and one
 * left idle for a day is dropped, along with its registry upload session.
 */
class EcrLayerUploads {

    /** The part size {@code InitiateLayerUpload} reports. */
    static final long PART_SIZE = 10L * 1024 * 1024;
    /** The largest part {@code UploadLayerPart} takes, as the API reference gives it. */
    static final long MAX_PART_SIZE = 20L * 1024 * 1024;
    /** The smallest part other than the last, as {@code LayerPartTooSmallException} gives it. */
    static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    static final Duration IDLE_LIMIT = Duration.ofDays(1);

    /** One open upload: the repository it belongs to, the registry URL for its next chunk and its parts so far. */
    static final class Session {
        private final String scope;
        private String location;
        private long received;
        private final List<Long> partSizes = new ArrayList<>();
        private Instant lastTouched;

        Session(String scope, String location, Instant now) {
            this.scope = scope;
            this.location = location;
            this.lastTouched = now;
        }

        String location() {
            return location;
        }

        long received() {
            return received;
        }

        List<Long> partSizes() {
            return partSizes;
        }

        /** Records a part the registry accepted, and the URL it gave for the next one. */
        void accept(long size, String nextLocation, Instant now) {
            partSizes.add(size);
            received += size;
            location = nextLocation;
            lastTouched = now;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    /** Cancels a dropped session's registry upload, given its location. */
    private final Consumer<String> discard;

    EcrLayerUploads(Consumer<String> discard) {
        this(Clock.systemUTC(), discard);
    }

    EcrLayerUploads(Clock clock, Consumer<String> discard) {
        this.clock = clock;
        this.discard = discard;
    }

    Instant now() {
        return clock.instant();
    }

    /** Opens an upload for the repository {@code scope} names, returning its upload ID. */
    String open(String scope, String location) {
        Instant now = clock.instant();
        sessions.forEach((id, session) -> {
            if (idle(session, now)) {
                drop(id, session);
            }
        });
        String uploadId = UUID.randomUUID().toString();
        sessions.put(uploadId, new Session(scope, location, now));
        return uploadId;
    }

    /**
     * The open upload {@code uploadId} names in this repository, or {@code UploadNotFoundException},
     * which is also the answer for one idle for a day even if no other upload has opened since.
     */
    Session require(String scope, String uploadId, String repositoryName) {
        Session session = uploadId == null ? null : sessions.get(uploadId);
        if (session != null && idle(session, clock.instant())) {
            drop(uploadId, session);
            session = null;
        }
        if (session == null || !session.scope.equals(scope)) {
            throw new AwsException("UploadNotFoundException",
                    "Upload with id '" + uploadId + "' is not found for repository '" + repositoryName + "'", 400);
        }
        return session;
    }

    void close(String uploadId) {
        sessions.remove(uploadId);
    }

    private static boolean idle(Session session, Instant now) {
        return session.lastTouched.plus(IDLE_LIMIT).isBefore(now);
    }

    private void drop(String uploadId, Session session) {
        if (sessions.remove(uploadId, session)) {
            discard.accept(session.location);
        }
    }

    /**
     * A part must follow the last byte received, match its stated range and fit the size limit.
     * The error carries the upload and its last valid byte, as {@code InvalidLayerPartException} does.
     */
    static void checkPart(Session session, long firstByte, long lastByte, int length,
                          String registryId, String repositoryName, String uploadId) {
        long expected = session.received;
        String message = null;
        if (firstByte != expected) {
            message = "The part's first byte " + firstByte + " is not consecutive to the last byte "
                    + "received; the next part must start at byte " + expected + ".";
        } else if (length == 0 || lastByte - firstByte + 1 != length) {
            message = "The part holds " + length + " bytes, but the range " + firstByte + "-" + lastByte
                    + " names " + Math.max(0, lastByte - firstByte + 1) + ".";
        } else if (length > MAX_PART_SIZE) {
            message = "A layer part can be at most " + MAX_PART_SIZE + " bytes.";
        }
        if (message != null) {
            throw new AwsException("InvalidLayerPartException", message, 400, Map.of(
                    "registryId", registryId,
                    "repositoryName", repositoryName,
                    "uploadId", uploadId,
                    "lastValidByteReceived", Math.max(0, expected - 1)));
        }
    }

    /** An upload completes only with at least one part, and every part but the last at least 5 MiB. */
    static void checkComplete(Session session) {
        List<Long> parts = session.partSizes;
        if (parts.isEmpty()) {
            throw new AwsException("EmptyUploadException", "The layer upload does not contain any layer parts.", 400);
        }
        for (int i = 0; i < parts.size() - 1; i++) {
            if (parts.get(i) < MIN_PART_SIZE) {
                throw new AwsException("LayerPartTooSmallException", "Layer parts must be at least 5 MiB in size.", 400);
            }
        }
    }
}
