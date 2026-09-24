package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The catalog of every AWS partition, read once from the vendored {@code aws/partitions.json}.
 * The file is generated from botocore's own partition metadata (MIT-licensed, published at
 * https://github.com/boto/botocore/tree/develop/botocore/data) by
 * {@code tools/aws/regen_partitions.py}, and {@code make aws-data-check} keeps it in sync.
 *
 * <p>A region resolves to its partition in three steps, strictest first: an exact match on a
 * {@code <partition>-global} pseudo-region, membership in a partition's published region list,
 * then botocore's {@code regionRegex}, which classifies a region launched after the vendored
 * data was generated. {@link #forRegion} is the strict form and answers {@code Optional.empty()}
 * for anything else; {@link #forRegionOrCommercial} is the fail-open form ARN minting uses,
 * because the AWS SDKs themselves put an unknown region in {@code aws}.
 *
 * <p>A missing or malformed resource is a build defect, not a runtime condition, so loading
 * fails with {@link IllegalStateException} rather than degrading to a commercial-only catalog.
 */
public final class AwsPartitions {

    static final String RESOURCE_NAME = "aws/partitions.json";
    static final String COMMERCIAL_ID = "aws";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Catalog(List<AwsPartition> partitions,
                   Map<String, AwsPartition> byId,
                   Map<String, AwsPartition> byRegion,
                   Map<String, AwsPartition> byPseudoRegion,
                   Set<String> regionIds,
                   List<String> dnsSuffixesLongestFirst) {
    }

    private static final class Holder {
        static final Catalog CATALOG = load();
    }

    /** The suffix and the host label(s) before it, from {@link #stripKnownDnsSuffix}. */
    public record DnsSuffixMatch(String prefix, String dnsSuffix) {
    }

    private AwsPartitions() {
    }

    public static List<AwsPartition> all() {
        return Holder.CATALOG.partitions();
    }

    public static AwsPartition commercial() {
        return byId(COMMERCIAL_ID);
    }

    public static Optional<AwsPartition> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Holder.CATALOG.byId().get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /** @throws IllegalArgumentException when {@code id} names no published partition */
    public static AwsPartition byId(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown AWS partition: " + id));
    }

    public static Set<String> ids() {
        return Holder.CATALOG.byId().keySet();
    }

    /**
     * The partition {@code region} belongs to, or empty for null, blank and unrecognised
     * labels. A {@code <partition>-global} pseudo-region resolves to its partition.
     */
    public static Optional<AwsPartition> forRegion(String region) {
        String normalized = normalize(region);
        if (normalized == null) {
            return Optional.empty();
        }
        Catalog catalog = Holder.CATALOG;
        AwsPartition byPseudoRegion = catalog.byPseudoRegion().get(normalized);
        if (byPseudoRegion != null) {
            return Optional.of(byPseudoRegion);
        }
        AwsPartition byRegion = catalog.byRegion().get(normalized);
        if (byRegion != null) {
            return Optional.of(byRegion);
        }
        return catalog.partitions().stream()
                .filter(partition -> partition.matchesRegionRegex(normalized))
                .findFirst();
    }

    /**
     * {@link #forRegion}, falling back to the commercial partition. This is the AWS SDKs'
     * behaviour for an unrecognised region and what ARN minting relies on so a region AWS
     * launches tomorrow keeps working.
     */
    public static AwsPartition forRegionOrCommercial(String region) {
        return forRegion(region).orElseGet(AwsPartitions::commercial);
    }

    /** True when {@code region} is in some partition's published list (pseudo-regions excluded). */
    public static boolean isPublishedRegion(String region) {
        String normalized = normalize(region);
        return normalized != null && Holder.CATALOG.regionIds().contains(normalized);
    }

    /** Every published region id across all partitions. */
    public static Set<String> allRegionIds() {
        return Holder.CATALOG.regionIds();
    }

    /**
     * {@code label} with a {@code <partition>-global} pseudo-region replaced by that partition's
     * implicit global region ({@code aws-cn-global} becomes {@code cn-north-1}); any other label
     * is returned trimmed and lower-cased, and null stays null.
     */
    public static String normalizeRegion(String label) {
        String normalized = normalize(label);
        if (normalized == null) {
            return label;
        }
        AwsPartition partition = Holder.CATALOG.byPseudoRegion().get(normalized);
        return partition == null ? normalized : partition.implicitGlobalRegion();
    }

    /**
     * Splits {@code host} at the longest published DNS suffix it ends with, so
     * {@code bucket.s3.cn-north-1.amazonaws.com.cn} yields the {@code amazonaws.com.cn} suffix
     * rather than {@code amazonaws.com} with a dangling {@code .cn}. Case-insensitive; a port is
     * not tolerated. Empty when {@code host} ends with no published suffix, or is nothing but one.
     */
    public static Optional<DnsSuffixMatch> stripKnownDnsSuffix(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String lower = host.trim().toLowerCase(Locale.ROOT);
        for (String suffix : Holder.CATALOG.dnsSuffixesLongestFirst()) {
            if (lower.endsWith("." + suffix)) {
                return Optional.of(new DnsSuffixMatch(lower.substring(0, lower.length() - suffix.length() - 1), suffix));
            }
        }
        return Optional.empty();
    }

    private static String normalize(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        return label.trim().toLowerCase(Locale.ROOT);
    }

    private static Catalog load() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException("Partition catalog resource not found: " + RESOURCE_NAME);
            }
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the partition catalog " + RESOURCE_NAME, e);
        }
    }

    static Catalog parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        JsonNode partitionsNode = root.path("partitions");
        if (!partitionsNode.isArray() || partitionsNode.isEmpty()) {
            throw new IllegalStateException(RESOURCE_NAME + " has no partitions array");
        }
        List<AwsPartition> partitions = new ArrayList<>();
        Map<String, AwsPartition> byId = new LinkedHashMap<>();
        Map<String, AwsPartition> byRegion = new LinkedHashMap<>();
        Map<String, AwsPartition> byPseudoRegion = new LinkedHashMap<>();
        Set<String> regionIds = new LinkedHashSet<>();
        Set<String> dnsSuffixes = new LinkedHashSet<>();
        for (JsonNode node : partitionsNode) {
            AwsPartition partition = parsePartition(node);
            if (byId.put(partition.id(), partition) != null) {
                throw new IllegalStateException(RESOURCE_NAME + " lists partition " + partition.id() + " twice");
            }
            partitions.add(partition);
            for (AwsPartition.Region region : partition.regions()) {
                if (byRegion.put(region.id(), partition) != null) {
                    throw new IllegalStateException(RESOURCE_NAME + " lists region " + region.id() + " twice");
                }
                regionIds.add(region.id());
            }
            if (partition.globalPseudoRegion() != null) {
                byPseudoRegion.put(partition.globalPseudoRegion(), partition);
            }
            dnsSuffixes.add(partition.dnsSuffix());
            dnsSuffixes.add(partition.dualStackDnsSuffix());
        }
        if (!byId.containsKey(COMMERCIAL_ID)) {
            throw new IllegalStateException(RESOURCE_NAME + " lacks the commercial partition");
        }
        List<String> suffixesLongestFirst = new ArrayList<>(dnsSuffixes);
        suffixesLongestFirst.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        return new Catalog(List.copyOf(partitions), Map.copyOf(byId), Map.copyOf(byRegion),
                Map.copyOf(byPseudoRegion), Set.copyOf(regionIds), List.copyOf(suffixesLongestFirst));
    }

    private static AwsPartition parsePartition(JsonNode node) {
        String id = required(node, "id");
        List<AwsPartition.Region> regions = new ArrayList<>();
        for (JsonNode regionNode : node.path("regions")) {
            regions.add(new AwsPartition.Region(
                    required(regionNode, "id"),
                    regionNode.path("description").asText(""),
                    regionNode.path("optIn").asBoolean(false),
                    regionNode.path("s3WebsiteDashForm").asBoolean(false)));
        }
        Set<String> services = new LinkedHashSet<>();
        for (JsonNode service : node.path("services")) {
            services.add(service.asText());
        }
        Map<String, AwsPartition.GlobalEndpoint> globalServices = new LinkedHashMap<>();
        JsonNode globalNode = node.path("globalServices");
        globalNode.fieldNames().forEachRemaining(service -> {
            JsonNode endpoint = globalNode.get(service);
            globalServices.put(service, new AwsPartition.GlobalEndpoint(
                    required(endpoint, "hostname"),
                    required(endpoint, "signingRegion"),
                    endpoint.path("regionalized").asBoolean(false)));
        });
        JsonNode pseudo = node.get("globalPseudoRegion");
        return new AwsPartition(
                id,
                required(node, "name"),
                required(node, "dnsSuffix"),
                required(node, "dualStackDnsSuffix"),
                required(node, "implicitGlobalRegion"),
                Pattern.compile(required(node, "regionRegex")),
                pseudo == null || pseudo.isNull() ? null : pseudo.asText(),
                regions,
                services,
                globalServices,
                node.path("supportsDualStack").asBoolean(false),
                node.path("supportsFips").asBoolean(false));
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException(RESOURCE_NAME + ": missing " + field + " in " + node);
        }
        return value.asText();
    }
}
