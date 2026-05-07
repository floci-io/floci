package io.github.hectorvent.floci.services.route53;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.route53.model.ChangeInfo;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class Route53Service {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record CreateZoneResult(HostedZone zone, ChangeInfo change) {}

    private final StorageBackend<String, HostedZone> zoneStore;
    private final StorageBackend<String, List<ResourceRecordSet>> recordStore;
    private final StorageBackend<String, HealthCheck> healthCheckStore;
    private final StorageBackend<String, ChangeInfo> changeStore;
    private final StorageBackend<String, Map<String, String>> tagStore;
    private final List<String> nameServers;

    @Inject
    public Route53Service(StorageFactory factory, EmulatorConfig config) {
        this.zoneStore = factory.create("route53", "route53-zones.json",
                new TypeReference<Map<String, HostedZone>>() {});
        this.recordStore = factory.create("route53", "route53-records.json",
                new TypeReference<Map<String, List<ResourceRecordSet>>>() {});
        this.healthCheckStore = factory.create("route53", "route53-health-checks.json",
                new TypeReference<Map<String, HealthCheck>>() {});
        this.changeStore = factory.create("route53", "route53-changes.json",
                new TypeReference<Map<String, ChangeInfo>>() {});
        this.tagStore = factory.create("route53", "route53-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});

        EmulatorConfig.Route53ServiceConfig r53 = config.services().route53();
        this.nameServers = List.of(
                r53.defaultNameserver1(),
                r53.defaultNameserver2(),
                r53.defaultNameserver3(),
                r53.defaultNameserver4()
        );
    }

    // ── Hosted Zones ──────────────────────────────────────────────────────────

    public synchronized CreateZoneResult createHostedZone(String name, String callerReference,
                                                           String comment, boolean privateZone) {
        String normalizedName = normalizeName(name);

        for (HostedZone existing : zoneStore.scan(k -> true)) {
            if (existing.getCallerReference().equals(callerReference)) {
                throw new AwsException("HostedZoneAlreadyExists",
                        "A hosted zone with caller reference " + callerReference + " already exists.", 409);
            }
        }

        String id = generateZoneId();
        HostedZone zone = new HostedZone(id, normalizedName, callerReference, comment, privateZone);
        zoneStore.put(id, zone);
        recordStore.put(id, buildDefaultRecords(normalizedName));
        ChangeInfo change = newChange(null);
        return new CreateZoneResult(zone, change);
    }

    public HostedZone getHostedZone(String id) {
        HostedZone zone = zoneStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchHostedZone",
                        "No hosted zone found with ID: " + id, 404));
        zone.setResourceRecordSetCount(recordCount(id));
        return zone;
    }

    public synchronized ChangeInfo deleteHostedZone(String id) {
        HostedZone zone = getHostedZone(id);
        List<ResourceRecordSet> records = recordStore.get(id).orElse(List.of());
        long nonDefault = records.stream()
                .filter(r -> !isApexSoaOrNs(r, zone.getName()))
                .count();
        if (nonDefault > 0) {
            throw new AwsException("HostedZoneNotEmpty",
                    "The hosted zone contains resource record sets in addition to the default NS and SOA records.", 400);
        }
        zoneStore.delete(id);
        recordStore.delete(id);
        tagStore.delete("hostedzone/" + id);
        return newChange(null);
    }

    public List<HostedZone> listHostedZones(String marker, int maxItems) {
        List<HostedZone> all = new ArrayList<>(zoneStore.scan(k -> true));
        all.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (HostedZone zone : all) {
            zone.setResourceRecordSetCount(recordCount(zone.getId()));
        }
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    public List<HostedZone> listHostedZonesByName(String dnsName, int maxItems) {
        List<HostedZone> all = new ArrayList<>(zoneStore.scan(k -> true));
        all.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (HostedZone zone : all) {
            zone.setResourceRecordSetCount(recordCount(zone.getId()));
        }
        if (dnsName != null && !dnsName.isEmpty()) {
            String normalized = normalizeName(dnsName);
            all = all.stream()
                    .filter(z -> z.getName().compareTo(normalized) >= 0)
                    .toList();
            all = new ArrayList<>(all);
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    public long getHostedZoneCount() {
        return zoneStore.keys().size();
    }

    // ── Resource Record Sets ──────────────────────────────────────────────────

    public synchronized ChangeInfo changeResourceRecordSets(String zoneId,
                                                             List<Map<String, Object>> changes,
                                                             String comment) {
        HostedZone zone = getHostedZone(zoneId);
        List<ResourceRecordSet> current = new ArrayList<>(
                recordStore.get(zoneId).orElse(new ArrayList<>()));

        // Validate all changes before applying any
        for (Map<String, Object> change : changes) {
            String action = (String) change.get("action");
            ResourceRecordSet rrs = (ResourceRecordSet) change.get("rrs");
            validateChange(action, rrs, current, zone.getName());
        }

        // Apply all changes
        for (Map<String, Object> change : changes) {
            String action = (String) change.get("action");
            ResourceRecordSet rrs = (ResourceRecordSet) change.get("rrs");
            applyChange(action, rrs, current);
        }

        zone.setResourceRecordSetCount(current.size());
        zoneStore.put(zoneId, zone);
        recordStore.put(zoneId, current);
        return newChange(comment);
    }

    public List<ResourceRecordSet> listResourceRecordSets(String zoneId, String startName,
                                                           String startType, int maxItems) {
        getHostedZone(zoneId);
        List<ResourceRecordSet> records = new ArrayList<>(
                recordStore.get(zoneId).orElse(List.of()));

        records.sort((a, b) -> {
            int cmp = a.getName().compareTo(b.getName());
            if (cmp != 0) return cmp;
            return a.getType().compareTo(b.getType());
        });

        if (startName != null && !startName.isEmpty()) {
            String normalizedStart = normalizeName(startName);
            final String finalStartType = startType;
            records = records.stream()
                    .filter(r -> {
                        int cmp = r.getName().compareTo(normalizedStart);
                        if (cmp > 0) return true;
                        if (cmp == 0 && finalStartType != null && !finalStartType.isEmpty()) {
                            return r.getType().compareTo(finalStartType) >= 0;
                        }
                        return cmp == 0;
                    })
                    .toList();
            records = new ArrayList<>(records);
        }

        if (maxItems > 0 && records.size() > maxItems) {
            return records.subList(0, maxItems);
        }
        return records;
    }

    // ── Changes ───────────────────────────────────────────────────────────────

    public ChangeInfo getChange(String changeId) {
        return changeStore.get(changeId).orElseThrow(() ->
                new AwsException("NoSuchChange",
                        "No change found with ID: " + changeId, 404));
    }

    // ── Health Checks ─────────────────────────────────────────────────────────

    public synchronized HealthCheck createHealthCheck(String callerReference, HealthCheckConfig cfg) {
        for (HealthCheck existing : healthCheckStore.scan(k -> true)) {
            if (existing.getCallerReference().equals(callerReference)) {
                throw new AwsException("HealthCheckAlreadyExists",
                        "A health check with caller reference " + callerReference + " already exists.", 409);
            }
        }
        String id = UUID.randomUUID().toString();
        HealthCheck hc = new HealthCheck(id, callerReference, cfg);
        healthCheckStore.put(id, hc);
        return hc;
    }

    public HealthCheck getHealthCheck(String id) {
        return healthCheckStore.get(id).orElseThrow(() ->
                new AwsException("NoSuchHealthCheck",
                        "No health check found with ID: " + id, 404));
    }

    public void deleteHealthCheck(String id) {
        getHealthCheck(id);
        healthCheckStore.delete(id);
        tagStore.delete("healthcheck/" + id);
    }

    public List<HealthCheck> listHealthChecks(String marker, int maxItems) {
        List<HealthCheck> all = new ArrayList<>(healthCheckStore.scan(k -> true));
        if (marker != null && !marker.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getId().equals(marker)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxItems > 0 && all.size() > maxItems) {
            return all.subList(0, maxItems);
        }
        return all;
    }

    public HealthCheck updateHealthCheck(String id, HealthCheckConfig cfg) {
        HealthCheck hc = getHealthCheck(id);
        hc.setConfig(cfg);
        hc.setHealthCheckVersion(hc.getHealthCheckVersion() + 1);
        healthCheckStore.put(id, hc);
        return hc;
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTagsForResource(String resourceType, String resourceId) {
        return tagStore.get(resourceType + "/" + resourceId).orElse(Collections.emptyMap());
    }

    public void changeTagsForResource(String resourceType, String resourceId,
                                      List<Map<String, String>> addTags, List<String> removeTagKeys) {
        String key = resourceType + "/" + resourceId;
        Map<String, String> tags = new LinkedHashMap<>(tagStore.get(key).orElse(new LinkedHashMap<>()));
        if (removeTagKeys != null) {
            removeTagKeys.forEach(tags::remove);
        }
        if (addTags != null) {
            addTags.forEach(t -> {
                if (t.get("Key") != null) {
                    tags.put(t.get("Key"), t.getOrDefault("Value", ""));
                }
            });
        }
        tagStore.put(key, tags);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public List<String> getNameServers() {
        return nameServers;
    }

    private static String normalizeName(String name) {
        if (name == null || name.isEmpty()) return name;
        return name.endsWith(".") ? name : name + ".";
    }

    private static String generateZoneId() {
        StringBuilder sb = new StringBuilder("Z");
        for (int i = 0; i < 14; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static String generateChangeId() {
        StringBuilder sb = new StringBuilder("C");
        for (int i = 0; i < 13; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private ChangeInfo newChange(String comment) {
        String id = generateChangeId();
        ChangeInfo change = new ChangeInfo(id, Instant.now().toString(), comment);
        changeStore.put(id, change);
        return change;
    }

    private List<ResourceRecordSet> buildDefaultRecords(String zoneName) {
        List<ResourceRecordSet> records = new ArrayList<>();

        ResourceRecordSet soa = new ResourceRecordSet();
        soa.setName(zoneName);
        soa.setType("SOA");
        soa.setTtl(900L);
        soa.setRecords(List.of(new ResourceRecord(
                nameServers.get(0) + " awsdns-hostmaster.amazon.com. 1 7200 900 1209600 86400")));
        records.add(soa);

        ResourceRecordSet ns = new ResourceRecordSet();
        ns.setName(zoneName);
        ns.setType("NS");
        ns.setTtl(172800L);
        ns.setRecords(nameServers.stream()
                .map(n -> new ResourceRecord(n + "."))
                .toList());
        records.add(ns);

        return records;
    }

    private boolean isApexSoaOrNs(ResourceRecordSet rrs, String zoneName) {
        return rrs.getName().equals(zoneName) &&
                ("SOA".equals(rrs.getType()) || "NS".equals(rrs.getType()));
    }

    private int recordCount(String zoneId) {
        return recordStore.get(zoneId).map(List::size).orElse(0);
    }

    private void validateChange(String action, ResourceRecordSet rrs,
                                List<ResourceRecordSet> current, String zoneName) {
        if ("DELETE".equals(action) && isApexSoaOrNs(rrs, zoneName)) {
            throw new AwsException("InvalidChangeBatch",
                    "Invalid resource record set: Deleting the SOA or NS record at the zone apex is not permitted.", 400);
        }
        if ("CREATE".equals(action)) {
            boolean exists = current.stream().anyMatch(r ->
                    r.getName().equals(rrs.getName()) &&
                    r.getType().equals(rrs.getType()) &&
                    equalOrNull(r.getSetIdentifier(), rrs.getSetIdentifier()));
            if (exists) {
                throw new AwsException("InvalidChangeBatch",
                        "Tried to create resource record set [name='" + rrs.getName() +
                        "', type='" + rrs.getType() + "'] but it already exists.", 400);
            }
        }
        if ("DELETE".equals(action)) {
            boolean found = current.stream().anyMatch(r ->
                    r.getName().equals(rrs.getName()) && r.getType().equals(rrs.getType()));
            if (!found) {
                throw new AwsException("InvalidChangeBatch",
                        "Tried to delete resource record set [name='" + rrs.getName() +
                        "', type='" + rrs.getType() + "'] but it was not found.", 400);
            }
        }
    }

    private void applyChange(String action, ResourceRecordSet rrs, List<ResourceRecordSet> current) {
        switch (action) {
            case "CREATE" -> current.add(rrs);
            case "DELETE" -> current.removeIf(r ->
                    r.getName().equals(rrs.getName()) && r.getType().equals(rrs.getType()) &&
                    equalOrNull(r.getSetIdentifier(), rrs.getSetIdentifier()));
            case "UPSERT" -> {
                current.removeIf(r ->
                        r.getName().equals(rrs.getName()) && r.getType().equals(rrs.getType()) &&
                        equalOrNull(r.getSetIdentifier(), rrs.getSetIdentifier()));
                current.add(rrs);
            }
        }
    }

    private static boolean equalOrNull(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
