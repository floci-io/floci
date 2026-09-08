package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Catalog of AWS managed policies, loaded from {@code iam/managed-policies.yaml}.
 *
 * <p>Floci resolves {@code arn:aws:iam::aws:policy/*} ARNs against this catalog, so an ARN
 * that is absent returns {@code NoSuchEntity} — the same as real AWS. Carrying the full
 * published list is what keeps that faithful in both directions: policies AWS actually
 * publishes attach cleanly, while typos and invented names are still rejected. A curated
 * subset would reject valid configurations; resolving every well-formed ARN would accept
 * invalid ones.
 *
 * <p>Policy documents are loaded from the versioned data generated from the public AWS managed
 * policy dataset. Entries without a document are omitted so an unavailable policy fails closed
 * through the normal {@code NoSuchEntity} path rather than receiving broader permissions.
 */
final class AwsManagedPolicies {

    private static final Logger LOG = Logger.getLogger(AwsManagedPolicies.class);

    static final String ARN_PREFIX = "arn:aws:iam::aws:policy";

    private static final String CATALOG_RESOURCE_NAME = "iam/managed-policies.yaml";
    private static final String DOCUMENTS_RESOURCE_NAME = "iam/managed-policy-documents.json";

    record ManagedPolicyDef(String name, String path, String description, String document) {
        String arn() {
            return ARN_PREFIX + path + name;
        }
    }

    static final List<ManagedPolicyDef> POLICIES = load();

    private AwsManagedPolicies() {
    }

    private static List<ManagedPolicyDef> load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CATALOG_RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(
                        "AWS managed policy catalog not found on the classpath: " + CATALOG_RESOURCE_NAME);
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Catalog catalog = mapper.readValue(in, Catalog.class);
            Map<String, JsonNode> documents = loadDocuments();
            List<ManagedPolicyDef> defs = new ArrayList<>();
            for (CatalogEntry entry : catalog.policies == null ? List.<CatalogEntry>of() : catalog.policies) {
                if (entry.name == null || entry.name.isBlank() || entry.path == null || entry.path.isBlank()) {
                    continue;
                }
                JsonNode document = documents.get(entry.name);
                if (document == null || document.isNull()) {
                    LOG.warnv("No AWS managed policy document available for {0}; omitting it from the resolvable catalog",
                            entry.name);
                    continue;
                }
                defs.add(new ManagedPolicyDef(entry.name, entry.path, entry.description, document.toString()));
            }
            LOG.debugv("Loaded {0} AWS managed policies from {1} and {2}",
                    defs.size(), CATALOG_RESOURCE_NAME, DOCUMENTS_RESOURCE_NAME);
            return List.copyOf(defs);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read the AWS managed policy catalog: " + CATALOG_RESOURCE_NAME, e);
        }
    }

    private static Map<String, JsonNode> loadDocuments() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(DOCUMENTS_RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(
                        "AWS managed policy documents not found on the classpath: " + DOCUMENTS_RESOURCE_NAME);
            }
            ObjectMapper jsonMapper = new ObjectMapper();
            return jsonMapper.readValue(in, jsonMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, JsonNode.class));
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Catalog {
        public List<CatalogEntry> policies;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class CatalogEntry {
        public String name;
        public String path;
        public String description;
    }
}
