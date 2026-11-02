package io.github.hectorvent.floci.services.elbv2;

import io.vertx.core.MultiMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The {@code X-Amzn-Trace-Id} header an Application Load Balancer adds or updates before it sends a
 * request to a target. Each field has the form {@code Field=1-<8 hex epoch seconds>-<24 hex id>}.
 */
final class ElbV2TraceHeader {

    static final String NAME = "X-Amzn-Trace-Id";

    private static final int REWRITE_ABOVE_BYTES = 7 * 1024;
    private static final String ROOT = "Root=";
    private static final String SELF = "Self=";

    private ElbV2TraceHeader() {
    }

    static void update(MultiMap headers) {
        String received = String.join(";", headers.getAll(NAME));
        headers.set(NAME, next(received, headerBytes(headers) > REWRITE_ABOVE_BYTES));
    }

    static String next(String received, boolean oversized) {
        List<String> fields = new ArrayList<>();
        if (!oversized) {
            for (String field : received.split(";")) {
                if (!field.isBlank()) {
                    fields.add(field.trim());
                }
            }
        }
        boolean hasRoot = false;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).startsWith(SELF)) {
                fields.set(i, SELF + traceId());
                return String.join(";", fields);
            }
            hasRoot |= fields.get(i).startsWith(ROOT);
        }
        fields.addFirst(hasRoot ? SELF + traceId() : ROOT + traceId());
        return String.join(";", fields);
    }

    private static int headerBytes(MultiMap headers) {
        int total = 0;
        for (Map.Entry<String, String> header : headers) {
            total += header.getKey().length() + header.getValue().length();
        }
        return total;
    }

    private static String traceId() {
        byte[] id = new byte[12];
        ThreadLocalRandom.current().nextBytes(id);
        return "1-" + String.format("%08x", Instant.now().getEpochSecond()) + "-" + HexFormat.of().formatHex(id);
    }
}
