package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads an npm publish envelope as a stream and keeps only what the republish check, plus
 * {@code GetPackageVersionReadme}'s own capture-at-publish-time accounting, needs. Jackson
 * decodes each attachment's base64 {@code data} straight into a SHA-512 digest, so the tarball is
 * never held in memory. Any structure this scanner does not expect throws, and callers treat a
 * throw as not confirmable and forward the request unchanged.
 */
final class NpmPublishEnvelopeScanner {

    /**
     * {@code readme} is each version's own, exactly as a real npm client sends it (confirmed
     * against a real captured publish fixture): a real {@code npm publish} always sends one, even
     * when the package has no README.md at all ({@code "ERROR: No README data found!"}, npm's own
     * literal placeholder text, not an absent field). {@code null} only for a client that omits
     * the field outright. Deliberately not sourced from Verdaccio's own packument later: confirmed
     * directly against a live Verdaccio container that it hoists {@code readme} to the top level
     * only (the latest published version, truncated to 64K, matching real npmjs.org's own
     * documented response shape) and drops the per-version field entirely, so this is the only
     * point where a specific version's own readme is still available at all.
     */
    record Version(String tarball, String integrity, String readme) {}

    /** {@code size} is the attachment's real, decoded byte count, counted alongside its digest. */
    record Attachment(String integrity, long size) {}

    record Envelope(String name, Map<String, String> distTags, Map<String, Version> versions,
            Map<String, Attachment> attachments) {}

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(32)
                    .maxNumberLength(1_000_000)
                    .maxStringLength(1_000_000)
                    .build())
            .build();

    @FunctionalInterface
    private interface FieldHandler {
        void accept(String fieldName) throws IOException;
    }

    private final JsonParser parser;
    private String name;
    private final Map<String, String> distTags = new HashMap<>();
    private final Map<String, Map<String, String>> versionFields = new HashMap<>();
    private final Map<String, Attachment> attachments = new HashMap<>();

    private NpmPublishEnvelopeScanner(JsonParser parser) {
        this.parser = parser;
    }

    static Envelope scan(InputStream in) throws IOException {
        try (JsonParser parser = FACTORY.createParser(in)) {
            NpmPublishEnvelopeScanner scanner = new NpmPublishEnvelopeScanner(parser);
            scanner.parseEnvelope();
            return scanner.envelope();
        }
    }

    private void parseEnvelope() throws IOException {
        parser.nextToken();
        requireToken(JsonToken.START_OBJECT);
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String key = parser.currentName();
            parser.nextToken();
            switch (key) {
                case "name" -> name = stringValue();
                case "dist-tags" -> forEachField(tag -> distTags.put(tag, stringValue()));
                case "versions" -> forEachField(this::readVersion);
                case "_attachments" -> forEachField(filename ->
                        forEachField(attachmentField -> {
                            if (attachmentField.equals("data")) {
                                attachments.put(filename, hashBase64());
                            } else {
                                parser.skipChildren();
                            }
                        }));
                default -> parser.skipChildren();
            }
        }
        requireToken(JsonToken.END_OBJECT);
        if (parser.nextToken() != null) {
            throw invalid("trailing data after the publish envelope");
        }
    }

    private void readVersion(String version) throws IOException {
        Map<String, String> fields = new HashMap<>();
        forEachField(field -> {
            if (field.equals("dist")) {
                forEachField(distField -> {
                    if (distField.equals("tarball") || distField.equals("integrity")) {
                        fields.put(distField, stringValue());
                    } else {
                        parser.skipChildren();
                    }
                });
            } else if (field.equals("readme")) {
                // Best-effort: unlike dist.tarball/integrity, which a confirmable envelope must
                // have, a readme missing or of the wrong shape (null, an object, ...) must not
                // reject the whole envelope the way an actually malformed required field does.
                // Before this field existed at all, any shape here fell to the default branch's
                // plain skipChildren() below; this keeps that same tolerance for this one field
                // now that it is read instead of skipped.
                if (parser.currentToken() == JsonToken.VALUE_STRING) {
                    fields.put("readme", parser.getText());
                } else {
                    parser.skipChildren();
                }
            } else {
                parser.skipChildren();
            }
        });
        versionFields.put(version, fields);
    }

    /**
     * Walks the object the parser is currently on, handing each field name to {@code handler} with the
     * parser positioned on that field's value. Leaves the parser on the object's END_OBJECT.
     */
    private void forEachField(FieldHandler handler) throws IOException {
        requireToken(JsonToken.START_OBJECT);
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String fieldName = parser.currentName();
            parser.nextToken();
            handler.accept(fieldName);
        }
        requireToken(JsonToken.END_OBJECT);
    }

    private String stringValue() throws IOException {
        requireToken(JsonToken.VALUE_STRING);
        return parser.getText();
    }

    /**
     * {@link JsonParser#readBinaryValue} already resolves a JSON escape sequence within the
     * base64 text before decoding it (confirmed directly: a validly-escaped, valid-length
     * attachment decodes correctly, digest and byte count both exactly right), so this never
     * needs a separate unescaping path of its own.
     */
    private Attachment hashBase64() throws IOException {
        requireToken(JsonToken.VALUE_STRING);
        MessageDigest digest = sha512();
        CountingOutputStream counted = new CountingOutputStream();
        try (OutputStream sink = new DigestOutputStream(counted, digest)) {
            parser.readBinaryValue(sink);
        } catch (IllegalArgumentException e) {
            throw invalid("attachment data is not plain base64");
        }
        return new Attachment("sha512-" + Base64.getEncoder().encodeToString(digest.digest()), counted.count());
    }

    /** Discards every byte written to it, counting them as it goes. */
    private static final class CountingOutputStream extends OutputStream {
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }

        long count() {
            return count;
        }
    }

    private void requireToken(JsonToken expected) throws IOException {
        if (parser.currentToken() != expected) {
            throw invalid("expected " + expected + " but found " + parser.currentToken());
        }
    }

    private Envelope envelope() {
        Map<String, Version> versions = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : versionFields.entrySet()) {
            Map<String, String> fields = entry.getValue();
            versions.put(entry.getKey(), new Version(fields.get("tarball"), fields.get("integrity"),
                    fields.get("readme")));
        }
        return new Envelope(name, distTags, versions, attachments);
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is required by every Java runtime", e);
        }
    }

    private static IOException invalid(String reason) {
        return new IOException("Not a publish envelope this check can confirm: " + reason);
    }
}
