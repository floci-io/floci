package io.floci.aws.services.ssm;

import io.floci.aws.services.ssm.model.SsmDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class BuiltinSessionDocuments {
    static final String OWNER = "Amazon";

    private static final Map<String, String> CONTENT = Map.of(
            "AWS-StartSSHSession", load("AWS-StartSSHSession"),
            "AWS-StartPortForwardingSession", load("AWS-StartPortForwardingSession"),
            "AWS-StartPortForwardingSessionToRemoteHost", load("AWS-StartPortForwardingSessionToRemoteHost"));

    private BuiltinSessionDocuments() {}

    static boolean contains(String name) {
        return name != null && CONTENT.containsKey(name);
    }

    static Optional<SsmDocument> find(String name) {
        return Optional.ofNullable(name == null ? null : CONTENT.get(name)).map(content -> document(name, content));
    }

    static List<SsmDocument> list() {
        return CONTENT.entrySet().stream()
                .map(entry -> document(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static SsmDocument document(String name, String content) {
        SsmDocument document = new SsmDocument(name, content, "Session");
        document.setOwner(OWNER);
        document.setCreatedDate(null);
        return document;
    }

    private static String load(String name) {
        String path = "/ssm/" + name + ".json";
        try (InputStream input = Objects.requireNonNull(BuiltinSessionDocuments.class.getResourceAsStream(path), path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read built-in AWS document " + name, e);
        }
    }
}
