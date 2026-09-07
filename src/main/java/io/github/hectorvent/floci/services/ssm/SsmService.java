package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.PatchBaselineIdentity;
import io.github.hectorvent.floci.services.ssm.model.ServiceSetting;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import java.util.ArrayList;
import java.util.Set;

@ApplicationScoped
public class SsmService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(SsmService.class);

    /**
     * Account-default values for the service settings floci models. AWS rejects
     * unknown setting ids with ServiceSettingNotFound; so do we.
     */
    private static final Map<String, String> SERVICE_SETTING_DEFAULTS = Map.of(
            "/ssm/documents/console/public-sharing-permission", "Enable",
            "/ssm/parameter-store/default-parameter-tier", "Standard",
            "/ssm/parameter-store/high-throughput-enabled", "false",
            "/ssm/managed-instance/activation-tier", "standard"
    );

    private final StorageBackend<String, Parameter> parameterStore;
    private final StorageBackend<String, List<ParameterHistory>> historyStore;
    private final StorageBackend<String, List<String>> documentPermissionStore;
    private final StorageBackend<String, SsmDocument> documentStore;
    private final StorageBackend<String, ServiceSetting> serviceSettingStore;
    private final StorageBackend<String, SsmAssociation> associationStore;
    private final int maxParameterHistory;
    private final RegionResolver regionResolver;

    @Inject
    public SsmService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this(
                storageFactory.create("ssm", "ssm-parameters.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-history.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-document-permissions.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-documents.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-service-settings.json",
                        new TypeReference<>() {
                        }),
                storageFactory.create("ssm", "ssm-associations.json",
                        new TypeReference<>() {
                        }),
                config.services().ssm().maxParameterHistory(),
                regionResolver
        );
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, documentPermissionStore, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000"));
    }

    /**
     * Package-private constructor for testing without CDI.
     */
    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               int maxParameterHistory) {
        this(parameterStore, historyStore, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), maxParameterHistory,
                new RegionResolver("us-east-1", "000000000000"));
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               StorageBackend<String, SsmDocument> documentStore,
               StorageBackend<String, ServiceSetting> serviceSettingStore,
               int maxParameterHistory, RegionResolver regionResolver) {
        this(parameterStore, historyStore, documentPermissionStore, documentStore,
                serviceSettingStore, new InMemoryStorage<>(), maxParameterHistory, regionResolver);
    }

    SsmService(StorageBackend<String, Parameter> parameterStore,
               StorageBackend<String, List<ParameterHistory>> historyStore,
               StorageBackend<String, List<String>> documentPermissionStore,
               StorageBackend<String, SsmDocument> documentStore,
               StorageBackend<String, ServiceSetting> serviceSettingStore,
               StorageBackend<String, SsmAssociation> associationStore,
               int maxParameterHistory, RegionResolver regionResolver) {
        this.parameterStore = parameterStore;
        this.historyStore = historyStore;
        this.documentPermissionStore = documentPermissionStore;
        this.documentStore = documentStore;
        this.serviceSettingStore = serviceSettingStore;
        this.associationStore = associationStore;
        this.maxParameterHistory = maxParameterHistory;
        this.regionResolver = regionResolver;
    }

    /**
     * Create or update a parameter.
     * Returns the version number.
     */
    public long putParameter(String name, String value, String type, String description, boolean overwrite, String region) {
        String storageKey = regionKey(region, name);
        Parameter existing = parameterStore.get(storageKey).orElse(null);

        if (existing != null && !overwrite) {
            throw new AwsException("ParameterAlreadyExists",
                    "The parameter already exists. To overwrite this value, set the overwrite option in the request to true.",
                    400);
        }

        long version = (existing != null) ? existing.getVersion() + 1 : 1;

        Parameter parameter = new Parameter(name, value, type != null ? type : "String");
        parameter.setVersion(version);
        parameter.setDescription(description);
        parameter.setArn(regionResolver.buildArn("ssm", region, "parameter" + name));
        parameter.setLastModifiedDate(Instant.now());

        parameterStore.put(storageKey, parameter);
        addHistory(storageKey, parameter);

        LOG.infov("Put parameter: {0} in region {1} (version {2})", name, region, version);
        return version;
    }

    public Parameter getParameter(String name, String region) {
        String storageKey = regionKey(region, name);
        return parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("ParameterNotFound",
                        "Parameter " + name + " not found.", 400));
    }

    public List<Parameter> getParameters(List<String> names, String region) {
        List<Parameter> result = new ArrayList<>();
        for (String name : names) {
            parameterStore.get(regionKey(region, name)).ifPresent(result::add);
        }
        return result;
    }

    public List<Parameter> getParametersByPath(String path, boolean recursive, String region) {
        String normalizedPath = path.endsWith("/") ? path : path + "/";
        String prefix = region + "::";

        return parameterStore.scan(key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            String paramName = key.substring(prefix.length());
            if (!paramName.startsWith(normalizedPath)) {
                return false;
            }
            if (recursive) {
                return true;
            }
            String remainder = paramName.substring(normalizedPath.length());
            return !remainder.contains("/");
        });
    }

    public void deleteParameter(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        parameterStore.delete(storageKey);
        historyStore.delete(storageKey);
        LOG.infov("Deleted parameter: {0}", name);
    }

    public List<String> deleteParameters(List<String> names, String region) {
        List<String> deleted = new ArrayList<>();
        for (String name : names) {
            String storageKey = regionKey(region, name);
            if (parameterStore.get(storageKey).isPresent()) {
                parameterStore.delete(storageKey);
                historyStore.delete(storageKey);
                deleted.add(name);
            }
        }
        return deleted;
    }

    public List<ParameterHistory> getParameterHistory(String name, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }
        return historyStore.get(storageKey).orElse(Collections.emptyList());
    }

    public List<Parameter> describeParameters(String region) {
        return describeParameters(List.of(), region);
    }

    public List<Parameter> describeParameters(List<String> nameFilters, String region) {
        String prefix = region + "::";
        return parameterStore.scan(key -> {
            if (!key.startsWith(prefix)) return false;
            if (nameFilters.isEmpty()) return true;
            String name = key.substring(prefix.length());
            return nameFilters.contains(name);
        });
    }

    public void labelParameterVersion(String name, long parameterVersion, List<String> labels, String region) {
        String storageKey = regionKey(region, name);
        if (parameterStore.get(storageKey).isEmpty()) {
            throw new AwsException("ParameterNotFound",
                    "Parameter " + name + " not found.", 400);
        }

        List<ParameterHistory> history = historyStore.get(storageKey)
                .orElse(List.of());

        history = new ArrayList<>(history);

        boolean found = false;
        for (ParameterHistory h : history) {
            if (h.getVersion() == parameterVersion) {
                List<String> existing = h.getLabels() != null ? new ArrayList<>(h.getLabels()) : new ArrayList<>();
                for (String label : labels) {
                    if (!existing.contains(label)) {
                        existing.add(label);
                    }
                }
                h.setLabels(existing);
                found = true;
                break;
            }
        }

        if (!found) {
            throw new AwsException("ParameterVersionNotFound", "Parameter version " + parameterVersion + " not found.", 400);
        }

        historyStore.put(storageKey, history);
        LOG.infov("Labeled parameter {0} version {1} with labels {2}", name, parameterVersion, labels);
    }

    public void addTagsToResource(String resourceId, Map<String, String> tags, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() == null) {
            param.setTags(new HashMap<>());
        }
        param.getTags().putAll(tags);
        parameterStore.put(storageKey, param);
        LOG.debugv("Added tags to parameter: {0}", resourceId);
    }

    public Map<String, String> listTagsForResource(String resourceId, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));
        return param.getTags() != null ? param.getTags() : Map.of();
    }

    public void removeTagsFromResource(String resourceId, List<String> tagKeys, String region) {
        String storageKey = regionKey(region, resourceId);
        Parameter param = parameterStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidResourceId",
                        "Resource " + resourceId + " not found.", 400));

        if (param.getTags() != null) {
            for (String key : tagKeys) {
                param.getTags().remove(key);
            }
            parameterStore.put(storageKey, param);
        }
        LOG.debugv("Removed tags from parameter: {0}", resourceId);
    }

    // ──────────────────────── Documents and Share Permissions ────────────────
    // Documents live in documentStore; share state is kept alongside it in
    // documentPermissionStore so callers like LZA's Custom::SSMShareDocument handler
    // can round-trip ModifyDocumentPermission -> DescribeDocumentPermission.
    // Both permission operations resolve the document first, so an unknown document
    // raises InvalidDocument instead of silently minting or reporting share state for
    // a document that does not exist.

    public SsmDocument getDocument(String name, String region) {
        SsmDocument document = documentStore.get(regionKey(region, name))
                .orElseThrow(() -> new AwsException("InvalidDocument",
                        "Document " + name + " does not exist.", 400));
        if (document.getOwner() == null) {
            document.setOwner(regionResolver.getAccountId());
        }
        return document;
    }

    public synchronized SsmDocument createDocument(String name, String content, String documentType, String region) {
        String storageKey = regionKey(region, name);
        if (documentStore.get(storageKey).isPresent()) {
            throw new AwsException("DocumentAlreadyExists",
                    "Document " + name + " already exists.", 400);
        }
        SsmDocument document = new SsmDocument(name, content, documentType);
        document.setOwner(regionResolver.getAccountId());
        documentStore.put(storageKey, document);
        return document;
    }

    public SsmDocument updateDocument(String name, String content, String region) {
        String storageKey = regionKey(region, name);
        SsmDocument document = documentStore.get(storageKey)
                .orElseThrow(() -> new AwsException("InvalidDocument",
                        "Document " + name + " does not exist.", 400));
        if (Objects.equals(document.getContent(), content)) {
            throw new AwsException("DuplicateDocumentContent",
                    "The content of the association document matches another document. "
                            + "Change the content of the document and try again.", 400);
        }
        long newVersion = document.getDocumentVersion() + 1;
        document.setContent(content);
        document.setDocumentVersion(newVersion);
        document.getVersions().put(String.valueOf(newVersion), content);
        documentStore.put(storageKey, document);
        return document;
    }

    /**
     * Lists the accounts a document is shared with. AWS raises InvalidDocument for a
     * document that does not exist rather than returning an empty list, so the document
     * is resolved first.
     */
    public List<String> describeDocumentPermission(String name, String region) {
        getDocument(name, region);
        return documentPermissionStore.get(regionKey(region, name))
                .map(List::copyOf)
                .orElse(List.of());
    }

    /**
     * Shares (or un-shares) a document with other accounts.
     *
     * <p>Only the document's owner may share it. Ownership here <em>is</em> the storage
     * partition: {@code documentStore} is an {@code AccountAwareStorageBackend}, whose key
     * prefix is the caller's account from {@code RequestContext} — the same resolution
     * {@code RegionResolver.getAccountId()} performs — so {@link #getDocument} can only
     * resolve a document in the caller's own partition. A caller that does not own the
     * document therefore gets InvalidDocument, which is AWS's answer for a document the
     * caller cannot see. Deriving the check from the partition rather than a second stored
     * owner field keeps the guard and the storage scope from ever disagreeing.
     */
    public synchronized void modifyDocumentPermission(String name, List<String> accountIdsToAdd,
                                         List<String> accountIdsToRemove, String region) {
        getDocument(name, region);
        String storageKey = regionKey(region, name);
        List<String> accountIds = new ArrayList<>(
                documentPermissionStore.get(storageKey).orElse(List.of()));
        for (String accountId : accountIdsToAdd) {
            if (!accountIds.contains(accountId)) {
                accountIds.add(accountId);
            }
        }
        accountIds.removeAll(accountIdsToRemove);
        documentPermissionStore.put(storageKey, accountIds);
        LOG.debugv("Modified document permission for {0}: {1} account(s) shared", name, accountIds.size());
    }

    public List<SsmDocument> listDocuments(String region, Map<String, List<String>> filters) {
        String prefix = regionKey(region, "");
        List<SsmDocument> docs = documentStore.scan(k -> k.startsWith(prefix));
        String accountId = regionResolver.getAccountId();
        for (SsmDocument d : docs) {
            if (d.getOwner() == null) {
                d.setOwner(accountId);
            }
        }
        if (filters == null || filters.isEmpty()) {
            return docs;
        }

        List<String> nameFilters = findFilterValues(filters, "Name");
        List<String> typeFilters = findFilterValues(filters, "DocumentType");
        List<String> ownerFilters = findFilterValues(filters, "Owner");
        List<String> platformFilters = findFilterValues(filters, "PlatformTypes");

        return docs.stream()
                .filter(d -> nameFilters.isEmpty()
                        || nameFilters.contains(d.getName())
                        || nameFilters.stream().anyMatch(n -> !n.isEmpty() && d.getName() != null && d.getName().startsWith(n)))
                .filter(d -> typeFilters.isEmpty()
                        || typeFilters.stream().anyMatch(t -> t.equalsIgnoreCase(d.getDocumentType())))
                .filter(d -> ownerFilters.isEmpty()
                        || ownerFilters.stream().anyMatch(o -> matchesOwner(o, d.getOwner(), accountId)))
                .filter(d -> platformFilters.isEmpty()
                        || (d.getPlatformTypes() != null && platformFilters.stream()
                                .anyMatch(p -> d.getPlatformTypes().stream().anyMatch(p::equalsIgnoreCase))))
                .toList();
    }

    /**
     * Every document visible through {@code documentStore} already belongs to the caller's
     * account (it is an account-partitioned store), so "Self"/"Private"/"All" and the caller's own
     * account id all match every visible document; "Amazon"/"Public"/"ThirdParty" match none, since
     * no AWS-owned or other-account documents are ever visible here. Documents created before the
     * {@code Owner} field existed persist with a null owner; since the store already guarantees the
     * caller's own partition, such a document is normalized to the current caller's account ID so
     * that it both matches owner filters and serializes its effective owner in list responses.
     */
    private boolean matchesOwner(String filterValue, String documentOwner, String accountId) {
        if ("Self".equalsIgnoreCase(filterValue) || "Private".equalsIgnoreCase(filterValue)
                || "All".equalsIgnoreCase(filterValue)) {
            return true;
        }
        if ("Amazon".equalsIgnoreCase(filterValue) || "Public".equalsIgnoreCase(filterValue)
                || "ThirdParty".equalsIgnoreCase(filterValue)) {
            return false;
        }
        String effectiveOwner = documentOwner != null ? documentOwner : accountId;
        return filterValue.equals(effectiveOwner);
    }

    private List<String> findFilterValues(Map<String, List<String>> filters, String targetKey) {
        if (filters == null) {
            return List.of();
        }
        for (Map.Entry<String, List<String>> entry : filters.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(targetKey) && entry.getValue() != null) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    /**
     * Rejects a {@code DocumentVersion} that cannot resolve on the target document.
     * Stored document versions are retained across updates, so numeric versions are accepted
     * when they exist in the document's version history; {@code $LATEST}/{@code $DEFAULT}
     * always resolve to the current version. {@link SsmDocument#hasVersion} also accepts a
     * numeric version that predates the retained history (see its javadoc) rather than rejecting
     * a version that legitimately existed just because its content was never captured.
     * Non-existent versions or malformed inputs throw {@code InvalidDocumentVersion}, matching
     * AWS SSM behavior.
     */
    private void validateDocumentVersion(SsmDocument document, String documentVersion) {
        if (documentVersion == null || documentVersion.isBlank()
                || "$LATEST".equals(documentVersion) || "$DEFAULT".equals(documentVersion)) {
            return;
        }
        if (!document.hasVersion(documentVersion)) {
            throw new AwsException("InvalidDocumentVersion",
                    "The document version is not valid or does not exist.", 400);
        }
    }

    // ──────────────────────── Associations ───────────────────────────────────

    public synchronized SsmAssociation createAssociation(
            String name,
            String associationName,
            String documentVersion,
            String instanceId,
            List<SsmAssociation.Target> targets,
            Map<String, List<String>> parameters,
            String scheduleExpression,
            String region) {
        return createAssociation(name, associationName, documentVersion, instanceId, targets, parameters,
                scheduleExpression, null, null, null, region);
    }

    public synchronized SsmAssociation createAssociation(
            String name,
            String associationName,
            String documentVersion,
            String instanceId,
            List<SsmAssociation.Target> targets,
            Map<String, List<String>> parameters,
            String scheduleExpression,
            String maxErrors,
            String maxConcurrency,
            String complianceSeverity,
            String region) {
        SsmDocument document = getDocument(name, region);
        validateDocumentVersion(document, documentVersion);

        if (instanceId != null && !instanceId.isBlank()) {
            boolean duplicate = listAssociations(region).stream()
                    .anyMatch(a -> Objects.equals(a.getName(), name) && Objects.equals(a.getInstanceId(), instanceId));
            if (duplicate) {
                throw new AwsException("AssociationAlreadyExists",
                        "An association already exists for instance " + instanceId + " and document " + name + ".", 400);
            }
        } else if (targets != null && !targets.isEmpty()) {
            boolean duplicate = listAssociations(region).stream()
                    .anyMatch(a -> Objects.equals(a.getName(), name) && Objects.equals(a.getTargets(), targets));
            if (duplicate) {
                throw new AwsException("AssociationAlreadyExists",
                        "An association already exists for document " + name + " with the given targets.", 400);
            }
        }

        String associationId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        SsmAssociation association = new SsmAssociation();
        association.setAssociationId(associationId);
        association.setAssociationName(associationName);
        association.setName(name);
        association.setDocumentVersion(documentVersion);
        association.setInstanceId(instanceId);
        association.setTargets(targets);
        association.setParameters(parameters);
        association.setScheduleExpression(scheduleExpression);
        association.setMaxErrors(maxErrors);
        association.setMaxConcurrency(maxConcurrency);
        association.setComplianceSeverity(complianceSeverity);
        association.setAssociationVersion("1");
        // Real SSM starts an association at "Pending" and transitions it to "Success"/"Failed" once
        // its execution engine runs it. This emulator has no execution engine, so it reports
        // "Success" immediately rather than modeling a state that would never change on its own.
        association.setStatus(new SsmAssociation.AssociationStatus("Success", "Success", now, null));
        association.setOverview(new SsmAssociation.AssociationOverview("Success", "Success"));
        association.setCreatedDate(now);
        association.setLastExecutionDate(now);

        associationStore.put(regionKey(region, associationId), association);
        LOG.infov("Created association {0} ({1}) for document {2} in region {3}",
                associationId, associationName, name, region);
        return association;
    }

    public synchronized SsmAssociation updateAssociation(
            String associationId,
            String associationName,
            String documentVersion,
            List<SsmAssociation.Target> targets,
            Map<String, List<String>> parameters,
            String scheduleExpression,
            String maxErrors,
            String maxConcurrency,
            String complianceSeverity,
            String region) {
        String storageKey = regionKey(region, associationId);
        SsmAssociation association = associationStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AssociationDoesNotExist",
                        "The specified association does not exist.", 400));

        if (documentVersion != null) {
            validateDocumentVersion(getDocument(association.getName(), region), documentVersion);
        }

        if (associationName != null) association.setAssociationName(associationName);
        if (documentVersion != null) association.setDocumentVersion(documentVersion);
        if (targets != null) association.setTargets(targets);
        if (parameters != null) association.setParameters(parameters);
        if (scheduleExpression != null) association.setScheduleExpression(scheduleExpression);
        if (maxErrors != null) association.setMaxErrors(maxErrors);
        if (maxConcurrency != null) association.setMaxConcurrency(maxConcurrency);
        if (complianceSeverity != null) association.setComplianceSeverity(complianceSeverity);

        long currentVersion = parseAssociationVersion(association.getAssociationVersion());
        association.setAssociationVersion(String.valueOf(currentVersion + 1));

        associationStore.put(storageKey, association);
        LOG.infov("Updated association {0} in region {1}", associationId, region);
        return association;
    }

    private static long parseAssociationVersion(String version) {
        try {
            return version == null ? 1 : Long.parseLong(version);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public List<SsmAssociation> listAssociations(String region) {
        return listAssociations(region, Map.of());
    }

    public List<SsmAssociation> listAssociations(String region, Map<String, List<String>> filters) {
        String prefix = regionKey(region, "");
        List<SsmAssociation> associations = associationStore.scan(k -> k.startsWith(prefix));
        if (filters == null || filters.isEmpty()) {
            return associations;
        }

        List<String> instanceIdFilters = findFilterValues(filters, "InstanceId");
        List<String> associationIdFilters = findFilterValues(filters, "AssociationId");
        List<String> nameFilters = findFilterValues(filters, "Name");
        List<String> associationNameFilters = findFilterValues(filters, "AssociationName");

        return associations.stream()
                .filter(a -> instanceIdFilters.isEmpty() || instanceIdFilters.contains(a.getInstanceId()))
                .filter(a -> associationIdFilters.isEmpty() || associationIdFilters.contains(a.getAssociationId()))
                .filter(a -> nameFilters.isEmpty() || nameFilters.contains(a.getName()))
                .filter(a -> associationNameFilters.isEmpty() || associationNameFilters.contains(a.getAssociationName()))
                .toList();
    }

    public SsmAssociation describeAssociation(String associationId, String name, String instanceId, String region) {
        if (associationId != null && !associationId.isBlank()) {
            return associationStore.get(regionKey(region, associationId))
                    .orElseThrow(() -> new AwsException("AssociationDoesNotExist",
                            "The specified association does not exist.", 400));
        }
        if (name != null && instanceId != null) {
            String prefix = regionKey(region, "");
            return associationStore.scan(k -> k.startsWith(prefix)).stream()
                    .filter(a -> Objects.equals(a.getName(), name) && Objects.equals(a.getInstanceId(), instanceId))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("AssociationDoesNotExist",
                            "The specified association does not exist.", 400));
        }
        throw new AwsException("ValidationException",
                "Either AssociationId or both Name and InstanceId must be specified.", 400);
    }

    public synchronized void deleteAssociation(String associationId, String name, String instanceId, String region) {
        SsmAssociation association = describeAssociation(associationId, name, instanceId, region);
        associationStore.delete(regionKey(region, association.getAssociationId()));
        LOG.infov("Deleted association {0} in region {1}", association.getAssociationId(), region);
    }

    // ──────────────────────────── Patch Baselines ────────────────────────────
    // AWS provides a fixed set of AWS-owned predefined patch baselines (one default per operating
    // system). These are static reference data, not customer state, so they live in-memory only.

    private static final List<PatchBaselineIdentity> PREDEFINED_BASELINES = buildPredefinedBaselines();

    private static List<PatchBaselineIdentity> buildPredefinedBaselines() {
        String[][] defs = {
                {"WINDOWS", "AWS-DefaultPatchBaseline", "Windows"},
                {"AMAZON_LINUX", "AWS-AmazonLinuxDefaultPatchBaseline", "Amazon Linux"},
                {"AMAZON_LINUX_2", "AWS-AmazonLinux2DefaultPatchBaseline", "Amazon Linux 2"},
                {"AMAZON_LINUX_2022", "AWS-AmazonLinux2022DefaultPatchBaseline", "Amazon Linux 2022"},
                {"AMAZON_LINUX_2023", "AWS-AmazonLinux2023DefaultPatchBaseline", "Amazon Linux 2023"},
                {"UBUNTU", "AWS-UbuntuDefaultPatchBaseline", "Ubuntu"},
                {"REDHAT_ENTERPRISE_LINUX", "AWS-RedHatDefaultPatchBaseline", "Red Hat Enterprise Linux"},
                {"SUSE", "AWS-SuseDefaultPatchBaseline", "SUSE Linux Enterprise Server"},
                {"CENTOS", "AWS-CentOSDefaultPatchBaseline", "CentOS"},
                {"ORACLE_LINUX", "AWS-OracleLinuxDefaultPatchBaseline", "Oracle Linux"},
                {"DEBIAN", "AWS-DebianDefaultPatchBaseline", "Debian Server"},
                {"MACOS", "AWS-MacOSDefaultPatchBaseline", "macOS"},
                {"RASPBIAN", "AWS-RaspbianDefaultPatchBaseline", "Raspbian"},
                {"ROCKY_LINUX", "AWS-RockyLinuxDefaultPatchBaseline", "Rocky Linux"},
                {"ALMA_LINUX", "AWS-AlmaLinuxDefaultPatchBaseline", "AlmaLinux"},
        };
        List<PatchBaselineIdentity> baselines = new ArrayList<>();
        for (String[] def : defs) {
            String os = def[0];
            String name = def[1];
            String description = "Default Patch Baseline for " + def[2] + " Provided by AWS.";
            baselines.add(new PatchBaselineIdentity(stableBaselineId(name), name, os, description, true));
        }
        return List.copyOf(baselines);
    }

    /** Deterministic AWS-style baseline id (pb-<17 hex>) derived from the baseline name. */
    private static String stableBaselineId(String name) {
        long h = 1125899906842597L;
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        String hex = String.format("%016x", h & 0x0FFFFFFFFFFFFFFFL);
        return "pb-0" + hex;
    }

    /**
     * Return AWS-owned predefined patch baselines matching the given DescribePatchBaselines filters
     * (supported keys: OWNER, OPERATING_SYSTEM, NAME_PREFIX). There are no customer-owned baselines.
     */
    public List<PatchBaselineIdentity> describePatchBaselines(Map<String, List<String>> filters) {
        List<String> owners = filters.getOrDefault("OWNER", List.of());
        // OWNER=Self matches only customer-owned baselines, of which there are none.
        if (!owners.isEmpty() && !owners.contains("AWS") && !owners.contains("All")) {
            return List.of();
        }

        List<String> operatingSystems = filters.getOrDefault("OPERATING_SYSTEM", List.of());
        List<String> namePrefixes = filters.getOrDefault("NAME_PREFIX", List.of());

        return PREDEFINED_BASELINES.stream()
                .filter(b -> operatingSystems.isEmpty() || operatingSystems.contains(b.operatingSystem()))
                .filter(b -> namePrefixes.isEmpty()
                        || namePrefixes.stream().anyMatch(prefix -> b.baselineName().startsWith(prefix)))
                .toList();
    }

    /** Return the default patch baseline id for an operating system (defaults to WINDOWS). */
    public String getDefaultPatchBaseline(String operatingSystem) {
        String os = (operatingSystem == null || operatingSystem.isBlank()) ? "WINDOWS" : operatingSystem;
        return PREDEFINED_BASELINES.stream()
                .filter(b -> b.operatingSystem().equals(os))
                .findFirst()
                .map(PatchBaselineIdentity::baselineId)
                .orElseThrow(() -> new AwsException("DoesNotExistException",
                        "No default patch baseline exists for operating system " + os, 400));
    }

    /**
     * Read a service setting for the calling account. Never-customized settings
     * report their account default with status "Default".
     */
    public ServiceSetting getServiceSetting(String settingId, String region) {
        String defaultValue = requireKnownSetting(settingId);
        return serviceSettingStore.get(settingKey(region, settingId))
                .orElseGet(() -> defaultSetting(settingId, defaultValue, region));
    }

    public void updateServiceSetting(String settingId, String settingValue, String region) {
        requireKnownSetting(settingId);
        ServiceSetting setting = new ServiceSetting(settingId, settingValue,
                settingArn(settingId, region), "Customized",
                "arn:aws:iam::" + regionResolver.getAccountId() + ":root");
        serviceSettingStore.put(settingKey(region, settingId), setting);
    }

    public ServiceSetting resetServiceSetting(String settingId, String region) {
        String defaultValue = requireKnownSetting(settingId);
        serviceSettingStore.delete(settingKey(region, settingId));
        return defaultSetting(settingId, defaultValue, region);
    }

    private String requireKnownSetting(String settingId) {
        String defaultValue = SERVICE_SETTING_DEFAULTS.get(settingId);
        if (defaultValue == null) {
            throw new AwsException("ServiceSettingNotFound",
                    "The specified service setting was not found: " + settingId, 400);
        }
        return defaultValue;
    }

    private ServiceSetting defaultSetting(String settingId, String defaultValue, String region) {
        return new ServiceSetting(settingId, defaultValue, settingArn(settingId, region),
                "Default", "System");
    }

    /**
     * Service settings are per-account per-region: LZA assumes a role into each
     * member account before updating, so the caller's resolved account scopes the key.
     */
    private String settingKey(String region, String settingId) {
        return regionResolver.getAccountId() + "::" + regionKey(region, settingId);
    }

    private String settingArn(String settingId, String region) {
        // Setting ids begin with "/", so concatenation yields .../servicesetting/ssm/...
        return "arn:aws:ssm:" + region + ":" + regionResolver.getAccountId()
                + ":servicesetting" + settingId;
    }

    private static String regionKey(String region, String name) {
        return region + "::" + name;
    }

    private void addHistory(String storageKey, Parameter parameter) {
        List<ParameterHistory> history = historyStore.get(storageKey)
                .orElse(new ArrayList<>());

        history = new ArrayList<>(history);
        history.add(new ParameterHistory(parameter));

        while (history.size() > maxParameterHistory) {
            history.removeFirst();
        }

        historyStore.put(storageKey, history);
    }

    public synchronized void deleteDocument(String name, String region) {
        String storageKey = regionKey(region, name);
        if (!documentStore.get(storageKey).isPresent()) {
            throw new AwsException("InvalidDocument",
                    "Document " + name + " does not exist.", 400);
        }
        documentStore.delete(storageKey);
        documentPermissionStore.delete(storageKey);
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Parameter parameter : parameterStore.scan(k -> true)) {
            String arn = parameter.getArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "ssm:parameter", "ssm",
                    parsed.region(), parsed.accountId(),
                    parameter.getLastModifiedDate() != null ? parameter.getLastModifiedDate() : Instant.now(),
                    parameter.getTags() != null ? parameter.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("ssm:parameter", "ssm", true));
    }
}
