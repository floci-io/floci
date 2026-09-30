package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsPartition;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalog of AWS managed policies, loaded from {@code iam/managed-policies.yaml}.
 *
 * <p>Floci resolves {@code arn:aws:iam::aws:policy/*} ARNs against this catalog, so an ARN
 * that is absent returns {@code NoSuchEntity}, the same as real AWS. Carrying the full
 * published list is what keeps that faithful in both directions: policies AWS actually
 * publishes attach cleanly, while typos and invented names are still rejected. A curated
 * subset would reject valid configurations; resolving every well-formed ARN would accept
 * invalid ones.
 *
 * <p>Policy documents are loaded from the versioned data generated from the public AWS managed
 * policy dataset. Entries without a document are omitted so an unavailable policy fails closed
 * through the normal {@code NoSuchEntity} path rather than receiving broader permissions.
 *
 * <p>Each document is the policy's <em>current default version</em>, and the version id it is
 * served under comes from {@code iam/managed-policy-versions.json} rather than being assumed to
 * be {@code v1}: AWS revises its managed policies in place, so {@code AmazonS3ReadOnlyAccess}
 * is on {@code v3} while {@code AdministratorAccess} is still {@code v1}. That file also carries
 * the policy's own creation date and the date of its current version, which back the
 * {@code CreateDate} and {@code UpdateDate} of {@code GetPolicy} respectively. Only the default
 * version's document is bundled, since the dataset does not carry superseded documents, so an
 * older version id is reported as {@code NoSuchEntity}, as AWS itself does once it prunes a
 * managed policy's history down to five versions. A policy missing from the versions file
 * keeps the {@code v1} default so a partial refresh degrades to the old behaviour instead of
 * dropping the policy.
 *
 * <p>The bundled data is the commercial partition's. Every other partition's catalog is
 * derived from it on first use ({@link #forPartition}): the ARN prefix and every
 * {@code arn:aws:} inside a document take the partition ({@code arn:aws-cn:iam::aws:policy/...},
 * which is what AWS's own SAM translator does, {@code samtranslator/translator/arn_generator.py}),
 * and the region-bearing hostnames a {@code kms:ViaService} condition names
 * ({@code s3.*.amazonaws.com}) take the partition's DNS suffix. Bare service principals
 * ({@code ec2.amazonaws.com}) and service-linked role paths are the same in every partition and
 * are left alone, as is the {@code ::aws:policy/} owner slot. Whether AWS's China or GovCloud
 * documents differ in content beyond that is not known; see the open questions in
 * {@code docs/configuration/partitions.md}.
 */
final class AwsManagedPolicies {

    private static final Logger LOG = Logger.getLogger(AwsManagedPolicies.class);

    private static final String COMMERCIAL_PARTITION = "aws";
    /** {@code arn:<partition>:iam::aws:policy/}, the owner slot literally {@code aws} in every partition. */
    private static final Pattern MANAGED_POLICY_ARN = Pattern.compile(
            "^arn:(" + AwsArnUtils.PARTITION_REGEX + "):iam::aws:policy/");
    /** Every commercial ARN inside a document; the whole prefix up to and including the second colon. */
    private static final Pattern COMMERCIAL_ARN = Pattern.compile("arn:aws:"); // partition-literal: the commercial prefix this rewrites away
    /**
     * A hostname whose label before the commercial suffix is a region or the {@code *} that
     * stands for one ({@code s3.*.amazonaws.com}, {@code s3.us-east-1.amazonaws.com}); a bare
     * principal has no such label and never matches.
     */
    private static final Pattern REGION_BEARING_HOST = Pattern.compile(
            "\\.(\\*|[a-z]{2,4}-[a-z-]+-\\d)\\.amazonaws\\.com(?![.\\w-])"); // partition-literal: the commercial suffix this rewrites away

    private static final String CATALOG_RESOURCE_NAME = "iam/managed-policies.yaml";
    private static final String DOCUMENTS_RESOURCE_NAME = "iam/managed-policy-documents.json";
    private static final String VERSIONS_RESOURCE_NAME = "iam/managed-policy-versions.json";

    private static final String FALLBACK_VERSION_ID = "v1";
    private static final Pattern VERSION_ID = Pattern.compile("v[1-9][0-9]*");

    /**
     * One resolvable AWS managed policy.
     *
     * @param partition        the partition whose catalog holds it, which its ARN names
     * @param document         the document of the policy's current default version
     * @param defaultVersionId the version id AWS reports for that document, e.g. {@code v3}
     * @param createDate       when AWS first published the policy, or {@code null} if unknown
     * @param updateDate       when AWS published the current default version, or {@code null}
     *                         if unknown
     */
    record ManagedPolicyDef(String partition, String name, String path, String description, String document,
                            String defaultVersionId, Instant createDate, Instant updateDate) {
        String arn() {
            return arnPrefix(partition) + path + name;
        }
    }

    /** The commercial catalog, byte-identical to the bundled data. */
    static final List<ManagedPolicyDef> POLICIES = load();

    private static final Map<String, List<ManagedPolicyDef>> BY_PARTITION = new ConcurrentHashMap<>();

    private AwsManagedPolicies() {
    }

    /** {@code arn:<partition>:iam::aws:policy}, without the trailing slash of the path. */
    static String arnPrefix(String partition) {
        return "arn:" + partition + ":iam::aws:policy";
    }

    /** True for an AWS managed policy ARN in any partition ({@code arn:aws-cn:iam::aws:policy/...}). */
    static boolean isManagedPolicyArn(String arn) {
        return arn != null && MANAGED_POLICY_ARN.matcher(arn).find();
    }

    /**
     * The catalog of {@code partition}: the bundled commercial data for {@code aws}, a derived
     * view otherwise, built once per partition. Bounded by the published partitions.
     *
     * @throws IllegalArgumentException when {@code partition} is not a published partition id
     */
    static List<ManagedPolicyDef> forPartition(String partition) {
        if (COMMERCIAL_PARTITION.equals(partition)) {
            return POLICIES;
        }
        AwsPartition target = AwsPartitions.find(partition).orElseThrow(() ->
                new IllegalArgumentException("Not a published AWS partition: " + partition));
        return BY_PARTITION.computeIfAbsent(target.id(), id -> derive(target));
    }

    private static List<ManagedPolicyDef> derive(AwsPartition partition) {
        long started = System.nanoTime();
        String arnPrefix = "arn:" + partition.id() + ":";
        List<ManagedPolicyDef> derived = new ArrayList<>(POLICIES.size());
        for (ManagedPolicyDef def : POLICIES) {
            derived.add(new ManagedPolicyDef(partition.id(), def.name(), def.path(), def.description(),
                    rewriteDocument(def.document(), arnPrefix, partition.dnsSuffix()),
                    def.defaultVersionId(), def.createDate(), def.updateDate()));
        }
        LOG.debugv("Derived the {0} managed policy catalog ({1} policies) in {2} ms",
                partition.id(), derived.size(), (System.nanoTime() - started) / 1_000_000);
        return List.copyOf(derived);
    }

    static String rewriteDocument(String document, String arnPrefix, String dnsSuffix) {
        String rewritten = COMMERCIAL_ARN.matcher(document).replaceAll(Matcher.quoteReplacement(arnPrefix));
        Matcher hosts = REGION_BEARING_HOST.matcher(rewritten);
        return hosts.replaceAll(match -> Matcher.quoteReplacement("." + match.group(1) + "." + dnsSuffix));
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
            Map<String, VersionEntry> versions = loadVersions();
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
                VersionEntry version = versions.get(entry.name);
                defs.add(new ManagedPolicyDef(COMMERCIAL_PARTITION, entry.name, entry.path, entry.description,
                        document.toString(),
                        defaultVersionId(entry.name, version),
                        parseDate(entry.name, "createDate", version == null ? null : version.createDate),
                        parseDate(entry.name, "updateDate", version == null ? null : version.updateDate)));
            }
            LOG.debugv("Loaded {0} AWS managed policies from {1}, {2} and {3}",
                    defs.size(), CATALOG_RESOURCE_NAME, DOCUMENTS_RESOURCE_NAME, VERSIONS_RESOURCE_NAME);
            return List.copyOf(defs);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read the AWS managed policy catalog: " + CATALOG_RESOURCE_NAME, e);
        }
    }

    private static String defaultVersionId(String name, VersionEntry version) {
        if (version == null || version.defaultVersionId == null || version.defaultVersionId.isBlank()) {
            LOG.debugv("No AWS managed policy version recorded for {0}; reporting it as {1}",
                    name, FALLBACK_VERSION_ID);
            return FALLBACK_VERSION_ID;
        }
        if (!VERSION_ID.matcher(version.defaultVersionId).matches()) {
            LOG.warnv("Ignoring malformed AWS managed policy version {0} for {1}; reporting it as {2}",
                    version.defaultVersionId, name, FALLBACK_VERSION_ID);
            return FALLBACK_VERSION_ID;
        }
        return version.defaultVersionId;
    }

    private static Instant parseDate(String name, String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            LOG.warnv("Ignoring malformed AWS managed policy {0} {1} for {2}", field, value, name);
            return null;
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

    private static Map<String, VersionEntry> loadVersions() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(VERSIONS_RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(
                        "AWS managed policy versions not found on the classpath: " + VERSIONS_RESOURCE_NAME);
            }
            ObjectMapper jsonMapper = new ObjectMapper();
            return jsonMapper.readValue(in, jsonMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, VersionEntry.class));
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

    /** The version metadata AWS publishes for one policy. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class VersionEntry {
        public String defaultVersionId;
        /** ISO-8601 instant at which AWS created the policy. */
        public String createDate;
        /** ISO-8601 instant at which AWS created the current default version. */
        public String updateDate;
    }
}
