package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.controltower.model.EnabledBaseline;
import io.github.hectorvent.floci.services.controltower.model.LandingZone;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control Tower landing-zone and baseline emulation, backed by the configured Floci storage mode.
 *
 * <p>Pre-seeds exactly one active landing zone on first read (lazy seed) so LZA's Prepare stage
 * never observes an empty {@code ListLandingZones} result — an empty list is LZA's create-path
 * trigger, and floci deliberately never implements {@code CreateLandingZone}
 * (see {@code issues/controltower/01-gap-analysis.md}). {@link #updateLandingZone} is a
 * reconciliation sink: it stores whatever manifest LZA sends and reports success, so any mismatch
 * between the seed and LZA's computed config self-heals on the first {@code UpdateLandingZone}
 * call rather than failing.
 */
@ApplicationScoped
public class ControlTowerService {

    static final String LANDING_ZONE_VERSION = "4.0";
    static final String LANDING_ZONE_ID = "FLOCISEEDEDLZ1";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String DRIFT_IN_SYNC = "IN_SYNC";
    static final String OP_SUCCEEDED = "SUCCEEDED";
    private static final String OP_TYPE_UPDATE = "UPDATE";
    private static final String OP_TYPE_BASELINE_ENABLED = "BASELINE_ENABLED";
    private static final String IDENTITY_CENTER_BASELINE_NAME = "IdentityCenterBaseline";
    private static final String IDENTITY_CENTER_BASELINE_ID = "LN25R72TTG6IGPTQ";
    private static final String IDENTITY_CENTER_ENABLED_BASELINE_ID = "FLOCIIDCBASELINE1";
    private static final String IDENTITY_CENTER_BASELINE_VERSION = "1.0";

    // Static baseline catalog. Only `name` is load-bearing (LZA matches case-insensitively on
    // name at register-organizational-unit/index.ts:109-111 and :502); ids are fixed for
    // determinism. Baseline arns are region-qualified with an empty account field, like real CT.
    private static final List<BaselineCatalogEntry> BASELINE_CATALOG = List.of(
            new BaselineCatalogEntry("AWSControlTowerBaseline", "17BSJV3IGJ2QSGA2",
                    "Sets up resources to govern an OU."),
            new BaselineCatalogEntry(IDENTITY_CENTER_BASELINE_NAME, IDENTITY_CENTER_BASELINE_ID,
                    "Sets up resources shared for IAM Identity Center access."),
            new BaselineCatalogEntry("AuditBaseline", "J8HX46AHS5MIKQPD",
                    "Sets up resources for the audit account."),
            new BaselineCatalogEntry("LogArchiveBaseline", "3WFXIAO9KPBTB5TE",
                    "Sets up resources for the log archive account."));

    private final StorageBackend<String, LandingZone> landingZoneStore;
    private final StorageBackend<String, EnabledBaseline> enabledBaselineStore;
    // Operation ledger: opId -> operationType. In-memory on purpose: pollers within one pipeline
    // run are the only consumers, and unknown ids still answer SUCCEEDED (restart-safe for LZA).
    private final Map<String, String> operations = new ConcurrentHashMap<>();

    @Inject
    public ControlTowerService(StorageFactory storageFactory) {
        this(
                storageFactory.create(
                        "controltower",
                        "controltower-landing-zones.json",
                        new TypeReference<Map<String, LandingZone>>() {
                        }),
                storageFactory.create(
                        "controltower",
                        "controltower-enabled-baselines.json",
                        new TypeReference<Map<String, EnabledBaseline>>() {
                        }));
    }

    ControlTowerService(
            StorageBackend<String, LandingZone> landingZoneStore,
            StorageBackend<String, EnabledBaseline> enabledBaselineStore) {
        this.landingZoneStore = landingZoneStore;
        this.enabledBaselineStore = enabledBaselineStore;
    }

    public synchronized LandingZone getOrSeedLandingZone(String accountId, String region) {
        Optional<LandingZone> existing = landingZoneStore.get(region);
        if (existing.isPresent()) {
            return existing.get();
        }
        LandingZone seeded = SeededLandingZoneFactory.create(accountId, region);
        landingZoneStore.put(region, seeded);
        return seeded;
    }

    public synchronized List<LandingZone> listLandingZones(String accountId, String region) {
        return List.of(getOrSeedLandingZone(accountId, region));
    }

    public synchronized String updateLandingZone(String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String version = requireText(request, "version");
        JsonNode manifest = request.get("manifest");
        if (manifest == null || !manifest.isObject()) {
            throw validation("manifest must be a JSON object.");
        }
        List<String> remediationTypes = readRemediationTypes(request);

        LandingZone lz = getOrSeedLandingZone(accountId, region);
        lz.setVersion(version);
        lz.setManifest(manifest);
        lz.setRemediationTypes(remediationTypes);
        lz.setLatestAvailableVersion(LANDING_ZONE_VERSION);
        lz.setStatus(STATUS_ACTIVE);
        lz.setDriftStatus(DRIFT_IN_SYNC);
        landingZoneStore.put(region, lz);

        String opId = UUID.randomUUID().toString();
        operations.put(opId, OP_TYPE_UPDATE);
        return opId;
    }

    public String getOperationType(String operationIdentifier) {
        return operations.getOrDefault(operationIdentifier, OP_TYPE_UPDATE);
    }

    public List<ObjectNode> listBaselines(String region) {
        List<ObjectNode> baselines = new ArrayList<>(BASELINE_CATALOG.size());
        for (BaselineCatalogEntry entry : BASELINE_CATALOG) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("arn", entry.arn(region));
            node.put("name", entry.name());
            node.put("description", entry.description());
            baselines.add(node);
        }
        return baselines;
    }

    public synchronized List<EnabledBaseline> listEnabledBaselines(String accountId, String region) {
        String prefix = region + "::";
        List<EnabledBaseline> stored = enabledBaselineStore.scan(key -> key.startsWith(prefix));

        List<EnabledBaseline> result = new ArrayList<>(stored.size() + 1);
        if (identityCenterAutoEnabled(accountId, region, stored)) {
            result.add(syntheticIdentityCenterBaseline(accountId, region));
        }
        result.addAll(stored);
        return result;
    }

    public synchronized EnableBaselineResult enableBaseline(String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String baselineIdentifier = requireText(request, "baselineIdentifier");
        String baselineVersion = requireText(request, "baselineVersion");
        String targetIdentifier = requireText(request, "targetIdentifier");
        JsonNode parameters = request.get("parameters");

        String key = region + "::" + targetIdentifier;
        String arn = "arn:aws:controltower:" + region + ":" + accountId
                + ":enabledbaseline/" + shortId();
        EnabledBaseline value = new EnabledBaseline(
                arn, baselineIdentifier, baselineVersion, targetIdentifier, OP_SUCCEEDED, parameters);
        enabledBaselineStore.put(key, value);

        String opId = UUID.randomUUID().toString();
        operations.put(opId, OP_TYPE_BASELINE_ENABLED);
        return new EnableBaselineResult(opId, arn);
    }

    public String getBaselineOperationType(String operationIdentifier) {
        return operations.getOrDefault(operationIdentifier, OP_TYPE_BASELINE_ENABLED);
    }

    private boolean identityCenterAutoEnabled(String accountId, String region, List<EnabledBaseline> stored) {
        String identityCenterBaselineArn = baselineArn(region, IDENTITY_CENTER_BASELINE_ID);
        boolean alreadyStored = stored.stream()
                .anyMatch(e -> identityCenterBaselineArn.equals(e.getBaselineIdentifier()));
        if (alreadyStored) {
            return false;
        }
        JsonNode manifest = getOrSeedLandingZone(accountId, region).getManifest();
        return manifest.path("accessManagement").path("enabled").asBoolean(false);
    }

    private EnabledBaseline syntheticIdentityCenterBaseline(String accountId, String region) {
        LandingZone lz = getOrSeedLandingZone(accountId, region);
        return new EnabledBaseline(
                "arn:aws:controltower:" + region + ":" + accountId
                        + ":enabledbaseline/" + IDENTITY_CENTER_ENABLED_BASELINE_ID,
                baselineArn(region, IDENTITY_CENTER_BASELINE_ID),
                IDENTITY_CENTER_BASELINE_VERSION,
                lz.getArn(),
                OP_SUCCEEDED,
                null);
    }

    private static String baselineArn(String region, String baselineId) {
        return "arn:aws:controltower:" + region + "::baseline/" + baselineId;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static List<String> readRemediationTypes(JsonNode request) {
        if (!request.has("remediationTypes")) {
            return null;
        }
        JsonNode array = request.get("remediationTypes");
        if (array == null || array.isNull()) {
            return null;
        }
        if (!array.isArray()) {
            throw validation("remediationTypes must be an array.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    public record EnableBaselineResult(String operationIdentifier, String arn) {
    }

    private record BaselineCatalogEntry(String name, String id, String description) {
        String arn(String region) {
            return baselineArn(region, id);
        }
    }
}
