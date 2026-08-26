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
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control Tower landing-zone and baseline emulation, backed by the configured Floci storage mode.
 *
 * <p>Pre-seeds exactly one active landing zone on first read (lazy seed) so LZA's Prepare stage
 * never observes an empty {@code ListLandingZones} result — an empty list is LZA's create-path
 * trigger. {@link #updateLandingZone} is a
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
    /** Per account+region ledger cap. LZA polls only recent operations, so older ones are evicted. */
    private static final int MAX_OPERATIONS_PER_SCOPE = 250;
    private static final String OP_TYPE_UPDATE = "UPDATE";
    private static final String OP_TYPE_CREATE = "CREATE";
    private static final String OP_TYPE_DELETE = "DELETE";
    private static final String OP_TYPE_RESET = "RESET";
    private static final String OP_TYPE_BASELINE_ENABLED = "BASELINE_ENABLED";
    private static final String OP_TYPE_BASELINE_UPDATE = "UPDATE_ENABLED_BASELINE";
    private static final String OP_TYPE_BASELINE_RESET = "BASELINE_RESET";
    private static final String IDENTITY_CENTER_BASELINE_NAME = "IdentityCenterBaseline";
    private static final String IDENTITY_CENTER_BASELINE_ID = "LN25R72TTG6IGPTQ";
    private static final String IDENTITY_CENTER_ENABLED_BASELINE_ID = "FLOCIIDCBASELINE1";
    private static final String IDENTITY_CENTER_BASELINE_VERSION = "1.0";
    private static final String CONTROL_TOWER_BASELINE_ID = "17BSJV3IGJ2QSGA2";

    // Static baseline catalog. Only `name` is load-bearing (LZA matches case-insensitively on
    // name at register-organizational-unit/index.ts:109-111 and :502); ids are fixed for
    // determinism. Baseline arns are region-qualified with an empty account field, like real CT.
    private static final List<BaselineCatalogEntry> BASELINE_CATALOG = List.of(
            new BaselineCatalogEntry("AWSControlTowerBaseline", CONTROL_TOWER_BASELINE_ID,
                    "Sets up resources to govern an OU."),
            new BaselineCatalogEntry(IDENTITY_CENTER_BASELINE_NAME, IDENTITY_CENTER_BASELINE_ID,
                    "Sets up resources shared for IAM Identity Center access."),
            new BaselineCatalogEntry("AuditBaseline", "J8HX46AHS5MIKQPD",
                    "Sets up resources for the audit account."),
            new BaselineCatalogEntry("LogArchiveBaseline", "3WFXIAO9KPBTB5TE",
                    "Sets up resources for the log archive account."));

    private final StorageBackend<String, LandingZone> landingZoneStore;
    private final StorageBackend<String, EnabledBaseline> enabledBaselineStore;
    private final OrganizationsService organizationsService;
    // Operation ledgers keyed by "accountId::region": opId -> operationType. In-memory on purpose:
    // pollers within one pipeline run are the only consumers, and unknown ids still answer
    // SUCCEEDED (restart-safe for LZA). Scoped so one account cannot enumerate another's
    // operations, and capped so a long-lived emulator cannot grow the ledger without bound.
    private final Map<String, OperationLedger> operationLedgers = new ConcurrentHashMap<>();

    @Inject
    public ControlTowerService(StorageFactory storageFactory, OrganizationsService organizationsService) {
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
                        }),
                organizationsService);
    }

    ControlTowerService(
            StorageBackend<String, LandingZone> landingZoneStore,
            StorageBackend<String, EnabledBaseline> enabledBaselineStore) {
        this(landingZoneStore, enabledBaselineStore, null);
    }

    ControlTowerService(
            StorageBackend<String, LandingZone> landingZoneStore,
            StorageBackend<String, EnabledBaseline> enabledBaselineStore,
            OrganizationsService organizationsService) {
        this.landingZoneStore = landingZoneStore;
        this.enabledBaselineStore = enabledBaselineStore;
        this.organizationsService = organizationsService;
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

    /**
     * Resolves the landing zone a read/reconcile request names, seeding first so a fresh
     * account+region still answers with its deterministic ARN. Delete and reset deliberately do
     * NOT go through here: they must report a missing landing zone rather than re-seeding one.
     */
    private LandingZone requireSeededLandingZone(
            String accountId, String region, String landingZoneIdentifier) {
        LandingZone landingZone = getOrSeedLandingZone(accountId, region);
        if (!landingZone.getArn().equals(landingZoneIdentifier)) {
            throw new AwsException("ResourceNotFoundException",
                    "Landing zone not found: " + landingZoneIdentifier, 404);
        }
        return landingZone;
    }

    public synchronized CreateLandingZoneResult createLandingZone(
            String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        JsonNode manifest = request.get("manifest");
        if (manifest == null || !manifest.isObject()) {
            throw validation("manifest must be a JSON object.");
        }
        String version = requireText(request, "version");
        if (!version.matches("^\\d+\\.\\d+$") || version.length() < 3 || version.length() > 10) {
            throw validation("version must be a valid landing zone version.");
        }
        validateTags(request.get("tags"));
        if (landingZoneStore.get(region).isPresent()) {
            throw new AwsException("ConflictException",
                    "Updating or deleting the resource can cause an inconsistent state.", 409);
        }

        String arn = "arn:aws:controltower:" + region + ":" + accountId + ":landingzone/" + shortId();
        LandingZone landingZone = new LandingZone(
                arn, version, version, STATUS_ACTIVE, DRIFT_IN_SYNC, manifest, null);
        landingZoneStore.put(region, landingZone);
        String operationIdentifier = UUID.randomUUID().toString();
        recordOperation(accountId, region, operationIdentifier, OP_TYPE_CREATE);
        return new CreateLandingZoneResult(arn, operationIdentifier);
    }

    public synchronized LandingZone getLandingZone(String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        return requireSeededLandingZone(accountId, region, requireText(request, "landingZoneIdentifier"));
    }

    public synchronized String updateLandingZone(String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String landingZoneIdentifier = requireText(request, "landingZoneIdentifier");
        String version = requireText(request, "version");
        JsonNode manifest = request.get("manifest");
        if (manifest == null || !manifest.isObject()) {
            throw validation("manifest must be a JSON object.");
        }
        List<String> remediationTypes = readRemediationTypes(request);

        LandingZone lz = requireSeededLandingZone(accountId, region, landingZoneIdentifier);
        lz.setVersion(version);
        lz.setManifest(manifest);
        lz.setRemediationTypes(remediationTypes);
        lz.setLatestAvailableVersion(LANDING_ZONE_VERSION);
        lz.setStatus(STATUS_ACTIVE);
        lz.setDriftStatus(DRIFT_IN_SYNC);
        landingZoneStore.put(region, lz);

        String opId = UUID.randomUUID().toString();
        recordOperation(accountId, region, opId, OP_TYPE_UPDATE);
        return opId;
    }

    public synchronized String deleteLandingZone(String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String landingZoneIdentifier = requireText(request, "landingZoneIdentifier");
        LandingZone landingZone = landingZoneStore.get(region)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Landing zone not found: " + landingZoneIdentifier, 404));
        if (!landingZone.getArn().equals(landingZoneIdentifier)) {
            throw new AwsException("ResourceNotFoundException",
                    "Landing zone not found: " + landingZoneIdentifier, 404);
        }
        landingZoneStore.delete(region);

        String opId = UUID.randomUUID().toString();
        recordOperation(accountId, region, opId, OP_TYPE_DELETE);
        return opId;
    }

    public synchronized String resetLandingZone(String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String landingZoneIdentifier = requireText(request, "landingZoneIdentifier");
        LandingZone landingZone = landingZoneStore.get(region)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Landing zone not found: " + landingZoneIdentifier, 404));
        if (!landingZone.getArn().equals(landingZoneIdentifier)) {
            throw new AwsException("ResourceNotFoundException",
                    "Landing zone not found: " + landingZoneIdentifier, 404);
        }

        String opId = UUID.randomUUID().toString();
        recordOperation(accountId, region, opId, OP_TYPE_RESET);
        return opId;
    }

    public String getOperationType(String accountId, String region, String operationIdentifier) {
        String recorded = recordedOperationType(accountId, region, operationIdentifier);
        return recorded == null ? OP_TYPE_UPDATE : recorded;
    }

    public ListLandingZoneOperationsResult listLandingZoneOperations(
            String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        int maxResults = readLandingZoneOperationsMaxResults(request);
        int start = readNextToken(request);
        Set<String> types = Set.of();
        Set<String> statuses = Set.of();
        JsonNode filter = request.get("filter");
        if (filter != null && !filter.isNull()) {
            requireObject(filter, "filter");
            types = readLandingZoneOperationFilter(filter, "types",
                    Set.of("DELETE", "CREATE", "UPDATE", "RESET"));
            statuses = readLandingZoneOperationFilter(filter, "statuses",
                    Set.of("SUCCEEDED", "FAILED", "IN_PROGRESS"));
        }

        OperationLedger ledger = operationLedgers.get(ledgerKey(accountId, region));
        List<LandingZoneOperationSummary> matching = new ArrayList<>();
        for (Map.Entry<String, String> operation : ledger == null ? List.<Map.Entry<String, String>>of()
                : ledger.newestFirst()) {
            String operationType = operation.getValue();
            if (!Set.of("DELETE", "CREATE", "UPDATE", "RESET").contains(operationType)
                    || (!types.isEmpty() && !types.contains(operationType))) {
                continue;
            }
            if (!statuses.isEmpty() && !statuses.contains(OP_SUCCEEDED)) {
                continue;
            }
            matching.add(new LandingZoneOperationSummary(operation.getKey(), operationType, OP_SUCCEEDED));
        }
        if (start > matching.size()) {
            throw validation("nextToken is invalid.");
        }
        int end = Math.min(start + maxResults, matching.size());
        String nextToken = end < matching.size() ? Integer.toString(end) : null;
        return new ListLandingZoneOperationsResult(matching.subList(start, end), nextToken);
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
        return listEnabledBaselines(accountId, region, JsonNodeFactory.instance.objectNode()).enabledBaselines();
    }

    public synchronized ListEnabledBaselinesResult listEnabledBaselines(
            String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String prefix = region + "::";
        List<EnabledBaseline> stored = enabledBaselineStore.scan(key -> key.startsWith(prefix));
        reconcileControlTowerGuardrails(accountId, stored);

        List<EnabledBaseline> result = new ArrayList<>(stored.size() + 1);
        if (identityCenterAutoEnabled(accountId, region, stored)) {
            result.add(syntheticIdentityCenterBaseline(accountId, region));
        }
        result.addAll(stored);
        result.sort(Comparator.comparing(EnabledBaseline::getArn, Comparator.nullsFirst(String::compareTo)));

        JsonNode filter = request.get("filter");
        if (filter != null && !filter.isNull()) {
            requireObject(filter, "filter");
            Set<String> baselines = readStringSet(filter, "baselineIdentifiers", 5);
            Set<String> targets = readStringSet(filter, "targetIdentifiers", 5);
            Set<String> parents = readStringSet(filter, "parentIdentifiers", 5);
            Set<String> statuses = readStringSet(filter, "statuses", 1);
            Set<String> driftStatuses = readStringSet(filter, "inheritanceDriftStatuses", 1);
            validateEnumValues(statuses, Set.of("SUCCEEDED", "FAILED", "UNDER_CHANGE"), "statuses");
            validateEnumValues(driftStatuses, Set.of("IN_SYNC", "DRIFTED"), "inheritanceDriftStatuses");
            result.removeIf(entry -> (!baselines.isEmpty() && !baselines.contains(entry.getBaselineIdentifier()))
                    || (!targets.isEmpty() && !targets.contains(entry.getTargetIdentifier()))
                    || (!parents.isEmpty() && !parents.contains(entry.getParentIdentifier()))
                    || (!statuses.isEmpty() && !statuses.contains(entry.getStatus()))
                    || (!driftStatuses.isEmpty() && !driftStatuses.contains(
                            Optional.ofNullable(entry.getDriftStatus()).orElse("IN_SYNC"))));
        }

        boolean includeChildren = readOptionalBoolean(request, "includeChildren");
        if (includeChildren) {
            // Child enabled baselines are not materialized by Floci; the response therefore
            // contains only the parent resources represented by the configured stores.
        }
        int maxResults = readMaxResults(request);
        int offset = readNextToken(request);
        if (offset > result.size()) {
            throw validation("nextToken is invalid.");
        }
        int end = Math.min(result.size(), offset + maxResults);
        String nextToken = end < result.size() ? String.valueOf(end) : null;
        return new ListEnabledBaselinesResult(new ArrayList<>(result.subList(offset, end)), nextToken);
    }

    public synchronized EnabledBaseline getEnabledBaseline(String accountId, String region, String identifier) {
        if (identifier == null || identifier.isBlank() || !isArn(identifier)) {
            throw validation("enabledBaselineIdentifier must be a string.");
        }
        return listEnabledBaselines(accountId, region).stream()
                .filter(entry -> identifier.equals(entry.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The request references a resource that does not exist.", 404));
    }

    private boolean isIdentityCenterBaseline(String arn) {
        return arn != null && arn.endsWith(":enabledbaseline/" + IDENTITY_CENTER_ENABLED_BASELINE_ID);
    }

    public synchronized EnableBaselineResult enableBaseline(String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String baselineIdentifier = requireText(request, "baselineIdentifier");
        if (!isArn(baselineIdentifier)) {
            throw validation("baselineIdentifier must be a valid ARN.");
        }
        String baselineVersion = requireText(request, "baselineVersion");
        requireBaselineVersion(baselineVersion);
        String targetIdentifier = requireText(request, "targetIdentifier");
        if (!isArn(targetIdentifier)) {
            throw validation("targetIdentifier must be a valid ARN.");
        }
        JsonNode parameters = request.get("parameters");

        if (isControlTowerOuBaseline(baselineIdentifier)) {
            reconcileControlTowerGuardrails(accountId, targetIdentifier);
        }

        String key = region + "::" + targetIdentifier;
        String arn = "arn:aws:controltower:" + region + ":" + accountId
                + ":enabledbaseline/" + shortId();
        String opId = UUID.randomUUID().toString();
        EnabledBaseline value = new EnabledBaseline(
                arn, baselineIdentifier, baselineVersion, targetIdentifier, OP_SUCCEEDED, parameters);
        value.setLastOperationIdentifier(opId);
        enabledBaselineStore.put(key, value);

        recordOperation(accountId, region, opId, OP_TYPE_BASELINE_ENABLED);
        return new EnableBaselineResult(opId, arn);
    }

    public synchronized String resetEnabledBaseline(String accountId, String region, String enabledBaselineIdentifier) {
        if (enabledBaselineIdentifier == null || enabledBaselineIdentifier.isBlank() || !isArn(enabledBaselineIdentifier)) {
            throw validation("enabledBaselineIdentifier must be a string.");
        }
        EnabledBaseline baseline = getEnabledBaseline(accountId, region, enabledBaselineIdentifier);
        String opId = UUID.randomUUID().toString();
        baseline.setLastOperationIdentifier(opId);
        String key = region + "::" + baseline.getTargetIdentifier();
        enabledBaselineStore.put(key, baseline);
        recordOperation(accountId, region, opId, OP_TYPE_BASELINE_RESET);
        return opId;
    }

    public synchronized String updateEnabledBaseline(
            String accountId, String region, JsonNode request) {
        requireObject(request, "Request body");
        String enabledBaselineIdentifier = requireText(request, "enabledBaselineIdentifier");
        if (!isArn(enabledBaselineIdentifier)) {
            throw validation("enabledBaselineIdentifier must be a valid ARN.");
        }
        String baselineVersion = requireText(request, "baselineVersion");
        requireBaselineVersion(baselineVersion);

        EnabledBaseline baseline = getEnabledBaseline(accountId, region, enabledBaselineIdentifier);
        JsonNode parameters = request.get("parameters");
        validateParameters(parameters);
        baseline.setBaselineVersion(baselineVersion);
        if (parameters != null && !parameters.isNull()) {
            baseline.setParameters(parameters);
        }
        baseline.setStatus(OP_SUCCEEDED);
        String opId = UUID.randomUUID().toString();
        baseline.setLastOperationIdentifier(opId);
        String key = region + "::" + baseline.getTargetIdentifier();
        enabledBaselineStore.put(key, baseline);
        recordOperation(accountId, region, opId, OP_TYPE_BASELINE_UPDATE);
        return opId;
    }

    public String getBaselineOperationType(String accountId, String region, String operationIdentifier) {
        String recorded = recordedOperationType(accountId, region, operationIdentifier);
        return recorded == null ? OP_TYPE_BASELINE_ENABLED : recorded;
    }

    private void reconcileControlTowerGuardrails(String accountId, List<EnabledBaseline> baselines) {
        if (organizationsService == null) {
            return;
        }
        Set<String> ouIds = baselines.stream()
                .filter(baseline -> isControlTowerOuBaseline(baseline.getBaselineIdentifier()))
                .map(EnabledBaseline::getTargetIdentifier)
                .map(ControlTowerService::organizationalUnitId)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        organizationsService.ensureControlTowerGuardrails(accountId, ouIds);
    }

    private void reconcileControlTowerGuardrails(String accountId, String targetIdentifier) {
        if (organizationsService == null) {
            return;
        }
        organizationalUnitId(targetIdentifier).ifPresent(ouId ->
                organizationsService.ensureControlTowerGuardrails(accountId, Set.of(ouId)));
    }

    private static boolean isControlTowerOuBaseline(String baselineIdentifier) {
        return baselineIdentifier != null && baselineIdentifier.endsWith("/" + CONTROL_TOWER_BASELINE_ID);
    }

    private static Optional<String> organizationalUnitId(String targetIdentifier) {
        if (targetIdentifier == null) {
            return Optional.empty();
        }
        String candidate = targetIdentifier.substring(targetIdentifier.lastIndexOf('/') + 1);
        return candidate.startsWith("ou-") ? Optional.of(candidate) : Optional.empty();
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

    private static Set<String> readStringSet(JsonNode parent, String field, int maxSize) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return Set.of();
        }
        if (!value.isArray() || value.size() == 0 || value.size() > maxSize) {
            throw validation(field + " must contain between 1 and " + maxSize + " items.");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw validation(field + " must contain strings.");
            }
            if ((field.endsWith("Identifiers") || "baselineIdentifiers".equals(field))
                    && !isArn(item.textValue())) {
                throw validation(field + " must contain ARN values.");
            }
            result.add(item.textValue());
        }
        return result;
    }

    private static boolean readOptionalBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static void validateEnumValues(Set<String> values, Set<String> allowed, String field) {
        if (values.stream().anyMatch(value -> !allowed.contains(value))) {
            throw validation(field + " contains an invalid value.");
        }
    }

    private static int readMaxResults(JsonNode request) {
        JsonNode value = request.get("maxResults");
        if (value == null || value.isNull()) {
            return 100;
        }
        if (!value.isIntegralNumber() || value.intValue() < 5 || value.intValue() > 100) {
            throw validation("maxResults must be between 5 and 100.");
        }
        return value.intValue();
    }

    private static int readLandingZoneOperationsMaxResults(JsonNode request) {
        JsonNode value = request.get("maxResults");
        if (value == null || value.isNull()) {
            return 100;
        }
        if (!value.isIntegralNumber() || value.intValue() < 1 || value.intValue() > 100) {
            throw validation("maxResults must be between 1 and 100.");
        }
        return value.intValue();
    }

    private static Set<String> readLandingZoneOperationFilter(
            JsonNode filter, String field, Set<String> allowed) {
        JsonNode value = filter.get(field);
        if (value == null || value.isNull()) {
            return Set.of();
        }
        if (!value.isArray() || value.size() != 1 || !value.get(0).isTextual()
                || !allowed.contains(value.get(0).textValue())) {
            throw validation(field + " must contain exactly one valid value.");
        }
        return Set.of(value.get(0).textValue());
    }

    private void recordOperation(
            String accountId, String region, String operationIdentifier, String operationType) {
        operationLedgers
                .computeIfAbsent(ledgerKey(accountId, region), key -> new OperationLedger())
                .record(operationIdentifier, operationType);
    }

    /**
     * Looks up an operation type without creating a ledger — read paths must never materialize a
     * scope, or an unauthenticated caller could grow the map one bogus account at a time.
     */
    private String recordedOperationType(String accountId, String region, String operationIdentifier) {
        OperationLedger ledger = operationLedgers.get(ledgerKey(accountId, region));
        return ledger == null ? null : ledger.type(operationIdentifier);
    }

    private static String ledgerKey(String accountId, String region) {
        return accountId + "::" + region;
    }

    /**
     * Insertion-ordered operation ledger for one account+region, holding at most
     * {@value #MAX_OPERATIONS_PER_SCOPE} entries and evicting the oldest first.
     */
    private static final class OperationLedger {

        private final LinkedHashMap<String, String> operations = new LinkedHashMap<>();

        synchronized void record(String operationIdentifier, String operationType) {
            operations.put(operationIdentifier, operationType);
            Iterator<String> oldest = operations.keySet().iterator();
            while (operations.size() > MAX_OPERATIONS_PER_SCOPE && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }

        synchronized String type(String operationIdentifier) {
            return operations.get(operationIdentifier);
        }

        /** Snapshot in newest-first order so callers iterate outside the lock. */
        synchronized List<Map.Entry<String, String>> newestFirst() {
            List<Map.Entry<String, String>> snapshot = new ArrayList<>(operations.size());
            operations.forEach((identifier, type) -> snapshot.add(Map.entry(identifier, type)));
            Collections.reverse(snapshot);
            return snapshot;
        }
    }

    private static int readNextToken(JsonNode request) {
        JsonNode value = request.get("nextToken");
        if (value == null || value.isNull()) {
            return 0;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw validation("nextToken must be a non-empty string.");
        }
        try {
            return Integer.parseInt(value.textValue());
        } catch (NumberFormatException e) {
            throw validation("nextToken is invalid.");
        }
    }

    /**
     * The model pins {@code baselineVersion} to {@code \d+(?:\.\d+){0,2}} with a maximum length of
     * 10, on both EnableBaseline and UpdateEnabledBaseline — the value is stored verbatim, so an
     * unchecked one would persist a version AWS never accepts.
     */
    private static void requireBaselineVersion(String baselineVersion) {
        if (!baselineVersion.matches("^\\d+(?:\\.\\d+){0,2}$") || baselineVersion.length() > 10) {
            throw validation("baselineVersion must be a valid version.");
        }
    }

    private static boolean isArn(String value) {
        return value.length() >= 20 && value.matches("^arn:aws[0-9a-zA-Z_\\-:\\/]+$");
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
        // The model pins the list to exactly one element (min 1 / max 1) whose only
        // valid value is INHERITANCE_DRIFT.
        if (array.size() != 1 || !"INHERITANCE_DRIFT".equals(array.get(0).asText())) {
            throw validation("remediationTypes must contain exactly one value: INHERITANCE_DRIFT.");
        }
        List<String> values = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            values.add(value.asText());
        }
        return values;
    }

    private static void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isObject() || tags.size() > 200) {
            throw validation("tags must be an object with at most 200 entries.");
        }
        tags.fields().forEachRemaining(entry -> {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128
                    || !entry.getValue().isTextual() || entry.getValue().textValue().length() > 256) {
                throw validation("tags must contain string values with valid lengths.");
            }
        });
    }

    private static void validateParameters(JsonNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return;
        }
        if (!parameters.isArray()) {
            throw validation("parameters must be an array.");
        }
        for (JsonNode parameter : parameters) {
            requireObject(parameter, "parameters item");
            requireText(parameter, "key");
            if (!parameter.has("value") || parameter.get("value").isMissingNode()) {
                throw validation("parameters item must include value.");
            }
        }
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

    public record CreateLandingZoneResult(String arn, String operationIdentifier) {
    }

    public record EnableBaselineResult(String operationIdentifier, String arn) {
    }

    public record ListEnabledBaselinesResult(List<EnabledBaseline> enabledBaselines, String nextToken) {
    }

    public record ListLandingZoneOperationsResult(
            List<LandingZoneOperationSummary> landingZoneOperations, String nextToken) {
    }

    public record LandingZoneOperationSummary(
            String operationIdentifier, String operationType, String status) {
    }

    private record BaselineCatalogEntry(String name, String id, String description) {
        String arn(String region) {
            return baselineArn(region, id);
        }
    }
}
