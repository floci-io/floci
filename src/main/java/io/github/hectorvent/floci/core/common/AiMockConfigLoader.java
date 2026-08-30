package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads the shared mock-response configuration file referenced by {@code AI_MOCK_CONFIG}
 * or {@code floci.ai-mock-config-file}, used by the fixed-stub AI services (Textract,
 * Comprehend, Rekognition) to return a caller-configured response instead of their default
 * canned stub. The file is re-read when its modification time changes, so it can be edited
 * without restarting the emulator — same mechanism as {@code SfnMockLoader}.
 * <p>
 * Unlike {@code SfnMockLoader} (where the mock configuration is the only way to run a
 * {@code StartExecution} test case, so a missing file or entry is a client error), mocking
 * here is an opt-in layer on top of every service's always-available default stub. This
 * loader therefore never throws: no file configured, a missing/unreadable file, or no
 * matching entry all resolve to {@link Optional#empty()}, and callers fall back to their
 * default response.
 * <p>
 * Config file shape: {@code {"<serviceKey>": {"<lookupKey>": {"<Action>": <response>}}}}.
 * See {@code docs/services/textract.md} / {@code comprehend.md} / {@code rekognition.md}
 * "Mock Responses" sections for each service's lookup-key convention.
 */
@ApplicationScoped
public class AiMockConfigLoader {

    private static final Logger LOG = Logger.getLogger(AiMockConfigLoader.class);

    private final Optional<String> configuredPath;
    private final ObjectMapper objectMapper;
    private volatile CachedMockFile cache;
    private volatile boolean warnedMissingFile;

    private record CachedMockFile(String path, long lastModified, JsonNode root) {
    }

    @Inject
    public AiMockConfigLoader(EmulatorConfig config, ObjectMapper objectMapper) {
        this(config.aiMockConfigFile(), objectMapper);
    }

    AiMockConfigLoader(Optional<String> configuredPath, ObjectMapper objectMapper) {
        this.configuredPath = configuredPath.filter(path -> !path.isBlank());
        this.objectMapper = objectMapper;
    }

    /**
     * Looks up a configured mock response for the given service, lookup key, and action.
     * Returns empty whenever a default-stub fallback is appropriate — this method never
     * throws.
     */
    public Optional<JsonNode> lookup(String serviceKey, String lookupKey, String action) {
        if (configuredPath.isEmpty() || lookupKey == null) {
            return Optional.empty();
        }
        JsonNode root = load(configuredPath.get());
        if (root == null) {
            return Optional.empty();
        }
        JsonNode response = root.path(serviceKey).path(lookupKey).path(action);
        if (response.isObject()) {
            LOG.debugv("AI mock hit: {0}/{1}/{2}", serviceKey, lookupKey, action);
            return Optional.of(response);
        }
        return Optional.empty();
    }

    private JsonNode load(String path) {
        try {
            return loadOrThrow(path);
        } catch (RuntimeException e) {
            // Path.of(path) throws InvalidPathException (unchecked) for a malformed path
            // string (e.g. an embedded NUL character), and filesystem access can throw
            // other unchecked exceptions (e.g. SecurityException) that IOException alone
            // does not cover. None of that may ever break a real request: mocking is
            // optional, so any failure to load the config falls back to the default stub,
            // exactly like the IOException cases below.
            LOG.warnv("AI mock configuration at {0} could not be loaded ({1}); using default stub responses.",
                    path, e);
            return null;
        }
    }
    private JsonNode loadOrThrow(String path) {
        var file = Path.of(path);
        long lastModified;
        try {
            lastModified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            if (!warnedMissingFile) {
                warnedMissingFile = true;
                LOG.warnv("AI mock configuration file not found: {0}; using default stub responses.", path);
            }
            return null;
        }
        var cached = cache;
        if (cached != null && cached.path().equals(path) && cached.lastModified() == lastModified) {
            return cached.root();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            LOG.warnv("Failed to read AI mock configuration file {0}: {1}", path, e.getMessage());
            return null;
        }
        if (root == null || !root.isObject()) {
            LOG.warnv("AI mock configuration file {0} must contain a JSON object; ignoring.", path);
            return null;
        }
        cache = new CachedMockFile(path, lastModified, root);
        return root;
    }
}
