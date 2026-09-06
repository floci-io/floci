package io.github.hectorvent.floci.services.verifiedpermissions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.verifiedpermissions.model.EntityIdentifier;
import io.github.hectorvent.floci.services.verifiedpermissions.model.IdempotencyRecord;
import io.github.hectorvent.floci.services.verifiedpermissions.model.IdentitySource;
import io.github.hectorvent.floci.services.verifiedpermissions.model.Policy;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyStore;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyStoreAlias;
import io.github.hectorvent.floci.services.verifiedpermissions.model.PolicyTemplate;
import com.cedarpolicy.model.policy.PolicySet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class VerifiedPermissionsService implements Resettable {
    static final Duration ALIAS_SOFT_DELETE_RETENTION = Duration.ofHours(24);
    private static final Pattern STORE_ID = Pattern.compile("[A-Za-z0-9-/_]{1,200}");
    private static final Pattern ALIAS_NAME = Pattern.compile("policy-store-alias/[A-Za-z0-9-/_]*");
    private static final int MAX_TAGS = 200;
    private static final int MAX_ALIASES_PER_POLICY_STORE = 10;
    private static final int MAX_POLICY_STATEMENT_LENGTH = 10_000;

    private final StorageBackend<String, PolicyStore> policyStores;
    private final StorageBackend<String, PolicyStoreAlias> aliases;
    private final StorageBackend<String, Policy> policies;
    private final StorageBackend<String, PolicyTemplate> policyTemplates;
    private final StorageBackend<String, IdempotencyRecord> idempotency;
    private final StorageBackend<String, IdentitySource> identitySources;
    private final RegionResolver regionResolver;
    private final KmsService kmsService;
    private final ObjectMapper objectMapper;

    @Inject
    public VerifiedPermissionsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                      ObjectMapper objectMapper, KmsService kmsService) {
        this(storageFactory.create("verifiedpermissions", "verifiedpermissions-policy-stores.json",
                        new TypeReference<Map<String, PolicyStore>>() {}),
                storageFactory.create("verifiedpermissions", "verifiedpermissions-policy-store-aliases.json",
                        new TypeReference<Map<String, PolicyStoreAlias>>() {}),
                storageFactory.create("verifiedpermissions", "verifiedpermissions-policies.json",
                        new TypeReference<Map<String, Policy>>() {}),
                storageFactory.create("verifiedpermissions", "verifiedpermissions-policy-templates.json",
                        new TypeReference<Map<String, PolicyTemplate>>() {}),
                storageFactory.create("verifiedpermissions", "verifiedpermissions-idempotency.json",
                        new TypeReference<Map<String, IdempotencyRecord>>() {}),
                storageFactory.create("verifiedpermissions", "verifiedpermissions-identity-sources.json",
                        new TypeReference<Map<String, IdentitySource>>() {}),
                regionResolver, objectMapper, kmsService);
    }

    VerifiedPermissionsService(StorageBackend<String, PolicyStore> policyStores,
                               StorageBackend<String, PolicyStoreAlias> aliases,
                               StorageBackend<String, Policy> policies,
                               StorageBackend<String, PolicyTemplate> policyTemplates,
                               StorageBackend<String, IdempotencyRecord> idempotency,
                               StorageBackend<String, IdentitySource> identitySources,
                               RegionResolver regionResolver, ObjectMapper objectMapper, KmsService kmsService) {
        this.policyStores = policyStores;
        this.aliases = aliases;
        this.policies = policies;
        this.policyTemplates = policyTemplates;
        this.idempotency = idempotency;
        this.identitySources = identitySources;
        this.regionResolver = regionResolver;
        this.kmsService = kmsService;
        this.objectMapper = objectMapper;
    }

    public synchronized PolicyStore createPolicyStore(JsonNode request, String region) {
        IdempotencyRecord replay = findIdempotent("CreatePolicyStore", request, region);
        if (replay != null) {
            return getPolicyStore(replay.resourceId(), region);
        }
        JsonNode settings = requiredObject(request, "validationSettings");
        String mode = requiredText(settings, "mode");
        validateMode(mode);
        String deletionProtection = text(request, "deletionProtection", "DISABLED");
        validateDeletionProtection(deletionProtection);
        String description = text(request, "description", null);
        validateDescription(description);
        Map<String, String> tags = stringMap(request.get("tags"));
        validateTags(tags);
        Encryption encryption = parseEncryption(request.get("encryptionSettings"), region);

        String id = "PS" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Instant now = Instant.now();
        PolicyStore store = new PolicyStore(id, storeArn(region, id), mode, deletionProtection,
                description, encryption.keyArn(), encryption.context(), tags, null, null, null, now, now);
        policyStores.put(storeKey(region, id), store);
        rememberIdempotent("CreatePolicyStore", request, region, id, id);
        return store;
    }

    public PolicyStore getPolicyStore(String identifier, String region) {
        String id = resolveStoreId(identifier, region, true);
        return policyStores.get(storeKey(region, id))
                .orElseThrow(() -> notFound("Policy store not found: " + identifier));
    }

    public synchronized PolicyStore updatePolicyStore(JsonNode request, String region) {
        PolicyStore current = getPolicyStore(requiredText(request, "policyStoreId"), region);
        JsonNode settings = requiredObject(request, "validationSettings");
        String mode = requiredText(settings, "mode");
        validateMode(mode);
        String deletionProtection = request.has("deletionProtection")
                ? requiredText(request, "deletionProtection") : current.deletionProtection();
        validateDeletionProtection(deletionProtection);
        String description = request.has("description") ? request.path("description").asText() : current.description();
        validateDescription(description);
        PolicyStore updated = current.withSettings(mode, deletionProtection, description, Instant.now());
        policyStores.put(storeKey(region, updated.policyStoreId()), updated);
        return updated;
    }

    public synchronized void deletePolicyStore(String identifier, String region) {
        String id = resolveStoreId(identifier, region, false);
        PolicyStore store = policyStores.get(storeKey(region, id)).orElse(null);
        if (store == null) {
            return;
        }
        if ("ENABLED".equals(store.deletionProtection())) {
            throw new AwsException("InvalidStateException", "The policy store can't be deleted because deletion protection is enabled.", 400);
        }
        policyStores.delete(storeKey(region, store.policyStoreId()));
        aliases.scan(k -> k.startsWith(region + ":")).stream()
                .filter(a -> a.policyStoreId().equals(store.policyStoreId()))
                .forEach(a -> aliases.delete(aliasKey(region, a.aliasName())));
        policies.keys().stream().filter(k -> k.startsWith(region + ":" + store.policyStoreId() + ":"))
                .toList().forEach(policies::delete);
        policyTemplates.keys().stream().filter(k -> k.startsWith(region + ":" + store.policyStoreId() + ":"))
                .toList().forEach(policyTemplates::delete);
        identitySources.keys().stream().filter(k -> k.startsWith(region + ":" + store.policyStoreId() + ":"))
                .toList().forEach(identitySources::delete);
    }

    public PaginatedResult<PolicyStore> listPolicyStores(JsonNode request, String region) {
        List<PolicyStore> stores = policyStores.scan(k -> k.startsWith(region + ":")).stream()
                .sorted(Comparator.comparing(PolicyStore::policyStoreId)).toList();
        return Pagination.paginate(stores, PolicyStore::policyStoreId,
                optionalInt(request, "maxResults"), text(request, "nextToken", null), 10, 50, "ValidationException");
    }

    public synchronized PolicyStore putSchema(JsonNode request, String region) {
        PolicyStore store = getPolicyStore(requiredText(request, "policyStoreId"), region);
        JsonNode definition = requiredObject(request, "definition");
        if (!definition.has("cedarJson") || !definition.get("cedarJson").isTextual()) {
            throw validation("definition.cedarJson is required.");
        }
        String schema = definition.get("cedarJson").asText();
        validateSchemaJson(schema);
        Instant now = Instant.now();
        Instant created = store.schemaCreatedDate() == null ? now : store.schemaCreatedDate();
        PolicyStore updated = store.withSchema("{}".equals(schema.trim()) ? null : schema,
                "{}".equals(schema.trim()) ? null : created,
                "{}".equals(schema.trim()) ? null : now);
        policyStores.put(storeKey(region, store.policyStoreId()), updated);
        return updated;
    }

    public PolicyStore getSchema(String identifier, String region) {
        PolicyStore store = getPolicyStore(identifier, region);
        if (store.schema() == null) {
            throw notFound("Schema not found for policy store: " + store.policyStoreId());
        }
        return store;
    }



    public synchronized IdentitySource createIdentitySource(JsonNode request, String region) {
        IdempotencyRecord replay = findIdempotent("CreateIdentitySource", request, region);
        if (replay != null) {
            return getIdentitySource(replay.policyStoreId(), replay.resourceId(), region);
        }
        PolicyStore store = getPolicyStore(requiredText(request, "policyStoreId"), region);
        JsonNode configuration = normalizeIdentityConfiguration(requiredObject(request, "configuration"), region);
        String principalEntityType = text(request, "principalEntityType", "AWS::Cognito");
        validateEntityType(principalEntityType, "principalEntityType");
        String issuer = identityIssuer(configuration);
        boolean duplicate = identitySources.scan(k -> k.startsWith(region + ":" + store.policyStoreId() + ":")).stream()
                .anyMatch(existing -> issuer.equals(identityIssuer(existing.configuration())));
        if (duplicate) {
            throw conflict("An identity source for this issuer already exists in the policy store.");
        }
        Instant now = Instant.now();
        String id = "IS" + compactId();
        IdentitySource source = new IdentitySource(store.policyStoreId(), id, principalEntityType,
                configuration.deepCopy(), now, now);
        identitySources.put(identitySourceKey(region, store.policyStoreId(), id), source);
        rememberIdempotent("CreateIdentitySource", request, region, id, store.policyStoreId());
        return source;
    }

    public IdentitySource getIdentitySource(String storeIdentifier, String identitySourceId, String region) {
        PolicyStore store = getPolicyStore(storeIdentifier, region);
        return identitySources.get(identitySourceKey(region, store.policyStoreId(), identitySourceId))
                .orElseThrow(() -> notFound("Identity source not found: " + identitySourceId));
    }

    public synchronized IdentitySource updateIdentitySource(JsonNode request, String region) {
        IdentitySource current = getIdentitySource(requiredText(request, "policyStoreId"),
                requiredText(request, "identitySourceId"), region);
        JsonNode update = requiredObject(request, "updateConfiguration");
        JsonNode normalized = normalizeIdentityConfiguration(update, region);
        String oldKind = identityConfigurationKind(current.configuration());
        String newKind = identityConfigurationKind(normalized);
        if (!oldKind.equals(newKind)) {
            throw validation("The identity provider type can't be changed by UpdateIdentitySource.");
        }
        String principalType = request.has("principalEntityType")
                ? request.path("principalEntityType").asText() : current.principalEntityType();
        validateEntityType(principalType, "principalEntityType");
        String issuer = identityIssuer(normalized);
        boolean duplicate = identitySources.scan(k -> k.startsWith(region + ":" + current.policyStoreId() + ":")).stream()
                .anyMatch(existing -> !existing.identitySourceId().equals(current.identitySourceId())
                        && issuer.equals(identityIssuer(existing.configuration())));
        if (duplicate) {
            throw conflict("An identity source for this issuer already exists in the policy store.");
        }
        IdentitySource updated = current.updated(principalType, normalized.deepCopy(), Instant.now());
        identitySources.put(identitySourceKey(region, current.policyStoreId(), current.identitySourceId()), updated);
        return updated;
    }

    public synchronized void deleteIdentitySource(String storeIdentifier, String identitySourceId, String region) {
        IdentitySource source = getIdentitySource(storeIdentifier, identitySourceId, region);
        identitySources.delete(identitySourceKey(region, source.policyStoreId(), source.identitySourceId()));
    }

    public PaginatedResult<IdentitySource> listIdentitySources(JsonNode request, String region) {
        PolicyStore store = getPolicyStore(requiredText(request, "policyStoreId"), region);
        JsonNode filters = request.get("filters");
        validateIdentityFilters(filters);
        List<IdentitySource> items = identitySources.scan(k -> k.startsWith(region + ":" + store.policyStoreId() + ":")).stream()
                .filter(source -> matchesIdentityFilters(source, filters))
                .sorted(Comparator.comparing(IdentitySource::identitySourceId)).toList();
        return Pagination.paginate(items, IdentitySource::identitySourceId, optionalInt(request, "maxResults"),
                text(request, "nextToken", null), 10, 50, "ValidationException");
    }

    public List<IdentitySource> identitySourcesForStore(String storeIdentifier, String region) {
        PolicyStore store = getPolicyStore(storeIdentifier, region);
        return identitySources.scan(k -> k.startsWith(region + ":" + store.policyStoreId() + ":"));
    }

    public synchronized PolicyTemplate createPolicyTemplate(JsonNode request, String region) {
        IdempotencyRecord replay = findIdempotent("CreatePolicyTemplate", request, region);
        if (replay != null) {
            return getPolicyTemplate(replay.policyStoreId(), replay.resourceId(), region);
        }
        PolicyStore store = getPolicyStore(requiredText(request, "policyStoreId"), region);
        String statement = requiredText(request, "statement");
        validatePolicyStatement(statement);
        validateTemplate(statement);
        validateStrictPolicy(store, parseTemplate(statement, "validation-template"), true);
        String name = optionalName(request, "name");
        ensureTemplateNameAvailable(region, store.policyStoreId(), name, null);
        String description = text(request, "description", null);
        validateDescription(description);
        String id = "PT" + compactId();
        Instant now = Instant.now();
        PolicyTemplate template = new PolicyTemplate(store.policyStoreId(), id, name, statement,
                description, now, now);
        policyTemplates.put(templateKey(region, store.policyStoreId(), id), template);
        rememberIdempotent("CreatePolicyTemplate", request, region, id, store.policyStoreId());
        return template;
    }

    public PolicyTemplate getPolicyTemplate(String storeIdentifier, String templateIdentifier, String region) {
        PolicyStore store = getPolicyStore(storeIdentifier, region);
        String id = resolveTemplateId(region, store.policyStoreId(), templateIdentifier);
        return policyTemplates.get(templateKey(region, store.policyStoreId(), id))
                .orElseThrow(() -> notFound("Policy template not found: " + templateIdentifier));
    }

    public synchronized PolicyTemplate updatePolicyTemplate(JsonNode request, String region) {
        PolicyTemplate current = getPolicyTemplate(requiredText(request, "policyStoreId"),
                requiredText(request, "policyTemplateId"), region);
        String statement = requiredText(request, "statement");
        validatePolicyStatement(statement);
        validateTemplate(statement);
        ensureProtectedScopeUnchanged(current.statement(), statement, true);
        PolicyStore store = getPolicyStore(current.policyStoreId(), region);
        validateStrictPolicy(store, parseTemplate(statement, current.policyTemplateId()), true);
        String name = request.has("name") ? normalizeOptionalName(request.get("name").asText()) : current.name();
        ensureTemplateNameAvailable(region, current.policyStoreId(), name, current.policyTemplateId());
        String description = request.has("description") ? request.path("description").asText() : current.description();
        validateDescription(description);
        PolicyTemplate updated = current.updated(name, statement, description, Instant.now());
        policyTemplates.put(templateKey(region, current.policyStoreId(), current.policyTemplateId()), updated);
        return updated;
    }

    public synchronized void deletePolicyTemplate(String storeIdentifier, String templateIdentifier, String region) {
        PolicyTemplate template = getPolicyTemplate(storeIdentifier, templateIdentifier, region);
        policies.keys().stream()
                .filter(k -> k.startsWith(region + ":" + template.policyStoreId() + ":"))
                .filter(k -> policies.get(k).map(p -> template.policyTemplateId().equals(p.policyTemplateId())).orElse(false))
                .toList()
                .forEach(policies::delete);
        policyTemplates.delete(templateKey(region, template.policyStoreId(), template.policyTemplateId()));
    }

    public PaginatedResult<PolicyTemplate> listPolicyTemplates(JsonNode request, String region) {
        PolicyStore store = getPolicyStore(requiredText(request, "policyStoreId"), region);
        List<PolicyTemplate> items = policyTemplates.scan(k -> k.startsWith(region + ":" + store.policyStoreId() + ":")).stream()
                .sorted(Comparator.comparing(PolicyTemplate::policyTemplateId)).toList();
        return Pagination.paginate(items, PolicyTemplate::policyTemplateId, optionalInt(request, "maxResults"),
                text(request, "nextToken", null), 10, 50, "ValidationException");
    }

    public synchronized Policy createPolicy(JsonNode request, String region) {
        IdempotencyRecord replay = findIdempotent("CreatePolicy", request, region);
        if (replay != null) {
            return getPolicy(replay.policyStoreId(), replay.resourceId(), region);
        }
        PolicyStore store = getPolicyStore(requiredText(request, "policyStoreId"), region);
        JsonNode definition = requiredObject(request, "definition");
        String name = optionalName(request, "name");
        ensurePolicyNameAvailable(region, store.policyStoreId(), name, null);
        Instant now = Instant.now();
        Policy policy;
        if (definition.has("static") && definition.get("static").isObject() && definition.size() == 1) {
            JsonNode body = definition.get("static");
            String statement = requiredText(body, "statement");
            validatePolicyStatement(statement);
            String description = text(body, "description", null);
            validateDescription(description);
            String effect = validateStaticPolicy(statement);
            validateStrictPolicy(store, parseStatic(statement, "validation-policy"), false);
            policy = new Policy(store.policyStoreId(), "SP" + compactId(), name, "STATIC", statement,
                    description, null, null, null, effect, now, now);
        } else if (definition.has("templateLinked") && definition.get("templateLinked").isObject() && definition.size() == 1) {
            JsonNode body = definition.get("templateLinked");
            PolicyTemplate template = getPolicyTemplate(store.policyStoreId(), requiredText(body, "policyTemplateId"), region);
            EntityIdentifier principal = entityIdentifier(body.get("principal"));
            EntityIdentifier resource = entityIdentifier(body.get("resource"));
            validateTemplateSlots(template.statement(), principal, resource);
            policy = new Policy(store.policyStoreId(), "LP" + compactId(), name, "TEMPLATE_LINKED", null, null,
                    template.policyTemplateId(), principal, resource, templateEffect(template.statement()), now, now);
        } else {
            throw validation("definition must contain exactly one of static or templateLinked.");
        }
        policies.put(policyKey(region, store.policyStoreId(), policy.policyId()), policy);
        rememberIdempotent("CreatePolicy", request, region, policy.policyId(), store.policyStoreId());
        return policy;
    }

    public Policy getPolicy(String storeIdentifier, String policyIdentifier, String region) {
        PolicyStore store = getPolicyStore(storeIdentifier, region);
        String id = resolvePolicyId(region, store.policyStoreId(), policyIdentifier);
        return policies.get(policyKey(region, store.policyStoreId(), id))
                .orElseThrow(() -> notFound("Policy not found: " + policyIdentifier));
    }

    public synchronized Policy updatePolicy(JsonNode request, String region) {
        Policy current = getPolicy(requiredText(request, "policyStoreId"), requiredText(request, "policyId"), region);
        if (!"STATIC".equals(current.policyType())) {
            throw validation("Only static policies can be updated.");
        }

        String statement = current.statement();
        String description = current.description();
        JsonNode definition = request.get("definition");
        if (definition != null && !definition.isNull()) {
            if (!definition.isObject() || !definition.has("static")
                    || !definition.get("static").isObject() || definition.size() != 1) {
                throw validation("definition must contain exactly one static member when updating a policy.");
            }
            JsonNode body = definition.get("static");
            statement = requiredText(body, "statement");
            validatePolicyStatement(statement);
            String effect = validateStaticPolicy(statement);
            if (!effect.equals(current.effect())) {
                throw validation("The policy effect can't be changed by UpdatePolicy.");
            }
            ensureProtectedScopeUnchanged(current.statement(), statement, false);
            PolicyStore store = getPolicyStore(current.policyStoreId(), region);
            validateStrictPolicy(store, parseStatic(statement, current.policyId()), false);
            if (body.has("description")) {
                description = body.path("description").asText();
            }
        }
        validateDescription(description);
        String name = request.has("name") ? normalizeOptionalName(request.get("name").asText()) : current.name();
        ensurePolicyNameAvailable(region, current.policyStoreId(), name, current.policyId());
        Policy updated = current.updated(name, statement, description, Instant.now());
        policies.put(policyKey(region, current.policyStoreId(), current.policyId()), updated);
        return updated;
    }

    public synchronized void deletePolicy(String storeIdentifier, String policyIdentifier, String region) {
        PolicyStore store = getPolicyStore(storeIdentifier, region);
        String policyId = resolvePolicyIdOrNull(region, store.policyStoreId(), policyIdentifier);
        if (policyId != null) {
            policies.delete(policyKey(region, store.policyStoreId(), policyId));
        }
    }

    public PaginatedResult<Policy> listPolicies(JsonNode request, String region) {
        PolicyStore store = getPolicyStore(requiredText(request, "policyStoreId"), region);
        JsonNode filter = request.get("filter");
        List<Policy> items = policies.scan(k -> k.startsWith(region + ":" + store.policyStoreId() + ":")).stream()
                .filter(p -> matchesPolicyFilter(p, filter))
                .sorted(Comparator.comparing(Policy::policyId)).toList();
        return Pagination.paginate(items, Policy::policyId, optionalInt(request, "maxResults"),
                text(request, "nextToken", null), 10, 50, "ValidationException");
    }

    public List<Policy> policiesForStore(String policyStoreId, String region) {
        PolicyStore store = getPolicyStore(policyStoreId, region);
        return policies.scan(k -> k.startsWith(region + ":" + store.policyStoreId() + ":"));
    }

    public PolicyTemplate templateForPolicy(Policy policy, String region) {
        return getPolicyTemplate(policy.policyStoreId(), policy.policyTemplateId(), region);
    }

    public Map<String, PolicyTemplate> templatesForStore(String policyStoreId, String region) {
        PolicyStore store = getPolicyStore(policyStoreId, region);
        Map<String, PolicyTemplate> result = new LinkedHashMap<>();
        policyTemplates.scan(k -> k.startsWith(region + ":" + store.policyStoreId() + ":"))
                .forEach(t -> result.put(t.policyTemplateId(), t));
        return result;
    }

    public synchronized PolicyStoreAlias createAlias(JsonNode request, String region) {
        purgeExpiredAliases(region);
        String aliasName = requiredText(request, "aliasName");
        validateAliasName(aliasName);
        String storeId = requiredText(request, "policyStoreId");
        if (storeId.startsWith("policy-store-alias/")) {
            throw validation("policyStoreId must be a policy store ID when creating an alias.");
        }
        PolicyStore store = getPolicyStore(storeId, region);
        String key = aliasKey(region, aliasName);
        PolicyStoreAlias existing = aliases.get(key).orElse(null);
        if (existing != null) {
            if ("Active".equals(existing.state()) && existing.policyStoreId().equals(store.policyStoreId())) {
                return existing;
            }
            throw conflict("A policy store alias with this name already exists.");
        }
        long aliasCount = aliases.scan(k -> k.startsWith(region + ":")).stream()
                .filter(a -> a.policyStoreId().equals(store.policyStoreId()))
                .count();
        if (aliasCount >= MAX_ALIASES_PER_POLICY_STORE) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The policy store alias quota has been exceeded for policy store " + store.policyStoreId() + ".", 400);
        }
        PolicyStoreAlias alias = new PolicyStoreAlias(aliasName, aliasArn(region, aliasName),
                store.policyStoreId(), "Active", Instant.now(), null);
        aliases.put(key, alias);
        return alias;
    }

    public PolicyStoreAlias getAlias(String aliasName, String region) {
        purgeExpiredAliases(region);
        validateAliasName(aliasName);
        return aliases.get(aliasKey(region, aliasName))
                .orElseThrow(() -> notFound("Policy store alias not found: " + aliasName));
    }

    public PaginatedResult<PolicyStoreAlias> listAliases(JsonNode request, String region) {
        purgeExpiredAliases(region);
        String filterStoreId = null;
        JsonNode filter = request.get("filter");
        if (filter != null && !filter.isNull()) {
            filterStoreId = requiredText(filter, "policyStoreId");
            getPolicyStore(filterStoreId, region);
        }
        String finalFilterStoreId = filterStoreId;
        List<PolicyStoreAlias> items = aliases.scan(k -> k.startsWith(region + ":")).stream()
                .filter(a -> finalFilterStoreId == null || finalFilterStoreId.equals(a.policyStoreId()))
                .sorted(Comparator.comparing(PolicyStoreAlias::aliasName)).toList();
        return Pagination.paginate(items, PolicyStoreAlias::aliasName,
                optionalInt(request, "maxResults"), text(request, "nextToken", null), 5, 50, "ValidationException");
    }

    public synchronized void deleteAlias(JsonNode request, String region) {
        purgeExpiredAliases(region);
        String aliasName = requiredText(request, "aliasName");
        validateAliasName(aliasName);
        String mode = text(request, "deletionMode", "SoftDelete");
        if (!"SoftDelete".equals(mode) && !"HardDelete".equals(mode)) {
            throw validation("deletionMode must be SoftDelete or HardDelete.");
        }
        String key = aliasKey(region, aliasName);
        PolicyStoreAlias alias = aliases.get(key).orElse(null);
        if (alias == null) {
            return;
        }
        if ("HardDelete".equals(mode)) {
            aliases.delete(key);
            return;
        }
        aliases.put(key, alias.pendingDeletion(Instant.now().plus(ALIAS_SOFT_DELETE_RETENTION)));
    }

    public synchronized void tagResource(String arn, Map<String, String> tags, String region) {
        validateTags(tags);
        PolicyStore store = storeByArn(arn, region);
        Map<String, String> merged = new LinkedHashMap<>(store.tags());
        merged.putAll(tags);
        validateTags(merged);
        PolicyStore updated = store.withTags(merged, Instant.now());
        policyStores.put(storeKey(region, store.policyStoreId()), updated);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys, String region) {
        PolicyStore store = storeByArn(arn, region);
        Map<String, String> tags = new LinkedHashMap<>(store.tags());
        if (tagKeys != null) {
            tagKeys.forEach(tags::remove);
        }
        PolicyStore updated = store.withTags(tags, Instant.now());
        policyStores.put(storeKey(region, store.policyStoreId()), updated);
    }

    public Map<String, String> listTags(String arn, String region) {
        return Map.copyOf(storeByArn(arn, region).tags());
    }

    String resolveStoreId(String identifier, String region, boolean aliasesAllowed) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 200 || !STORE_ID.matcher(identifier).matches()) {
            throw validation("policyStoreId does not satisfy the required constraints.");
        }
        if (!identifier.startsWith("policy-store-alias/")) {
            return identifier;
        }
        if (!aliasesAllowed) {
            throw validation("A policy store alias cannot be used for this operation.");
        }
        PolicyStoreAlias alias = getAlias(identifier, region);
        if (!"Active".equals(alias.state())) {
            throw notFound("Policy store not found: " + identifier);
        }
        return alias.policyStoreId();
    }

    @Override
    public void clear() {
        policyStores.clear();
        aliases.clear();
        policies.clear();
        policyTemplates.clear();
        idempotency.clear();
        identitySources.clear();
    }


    private JsonNode normalizeIdentityConfiguration(JsonNode configuration, String region) {
        if (!configuration.isObject() || configuration.size() != 1) {
            throw validation("configuration must contain exactly one union member.");
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        if (configuration.has("cognitoUserPoolConfiguration")) {
            JsonNode source = configuration.get("cognitoUserPoolConfiguration");
            if (!source.isObject()) {
                throw validation("cognitoUserPoolConfiguration must be an object.");
            }
            ObjectNode cognito = source.deepCopy();
            String arn = requiredText(cognito, "userPoolArn");
            String[] arnParts = arn.split(":", 6);
            if (arnParts.length != 6 || !"cognito-idp".equals(arnParts[2]) || !arnParts[5].startsWith("userpool/")) {
                throw validation("userPoolArn must be a Cognito user pool ARN.");
            }
            if (!region.equals(arnParts[3])) {
                throw validation("The Cognito user pool must be in the same Region as the policy store.");
            }
            String poolId = arnParts[5].substring("userpool/".length());
            cognito.put("issuer", "https://cognito-idp." + arnParts[3] + ".amazonaws.com/" + poolId);
            validateStringArray(cognito.get("clientIds"), "clientIds", 0, 1000);
            JsonNode group = cognito.get("groupConfiguration");
            if (group != null && !group.isNull()) {
                validateEntityType(requiredText(group, "groupEntityType"), "groupEntityType");
            }
            normalized.set("cognitoUserPoolConfiguration", cognito);
            return normalized;
        }
        if (configuration.has("openIdConnectConfiguration")) {
            JsonNode source = configuration.get("openIdConnectConfiguration");
            if (!source.isObject()) {
                throw validation("openIdConnectConfiguration must be an object.");
            }
            ObjectNode oidc = source.deepCopy();
            String issuer = requiredText(oidc, "issuer");
            if (!issuer.startsWith("https://") || issuer.length() > 2048) {
                throw validation("OIDC issuer must use HTTPS and be 2048 characters or fewer.");
            }
            JsonNode selection = requiredObject(oidc, "tokenSelection");
            if (selection.size() != 1 || (!selection.has("accessTokenOnly") && !selection.has("identityTokenOnly"))) {
                throw validation("tokenSelection must contain exactly one supported token type.");
            }
            JsonNode tokenConfig = selection.has("accessTokenOnly") ? selection.get("accessTokenOnly") : selection.get("identityTokenOnly");
            String listName = selection.has("accessTokenOnly") ? "audiences" : "clientIds";
            validateStringArray(tokenConfig.get(listName), listName,
                    selection.has("accessTokenOnly") ? 1 : 0,
                    selection.has("accessTokenOnly") ? 255 : 1000);
            if (tokenConfig.has("principalIdClaim") && tokenConfig.path("principalIdClaim").asText().isBlank()) {
                throw validation("principalIdClaim can't be empty.");
            }
            JsonNode group = oidc.get("groupConfiguration");
            if (group != null && !group.isNull()) {
                requiredText(group, "groupClaim");
                validateEntityType(requiredText(group, "groupEntityType"), "groupEntityType");
            }
            normalized.set("openIdConnectConfiguration", oidc);
            return normalized;
        }
        throw validation("configuration must contain cognitoUserPoolConfiguration or openIdConnectConfiguration.");
    }

    static String identityConfigurationKind(JsonNode configuration) {
        return configuration.has("cognitoUserPoolConfiguration") ? "cognito" : "oidc";
    }

    static String identityIssuer(JsonNode configuration) {
        JsonNode body = configuration.has("cognitoUserPoolConfiguration")
                ? configuration.get("cognitoUserPoolConfiguration") : configuration.get("openIdConnectConfiguration");
        return body.path("issuer").asText();
    }

    private static void validateIdentityFilters(JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return;
        }
        if (!filters.isArray() || filters.size() > 10) {
            throw validation("filters must be an array with at most 10 items.");
        }
        for (JsonNode filter : filters) {
            if (!filter.isObject()) {
                throw validation("Each identity source filter must be an object.");
            }
            if (filter.has("principalEntityType")) {
                validateEntityType(requiredText(filter, "principalEntityType"), "principalEntityType");
            }
        }
    }

    private static boolean matchesIdentityFilters(IdentitySource source, JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return true;
        }
        for (JsonNode filter : filters) {
            if (filter.has("principalEntityType")
                    && !filter.path("principalEntityType").asText().equals(source.principalEntityType())) return false;
        }
        return true;
    }

    private static void validateEntityType(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw validation(field + " does not satisfy the required constraints.");
        }
        if (com.cedarpolicy.value.EntityTypeName.parse(value).isEmpty()) {
            throw validation(field + " is not a valid Cedar entity type.");
        }
    }

    private static void validateStringArray(JsonNode node, String field, int min, int max) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray() || node.size() < min || node.size() > max) {
            throw validation(field + " has an invalid number of items.");
        }
        node.forEach(value -> {
            if (!value.isTextual() || value.asText().isEmpty() || value.asText().length() > 255) {
                throw validation(field + " must contain strings between 1 and 255 characters.");
            }
        });
    }

    private static String identitySourceKey(String region, String storeId, String identitySourceId) {
        return region + ":" + storeId + ":" + identitySourceId;
    }

    private IdempotencyRecord findIdempotent(String operation, JsonNode request, String region) {
        String token = text(request, "clientToken", null);
        if (token == null) {
            return null;
        }
        validateClientToken(token);
        String key = idempotencyKey(region, operation, token);
        IdempotencyRecord record = idempotency.get(key).orElse(null);
        if (record == null) {
            return null;
        }
        if (record.createdAt().plus(Duration.ofHours(8)).isBefore(Instant.now())) {
            idempotency.delete(key);
            return null;
        }
        String fingerprint = requestFingerprint(request);
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw conflict("The clientToken was already used with different request parameters.");
        }
        return record;
    }

    private void rememberIdempotent(String operation, JsonNode request, String region, String resourceId, String storeId) {
        String token = text(request, "clientToken", null);
        if (token == null) {
            return;
        }
        validateClientToken(token);
        idempotency.put(idempotencyKey(region, operation, token),
                new IdempotencyRecord(operation, token, requestFingerprint(request), resourceId, storeId, Instant.now()));
    }

    private String requestFingerprint(JsonNode request) {
        JsonNode copy = request.deepCopy();
        if (copy.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("clientToken");
        try {
            return canonical(copy);
        } catch (JsonProcessingException e) {
            throw validation("Request cannot be canonicalized for idempotency.");
        }
    }

    private String canonical(JsonNode node) throws JsonProcessingException {
        if (node.isObject()) {
            java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(objectMapper.writeValueAsString(e.getKey())).append(':').append(canonical(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(canonical(node.get(i)));
            }
            return b.append(']').toString();
        }
        return objectMapper.writeValueAsString(node);
    }

    private static void validateClientToken(String token) {
        if (token.length() < 1 || token.length() > 64 || !token.matches("[A-Za-z0-9-]*")) {
            throw validation("clientToken does not satisfy the required constraints.");
        }
    }

    private Encryption parseEncryption(JsonNode settings, String region) {
        if (settings == null || settings.isNull()) {
            return new Encryption(null, Map.of());
        }
        if (!settings.isObject() || settings.size() != 1) {
            throw validation("encryptionSettings must contain exactly one union member.");
        }
        if (settings.has("default")) {
            return new Encryption(null, Map.of());
        }
        JsonNode kms = settings.get("kmsEncryptionSettings");
        if (kms == null || !kms.isObject()) {
            throw validation("encryptionSettings must contain default or kmsEncryptionSettings.");
        }
        String key = requiredText(kms, "key");
        String keyArn = kmsService.describeKey(key, region).getArn();
        Map<String, String> context = stringMap(kms.get("encryptionContext"));
        return new Encryption(keyArn, context);
    }

    private static com.cedarpolicy.model.policy.Policy parseStatic(String statement, String id) {
        try { return new com.cedarpolicy.model.policy.Policy(statement, id); }
        catch (Exception e) { throw validation("The Cedar policy statement is invalid: " + safeMessage(e)); }
    }

    private static com.cedarpolicy.model.policy.Policy parseTemplate(String statement, String id) {
        try { return new com.cedarpolicy.model.policy.Policy(statement, id); }
        catch (Exception e) { throw validation("The Cedar policy template is invalid: " + safeMessage(e)); }
    }

    private static String idempotencyKey(String region, String operation, String token) {
        return region + ":" + operation + ":" + token;
    }

    private record Encryption(String keyArn, Map<String, String> context) {}

    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String policyKey(String region, String storeId, String policyId) {
        return region + ":" + storeId + ":" + policyId;
    }

    private static String templateKey(String region, String storeId, String templateId) {
        return region + ":" + storeId + ":" + templateId;
    }

    private String resolvePolicyId(String region, String storeId, String identifier) {
        String resolved = resolvePolicyIdOrNull(region, storeId, identifier);
        if (resolved == null) {
            throw notFound("Policy not found: " + identifier);
        }
        return resolved;
    }

    private String resolvePolicyIdOrNull(String region, String storeId, String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 200
                || !STORE_ID.matcher(identifier).matches()) {
            throw validation("policyId does not satisfy the required constraints.");
        }
        if (!identifier.startsWith("name/")) {
            return policies.get(policyKey(region, storeId, identifier)).isPresent() ? identifier : null;
        }
        return policies.scan(k -> k.startsWith(region + ":" + storeId + ":")).stream()
                .filter(p -> identifier.equals(p.name())).map(Policy::policyId).findFirst().orElse(null);
    }

    private String resolveTemplateId(String region, String storeId, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw validation("policyTemplateId is required.");
        }
        if (!identifier.startsWith("name/")) {
            return identifier;
        }
        return policyTemplates.scan(k -> k.startsWith(region + ":" + storeId + ":")).stream()
                .filter(t -> identifier.equals(t.name())).map(PolicyTemplate::policyTemplateId).findFirst()
                .orElseThrow(() -> notFound("Policy template not found: " + identifier));
    }

    private static String optionalName(JsonNode request, String field) {
        return request.has(field) ? normalizeOptionalName(request.get(field).asText()) : null;
    }

    private static String normalizeOptionalName(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > 150 || !value.startsWith("name/") || !value.matches("[A-Za-z0-9-/_]*")) {
            throw validation("Policy names must be prefixed with name/ and satisfy the documented constraints.");
        }
        return value;
    }

    private void ensurePolicyNameAvailable(String region, String storeId, String name, String exceptId) {
        if (name == null) {
            return;
        }
        boolean duplicate = policies.scan(k -> k.startsWith(region + ":" + storeId + ":")).stream()
                .anyMatch(p -> name.equals(p.name()) && !p.policyId().equals(exceptId));
        if (duplicate) {
            throw conflict("A policy with this name already exists.");
        }
    }

    private void ensureTemplateNameAvailable(String region, String storeId, String name, String exceptId) {
        if (name == null) {
            return;
        }
        boolean duplicate = policyTemplates.scan(k -> k.startsWith(region + ":" + storeId + ":")).stream()
                .anyMatch(t -> name.equals(t.name()) && !t.policyTemplateId().equals(exceptId));
        if (duplicate) {
            throw conflict("A policy template with this name already exists.");
        }
    }

    private static EntityIdentifier entityIdentifier(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw validation("Entity identifiers must be objects.");
        }
        return new EntityIdentifier(requiredText(node, "entityType"), requiredText(node, "entityId"));
    }

    private static String validateStaticPolicy(String statement) {
        try {
            com.cedarpolicy.model.policy.Policy parsed = com.cedarpolicy.model.policy.Policy.parseStaticPolicy(statement);
            return parsed.effect().name().equalsIgnoreCase("PERMIT") ? "Permit" : "Forbid";
        } catch (Exception e) {
            throw validation("The Cedar policy statement is invalid: " + safeMessage(e));
        }
    }

    private static void validateTemplate(String statement) {
        try {
            com.cedarpolicy.model.policy.Policy.parsePolicyTemplate(statement);
        } catch (Exception e) {
            throw validation("The Cedar policy template is invalid: " + safeMessage(e));
        }
    }

    private static String templateEffect(String statement) {
        try {
            return com.cedarpolicy.model.policy.Policy.parsePolicyTemplate(statement).effect().name().equalsIgnoreCase("PERMIT")
                    ? "Permit" : "Forbid";
        } catch (Exception e) {
            throw validation("The Cedar policy template is invalid: " + safeMessage(e));
        }
    }

    private static void validateTemplateSlots(String statement, EntityIdentifier principal, EntityIdentifier resource) {
        boolean needsPrincipal = statement.contains("?principal");
        boolean needsResource = statement.contains("?resource");
        if (needsPrincipal != (principal != null) || needsResource != (resource != null)) {
            throw validation("Template-linked policy values must exactly satisfy the template slots.");
        }
    }

    private void ensureProtectedScopeUnchanged(String oldStatement, String newStatement, boolean template) {
        JsonNode before = protectedScopeAst(oldStatement, template);
        JsonNode after = protectedScopeAst(newStatement, template);
        if (!before.equals(after)) {
            throw validation("The policy effect, principal, and resource scope can't be changed by an update.");
        }
    }

    public PolicyMetadata policyMetadata(Policy policy, String region) {
        JsonNode ast;
        EntityIdentifier linkedPrincipal = null;
        EntityIdentifier linkedResource = null;
        if ("STATIC".equals(policy.policyType())) {
            ast = staticPolicyAst(policy.statement());
        } else {
            PolicyTemplate template = getPolicyTemplate(policy.policyStoreId(), policy.policyTemplateId(), region);
            ast = templatePolicyAst(template.statement());
            linkedPrincipal = policy.principal();
            linkedResource = policy.resource();
        }
        return new PolicyMetadata(actionEntities(ast.path("action")),
                scopedEntity(ast.path("principal"), linkedPrincipal),
                scopedEntity(ast.path("resource"), linkedResource));
    }

    private JsonNode protectedScopeAst(String statement, boolean template) {
        JsonNode ast = template ? templatePolicyAst(statement) : staticPolicyAst(statement);
        ObjectNode protectedScope = objectMapper.createObjectNode();
        protectedScope.set("effect", ast.path("effect").deepCopy());
        protectedScope.set("principal", ast.path("principal").deepCopy());
        protectedScope.set("resource", ast.path("resource").deepCopy());
        return protectedScope;
    }

    private JsonNode staticPolicyAst(String statement) {
        try {
            String json = com.cedarpolicy.model.policy.Policy.parseStaticPolicy(statement).toJson();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw validation("The Cedar policy statement is invalid: " + safeMessage(e));
        }
    }

    private JsonNode templatePolicyAst(String statement) {
        try {
            com.cedarpolicy.model.policy.Policy template = com.cedarpolicy.model.policy.Policy.parsePolicyTemplate(statement);
            PolicySet set = new PolicySet(Set.of(), Set.of(template));
            JsonNode root = objectMapper.readTree(set.toJson());
            JsonNode templates = root.path("templates");
            if (!templates.isObject() || templates.isEmpty()) {
                throw new IllegalStateException("Cedar template AST is empty");
            }
            return templates.elements().next();
        } catch (Exception e) {
            throw validation("The Cedar policy template is invalid: " + safeMessage(e));
        }
    }

    private static List<EntityIdentifier> actionEntities(JsonNode scope) {
        List<EntityIdentifier> result = new ArrayList<>();
        if (scope.has("entity")) {
            result.add(cedarJsonEntity(scope.get("entity")));
        } else if (scope.path("entities").isArray()) {
            scope.path("entities").forEach(entity -> result.add(cedarJsonEntity(entity)));
        }
        return List.copyOf(result);
    }

    private static EntityIdentifier scopedEntity(JsonNode scope, EntityIdentifier slotValue) {
        if (scope.has("entity")) {
            return cedarJsonEntity(scope.get("entity"));
        }
        if (scope.has("slot")) {
            return slotValue;
        }
        return null;
    }

    private static EntityIdentifier cedarJsonEntity(JsonNode entity) {
        if (entity == null || !entity.isObject()) {
            return null;
        }
        String type = entity.path("type").asText(null);
        String id = entity.path("id").asText(null);
        if (type == null || id == null) {
            return null;
        }
        return new EntityIdentifier(type, id);
    }

    public record PolicyMetadata(List<EntityIdentifier> actions, EntityIdentifier principal, EntityIdentifier resource) {}

    private static boolean matchesPolicyFilter(Policy policy, JsonNode filter) {
        if (filter == null || filter.isNull()) {
            return true;
        }
        if (filter.has("policyType") && !filter.path("policyType").asText().equals(policy.policyType())) {
            return false;
        }
        if (filter.has("policyTemplateId") && !filter.path("policyTemplateId").asText().equals(policy.policyTemplateId())) {
            return false;
        }
        if (filter.has("principal") && !matchesReference(policy.principal(), filter.get("principal"))) {
            return false;
        }
        return !filter.has("resource") || matchesReference(policy.resource(), filter.get("resource"));
    }

    private static boolean matchesReference(EntityIdentifier entity, JsonNode reference) {
        if (reference == null || !reference.isObject()) {
            return false;
        }
        if (reference.has("unspecified")) {
            return reference.path("unspecified").asBoolean(false) && entity == null;
        }
        JsonNode id = reference.get("identifier");
        return entity != null && id != null && id.isObject()
                && entity.entityType().equals(id.path("entityType").asText())
                && entity.entityId().equals(id.path("entityId").asText());
    }

    private void ensureStrictSchemaPresent(PolicyStore store) {
        if ("STRICT".equals(store.validationMode()) && store.schema() == null) {
            throw validation("STRICT validation requires a schema before policies or templates can be stored.");
        }
    }

    private void validateStrictPolicy(PolicyStore store, com.cedarpolicy.model.policy.Policy policy, boolean template) {
        ensureStrictSchemaPresent(store);
        if (!"STRICT".equals(store.validationMode())) {
            return;
        }
        try {
            com.cedarpolicy.model.schema.Schema schema = com.cedarpolicy.model.schema.Schema.parse(
                    com.cedarpolicy.model.schema.Schema.JsonOrCedar.Json, store.schema());
            PolicySet set = template
                    ? new PolicySet(java.util.Set.of(), java.util.Set.of(policy))
                    : new PolicySet(java.util.Set.of(policy));
            com.cedarpolicy.model.ValidationResponse response = new com.cedarpolicy.BasicAuthorizationEngine()
                    .validate(new com.cedarpolicy.model.ValidationRequest(schema, set));
            if (!response.validationPassed()) {
                throw validation("The Cedar policy failed STRICT schema validation: " + response);
            }
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("The Cedar policy failed STRICT schema validation: " + safeMessage(e));
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private PolicyStore storeByArn(String arn, String region) {
        if (arn == null) {
            throw validation("resourceArn is required.");
        }
        return policyStores.scan(k -> k.startsWith(region + ":")).stream()
                .filter(s -> arn.equals(s.arn())).findFirst()
                .orElseThrow(() -> notFound("Resource not found: " + arn));
    }

    private void purgeExpiredAliases(String region) {
        Instant now = Instant.now();
        aliases.scan(k -> k.startsWith(region + ":")).stream()
                .filter(a -> "PendingDeletion".equals(a.state()) && a.deleteAfter() != null && !now.isBefore(a.deleteAfter()))
                .forEach(a -> aliases.delete(aliasKey(region, a.aliasName())));
    }

    private String storeArn(String region, String id) {
        return "arn:aws:verifiedpermissions:" + region + ":" + regionResolver.getAccountId() + ":policy-store/" + id;
    }

    private String aliasArn(String region, String aliasName) {
        return "arn:aws:verifiedpermissions:" + region + ":" + regionResolver.getAccountId() + ":" + aliasName;
    }

    private static String storeKey(String region, String id) { return region + ":" + id; }
    private static String aliasKey(String region, String name) { return region + ":" + name; }

    private void validateSchemaJson(String schema) {
        try {
            JsonNode node = objectMapper.readTree(schema);
            if (node == null || !node.isObject()) {
                throw validation("The Cedar schema must be a JSON object.");
            }
            com.cedarpolicy.model.schema.Schema.parse(
                    com.cedarpolicy.model.schema.Schema.JsonOrCedar.Json, schema);
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("The Cedar schema is invalid: " + safeMessage(e));
        }
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (!node.isObject()) {
            throw validation("tags must be an object.");
        }
        node.fields().forEachRemaining(e -> {
            if (!e.getValue().isTextual()) {
                throw validation("Tag values must be strings.");
            }
            result.put(e.getKey(), e.getValue().asText());
        });
        return result;
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags.size() > MAX_TAGS) {
            throw validation("A resource can have at most 200 tags.");
        }
        for (var tag : tags.entrySet()) {
            if (tag.getKey() == null || tag.getKey().isEmpty() || tag.getKey().length() > 128 || tag.getValue().length() > 256) {
                throw validation("A tag doesn't satisfy the required length constraints.");
            }
        }
    }

    private static void validateMode(String mode) {
        if (!"OFF".equals(mode) && !"STRICT".equals(mode)) {
            throw validation("mode must be OFF or STRICT.");
        }
    }
    private static void validateDeletionProtection(String value) {
        if (!"ENABLED".equals(value) && !"DISABLED".equals(value)) {
            throw validation("deletionProtection must be ENABLED or DISABLED.");
        }
    }
    private static void validatePolicyStatement(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_POLICY_STATEMENT_LENGTH) {
            throw validation("statement must contain between 1 and 10000 characters.");
        }
    }
    private static void validateDescription(String value) {
        if (value != null && value.length() > 150) {
            throw validation("description must be 150 characters or fewer.");
        }
    }
    private static void validateAliasName(String value) {
        if (value == null || value.length() > 150 || !ALIAS_NAME.matcher(value).matches()) {
            throw validation("aliasName does not satisfy the required constraints.");
        }
    }
    static String requiredText(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw validation(name + " is required.");
        }
        return value.asText();
    }
    static JsonNode requiredObject(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isObject()) {
            throw validation(name + " is required.");
        }
        return value;
    }
    static String text(JsonNode node, String name, String defaultValue) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? defaultValue : value.asText();
    }
    static Integer optionalInt(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asInt();
    }
    static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }
    static AwsException notFound(String message) { return new AwsException("ResourceNotFoundException", message, 400); }
    static AwsException conflict(String message) { return new AwsException("ConflictException", message, 400); }
}
