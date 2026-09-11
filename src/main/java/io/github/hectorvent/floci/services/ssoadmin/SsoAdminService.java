package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssoadmin.model.Assignment;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentDeletionOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAccessScope;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAssignment;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAuthenticationMethod;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationGrant;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationPortalOptions;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationSignInOptions;
import io.github.hectorvent.floci.services.ssoadmin.model.AccessControlAttribute;
import io.github.hectorvent.floci.services.ssoadmin.model.CustomerManagedPolicyReference;
import io.github.hectorvent.floci.services.ssoadmin.model.InstanceAccessControlAttributeConfiguration;
import io.github.hectorvent.floci.services.ssoadmin.model.InstanceUpdateState;
import io.github.hectorvent.floci.services.ssoadmin.model.OidcJwtIssuerConfiguration;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSet;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionsBoundary;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSetProvisioning;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSetProvisioningOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.RegionMetadata;
import io.github.hectorvent.floci.services.ssoadmin.model.SsoApplication;
import io.github.hectorvent.floci.services.ssoadmin.model.SsoInstance;
import io.github.hectorvent.floci.services.ssoadmin.model.TrustedTokenIssuer;
import io.github.hectorvent.floci.services.identitystore.IdentityStoreService;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class SsoAdminService implements Resettable {
    private static final String INSTANCE_ARN = globalArn("sso", "us-east-1", "", "instance/ssoins-7223b02a5d9f7c8e");
    private static final String IDENTITY_STORE_ID = "d-9067f2a3c1";
    private static final String PRIMARY_REGION = "us-east-1";
    private static final Pattern INSTANCE_ARN_PATTERN = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:sso:::instance/(?:sso)?ins-[a-zA-Z0-9-.]{16}");
    private static final Pattern PERMISSION_SET_NAME = Pattern.compile("[\\w+=,.@-]+");
    private static final Pattern PERMISSION_SET_ARN = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:sso:::permissionSet/(?:sso)?ins-[a-zA-Z0-9-.]{16}/ps-[a-zA-Z0-9-./]{16}");
    private static final Pattern PRINCIPAL_ID = Pattern.compile("([0-9a-f]{10}-|)[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}");
    private static final Pattern MANAGED_POLICY_ARN = Pattern.compile("arn:aws:iam::aws:policy/.+");
    private static final Pattern CUSTOMER_MANAGED_POLICY_NAME = Pattern.compile("[\\w+=,.@-]+");
    private static final Pattern CUSTOMER_MANAGED_POLICY_PATH = Pattern.compile("((/[A-Za-z0-9\\.,\\+@=_-]+)*)/");
    private static final Pattern REGION_NAME = Pattern.compile("([a-z]+-){2,3}\\d");
    private static final Pattern APPLICATION_PROVIDER_ARN = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:sso::aws:applicationProvider/[a-zA-Z0-9-/]+");
    private static final Pattern APPLICATION_ARN = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:sso::\\d{12}:application/(?:sso)?ins-[a-zA-Z0-9-.]{16}/apl-[a-zA-Z0-9]{16}");
    private static final Pattern TRUSTED_TOKEN_ISSUER_ARN = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:sso::\\d{12}:trustedTokenIssuer/(?:sso)?ins-[a-zA-Z0-9-.]{16}/tti-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern APPLICATION_ACCESS_SCOPE = Pattern.compile("([A-Za-z0-9_]{1,50})(:[A-Za-z0-9_]{1,50}){0,1}(:[A-Za-z0-9_]{1,50}){0,1}");
    private static final Pattern APPLICATION_ACCESS_TARGET = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:sso::(?:\\d{12}:application/(?:sso)?ins-[a-zA-Z0-9-.]{16}/apl-[a-zA-Z0-9]{16}|:instance/(?:sso)?ins-[a-zA-Z0-9-.]{16})");
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[!-~]+");
    private static final Pattern APPLICATION_URL = Pattern.compile("http(s)?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%?=~_|]");
    private static final Pattern TAG_VALUE = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*");
    private static final Pattern NEXT_TOKEN = Pattern.compile("[-a-zA-Z0-9+=/_]*");
    private static final Pattern INSTANCE_NAME = Pattern.compile("[\\w+=,.@-]+");
    private static final Pattern ACCESS_CONTROL_ATTRIBUTE_KEY = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]+");
    private static final Pattern ACCESS_CONTROL_ATTRIBUTE_SOURCE = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@\\[\\]\\{\\}$\\\\\"]*");
    private static final Pattern OIDC_CLAIM_ATTRIBUTE_PATH = Pattern.compile("\\p{L}+(?:(\\.|_)\\p{L}+){0,2}");
    private static final Pattern OIDC_IDENTITY_STORE_ATTRIBUTE_PATH = Pattern.compile("\\p{L}+(?:\\.\\p{L}+){0,2}");
    private static final Pattern OIDC_ISSUER_URL = Pattern.compile("https?://[-a-zA-Z0-9+&@/%=~_|!:,.;]*[-a-zA-Z0-9+&@/%=~_|]");
    private static final Pattern KMS_KEY_ARN = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:kms:([a-z]{2,}(-[a-z0-9]+)+):[0-9]{12}:key/(?:mrk-[a-f0-9]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final int PERMISSION_SET_QUOTA = 3500;
    private static final int REGION_QUOTA = 6;
    private static final int MANAGED_POLICY_QUOTA = 25;
    private static final int APPLICATION_QUOTA = 7000;
    private static final int APPLICATION_GROUP_ASSIGNMENT_QUOTA = 100;
    private static final int TRUSTED_TOKEN_ISSUER_QUOTA = 10;
    private static final Set<String> APPLICATION_GRANT_TYPES = Set.of(
            "authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "urn:ietf:params:oauth:grant-type:token-exchange");
    private static final int MAX_INLINE_POLICY_BYTES = 32_768;
    private static final int MAX_INLINE_NON_WHITESPACE = 10_240;
    private static final Set<String> PRINCIPAL_TYPES = Set.of("USER", "GROUP");

    private final StorageBackend<String, PermissionSet> permissionSets;
    private final StorageBackend<String, Assignment> assignments;
    private final StorageBackend<String, AssignmentOperation> assignmentOperations;
    private final StorageBackend<String, AssignmentDeletionOperation> assignmentDeletionOperations;
    private final StorageBackend<String, PermissionSetProvisioning> permissionSetProvisionings;
    private final StorageBackend<String, PermissionSetProvisioningOperation> permissionSetProvisioningOperations;
    private final StorageBackend<String, RegionMetadata> regions;
    private final StorageBackend<String, SsoApplication> applications;
    private final StorageBackend<String, SsoApplication> applicationUpdateOverrides;
    private final StorageBackend<String, String> applicationClientTokens;
    private final StorageBackend<String, ApplicationAssignment> applicationAssignments;
    private final StorageBackend<String, ApplicationAccessScope> applicationAccessScopes;
    private final StorageBackend<String, Boolean> applicationAssignmentConfigurations;
    private final StorageBackend<String, ApplicationAuthenticationMethod> applicationAuthenticationMethods;
    private final StorageBackend<String, ApplicationGrant> applicationGrants;
    private final StorageBackend<String, String> applicationSessionConfigurations;
    private final StorageBackend<String, Map<String, String>> resourceTagOverrides;
    private final StorageBackend<String, SsoInstance> instances;
    private final StorageBackend<String, InstanceUpdateState> instanceUpdateStates;
    private final StorageBackend<String, String> instanceClientTokens;
    private final StorageBackend<String, Boolean> instanceDeletionMarkers;
    private final StorageBackend<String, InstanceAccessControlAttributeConfiguration> accessControlAttributeConfigurations;
    private final StorageBackend<String, TrustedTokenIssuer> trustedTokenIssuers;
    private final StorageBackend<String, TrustedTokenIssuer> trustedTokenIssuerUpdateOverrides;
    private final StorageBackend<String, String> trustedTokenIssuerClientTokens;
    private final IdentityStoreService identityStoreService;
    private final OrganizationsService organizationsService;
    private final String defaultAccountId;
    private final String defaultRegion;

    @Inject
    public SsoAdminService(StorageFactory storageFactory, IdentityStoreService identityStoreService,
                           OrganizationsService organizationsService, EmulatorConfig config) {
        this(
                storageFactory.create("ssoadmin", "ssoadmin-permission-sets.json", new TypeReference<Map<String, PermissionSet>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-assignments.json", new TypeReference<Map<String, Assignment>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-assignment-operations.json", new TypeReference<Map<String, AssignmentOperation>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-assignment-deletion-operations.json", new TypeReference<Map<String, AssignmentDeletionOperation>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-permission-set-provisionings.json", new TypeReference<Map<String, PermissionSetProvisioning>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-permission-set-provisioning-operations.json", new TypeReference<Map<String, PermissionSetProvisioningOperation>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-regions.json", new TypeReference<Map<String, RegionMetadata>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-applications.json", new TypeReference<Map<String, SsoApplication>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-update-overrides.json", new TypeReference<Map<String, SsoApplication>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-client-tokens.json", new TypeReference<Map<String, String>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-assignments.json", new TypeReference<Map<String, ApplicationAssignment>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-access-scopes.json", new TypeReference<Map<String, ApplicationAccessScope>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-assignment-configurations.json", new TypeReference<Map<String, Boolean>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-authentication-methods.json", new TypeReference<Map<String, ApplicationAuthenticationMethod>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-grants.json", new TypeReference<Map<String, ApplicationGrant>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-application-session-configurations.json", new TypeReference<Map<String, String>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-resource-tag-overrides.json", new TypeReference<Map<String, Map<String, String>>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-instances.json", new TypeReference<Map<String, SsoInstance>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-instance-update-states.json", new TypeReference<Map<String, InstanceUpdateState>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-instance-client-tokens.json", new TypeReference<Map<String, String>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-instance-deletion-markers.json", new TypeReference<Map<String, Boolean>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-access-control-attribute-configurations.json", new TypeReference<Map<String, InstanceAccessControlAttributeConfiguration>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-trusted-token-issuers.json", new TypeReference<Map<String, TrustedTokenIssuer>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-trusted-token-issuer-update-overrides.json", new TypeReference<Map<String, TrustedTokenIssuer>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-trusted-token-issuer-client-tokens.json", new TypeReference<Map<String, String>>() {}),
                identityStoreService,
                organizationsService,
                config.defaultAccountId(),
                config.defaultRegion());
    }

    SsoAdminService(StorageBackend<String, PermissionSet> permissionSets,
                    StorageBackend<String, Assignment> assignments,
                    StorageBackend<String, AssignmentOperation> assignmentOperations,
                    StorageBackend<String, AssignmentDeletionOperation> assignmentDeletionOperations,
                    StorageBackend<String, PermissionSetProvisioning> permissionSetProvisionings,
                    StorageBackend<String, PermissionSetProvisioningOperation> permissionSetProvisioningOperations,
                    StorageBackend<String, RegionMetadata> regions,
                    StorageBackend<String, SsoApplication> applications,
                    StorageBackend<String, SsoApplication> applicationUpdateOverrides,
                    StorageBackend<String, String> applicationClientTokens,
                    StorageBackend<String, ApplicationAssignment> applicationAssignments,
                    StorageBackend<String, ApplicationAccessScope> applicationAccessScopes,
                    StorageBackend<String, Boolean> applicationAssignmentConfigurations,
                    StorageBackend<String, ApplicationAuthenticationMethod> applicationAuthenticationMethods,
                    StorageBackend<String, ApplicationGrant> applicationGrants,
                    StorageBackend<String, String> applicationSessionConfigurations,
                    StorageBackend<String, Map<String, String>> resourceTagOverrides,
                    StorageBackend<String, SsoInstance> instances,
                    StorageBackend<String, InstanceUpdateState> instanceUpdateStates,
                    StorageBackend<String, String> instanceClientTokens,
                    StorageBackend<String, Boolean> instanceDeletionMarkers,
                    StorageBackend<String, InstanceAccessControlAttributeConfiguration> accessControlAttributeConfigurations,
                    StorageBackend<String, TrustedTokenIssuer> trustedTokenIssuers,
                    StorageBackend<String, TrustedTokenIssuer> trustedTokenIssuerUpdateOverrides,
                    StorageBackend<String, String> trustedTokenIssuerClientTokens,
                    IdentityStoreService identityStoreService,
                    OrganizationsService organizationsService,
                    String defaultAccountId,
                    String defaultRegion) {
        this.permissionSets = permissionSets;
        this.assignments = assignments;
        this.assignmentOperations = assignmentOperations;
        this.assignmentDeletionOperations = assignmentDeletionOperations;
        this.permissionSetProvisionings = permissionSetProvisionings;
        this.permissionSetProvisioningOperations = permissionSetProvisioningOperations;
        this.regions = regions;
        this.applications = applications;
        this.applicationUpdateOverrides = applicationUpdateOverrides;
        this.applicationClientTokens = applicationClientTokens;
        this.applicationAssignments = applicationAssignments;
        this.applicationAccessScopes = applicationAccessScopes;
        this.applicationAssignmentConfigurations = applicationAssignmentConfigurations;
        this.applicationAuthenticationMethods = applicationAuthenticationMethods;
        this.applicationGrants = applicationGrants;
        this.applicationSessionConfigurations = applicationSessionConfigurations;
        this.resourceTagOverrides = resourceTagOverrides;
        this.instances = instances;
        this.instanceUpdateStates = instanceUpdateStates;
        this.instanceClientTokens = instanceClientTokens;
        this.instanceDeletionMarkers = instanceDeletionMarkers;
        this.accessControlAttributeConfigurations = accessControlAttributeConfigurations;
        this.trustedTokenIssuers = trustedTokenIssuers;
        this.trustedTokenIssuerUpdateOverrides = trustedTokenIssuerUpdateOverrides;
        this.trustedTokenIssuerClientTokens = trustedTokenIssuerClientTokens;
        this.identityStoreService = identityStoreService;
        this.organizationsService = organizationsService;
        this.defaultAccountId = defaultAccountId;
        this.defaultRegion = defaultRegion;
        ensureBootstrapInstance(defaultAccountId, defaultRegion);
    }

    public String getInstanceArn() { return INSTANCE_ARN; }
    public String getIdentityStoreId() { return IDENTITY_STORE_ID; }

    public boolean hasIdentityStore(String identityStoreId) {
        if (identityStoreId == null) {
            return false;
        }
        // Self-heals like listInstances(): storageFactory.clearAll() wipes `instances` right after
        // clear() re-seeds it, so /state/reset otherwise leaves the bootstrap instance missing.
        ensureBootstrapInstance(defaultAccountId, defaultRegion);
        if (instances.scan(key -> true).stream()
                .anyMatch(instance -> identityStoreId.equals(instance.identityStoreId()))) {
            return true;
        }
        if (instances instanceof AccountAwareStorageBackend<?> accountAware) {
            return accountAware.scanAllAccounts().stream()
                    .filter(SsoInstance.class::isInstance)
                    .map(SsoInstance.class::cast)
                    .anyMatch(instance -> identityStoreId.equals(instance.identityStoreId()));
        }
        return false;
    }

    void ensureBootstrapInstance(String ownerAccountId, String region) {
        if (instanceDeletionMarkers.get(ownerAccountId).orElse(false)) {
            return;
        }
        if (instances.get(ownerAccountId).isEmpty()) {
            instances.put(ownerAccountId, new SsoInstance(INSTANCE_ARN, IDENTITY_STORE_ID, "floci-identity-center",
                    ownerAccountId, region, System.currentTimeMillis(), "ACTIVE", null, false,
                    new LinkedHashMap<>()));
        }
    }

    public synchronized List<SsoInstance> listInstances(String callerAccountId) {
        validateAccountId(callerAccountId);
        if (callerAccountId.equals(defaultAccountId) && instances.get(callerAccountId).isEmpty()) {
            ensureBootstrapInstance(callerAccountId, defaultRegion);
        }
        return instances.get(callerAccountId).map(List::of).orElseGet(List::of);
    }

    public List<RegionMetadata> listRegionsForInstance(SsoInstance instance) {
        List<RegionMetadata> result = new ArrayList<>();
        result.add(new RegionMetadata(instance.primaryRegion(), "ACTIVE",
                java.time.Instant.ofEpochMilli(instance.createdDateEpochMillis()).toString(), true));
        if (!instance.accountInstance()) {
            result.addAll(regions.scan(key -> true).stream()
                    .filter(region -> !instance.primaryRegion().equals(region.regionName()))
                    .sorted(Comparator.comparing(RegionMetadata::regionName))
                    .toList());
        }
        return result;
    }

    public double regionAddedDateEpochSeconds(RegionMetadata region) {
        return java.time.Instant.parse(region.addedDate()).toEpochMilli() / 1000.0d;
    }

    public synchronized TrustedTokenIssuer createTrustedTokenIssuer(JsonNode request, String callerAccountId) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        validateAccountId(callerAccountId);
        String name = required(request, "Name");
        if (name.length() > 255 || !INSTANCE_NAME.matcher(name).matches()) {
            throw validation("Name must be 1-255 characters and match [\\w+=,.@-]+.");
        }
        String issuerType = required(request, "TrustedTokenIssuerType");
        if (!"OIDC_JWT".equals(issuerType)) {
            throw validation("TrustedTokenIssuerType must be OIDC_JWT.");
        }
        JsonNode configurationNode = request.get("TrustedTokenIssuerConfiguration");
        if (configurationNode == null || !configurationNode.isObject()
                || configurationNode.size() != 1
                || !configurationNode.has("OidcJwtConfiguration")
                || !configurationNode.get("OidcJwtConfiguration").isObject()) {
            throw validation("TrustedTokenIssuerConfiguration must contain exactly one OidcJwtConfiguration.");
        }
        JsonNode oidcNode = configurationNode.get("OidcJwtConfiguration");
        String claimAttributePath = required(oidcNode, "ClaimAttributePath");
        if (claimAttributePath.length() > 255 || !OIDC_CLAIM_ATTRIBUTE_PATH.matcher(claimAttributePath).matches()) {
            throw validation("ClaimAttributePath is invalid.");
        }
        String identityStoreAttributePath = required(oidcNode, "IdentityStoreAttributePath");
        if (identityStoreAttributePath.length() > 255
                || !OIDC_IDENTITY_STORE_ATTRIBUTE_PATH.matcher(identityStoreAttributePath).matches()) {
            throw validation("IdentityStoreAttributePath is invalid.");
        }
        String issuerUrl = required(oidcNode, "IssuerUrl");
        if (issuerUrl.length() > 512 || !OIDC_ISSUER_URL.matcher(issuerUrl).matches()) {
            throw validation("IssuerUrl is invalid.");
        }
        String jwksRetrievalOption = required(oidcNode, "JwksRetrievalOption");
        if (!"OPEN_ID_DISCOVERY".equals(jwksRetrievalOption)) {
            throw validation("JwksRetrievalOption must be OPEN_ID_DISCOVERY.");
        }
        OidcJwtIssuerConfiguration oidcConfiguration = new OidcJwtIssuerConfiguration(
                claimAttributePath, identityStoreAttributePath, issuerUrl, jwksRetrievalOption);
        Map<String, String> tags = parseTags(request.get("Tags"));

        JsonNode clientTokenNode = request.get("ClientToken");
        String clientToken = null;
        if (clientTokenNode != null && !clientTokenNode.isNull()) {
            if (!clientTokenNode.isTextual()) {
                throw validation("ClientToken must be a string.");
            }
            clientToken = clientTokenNode.textValue();
            if (clientToken.isEmpty() || clientToken.length() > 64 || !CLIENT_TOKEN.matcher(clientToken).matches()) {
                throw validation("ClientToken must be between 1 and 64 visible ASCII characters.");
            }
        }
        String tokenKey = clientToken == null ? null : instanceArn + "::" + clientToken;
        if (tokenKey != null) {
            var priorArn = trustedTokenIssuerClientTokens.get(tokenKey);
            if (priorArn.isPresent()) {
                TrustedTokenIssuer prior = trustedTokenIssuers.get(priorArn.get()).orElse(null);
                if (prior != null && trustedTokenIssuerMatches(prior, name, issuerType, oidcConfiguration, tags)) {
                    return prior;
                }
                throw new AwsException("IdempotentParameterMismatch",
                        "ClientToken was reused with different request parameters.", 400);
            }
        }
        long count = trustedTokenIssuers.scan(ignored -> true).stream()
                .filter(issuer -> instanceArn.equals(issuer.instanceArn()))
                .count();
        if (count >= TRUSTED_TOKEN_ISSUER_QUOTA) {
            throw quota("An IAM Identity Center instance can have at most 10 trusted token issuers.");
        }
        String instanceId = instanceArn.substring(instanceArn.lastIndexOf('/') + 1);
        String ownerAccountId = instance.ownerAccountId() == null ? callerAccountId : instance.ownerAccountId();
        String issuerArn = globalArn("sso", instance.primaryRegion(), ownerAccountId,
                "trustedTokenIssuer/" + instanceId + "/tti-" + UUID.randomUUID());
        TrustedTokenIssuer issuer = new TrustedTokenIssuer(
                issuerArn, instanceArn, name, issuerType, oidcConfiguration, tags);
        trustedTokenIssuers.put(issuerArn, issuer);
        if (tokenKey != null) {
            trustedTokenIssuerClientTokens.put(tokenKey, issuerArn);
        }
        return issuer;
    }

    public TrustedTokenIssuer describeTrustedTokenIssuer(JsonNode request) {
        return getTrustedTokenIssuer(required(request, "TrustedTokenIssuerArn"));
    }

    public PaginatedResult<TrustedTokenIssuer> listTrustedTokenIssuers(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        requireInstance(instanceArn);
        List<TrustedTokenIssuer> matching = trustedTokenIssuers.scan(key -> true).stream()
                .map(issuer -> trustedTokenIssuerUpdateOverrides.get(issuer.trustedTokenIssuerArn()).orElse(issuer))
                .filter(issuer -> instanceArn.equals(issuer.instanceArn()))
                .sorted(Comparator.comparing(TrustedTokenIssuer::trustedTokenIssuerArn))
                .toList();
        return Pagination.paginate(matching, TrustedTokenIssuer::trustedTokenIssuerArn,
                optionalMaxResults(request), text(request, "NextToken"), 100, 100, "ValidationException");
    }

    public PaginatedResult<Map.Entry<String, String>> listTagsForResource(JsonNode request) {
        String instanceArn = optionalInstanceArn(request);
        String resourceArn = required(request, "ResourceArn");
        TaggableResource resource = resolveTaggableResource(resourceArn);
        validateResourceInstance(instanceArn, resource.instanceArn());
        Map<String, String> tags = resourceTagOverrides.get(resourceArn)
                .orElseGet(() -> resource.tags());
        List<Map.Entry<String, String>> entries = tags.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        return Pagination.paginate(entries, Map.Entry::getKey, null,
                optionalNextToken(request), 75, 75, "ValidationException");
    }

    public synchronized void tagResource(JsonNode request) {
        String instanceArn = optionalInstanceArn(request);
        String resourceArn = required(request, "ResourceArn");
        TaggableResource resource = resolveTaggableResource(resourceArn);
        validateResourceInstance(instanceArn, resource.instanceArn());
        JsonNode tagsNode = request == null ? null : request.get("Tags");
        if (tagsNode == null || tagsNode.isNull()) {
            throw validation("Tags is required.");
        }
        Map<String, String> requestedTags = parseTags(tagsNode);
        Map<String, String> updated = new LinkedHashMap<>(resourceTagOverrides.get(resourceArn)
                .orElseGet(resource::tags));
        updated.putAll(requestedTags);
        if (updated.size() > 75) {
            throw quota("A resource can have at most 75 tags.");
        }
        resourceTagOverrides.put(resourceArn, updated);
    }

    public synchronized void untagResource(JsonNode request) {
        String instanceArn = optionalInstanceArn(request);
        String resourceArn = required(request, "ResourceArn");
        TaggableResource resource = resolveTaggableResource(resourceArn);
        validateResourceInstance(instanceArn, resource.instanceArn());
        JsonNode tagKeysNode = request == null ? null : request.get("TagKeys");
        if (tagKeysNode == null || !tagKeysNode.isArray() || tagKeysNode.size() < 1 || tagKeysNode.size() > 75) {
            throw validation("TagKeys must contain between 1 and 75 entries.");
        }
        List<String> tagKeys = new ArrayList<>();
        for (JsonNode keyNode : tagKeysNode) {
            if (!keyNode.isTextual()) {
                throw validation("TagKeys values must be strings.");
            }
            String key = keyNode.textValue();
            if (key.isEmpty() || key.length() > 128 || !TAG_VALUE.matcher(key).matches()) {
                throw validation("Tag key is invalid.");
            }
            tagKeys.add(key);
        }
        Map<String, String> updated = new LinkedHashMap<>(resourceTagOverrides.get(resourceArn)
                .orElseGet(resource::tags));
        tagKeys.forEach(updated::remove);
        resourceTagOverrides.put(resourceArn, updated);
    }

    private TaggableResource resolveTaggableResource(String resourceArn) {
        if (resourceArn == null || resourceArn.length() < 10 || resourceArn.length() > 2048) {
            throw validation("ResourceArn is invalid.");
        }
        if (INSTANCE_ARN_PATTERN.matcher(resourceArn).matches()) {
            SsoInstance instance = requireInstance(resourceArn);
            return new TaggableResource(instance.tags(), instance.instanceArn());
        }
        if (PERMISSION_SET_ARN.matcher(resourceArn).matches()) {
            PermissionSet permissionSet = permissionSets.get(resourceArn)
                    .orElseThrow(() -> notFound("Permission set not found: " + resourceArn));
            return new TaggableResource(permissionSet.tags(), instanceArnForPermissionSet(resourceArn));
        }
        if (APPLICATION_ARN.matcher(resourceArn).matches()) {
            SsoApplication application = getApplication(resourceArn);
            return new TaggableResource(application.tags(), application.instanceArn());
        }
        if (TRUSTED_TOKEN_ISSUER_ARN.matcher(resourceArn).matches()) {
            TrustedTokenIssuer issuer = getTrustedTokenIssuer(resourceArn);
            return new TaggableResource(issuer.tags(), issuer.instanceArn());
        }
        throw validation("ResourceArn is invalid.");
    }

    private static void validateResourceInstance(String requestedInstanceArn, String resourceInstanceArn) {
        if (requestedInstanceArn != null && !requestedInstanceArn.equals(resourceInstanceArn)) {
            throw notFound("Resource not found under the specified IAM Identity Center instance.");
        }
    }

    private record TaggableResource(Map<String, String> tags, String instanceArn) {}

    public TrustedTokenIssuer getTrustedTokenIssuer(String trustedTokenIssuerArn) {
        validateTrustedTokenIssuerArn(trustedTokenIssuerArn);
        TrustedTokenIssuer base = trustedTokenIssuers.get(trustedTokenIssuerArn)
                .orElseThrow(() -> notFound("Trusted token issuer not found: " + trustedTokenIssuerArn));
        return trustedTokenIssuerUpdateOverrides.get(trustedTokenIssuerArn).orElse(base);
    }

    public synchronized TrustedTokenIssuer updateTrustedTokenIssuer(JsonNode request) {
        String trustedTokenIssuerArn = required(request, "TrustedTokenIssuerArn");
        TrustedTokenIssuer current = getTrustedTokenIssuer(trustedTokenIssuerArn);
        String name = current.name();
        if (request != null && request.has("Name") && !request.get("Name").isNull()) {
            name = text(request, "Name");
            if (name == null || name.isEmpty() || name.length() > 255 || !INSTANCE_NAME.matcher(name).matches()) {
                throw validation("Name must be 1-255 characters and match [\\w+=,.@-]+.");
            }
        }
        OidcJwtIssuerConfiguration oidc = current.oidcJwtConfiguration();
        if (request != null && request.has("TrustedTokenIssuerConfiguration")
                && !request.get("TrustedTokenIssuerConfiguration").isNull()) {
            JsonNode configuration = request.get("TrustedTokenIssuerConfiguration");
            if (!configuration.isObject() || configuration.size() != 1
                    || !configuration.has("OidcJwtConfiguration")
                    || !configuration.get("OidcJwtConfiguration").isObject()) {
                throw validation("TrustedTokenIssuerConfiguration must contain exactly one OidcJwtConfiguration.");
            }
            JsonNode update = configuration.get("OidcJwtConfiguration");
            if (update.has("IssuerUrl")) {
                throw validation("IssuerUrl cannot be updated.");
            }
            String claimAttributePath = oidc.claimAttributePath();
            if (update.has("ClaimAttributePath") && !update.get("ClaimAttributePath").isNull()) {
                claimAttributePath = text(update, "ClaimAttributePath");
                if (claimAttributePath == null || claimAttributePath.isEmpty() || claimAttributePath.length() > 255
                        || !OIDC_CLAIM_ATTRIBUTE_PATH.matcher(claimAttributePath).matches()) {
                    throw validation("ClaimAttributePath is invalid.");
                }
            }
            String identityStoreAttributePath = oidc.identityStoreAttributePath();
            if (update.has("IdentityStoreAttributePath") && !update.get("IdentityStoreAttributePath").isNull()) {
                identityStoreAttributePath = text(update, "IdentityStoreAttributePath");
                if (identityStoreAttributePath == null || identityStoreAttributePath.isEmpty()
                        || identityStoreAttributePath.length() > 255
                        || !OIDC_IDENTITY_STORE_ATTRIBUTE_PATH.matcher(identityStoreAttributePath).matches()) {
                    throw validation("IdentityStoreAttributePath is invalid.");
                }
            }
            String jwksRetrievalOption = oidc.jwksRetrievalOption();
            if (update.has("JwksRetrievalOption") && !update.get("JwksRetrievalOption").isNull()) {
                jwksRetrievalOption = text(update, "JwksRetrievalOption");
                if (!"OPEN_ID_DISCOVERY".equals(jwksRetrievalOption)) {
                    throw validation("JwksRetrievalOption must be OPEN_ID_DISCOVERY.");
                }
            }
            oidc = new OidcJwtIssuerConfiguration(claimAttributePath, identityStoreAttributePath,
                    oidc.issuerUrl(), jwksRetrievalOption);
        }
        TrustedTokenIssuer updated = new TrustedTokenIssuer(
                current.trustedTokenIssuerArn(), current.instanceArn(), name, current.trustedTokenIssuerType(),
                oidc, current.tags());
        trustedTokenIssuerUpdateOverrides.put(trustedTokenIssuerArn, updated);
        return updated;
    }

    public synchronized void deleteTrustedTokenIssuer(JsonNode request) {
        String trustedTokenIssuerArn = required(request, "TrustedTokenIssuerArn");
        getTrustedTokenIssuer(trustedTokenIssuerArn);
        trustedTokenIssuers.delete(trustedTokenIssuerArn);
        trustedTokenIssuerUpdateOverrides.delete(trustedTokenIssuerArn);
        resourceTagOverrides.delete(trustedTokenIssuerArn);
        for (String key : new ArrayList<>(trustedTokenIssuerClientTokens.keys())) {
            if (trustedTokenIssuerArn.equals(trustedTokenIssuerClientTokens.get(key).orElse(null))) {
                trustedTokenIssuerClientTokens.delete(key);
            }
        }
    }

    private static void validateTrustedTokenIssuerArn(String trustedTokenIssuerArn) {
        if (trustedTokenIssuerArn == null || trustedTokenIssuerArn.length() < 10 || trustedTokenIssuerArn.length() > 1224
                || !TRUSTED_TOKEN_ISSUER_ARN.matcher(trustedTokenIssuerArn).matches()) {
            throw validation("TrustedTokenIssuerArn is invalid.");
        }
    }

    private static boolean trustedTokenIssuerMatches(TrustedTokenIssuer issuer, String name, String issuerType,
                                                       OidcJwtIssuerConfiguration oidcConfiguration,
                                                       Map<String, String> tags) {
        return java.util.Objects.equals(issuer.name(), name)
                && java.util.Objects.equals(issuer.trustedTokenIssuerType(), issuerType)
                && java.util.Objects.equals(issuer.oidcJwtConfiguration(), oidcConfiguration)
                && java.util.Objects.equals(issuer.tags(), tags);
    }

    public synchronized InstanceAccessControlAttributeConfiguration createInstanceAccessControlAttributeConfiguration(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        requireInstance(instanceArn);
        if (accessControlAttributeConfigurations.get(instanceArn).isPresent()) {
            throw conflict("An instance access control attribute configuration already exists for this IAM Identity Center instance.");
        }
        List<AccessControlAttribute> attributes = parseAccessControlAttributes(request);
        InstanceAccessControlAttributeConfiguration configuration = new InstanceAccessControlAttributeConfiguration(
                instanceArn, attributes, "ENABLED", null);
        accessControlAttributeConfigurations.put(instanceArn, configuration);
        return configuration;
    }

    public synchronized InstanceAccessControlAttributeConfiguration updateInstanceAccessControlAttributeConfiguration(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        requireInstance(instanceArn);
        if (accessControlAttributeConfigurations.get(instanceArn).isEmpty()) {
            throw notFound("Instance access control attribute configuration not found for: " + instanceArn);
        }
        InstanceAccessControlAttributeConfiguration configuration = new InstanceAccessControlAttributeConfiguration(
                instanceArn, parseAccessControlAttributes(request), "ENABLED", null);
        accessControlAttributeConfigurations.put(instanceArn, configuration);
        return configuration;
    }

    public InstanceAccessControlAttributeConfiguration describeInstanceAccessControlAttributeConfiguration(JsonNode request) {
        return getInstanceAccessControlAttributeConfiguration(required(request, "InstanceArn"));
    }

    public InstanceAccessControlAttributeConfiguration getInstanceAccessControlAttributeConfiguration(String instanceArn) {
        requireInstance(instanceArn);
        return accessControlAttributeConfigurations.get(instanceArn)
                .orElseThrow(() -> notFound("Instance access control attribute configuration not found for: " + instanceArn));
    }

    public synchronized void deleteInstanceAccessControlAttributeConfiguration(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        requireInstance(instanceArn);
        if (accessControlAttributeConfigurations.get(instanceArn).isEmpty()) {
            throw notFound("Instance access control attribute configuration not found for: " + instanceArn);
        }
        accessControlAttributeConfigurations.delete(instanceArn);
    }

    public synchronized SsoInstance createInstance(JsonNode request, String callerAccountId, String region) {
        validateAccountId(callerAccountId);
        validateRegionName(region);
        if (organizationsService.isManagementAccount(callerAccountId)) {
            throw accessDenied("The AWS Organizations management account cannot create an account instance of IAM Identity Center.");
        }
        String name = optionalInstanceName(request);
        Map<String, String> tags = parseTags(request.get("Tags"));
        JsonNode clientTokenNode = request == null ? null : request.get("ClientToken");
        String clientToken;
        if (clientTokenNode == null || clientTokenNode.isNull()) {
            clientToken = UUID.randomUUID().toString();
        } else if (!clientTokenNode.isTextual()) {
            throw validation("ClientToken must be a string.");
        } else {
            clientToken = clientTokenNode.textValue();
            if (clientToken.length() > 64 || !CLIENT_TOKEN.matcher(clientToken).matches()) {
                throw validation("ClientToken must be between 1 and 64 visible ASCII characters.");
            }
        }
        String tokenKey = callerAccountId + "::" + clientToken;
        var priorArn = instanceClientTokens.get(tokenKey);
        if (priorArn.isPresent()) {
            SsoInstance prior = findInstanceByArn(priorArn.get());
            if (prior != null && java.util.Objects.equals(prior.name(), name)
                    && java.util.Objects.equals(prior.tags(), tags)) {
                return prior;
            }
            throw new AwsException("IdempotentParameterMismatch",
                    "ClientToken was reused with different request parameters.", 400);
        }
        if (instances.get(callerAccountId).isPresent()) {
            throw quota("Only one IAM Identity Center instance can exist in an AWS account.");
        }
        String instanceArn = globalArn("sso", region, "", "instance/ssoins-" + shortId());
        String identityStoreId = "d-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        SsoInstance instance = new SsoInstance(instanceArn, identityStoreId, name, callerAccountId, region,
                System.currentTimeMillis(), "ACTIVE", null, true, tags);
        instances.put(callerAccountId, instance);
        instanceDeletionMarkers.delete(callerAccountId);
        instanceClientTokens.put(tokenKey, instanceArn);
        return instance;
    }

    public SsoInstance describeInstance(JsonNode request) {
        return requireInstance(required(request, "InstanceArn"));
    }

    public synchronized InstanceUpdateState updateInstance(JsonNode request, String callerAccountId) {
        validateAccountId(callerAccountId);
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (!callerAccountId.equals(instance.ownerAccountId())) {
            throw accessDenied("Only the owning AWS account can update this IAM Identity Center instance.");
        }
        InstanceUpdateState current = instanceUpdateState(instance);
        boolean hasEncryption = request != null && request.has("EncryptionConfiguration")
                && !request.get("EncryptionConfiguration").isNull();
        boolean hasPermissionSets = request != null && request.has("PermissionSetsEnabled")
                && !request.get("PermissionSetsEnabled").isNull();
        if (hasEncryption && hasPermissionSets) {
            throw validation("EncryptionConfiguration and PermissionSetsEnabled cannot be updated in the same request.");
        }

        String name = current.name();
        if (request != null && request.has("Name") && !request.get("Name").isNull()) {
            name = optionalInstanceName(request);
        }
        boolean permissionSetsEnabled = current.permissionSetsEnabled();
        String keyType = current.keyType();
        String kmsKeyArn = current.kmsKeyArn();
        if (hasPermissionSets) {
            JsonNode enabled = request.get("PermissionSetsEnabled");
            if (!enabled.isBoolean() || !enabled.booleanValue()) {
                throw validation("PermissionSetsEnabled only accepts true and cannot be disabled.");
            }
            permissionSetsEnabled = true;
        }
        if (hasEncryption) {
            JsonNode encryption = request.get("EncryptionConfiguration");
            if (!encryption.isObject()) {
                throw validation("EncryptionConfiguration must be an object.");
            }
            keyType = required(encryption, "KeyType");
            if (!Set.of("AWS_OWNED_KMS_KEY", "CUSTOMER_MANAGED_KEY").contains(keyType)) {
                throw validation("EncryptionConfiguration.KeyType is invalid.");
            }
            JsonNode kmsArnNode = encryption.get("KmsKeyArn");
            kmsKeyArn = kmsArnNode == null || kmsArnNode.isNull() ? null : text(encryption, "KmsKeyArn");
            if ("CUSTOMER_MANAGED_KEY".equals(keyType)) {
                if (instance.accountInstance()) {
                    throw validation("Customer managed KMS keys are supported only for organization instances.");
                }
                if (kmsKeyArn == null || kmsKeyArn.length() < 20 || kmsKeyArn.length() > 2048
                        || !KMS_KEY_ARN.matcher(kmsKeyArn).matches()) {
                    throw validation("EncryptionConfiguration.KmsKeyArn is required and must be a valid KMS key ARN.");
                }
                java.util.regex.Matcher kmsArnMatcher = KMS_KEY_ARN.matcher(kmsKeyArn);
                if (!kmsArnMatcher.matches() || !instance.primaryRegion().equals(kmsArnMatcher.group(1))) {
                    throw validation("The customer managed KMS key must be in the IAM Identity Center primary Region.");
                }
                String kmsAccountId = kmsKeyArn.split(":", 6)[4];
                if (!instance.ownerAccountId().equals(kmsAccountId)) {
                    throw validation("The customer managed KMS key must be owned by the IAM Identity Center instance account.");
                }
                String keyId = kmsKeyArn.substring(kmsKeyArn.indexOf(":key/") + 5);
                if (!regions.scan(key -> true).isEmpty() && !keyId.startsWith("mrk-")) {
                    throw validation("A multi-Region IAM Identity Center instance requires a multi-Region KMS key.");
                }
            } else if (kmsKeyArn != null) {
                throw validation("KmsKeyArn cannot be specified with AWS_OWNED_KMS_KEY.");
            } else if (!regions.scan(key -> true).isEmpty()) {
                throw validation("AWS owned KMS keys are not supported for multi-Region IAM Identity Center instances.");
            }
        }
        InstanceUpdateState updated = new InstanceUpdateState(
                name, permissionSetsEnabled, keyType, kmsKeyArn, "ENABLED", null);
        instanceUpdateStates.put(instanceArn, updated);
        return updated;
    }

    public InstanceUpdateState instanceUpdateState(SsoInstance instance) {
        return instanceUpdateStates.get(instance.instanceArn()).orElseGet(() -> new InstanceUpdateState(
                instance.name(), !instance.accountInstance(), "AWS_OWNED_KMS_KEY", null, "ENABLED", null));
    }

    public synchronized void deleteInstance(JsonNode request, String callerAccountId) {
        validateAccountId(callerAccountId);
        String instanceArn = required(request, "InstanceArn");
        if (instanceArn.length() < 10 || instanceArn.length() > 1224 || !INSTANCE_ARN_PATTERN.matcher(instanceArn).matches()) {
            throw validation("InstanceArn is invalid.");
        }
        SsoInstance instance = findInstanceByArn(instanceArn);
        if (instance == null || !callerAccountId.equals(instance.ownerAccountId())) {
            throw accessDenied("Only the account that owns the IAM Identity Center instance can delete it.");
        }
        if (!regions.scan(key -> true).isEmpty()) {
            throw conflict("Remove all additional IAM Identity Center Regions before deleting the instance.");
        }
        List<String> applicationArns = applications.scan(key -> true).stream()
                .filter(application -> instanceArn.equals(application.instanceArn()))
                .map(SsoApplication::applicationArn)
                .toList();
        for (String applicationArn : applicationArns) {
            deleteApplication(applicationArn);
        }
        Set<String> instancePermissionSets = permissionSets.keys().stream()
                .filter(permissionSetArn -> instanceArn.equals(instanceArnForPermissionSet(permissionSetArn)))
                .collect(java.util.stream.Collectors.toSet());
        for (String permissionSetArn : instancePermissionSets) {
            permissionSets.delete(permissionSetArn);
            resourceTagOverrides.delete(permissionSetArn);
        }
        deleteByPermissionSet(assignments, Assignment::permissionSetArn, instancePermissionSets);
        deleteByPermissionSet(assignmentOperations, AssignmentOperation::permissionSetArn, instancePermissionSets);
        deleteByPermissionSet(assignmentDeletionOperations, AssignmentDeletionOperation::permissionSetArn, instancePermissionSets);
        deleteByPermissionSet(permissionSetProvisionings, PermissionSetProvisioning::permissionSetArn, instancePermissionSets);
        deleteByPermissionSet(permissionSetProvisioningOperations, PermissionSetProvisioningOperation::permissionSetArn,
                instancePermissionSets);
        accessControlAttributeConfigurations.delete(instanceArn);
        for (String key : new java.util.ArrayList<>(trustedTokenIssuers.keys())) {
            TrustedTokenIssuer issuer = trustedTokenIssuers.get(key).orElse(null);
            if (issuer != null && instanceArn.equals(issuer.instanceArn())) {
                trustedTokenIssuers.delete(key);
                trustedTokenIssuerUpdateOverrides.delete(key);
                resourceTagOverrides.delete(key);
            }
        }
        for (String key : new java.util.ArrayList<>(trustedTokenIssuerClientTokens.keys())) {
            String issuerArn = trustedTokenIssuerClientTokens.get(key).orElse(null);
            if (issuerArn != null && trustedTokenIssuers.get(issuerArn).isEmpty()) {
                trustedTokenIssuerClientTokens.delete(key);
            }
        }
        for (String key : new java.util.ArrayList<>(instanceClientTokens.keys())) {
            if (instanceArn.equals(instanceClientTokens.get(key).orElse(null))) {
                instanceClientTokens.delete(key);
            }
        }
        identityStoreService.deleteIdentityStore(instance.identityStoreId());
        instances.delete(callerAccountId);
        instanceUpdateStates.delete(instanceArn);
        resourceTagOverrides.delete(instanceArn);
        instanceDeletionMarkers.put(callerAccountId, true);
    }

    public synchronized void deleteApplication(String applicationArn) {
        getApplication(applicationArn);
        applications.delete(applicationArn);
        applicationUpdateOverrides.delete(applicationArn);
        resourceTagOverrides.delete(applicationArn);
        for (String key : new java.util.ArrayList<>(applicationAssignments.keys())) {
            ApplicationAssignment assignment = applicationAssignments.get(key).orElse(null);
            if (assignment != null && applicationArn.equals(assignment.applicationArn())) {
                applicationAssignments.delete(key);
            }
        }
        for (String key : new java.util.ArrayList<>(applicationClientTokens.keys())) {
            if (applicationArn.equals(applicationClientTokens.get(key).orElse(null))) {
                applicationClientTokens.delete(key);
            }
        }
        for (String key : new java.util.ArrayList<>(applicationAccessScopes.keys())) {
            ApplicationAccessScope scope = applicationAccessScopes.get(key).orElse(null);
            if (scope != null && applicationArn.equals(scope.applicationArn())) {
                applicationAccessScopes.delete(key);
            }
        }
        applicationAssignmentConfigurations.delete(applicationArn);
        for (String key : new java.util.ArrayList<>(applicationAuthenticationMethods.keys())) {
            ApplicationAuthenticationMethod method = applicationAuthenticationMethods.get(key).orElse(null);
            if (method != null && applicationArn.equals(method.applicationArn())) {
                applicationAuthenticationMethods.delete(key);
            }
        }
        for (String key : new java.util.ArrayList<>(applicationGrants.keys())) {
            ApplicationGrant grant = applicationGrants.get(key).orElse(null);
            if (grant != null && applicationArn.equals(grant.applicationArn())) {
                applicationGrants.delete(key);
            }
        }
        applicationSessionConfigurations.delete(applicationArn);
    }

    public boolean getApplicationAssignmentConfiguration(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        return applicationAssignmentConfigurations.get(applicationArn).orElse(true);
    }

    public synchronized void putApplicationAssignmentConfiguration(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        JsonNode assignmentRequired = request == null ? null : request.get("AssignmentRequired");
        if (assignmentRequired == null || assignmentRequired.isNull() || !assignmentRequired.isBoolean()) {
            throw validation("AssignmentRequired must be a boolean.");
        }
        applicationAssignmentConfigurations.put(applicationArn, assignmentRequired.booleanValue());
    }

    public synchronized ApplicationAccessScope putApplicationAccessScope(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String scope = required(request, "Scope");
        if (!APPLICATION_ACCESS_SCOPE.matcher(scope).matches()) {
            throw validation("Scope is invalid.");
        }
        List<String> authorizedTargets = new ArrayList<>();
        JsonNode targets = request == null ? null : request.get("AuthorizedTargets");
        if (targets != null && !targets.isNull()) {
            if (!targets.isArray() || targets.size() < 1 || targets.size() > 10) {
                throw validation("AuthorizedTargets must contain between 1 and 10 ARNs.");
            }
            for (JsonNode target : targets) {
                if (!target.isTextual() || target.textValue().length() > 100
                        || !APPLICATION_ACCESS_TARGET.matcher(target.textValue()).matches()) {
                    throw validation("AuthorizedTargets contains an invalid ARN.");
                }
                authorizedTargets.add(target.textValue());
            }
        }
        ApplicationAccessScope accessScope = new ApplicationAccessScope(applicationArn, scope, authorizedTargets);
        applicationAccessScopes.put(applicationAccessScopeKey(applicationArn, scope), accessScope);
        return accessScope;
    }

    public ApplicationAccessScope getApplicationAccessScope(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String scope = required(request, "Scope");
        if (!APPLICATION_ACCESS_SCOPE.matcher(scope).matches()) {
            throw validation("Scope is invalid.");
        }
        return applicationAccessScopes.get(applicationAccessScopeKey(applicationArn, scope))
                .orElseThrow(() -> notFound("Application access scope not found: " + scope));
    }

    public SsoApplication applicationForOidc(String applicationArn) {
        return getApplication(applicationArn);
    }

    public ApplicationGrant applicationGrantForOidc(String applicationArn, String grantType) {
        validateApplicationArn(applicationArn);
        getApplication(applicationArn);
        return applicationGrants.get(applicationGrantKey(applicationArn, grantType))
                .orElseThrow(() -> notFound("Application grant not found: " + grantType));
    }

    public List<ApplicationAccessScope> applicationAccessScopesForOidc(String applicationArn) {
        validateApplicationArn(applicationArn);
        getApplication(applicationArn);
        return applicationAccessScopes.scan(key -> true).stream()
                .filter(scope -> applicationArn.equals(scope.applicationArn()))
                .sorted(Comparator.comparing(ApplicationAccessScope::scope))
                .toList();
    }

    public boolean iamActorPolicyAllows(String applicationArn, String callerAccountId) {
        validateApplicationArn(applicationArn);
        getApplication(applicationArn);
        ApplicationAuthenticationMethod method = applicationAuthenticationMethods.get(
                        applicationAuthenticationMethodKey(applicationArn, "IAM"))
                .orElse(null);
        if (method == null || method.authenticationMethod() == null) {
            return false;
        }
        JsonNode policy = method.authenticationMethod().path("Iam").path("ActorPolicy");
        if (!policy.isObject() || !"2012-10-17".equals(policy.path("Version").asText())) {
            return false;
        }
        JsonNode statements = policy.get("Statement");
        if (statements == null) {
            return false;
        }
        List<JsonNode> statementList = new ArrayList<>();
        if (statements.isArray()) {
            statements.forEach(statementList::add);
        } else if (statements.isObject()) {
            statementList.add(statements);
        } else {
            return false;
        }
        boolean allowed = false;
        for (JsonNode statement : statementList) {
            if (!statement.isObject() || !policyActionMatches(statement.get("Action"))
                    || !policyResourceMatches(statement.get("Resource"))
                    || !policyPrincipalMatches(statement.get("Principal"), callerAccountId)) {
                continue;
            }
            if ("Deny".equals(statement.path("Effect").asText())) {
                return false;
            }
            if ("Allow".equals(statement.path("Effect").asText())) {
                allowed = true;
            }
        }
        return allowed;
    }

    private static boolean policyActionMatches(JsonNode action) {
        return policyStringOrArrayMatches(action, value -> "*".equals(value)
                || "sso-oauth:*".equalsIgnoreCase(value)
                || "sso-oauth:CreateTokenWithIAM".equalsIgnoreCase(value));
    }

    private static boolean policyResourceMatches(JsonNode resource) {
        return policyStringOrArrayMatches(resource, "*"::equals);
    }

    private static boolean policyPrincipalMatches(JsonNode principal, String accountId) {
        if (principal == null || accountId == null) {
            return false;
        }
        if (principal.isTextual()) {
            return "*".equals(principal.textValue());
        }
        if (!principal.isObject()) {
            return false;
        }
        JsonNode aws = principal.get("AWS");
        Pattern rootArn = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:iam::" + accountId + ":root");
        return policyStringOrArrayMatches(aws, value -> "*".equals(value)
                || accountId.equals(value) || rootArn.matcher(value).matches());
    }

    private static boolean policyStringOrArrayMatches(JsonNode node, java.util.function.Predicate<String> predicate) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return predicate.test(node.textValue());
        }
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.isTextual() && predicate.test(value.textValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    public PaginatedResult<ApplicationAccessScope> listApplicationAccessScopes(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        List<ApplicationAccessScope> matching = applicationAccessScopes.scan(key -> true).stream()
                .filter(accessScope -> applicationArn.equals(accessScope.applicationArn()))
                .sorted(Comparator.comparing(ApplicationAccessScope::scope))
                .toList();
        return Pagination.paginate(matching, ApplicationAccessScope::scope,
                optionalMaxResults(request), text(request, "NextToken"), 10, 10, "ValidationException");
    }

    public synchronized void deleteApplicationAccessScope(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String scope = required(request, "Scope");
        if (!APPLICATION_ACCESS_SCOPE.matcher(scope).matches()) {
            throw validation("Scope is invalid.");
        }
        String key = applicationAccessScopeKey(applicationArn, scope);
        if (applicationAccessScopes.get(key).isEmpty()) {
            throw notFound("Application access scope not found: " + scope);
        }
        applicationAccessScopes.delete(key);
    }

    public ApplicationAssignment describeApplicationAssignment(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String principalId = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }
        String key = applicationAssignmentKey(applicationArn, principalId, principalType);
        return applicationAssignments.get(key)
                .orElseThrow(() -> notFound("Application assignment not found for the specified principal."));
    }

    public String describeApplicationProvider(JsonNode request, String region) {
        String applicationProviderArn = required(request, "ApplicationProviderArn");
        if (applicationProviderArn.length() > 1224 || !APPLICATION_PROVIDER_ARN.matcher(applicationProviderArn).matches()) {
            throw validation("ApplicationProviderArn is invalid.");
        }
        if (!customApplicationProviderArn(region).equals(applicationProviderArn)) {
            throw notFound("Application provider not found: " + applicationProviderArn);
        }
        return applicationProviderArn;
    }

    public PaginatedResult<String> listApplicationProviders(JsonNode request, String region) {
        Integer requested = optionalMaxResults(request);
        if (requested != null && requested > 100) {
            throw validation("MaxResults must be between 1 and 100.");
        }
        return Pagination.paginate(java.util.List.of(customApplicationProviderArn(region)), value -> value,
                requested, text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public PaginatedResult<ApplicationAssignment> listApplicationAssignments(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        List<ApplicationAssignment> matching = applicationAssignments.scan(key -> true).stream()
                .filter(assignment -> applicationArn.equals(assignment.applicationArn()))
                .sorted(Comparator.comparing(ApplicationAssignment::principalType)
                        .thenComparing(ApplicationAssignment::principalId))
                .toList();
        return Pagination.paginate(matching,
                assignment -> assignment.principalType() + ":" + assignment.principalId(),
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public PaginatedResult<ApplicationAssignment> listApplicationAssignmentsForPrincipal(JsonNode request,
                                                                                           String callerAccountId) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        validateAccountId(callerAccountId);
        if (instance.accountInstance() && !callerAccountId.equals(instance.ownerAccountId())) {
            throw accessDenied("The account instance belongs to a different account.");
        }
        String principalId = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }

        String applicationFilter = null;
        JsonNode filter = request == null ? null : request.get("Filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject() || filter.size() != 1 || !filter.has("ApplicationArn")) {
                throw validation("Filter must contain exactly one ApplicationArn.");
            }
            applicationFilter = validateApplicationArn(required(filter, "ApplicationArn"));
            SsoApplication filteredApplication = getApplication(applicationFilter);
            if (!instanceArn.equals(filteredApplication.instanceArn())) {
                throw notFound("Application not found in the specified IAM Identity Center instance.");
            }
        }
        if (!instance.accountInstance() && !callerAccountId.equals(instance.ownerAccountId()) && applicationFilter == null) {
            throw accessDenied("Filter.ApplicationArn is required from a member account against an organization instance.");
        }

        Set<String> effectiveGroupIds = Set.of();
        if ("USER".equals(principalType)) {
            effectiveGroupIds = identityStoreService.groupIdsForUser(instance.identityStoreId(), principalId);
        }
        Set<String> finalEffectiveGroupIds = effectiveGroupIds;
        String finalApplicationFilter = applicationFilter;
        List<ApplicationAssignment> effective = applicationAssignments.scan(key -> true).stream()
                .filter(assignment -> {
                    SsoApplication application = applications.get(assignment.applicationArn()).orElse(null);
                    return application != null && instanceArn.equals(application.instanceArn());
                })
                .filter(assignment -> finalApplicationFilter == null
                        || finalApplicationFilter.equals(assignment.applicationArn()))
                .filter(assignment -> (principalId.equals(assignment.principalId())
                        && principalType.equals(assignment.principalType()))
                        || ("USER".equals(principalType) && "GROUP".equals(assignment.principalType())
                        && finalEffectiveGroupIds.contains(assignment.principalId())))
                .map(assignment -> new ApplicationAssignment(assignment.applicationArn(), principalId, principalType))
                .distinct()
                .sorted(Comparator.comparing(ApplicationAssignment::applicationArn))
                .toList();
        return Pagination.paginate(effective, ApplicationAssignment::applicationArn,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public synchronized void deleteApplicationAssignment(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String principalId = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }
        String key = applicationAssignmentKey(applicationArn, principalId, principalType);
        if (applicationAssignments.get(key).isEmpty()) {
            throw notFound("Application assignment not found for the specified principal.");
        }
        applicationAssignments.delete(key);
    }

    public ApplicationAuthenticationMethod getApplicationAuthenticationMethod(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String authenticationMethodType = required(request, "AuthenticationMethodType");
        if (!"IAM".equals(authenticationMethodType)) {
            throw validation("AuthenticationMethodType must be IAM.");
        }
        return applicationAuthenticationMethods.get(
                        applicationAuthenticationMethodKey(applicationArn, authenticationMethodType))
                .orElseThrow(() -> notFound(
                        "Application authentication method not found: " + authenticationMethodType));
    }

    public PaginatedResult<ApplicationAuthenticationMethod> listApplicationAuthenticationMethods(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        List<ApplicationAuthenticationMethod> matching = applicationAuthenticationMethods.scan(key -> true).stream()
                .filter(method -> applicationArn.equals(method.applicationArn()))
                .sorted(Comparator.comparing(ApplicationAuthenticationMethod::authenticationMethodType))
                .toList();
        return Pagination.paginate(matching, ApplicationAuthenticationMethod::authenticationMethodType,
                null, text(request, "NextToken"), 100, 100, "ValidationException");
    }

    public synchronized void putApplicationAuthenticationMethod(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String authenticationMethodType = required(request, "AuthenticationMethodType");
        if (!"IAM".equals(authenticationMethodType)) {
            throw validation("AuthenticationMethodType must be IAM.");
        }
        JsonNode authenticationMethod = request == null ? null : request.get("AuthenticationMethod");
        if (authenticationMethod == null || !authenticationMethod.isObject()
                || authenticationMethod.size() != 1 || !authenticationMethod.has("Iam")) {
            throw validation("AuthenticationMethod must contain exactly one Iam member.");
        }
        JsonNode iam = authenticationMethod.get("Iam");
        if (iam == null || !iam.isObject() || !iam.has("ActorPolicy") || iam.get("ActorPolicy").isNull()) {
            throw validation("AuthenticationMethod.Iam.ActorPolicy is required.");
        }
        String key = applicationAuthenticationMethodKey(applicationArn, authenticationMethodType);
        applicationAuthenticationMethods.put(key, new ApplicationAuthenticationMethod(
                applicationArn, authenticationMethodType, authenticationMethod.deepCopy()));
    }

    public synchronized void deleteApplicationAuthenticationMethod(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String authenticationMethodType = required(request, "AuthenticationMethodType");
        if (!"IAM".equals(authenticationMethodType)) {
            throw validation("AuthenticationMethodType must be IAM.");
        }
        String key = applicationAuthenticationMethodKey(applicationArn, authenticationMethodType);
        if (applicationAuthenticationMethods.get(key).isEmpty()) {
            throw notFound("Application authentication method not found: " + authenticationMethodType);
        }
        applicationAuthenticationMethods.delete(key);
    }

    public String getApplicationSessionConfiguration(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        return applicationSessionConfigurations.get(applicationArn).orElse("DISABLED");
    }

    public synchronized void putApplicationSessionConfiguration(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        JsonNode status = request == null ? null : request.get("UserBackgroundSessionApplicationStatus");
        if (status == null || status.isNull()) {
            return;
        }
        if (!status.isTextual() || !("ENABLED".equals(status.textValue()) || "DISABLED".equals(status.textValue()))) {
            throw validation("UserBackgroundSessionApplicationStatus must be ENABLED or DISABLED.");
        }
        applicationSessionConfigurations.put(applicationArn, status.textValue());
    }

    public ApplicationGrant getApplicationGrant(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String grantType = required(request, "GrantType");
        if (!APPLICATION_GRANT_TYPES.contains(grantType)) {
            throw validation("GrantType is invalid.");
        }
        return applicationGrants.get(applicationGrantKey(applicationArn, grantType))
                .orElseThrow(() -> notFound("Application grant not found: " + grantType));
    }

    public PaginatedResult<ApplicationGrant> listApplicationGrants(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        List<ApplicationGrant> matching = applicationGrants.scan(key -> true).stream()
                .filter(grant -> applicationArn.equals(grant.applicationArn()))
                .sorted(Comparator.comparing(ApplicationGrant::grantType))
                .toList();
        return Pagination.paginate(matching, ApplicationGrant::grantType,
                null, text(request, "NextToken"), 100, 100, "ValidationException");
    }

    public synchronized void putApplicationGrant(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String grantType = required(request, "GrantType");
        if (!APPLICATION_GRANT_TYPES.contains(grantType)) {
            throw validation("GrantType is invalid.");
        }
        JsonNode grant = request == null ? null : request.get("Grant");
        if (grant == null || !grant.isObject() || grant.size() != 1) {
            throw validation("Grant must contain exactly one grant configuration.");
        }
        String expectedMember = switch (grantType) {
            case "authorization_code" -> "AuthorizationCode";
            case "refresh_token" -> "RefreshToken";
            case "urn:ietf:params:oauth:grant-type:jwt-bearer" -> "JwtBearer";
            case "urn:ietf:params:oauth:grant-type:token-exchange" -> "TokenExchange";
            default -> throw validation("GrantType is invalid.");
        };
        if (!grant.has(expectedMember) || !grant.get(expectedMember).isObject()) {
            throw validation("Grant configuration must match GrantType.");
        }
        validateApplicationGrant(expectedMember, grant.get(expectedMember));
        applicationGrants.put(applicationGrantKey(applicationArn, grantType),
                new ApplicationGrant(applicationArn, grantType, grant.deepCopy()));
    }

    private static void validateApplicationGrant(String member, JsonNode configuration) {
        if ("AuthorizationCode".equals(member)) {
            JsonNode redirectUris = configuration.get("RedirectUris");
            if (redirectUris == null || !redirectUris.isArray() || redirectUris.size() < 1 || redirectUris.size() > 10) {
                throw validation("AuthorizationCode.RedirectUris must contain between 1 and 10 values.");
            }
            for (JsonNode uri : redirectUris) {
                if (!uri.isTextual()) {
                    throw validation("AuthorizationCode.RedirectUris values must be strings.");
                }
            }
            return;
        }
        if ("JwtBearer".equals(member)) {
            JsonNode issuers = configuration.get("AuthorizedTokenIssuers");
            if (issuers == null || !issuers.isArray() || issuers.size() < 1 || issuers.size() > 10) {
                throw validation("JwtBearer.AuthorizedTokenIssuers must contain between 1 and 10 values.");
            }
            for (JsonNode issuer : issuers) {
                if (!issuer.isObject()) {
                    throw validation("AuthorizedTokenIssuers values must be objects.");
                }
                JsonNode issuerArn = issuer.get("TrustedTokenIssuerArn");
                if (issuerArn != null && !issuerArn.isNull()) {
                    if (!issuerArn.isTextual()) {
                        throw validation("TrustedTokenIssuerArn must be a string.");
                    }
                    validateTrustedTokenIssuerArn(issuerArn.textValue());
                }
                JsonNode audiences = issuer.get("AuthorizedAudiences");
                if (audiences != null && !audiences.isNull()) {
                    if (!audiences.isArray() || audiences.size() < 1 || audiences.size() > 10) {
                        throw validation("AuthorizedAudiences must contain between 1 and 10 values.");
                    }
                    for (JsonNode audience : audiences) {
                        if (!audience.isTextual() || audience.textValue().isEmpty() || audience.textValue().length() > 512) {
                            throw validation("AuthorizedAudiences values must be strings between 1 and 512 characters.");
                        }
                    }
                }
            }
        }
    }

    public synchronized void deleteApplicationGrant(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String grantType = required(request, "GrantType");
        if (!APPLICATION_GRANT_TYPES.contains(grantType)) {
            throw validation("GrantType is invalid.");
        }
        String key = applicationGrantKey(applicationArn, grantType);
        if (applicationGrants.get(key).isEmpty()) {
            throw notFound("Application grant not found: " + grantType);
        }
        applicationGrants.delete(key);
    }

    public synchronized ApplicationAssignment createApplicationAssignment(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        getApplication(applicationArn);
        String principalId = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }
        String key = applicationAssignmentKey(applicationArn, principalId, principalType);
        if (applicationAssignments.get(key).isPresent()) {
            throw conflict("The application assignment already exists.");
        }
        if ("GROUP".equals(principalType)
                && applicationAssignments.scan(ignored -> true).stream()
                        .filter(a -> applicationArn.equals(a.applicationArn()) && "GROUP".equals(a.principalType()))
                        .count() >= APPLICATION_GROUP_ASSIGNMENT_QUOTA) {
            throw quota("An application can have at most 100 directly assigned groups.");
        }
        ApplicationAssignment assignment = new ApplicationAssignment(applicationArn, principalId, principalType);
        applicationAssignments.put(key, assignment);
        return assignment;
    }

    public synchronized SsoApplication createApplication(JsonNode request, String callerAccountId, String region) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        validateAccountId(callerAccountId);
        validateRegionName(region);
        String providerArn = required(request, "ApplicationProviderArn");
        if (providerArn.length() > 1224 || !APPLICATION_PROVIDER_ARN.matcher(providerArn).matches()) {
            throw validation("ApplicationProviderArn is invalid.");
        }
        String customProviderArn = globalArn("sso", region, "aws", "applicationProvider/custom");
        if (!customProviderArn.equals(providerArn)) {
            throw notFound("Application provider not found: " + providerArn);
        }
        String name = required(request, "Name");
        if (name.length() > 100) {
            throw validation("Name must be between 1 and 100 characters.");
        }
        String description = optionalString(request, "Description", 1, 128);
        String status = text(request, "Status");
        if (status == null) {
            status = "ENABLED";
        } else if (!Set.of("ENABLED", "DISABLED").contains(status)) {
            throw validation("Status must be ENABLED or DISABLED.");
        }
        ApplicationPortalOptions portalOptions = parsePortalOptions(request.get("PortalOptions"));
        Map<String, String> tags = parseTags(request.get("Tags"));
        String clientToken = text(request, "ClientToken");
        if (clientToken != null && (clientToken.length() > 64 || !CLIENT_TOKEN.matcher(clientToken).matches())) {
            throw validation("ClientToken must be between 1 and 64 visible ASCII characters.");
        }

        String tokenKey = clientToken == null ? null : callerAccountId + "::" + region + "::" + clientToken;
        if (tokenKey != null) {
            var existingArn = applicationClientTokens.get(tokenKey);
            if (existingArn.isPresent()) {
                SsoApplication existing = applications.get(existingArn.get()).orElse(null);
                if (existing != null && applicationMatches(existing, providerArn, description, name, portalOptions, status, tags)) {
                    return existing;
                }
                throw new AwsException("IdempotentParameterMismatch", "ClientToken was reused with different request parameters.", 400);
            }
        }
        if (applications.scan(a -> true).stream().filter(a -> callerAccountId.equals(a.applicationAccount())).count() >= APPLICATION_QUOTA) {
            throw quota("The IAM Identity Center application quota has been exceeded.");
        }

        String instanceId = instanceArn.substring(instanceArn.lastIndexOf('/') + 1);
        String ownerAccountId = instance.ownerAccountId() == null ? callerAccountId : instance.ownerAccountId();
        String applicationArn = globalArn("sso", region, ownerAccountId,
                "application/" + instanceId + "/apl-" + shortId());
        String identityStoreArn = globalArn("identitystore", region, ownerAccountId,
                "identitystore/" + instance.identityStoreId());
        SsoApplication application = new SsoApplication(ownerAccountId, applicationArn, providerArn,
                System.currentTimeMillis(), region, description, identityStoreArn, instanceArn, name,
                portalOptions, status, tags);
        applications.put(applicationArn, application);
        if (tokenKey != null) {
            applicationClientTokens.put(tokenKey, applicationArn);
        }
        return application;
    }

    public synchronized SsoApplication updateApplication(JsonNode request) {
        String applicationArn = validateApplicationArn(required(request, "ApplicationArn"));
        SsoApplication current = getApplication(applicationArn);
        String name = request != null && request.has("Name")
                ? optionalString(request, "Name", 1, 100) : current.name();
        String description = request != null && request.has("Description")
                ? optionalString(request, "Description", 1, 128) : current.description();
        String status = current.status();
        if (request != null && request.has("Status") && !request.get("Status").isNull()) {
            status = text(request, "Status");
            if (!Set.of("ENABLED", "DISABLED").contains(status)) {
                throw validation("Status must be ENABLED or DISABLED.");
            }
        }
        ApplicationPortalOptions portalOptions = current.portalOptions();
        if (request != null && request.has("PortalOptions") && !request.get("PortalOptions").isNull()) {
            portalOptions = parseUpdatePortalOptions(request.get("PortalOptions"), current.portalOptions());
        }
        SsoApplication updated = new SsoApplication(
                current.applicationAccount(), current.applicationArn(), current.applicationProviderArn(),
                current.createdDateEpochMillis(), current.createdFrom(), description, current.identityStoreArn(),
                current.instanceArn(), name, portalOptions, status, current.tags());
        applicationUpdateOverrides.put(applicationArn, updated);
        return updated;
    }

    public RegionMetadata describeRegion(JsonNode request) {
        SsoInstance instance = requireInstance(required(request, "InstanceArn"));
        String regionName = validateRegionName(required(request, "RegionName"));
        return listRegionsForInstance(instance).stream()
                .filter(region -> regionName.equals(region.regionName()))
                .findFirst()
                .orElseThrow(() -> notFound("Region is not enabled for this IAM Identity Center instance: " + regionName));
    }

    public PaginatedResult<RegionMetadata> listRegions(JsonNode request) {
        SsoInstance instance = requireInstance(required(request, "InstanceArn"));
        return Pagination.paginate(listRegionsForInstance(instance), RegionMetadata::regionName,
                optionalMaxResults(request), text(request, "NextToken"), 100, 100, "ValidationException");
    }

    public synchronized RegionMetadata addRegion(JsonNode request) {
        requireInstance(required(request, "InstanceArn"));
        String regionName = validateRegionName(required(request, "RegionName"));
        if (PRIMARY_REGION.equals(regionName) || regions.get(regionName).isPresent()) {
            throw conflict("Region is already enabled for this IAM Identity Center instance: " + regionName);
        }
        if (regions.scan(key -> true).size() >= REGION_QUOTA - 1) {
            throw quota("The IAM Identity Center Region quota has been exceeded.");
        }
        String addedDate = java.time.Instant.now().toString();
        RegionMetadata response = new RegionMetadata(regionName, "ADDING", addedDate, false);
        regions.put(regionName, new RegionMetadata(regionName, "ACTIVE", addedDate, false));
        return response;
    }

    public synchronized RegionMetadata removeRegion(JsonNode request, String requestRegion) {
        SsoInstance instance = requireInstance(required(request, "InstanceArn"));
        String regionName = validateRegionName(required(request, "RegionName"));
        if (!instance.primaryRegion().equals(requestRegion)) {
            throw accessDenied("RemoveRegion must be called from the primary IAM Identity Center Region.");
        }
        if (instance.primaryRegion().equals(regionName)) {
            throw conflict("The primary IAM Identity Center Region cannot be removed.");
        }
        RegionMetadata current = regions.get(regionName)
                .orElseThrow(() -> notFound("IAM Identity Center Region not found: " + regionName));
        boolean workflowInProgress = regions.scan(key -> true).stream()
                .anyMatch(region -> "ADDING".equals(region.status()) || "REMOVING".equals(region.status()));
        if (workflowInProgress) {
            throw conflict("Another IAM Identity Center Region add or remove workflow is already in progress.");
        }
        RegionMetadata response = new RegionMetadata(regionName, "REMOVING", current.addedDate(), false);
        regions.delete(regionName);
        return response;
    }

    public PaginatedResult<PermissionSet> listPermissionSets(JsonNode request) {
        requireInstance(required(request, "InstanceArn"));
        return Pagination.paginate(permissionSets.scan(key -> true), PermissionSet::arn,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public List<PermissionSet> listPermissionSets(String instanceArn) {
        requireInstance(instanceArn);
        return permissionSets.scan(key -> true).stream().sorted(Comparator.comparing(PermissionSet::name)).toList();
    }

    public PaginatedResult<Map.Entry<String, String>> listManagedPolicies(JsonNode request) {
        PermissionSet permissionSet = getPermissionSet(
                required(request, "InstanceArn"), required(request, "PermissionSetArn"));
        List<Map.Entry<String, String>> policies = permissionSet.managedPolicies().entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        return Pagination.paginate(policies, Map.Entry::getKey,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public synchronized PermissionSet createPermissionSet(JsonNode request) {
        SsoInstance instance = requireInstance(required(request, "InstanceArn"));
        String name = validateName(required(request, "Name"));
        String description = optionalDescription(request);
        String sessionDuration = validateSession(valueOr(request, "SessionDuration", "PT1H"));
        Map<String, String> tags = parseTags(request.get("Tags"));
        if (permissionSets.scan(key -> true).stream().anyMatch(p -> name.equals(p.name()))) {
            throw conflict("Permission set already exists: " + name);
        }
        if (permissionSets.scan(key -> true).size() >= PERMISSION_SET_QUOTA) {
            throw quota("The IAM Identity Center permission set quota has been exceeded.");
        }
        String instanceId = instance.instanceArn().substring(instance.instanceArn().lastIndexOf('/') + 1);
        String arn = globalArn("sso", instance.primaryRegion(), "",
                "permissionSet/" + instanceId + "/ps-" + shortId());
        PermissionSet permissionSet = new PermissionSet(arn, name, description, sessionDuration,
                new LinkedHashMap<>(), new LinkedHashMap<>(), null, null, tags);
        permissionSets.put(arn, permissionSet);
        return permissionSet;
    }

    public PermissionSet getPermissionSet(String instanceArn, String arn) {
        requireInstance(instanceArn);
        validatePermissionSetArn(arn);
        return permissionSets.get(arn).orElseThrow(() -> notFound("Permission set not found: " + arn));
    }

    public synchronized void deletePermissionSet(String instanceArn, String permissionSetArn) {
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Permission sets are only available from an organization instance.");
        }
        getPermissionSet(instanceArn, permissionSetArn);
        permissionSets.delete(permissionSetArn);
        resourceTagOverrides.delete(permissionSetArn);
        for (String key : new ArrayList<>(assignments.keys())) {
            Assignment assignment = assignments.get(key).orElse(null);
            if (assignment != null && permissionSetArn.equals(assignment.permissionSetArn())) {
                assignments.delete(key);
            }
        }
        for (String key : new ArrayList<>(permissionSetProvisionings.keys())) {
            PermissionSetProvisioning provisioning = permissionSetProvisionings.get(key).orElse(null);
            if (provisioning != null && permissionSetArn.equals(provisioning.permissionSetArn())) {
                permissionSetProvisionings.delete(key);
            }
        }
    }

    public synchronized PermissionSet updatePermissionSet(JsonNode request) {
        PermissionSet current = getPermissionSet(required(request, "InstanceArn"), required(request, "PermissionSetArn"));
        String description = request.has("Description") ? optionalDescription(request) : current.description();
        String session = request.has("SessionDuration")
                ? validateSession(required(request, "SessionDuration")) : current.sessionDuration();
        PermissionSet updated = new PermissionSet(current.arn(), current.name(), description, session,
                new LinkedHashMap<>(current.managedPolicies()),
                new LinkedHashMap<>(current.customerManagedPolicies()), current.inlinePolicy(), current.permissionsBoundary(),
                new LinkedHashMap<>(current.tags()));
        permissionSets.put(updated.arn(), updated);
        markPermissionSetProvisioningStale(updated.arn());
        return updated;
    }

    public synchronized void attachPolicy(String instanceArn, String arn, String policyArn) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        validateManagedPolicyArn(policyArn);
        if (current.managedPolicies().containsKey(policyArn)) {
            throw conflict("The managed policy is already attached to the permission set.");
        }
        if (managedPolicyCount(current) >= MANAGED_POLICY_QUOTA) {
            throw quota("A permission set can have at most 25 AWS managed and customer managed policies.");
        }
        current.managedPolicies().put(policyArn, policyArn.substring(policyArn.lastIndexOf('/') + 1));
        permissionSets.put(arn, current);
        markPermissionSetProvisioningStale(arn);
    }

    public synchronized void attachCustomerManagedPolicyReference(JsonNode request) {
        PermissionSet current = getPermissionSet(required(request, "InstanceArn"), required(request, "PermissionSetArn"));
        JsonNode reference = request.get("CustomerManagedPolicyReference");
        if (reference == null || !reference.isObject()) {
            throw validation("CustomerManagedPolicyReference must be an object.");
        }
        String name = required(reference, "Name");
        if (name.length() > 128 || !CUSTOMER_MANAGED_POLICY_NAME.matcher(name).matches()) {
            throw validation("CustomerManagedPolicyReference.Name is invalid.");
        }
        String path = text(reference, "Path");
        if (path == null) {
            path = "/";
        } else if (path.length() > 512 || !CUSTOMER_MANAGED_POLICY_PATH.matcher(path).matches()) {
            throw validation("CustomerManagedPolicyReference.Path is invalid.");
        }
        String key = customerManagedPolicyKey(name, path);
        if (current.customerManagedPolicies().containsKey(key)) {
            throw conflict("The customer managed policy reference is already attached to the permission set.");
        }
        if (managedPolicyCount(current) >= MANAGED_POLICY_QUOTA) {
            throw quota("A permission set can have at most 25 AWS managed and customer managed policies.");
        }
        current.customerManagedPolicies().put(key, new CustomerManagedPolicyReference(name, path));
        permissionSets.put(current.arn(), current);
        markPermissionSetProvisioningStale(current.arn());
    }

    public synchronized void detachCustomerManagedPolicyReference(JsonNode request) {
        PermissionSet current = getPermissionSet(required(request, "InstanceArn"), required(request, "PermissionSetArn"));
        JsonNode reference = request.get("CustomerManagedPolicyReference");
        if (reference == null || !reference.isObject()) {
            throw validation("CustomerManagedPolicyReference must be an object.");
        }
        String name = required(reference, "Name");
        if (name.length() > 128 || !CUSTOMER_MANAGED_POLICY_NAME.matcher(name).matches()) {
            throw validation("CustomerManagedPolicyReference.Name is invalid.");
        }
        String path = text(reference, "Path");
        if (path == null) {
            path = "/";
        } else if (path.length() > 512 || !CUSTOMER_MANAGED_POLICY_PATH.matcher(path).matches()) {
            throw validation("CustomerManagedPolicyReference.Path is invalid.");
        }
        String key = customerManagedPolicyKey(name, path);
        if (current.customerManagedPolicies().remove(key) == null) {
            throw notFound("Customer managed policy reference is not attached to the permission set.");
        }
        permissionSets.put(current.arn(), current);
        markPermissionSetProvisioningStale(current.arn());
    }

    public PaginatedResult<CustomerManagedPolicyReference> listCustomerManagedPolicyReferences(JsonNode request) {
        PermissionSet current = getPermissionSet(required(request, "InstanceArn"), required(request, "PermissionSetArn"));
        List<CustomerManagedPolicyReference> references = current.customerManagedPolicies().values().stream()
                .sorted(Comparator.comparing(CustomerManagedPolicyReference::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CustomerManagedPolicyReference::path))
                .toList();
        return Pagination.paginate(references,
                reference -> reference.name().toLowerCase(java.util.Locale.ROOT) + "\n" + reference.path(),
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public synchronized void detachPolicy(String instanceArn, String arn, String policyArn) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        validateManagedPolicyArn(policyArn);
        if (current.managedPolicies().remove(policyArn) == null) {
            throw conflict("The managed policy is not attached to the permission set.");
        }
        permissionSets.put(arn, current);
        markPermissionSetProvisioningStale(arn);
    }

    public synchronized void putInlinePolicy(String instanceArn, String arn, String policy) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        validateInlinePolicy(policy);
        permissionSets.put(arn, new PermissionSet(current.arn(), current.name(), current.description(),
                current.sessionDuration(), new LinkedHashMap<>(current.managedPolicies()),
                new LinkedHashMap<>(current.customerManagedPolicies()), policy, current.permissionsBoundary(),
                new LinkedHashMap<>(current.tags())));
        markPermissionSetProvisioningStale(arn);
    }

    public synchronized void deleteInlinePolicy(String instanceArn, String arn) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        permissionSets.put(arn, new PermissionSet(current.arn(), current.name(), current.description(),
                current.sessionDuration(), new LinkedHashMap<>(current.managedPolicies()),
                new LinkedHashMap<>(current.customerManagedPolicies()), null, current.permissionsBoundary(),
                new LinkedHashMap<>(current.tags())));
        markPermissionSetProvisioningStale(arn);
    }

    public PermissionsBoundary getPermissionsBoundary(String instanceArn, String permissionSetArn) {
        PermissionSet permissionSet = getPermissionSet(instanceArn, permissionSetArn);
        if (permissionSet.permissionsBoundary() == null) {
            throw notFound("Permissions boundary not found for permission set: " + permissionSetArn);
        }
        return permissionSet.permissionsBoundary();
    }

    public synchronized void deletePermissionsBoundary(String instanceArn, String permissionSetArn) {
        PermissionSet current = getPermissionSet(instanceArn, permissionSetArn);
        if (current.permissionsBoundary() == null) {
            throw notFound("Permissions boundary not found for permission set: " + permissionSetArn);
        }
        permissionSets.put(permissionSetArn, new PermissionSet(current.arn(), current.name(), current.description(),
                current.sessionDuration(), new LinkedHashMap<>(current.managedPolicies()),
                new LinkedHashMap<>(current.customerManagedPolicies()), current.inlinePolicy(), null,
                new LinkedHashMap<>(current.tags())));
        markPermissionSetProvisioningStale(permissionSetArn);
    }

    public synchronized void putPermissionsBoundary(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        String permissionSetArn = required(request, "PermissionSetArn");
        PermissionSet current = getPermissionSet(instanceArn, permissionSetArn);
        JsonNode boundaryNode = request.get("PermissionsBoundary");
        if (boundaryNode == null || !boundaryNode.isObject()) {
            throw validation("PermissionsBoundary must be an object.");
        }
        boolean hasCustomerReference = boundaryNode.hasNonNull("CustomerManagedPolicyReference");
        boolean hasManagedPolicyArn = boundaryNode.hasNonNull("ManagedPolicyArn");
        if (hasCustomerReference == hasManagedPolicyArn) {
            throw validation("PermissionsBoundary must specify exactly one of CustomerManagedPolicyReference or ManagedPolicyArn.");
        }

        PermissionsBoundary boundary;
        if (hasManagedPolicyArn) {
            String managedPolicyArn = required(boundaryNode, "ManagedPolicyArn");
            validateManagedPolicyArn(managedPolicyArn);
            boundary = new PermissionsBoundary(null, managedPolicyArn);
        } else {
            JsonNode referenceNode = boundaryNode.get("CustomerManagedPolicyReference");
            if (!referenceNode.isObject()) {
                throw validation("PermissionsBoundary.CustomerManagedPolicyReference must be an object.");
            }
            String name = required(referenceNode, "Name");
            if (name.length() > 128 || !CUSTOMER_MANAGED_POLICY_NAME.matcher(name).matches()) {
                throw validation("CustomerManagedPolicyReference.Name is invalid.");
            }
            String path = text(referenceNode, "Path");
            if (path == null) {
                path = "/";
            } else if (path.length() > 512 || !CUSTOMER_MANAGED_POLICY_PATH.matcher(path).matches()) {
                throw validation("CustomerManagedPolicyReference.Path is invalid.");
            }
            boundary = new PermissionsBoundary(new CustomerManagedPolicyReference(name, path), null);
        }

        permissionSets.put(permissionSetArn, new PermissionSet(current.arn(), current.name(), current.description(),
                current.sessionDuration(), new LinkedHashMap<>(current.managedPolicies()),
                new LinkedHashMap<>(current.customerManagedPolicies()), current.inlinePolicy(), boundary,
                new LinkedHashMap<>(current.tags())));
        markPermissionSetProvisioningStale(permissionSetArn);
    }

    public PaginatedResult<Assignment> listAssignments(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        requireInstance(instanceArn);
        String accountId = validateAccountId(required(request, "AccountId"));
        String permissionSetArn = required(request, "PermissionSetArn");
        getPermissionSet(instanceArn, permissionSetArn);
        List<Assignment> matching = assignments.scan(key -> true).stream()
                .filter(a -> accountId.equals(a.accountId()) && permissionSetArn.equals(a.permissionSetArn()))
                .toList();
        return Pagination.paginate(matching, Assignment::principalId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public List<Assignment> listAssignments(String instanceArn, String accountId, String permissionSetArn) {
        requireInstance(instanceArn);
        validateAccountId(accountId);
        getPermissionSet(instanceArn, permissionSetArn);
        return assignments.scan(key -> true).stream()
                .filter(a -> accountId.equals(a.accountId()) && permissionSetArn.equals(a.permissionSetArn()))
                .sorted(Comparator.comparing(Assignment::principalId)).toList();
    }

    public List<Assignment> portalAssignmentsForUser(String userId) {
        validatePrincipalId(userId);
        Map<String, Set<String>> groupsByIdentityStore = new java.util.HashMap<>();
        Set<String> invalidIdentityStores = new java.util.HashSet<>();
        return assignments.scan(key -> true).stream()
                .filter(assignment -> {
                    if ("USER".equals(assignment.principalType())) {
                        return userId.equals(assignment.principalId());
                    }
                    if (!"GROUP".equals(assignment.principalType())) {
                        return false;
                    }
                    SsoInstance instance = requireInstance(instanceArnForPermissionSet(assignment.permissionSetArn()));
                    String storeId = instance.identityStoreId();
                    if (invalidIdentityStores.contains(storeId)) {
                        return false;
                    }
                    try {
                        Set<String> groupIds = groupsByIdentityStore.computeIfAbsent(storeId,
                                id -> identityStoreService.groupIdsForUser(id, userId));
                        return groupIds.contains(assignment.principalId());
                    } catch (AwsException e) {
                        invalidIdentityStores.add(storeId);
                        return false;
                    }
                })
                .sorted(Comparator.comparing(Assignment::accountId)
                        .thenComparing(Assignment::permissionSetArn)
                        .thenComparing(Assignment::principalType)
                        .thenComparing(Assignment::principalId))
                .toList();
    }

    public SsoInstance instanceForPermissionSetForPortal(String permissionSetArn) {
        validatePermissionSetArn(permissionSetArn);
        return requireInstance(instanceArnForPermissionSet(permissionSetArn));
    }

    public PermissionSet permissionSetForPortal(String permissionSetArn) {
        SsoInstance instance = instanceForPermissionSetForPortal(permissionSetArn);
        return getPermissionSet(instance.instanceArn(), permissionSetArn);
    }

    public PaginatedResult<Assignment> listAssignmentsForPrincipal(JsonNode request, String callerAccountId) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        validateAccountId(callerAccountId);
        if (instance.accountInstance() || !callerAccountId.equals(instance.ownerAccountId())) {
            throw accessDenied("ListAccountAssignmentsForPrincipal must be called by the owner of an organization instance.");
        }
        String principalId = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }
        String accountFilter = null;
        JsonNode filter = request == null ? null : request.get("Filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject() || filter.size() > 1 || !filter.has("AccountId")) {
                throw validation("Filter may contain only AccountId.");
            }
            accountFilter = validateAccountId(required(filter, "AccountId"));
        }
        String finalAccountFilter = accountFilter;
        List<Assignment> matching = assignments.scan(key -> true).stream()
                .filter(a -> principalId.equals(a.principalId()) && principalType.equals(a.principalType()))
                .filter(a -> finalAccountFilter == null || finalAccountFilter.equals(a.accountId()))
                .toList();
        return Pagination.paginate(matching,
                a -> a.accountId() + "::" + a.permissionSetArn(),
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public synchronized PermissionSetProvisioningOperation provisionPermissionSet(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Permission sets can only be provisioned from an organization instance.");
        }
        String permissionSetArn = required(request, "PermissionSetArn");
        getPermissionSet(instanceArn, permissionSetArn);
        String targetType = required(request, "TargetType");
        String targetId = text(request, "TargetId");
        if ("AWS_ACCOUNT".equals(targetType)) {
            targetId = validateAccountId(required(request, "TargetId"));
            ensurePermissionSetProvisioned(permissionSetArn, targetId);
        } else if ("ALL_PROVISIONED_ACCOUNTS".equals(targetType)) {
            if (targetId != null) {
                throw validation("TargetId must not be provided when TargetType is ALL_PROVISIONED_ACCOUNTS.");
            }
            permissionSetProvisionings.scan(key -> true).stream()
                    .filter(provisioning -> permissionSetArn.equals(provisioning.permissionSetArn()))
                    .map(PermissionSetProvisioning::accountId)
                    .distinct()
                    .forEach(accountId -> ensurePermissionSetProvisioned(permissionSetArn, accountId));
        } else {
            throw validation("TargetType must be AWS_ACCOUNT or ALL_PROVISIONED_ACCOUNTS.");
        }
        String requestId = UUID.randomUUID().toString();
        PermissionSetProvisioningOperation operation = new PermissionSetProvisioningOperation(
                requestId, "SUCCEEDED", System.currentTimeMillis(), targetId, permissionSetArn, null);
        permissionSetProvisioningOperations.put(requestId, operation);
        return operation;
    }

    private void ensurePermissionSetProvisioned(String permissionSetArn, String accountId) {
        String key = permissionSetArn + "::" + accountId;
        permissionSetProvisionings.put(key, new PermissionSetProvisioning(
                permissionSetArn, accountId, "LATEST_PERMISSION_SET_PROVISIONED"));
    }

    public PaginatedResult<String> listPermissionSetsProvisionedToAccount(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Permission sets are only available from an organization instance.");
        }
        String accountId = validateAccountId(required(request, "AccountId"));
        String provisioningStatus = text(request, "ProvisioningStatus");
        if (provisioningStatus != null
                && !Set.of("LATEST_PERMISSION_SET_PROVISIONED", "LATEST_PERMISSION_SET_NOT_PROVISIONED")
                        .contains(provisioningStatus)) {
            throw validation("ProvisioningStatus is invalid.");
        }
        List<String> permissionSetArns = permissionSetProvisionings.scan(key -> true).stream()
                .filter(provisioning -> accountId.equals(provisioning.accountId()))
                .filter(provisioning -> provisioningStatus == null || provisioningStatus.equals(provisioning.status()))
                .map(PermissionSetProvisioning::permissionSetArn)
                .distinct()
                .toList();
        return Pagination.paginate(permissionSetArns, value -> value,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public PaginatedResult<String> listAccountsForProvisionedPermissionSet(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Permission sets are only available from an organization instance.");
        }
        String permissionSetArn = required(request, "PermissionSetArn");
        getPermissionSet(instanceArn, permissionSetArn);
        String provisioningStatus = text(request, "ProvisioningStatus");
        if (provisioningStatus != null
                && !Set.of("LATEST_PERMISSION_SET_PROVISIONED", "LATEST_PERMISSION_SET_NOT_PROVISIONED")
                        .contains(provisioningStatus)) {
            throw validation("ProvisioningStatus is invalid.");
        }
        List<String> accountIds = permissionSetProvisionings.scan(key -> true).stream()
                .filter(provisioning -> permissionSetArn.equals(provisioning.permissionSetArn()))
                .filter(provisioning -> provisioningStatus == null || provisioningStatus.equals(provisioning.status()))
                .map(PermissionSetProvisioning::accountId)
                .distinct()
                .toList();
        return Pagination.paginate(accountIds, value -> value,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    private void markPermissionSetProvisioningStale(String permissionSetArn) {
        for (PermissionSetProvisioning provisioning : permissionSetProvisionings.scan(key -> true)) {
            if (permissionSetArn.equals(provisioning.permissionSetArn())) {
                permissionSetProvisionings.put(permissionSetArn + "::" + provisioning.accountId(),
                        new PermissionSetProvisioning(permissionSetArn, provisioning.accountId(),
                                "LATEST_PERMISSION_SET_NOT_PROVISIONED"));
            }
        }
    }

    public synchronized AssignmentOperation createAssignment(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Account assignments can only be created from an organization instance.");
        }
        String account = validateAccountId(required(request, "TargetId"));
        if (!"AWS_ACCOUNT".equals(required(request, "TargetType"))) {
            throw validation("TargetType must be AWS_ACCOUNT.");
        }
        String permission = required(request, "PermissionSetArn");
        getPermissionSet(instanceArn, permission);
        String principal = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }
        String key = account + "::" + permission + "::" + principal;
        if (assignments.get(key).isPresent()) {
            throw conflict("The account assignment already exists.");
        }
        Assignment assignment = new Assignment(account, permission, principal, principalType);
        assignments.put(key, assignment);
        ensurePermissionSetProvisioned(permission, account);
        String requestId = UUID.randomUUID().toString();
        AssignmentOperation operation = new AssignmentOperation(requestId, "SUCCEEDED", System.currentTimeMillis(),
                account, permission, principal, principalType, null);
        assignmentOperations.put(requestId, operation);
        return operation;
    }

    public synchronized AssignmentDeletionOperation deleteAssignment(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        requireInstance(instanceArn);
        String account = validateAccountId(required(request, "TargetId"));
        if (!"AWS_ACCOUNT".equals(required(request, "TargetType"))) {
            throw validation("TargetType must be AWS_ACCOUNT.");
        }
        String permission = required(request, "PermissionSetArn");
        getPermissionSet(instanceArn, permission);
        String principal = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }
        String key = account + "::" + permission + "::" + principal;
        Assignment existing = assignments.get(key)
                .orElseThrow(() -> notFound("Account assignment not found."));
        if (!principalType.equals(existing.principalType())) {
            throw notFound("Account assignment not found.");
        }
        assignments.delete(key);
        String requestId = UUID.randomUUID().toString();
        AssignmentDeletionOperation operation = new AssignmentDeletionOperation(
                requestId, "SUCCEEDED", System.currentTimeMillis(), account, permission, principal, principalType, null);
        assignmentDeletionOperations.put(requestId, operation);
        return operation;
    }

    public AssignmentDeletionOperation getAssignmentDeletionOperation(String instanceArn, String requestId) {
        requireInstance(instanceArn);
        if (requestId == null || !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw validation("AccountAssignmentDeletionRequestId must be a UUID.");
        }
        return assignmentDeletionOperations.get(requestId)
                .orElseThrow(() -> notFound("Assignment deletion operation not found: " + requestId));
    }

    public PermissionSetProvisioningOperation getPermissionSetProvisioningOperation(String instanceArn, String requestId) {
        requireInstance(instanceArn);
        if (requestId == null || !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw validation("ProvisionPermissionSetRequestId must be a UUID.");
        }
        return permissionSetProvisioningOperations.get(requestId)
                .orElseThrow(() -> notFound("Permission set provisioning operation not found: " + requestId));
    }

    public PaginatedResult<PermissionSetProvisioningOperation> listPermissionSetProvisioningStatus(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Permission sets are only available from an organization instance.");
        }
        String statusFilter = null;
        JsonNode filter = request == null ? null : request.get("Filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject() || filter.size() > 1 || !filter.has("Status")) {
                throw validation("Filter may contain only Status.");
            }
            statusFilter = required(filter, "Status");
            if (!Set.of("IN_PROGRESS", "FAILED", "SUCCEEDED").contains(statusFilter)) {
                throw validation("Filter.Status is invalid.");
            }
        }
        String finalStatusFilter = statusFilter;
        List<PermissionSetProvisioningOperation> operations = permissionSetProvisioningOperations.scan(key -> true).stream()
                .filter(operation -> finalStatusFilter == null || finalStatusFilter.equals(operation.status()))
                .toList();
        return Pagination.paginate(operations, PermissionSetProvisioningOperation::requestId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public AssignmentOperation getAssignmentOperation(String instanceArn, String requestId) {
        requireInstance(instanceArn);
        if (requestId == null || !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw validation("AccountAssignmentCreationRequestId must be a UUID.");
        }
        return assignmentOperations.get(requestId).orElseThrow(() -> notFound("Assignment operation not found: " + requestId));
    }

    public PaginatedResult<AssignmentOperation> listAccountAssignmentCreationStatus(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Account assignments are only available from an organization instance.");
        }
        String statusFilter = null;
        JsonNode filter = request == null ? null : request.get("Filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject() || filter.size() > 1 || (filter.size() == 1 && !filter.has("Status"))) {
                throw validation("Filter may contain only Status.");
            }
            if (filter.has("Status")) {
                statusFilter = required(filter, "Status");
                if (!Set.of("IN_PROGRESS", "FAILED", "SUCCEEDED").contains(statusFilter)) {
                    throw validation("Filter.Status is invalid.");
                }
            }
        }
        String finalStatusFilter = statusFilter;
        List<AssignmentOperation> operations = assignmentOperations.scan(key -> true).stream()
                .filter(operation -> finalStatusFilter == null || finalStatusFilter.equals(operation.status()))
                .toList();
        return Pagination.paginate(operations, AssignmentOperation::requestId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public PaginatedResult<AssignmentDeletionOperation> listAccountAssignmentDeletionStatus(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        if (instance.accountInstance()) {
            throw accessDenied("Account assignments are only available from an organization instance.");
        }
        String statusFilter = null;
        JsonNode filter = request == null ? null : request.get("Filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject() || filter.size() > 1 || (filter.size() == 1 && !filter.has("Status"))) {
                throw validation("Filter may contain only Status.");
            }
            if (filter.has("Status")) {
                statusFilter = required(filter, "Status");
                if (!Set.of("IN_PROGRESS", "FAILED", "SUCCEEDED").contains(statusFilter)) {
                    throw validation("Filter.Status is invalid.");
                }
            }
        }
        String finalStatusFilter = statusFilter;
        List<AssignmentDeletionOperation> operations = assignmentDeletionOperations.scan(key -> true).stream()
                .filter(operation -> finalStatusFilter == null || finalStatusFilter.equals(operation.status()))
                .toList();
        return Pagination.paginate(operations, AssignmentDeletionOperation::requestId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    static String required(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " must be a non-empty string.");
        }
        return value;
    }

    static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static Integer optionalMaxResults(JsonNode request) {
        JsonNode node = request == null ? null : request.get("MaxResults");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw validation("MaxResults must be an integer.");
        }
        return node.intValue();
    }

    private String optionalInstanceArn(JsonNode request) {
        if (request == null || !request.has("InstanceArn") || request.get("InstanceArn").isNull()) {
            return null;
        }
        JsonNode node = request.get("InstanceArn");
        if (!node.isTextual()) {
            throw validation("InstanceArn is invalid.");
        }
        return requireInstance(node.textValue()).instanceArn();
    }

    private static String optionalNextToken(JsonNode request) {
        if (request == null || !request.has("NextToken") || request.get("NextToken").isNull()) {
            return null;
        }
        JsonNode node = request.get("NextToken");
        if (!node.isTextual()) {
            throw validation("NextToken is invalid.");
        }
        String token = node.textValue();
        if (token.length() > 2048 || !NEXT_TOKEN.matcher(token).matches()) {
            throw validation("NextToken is invalid.");
        }
        return token;
    }

    private static String validateName(String name) {
        if (name.length() > 32 || !PERMISSION_SET_NAME.matcher(name).matches()) {
            throw validation("Name must be 1-32 characters and match [\\w+=,.@-]+.");
        }
        return name;
    }

    private static String optionalDescription(JsonNode request) {
        if (!request.has("Description") || request.get("Description").isNull()) {
            return null;
        }
        String description = text(request, "Description");
        if (description == null || description.length() < 1 || description.length() > 700) {
            throw validation("Description must be between 1 and 700 characters.");
        }
        return description;
    }

    private static String validateSession(String value) {
        if (value == null || value.length() > 100) {
            throw validation("SessionDuration is invalid.");
        }
        try {
            Duration duration = Duration.parse(value);
            if (duration.compareTo(Duration.ofHours(1)) < 0 || duration.compareTo(Duration.ofHours(12)) > 0) {
                throw validation("SessionDuration must be between 1 and 12 hours.");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw validation("SessionDuration must use ISO-8601 duration syntax.");
        }
        return value;
    }

    private static void validatePermissionSetArn(String arn) {
        if (arn == null || !PERMISSION_SET_ARN.matcher(arn).matches()) {
            throw validation("PermissionSetArn is invalid.");
        }
    }

    private static void validateManagedPolicyArn(String arn) {
        if (arn == null || arn.length() > 2048 || !MANAGED_POLICY_ARN.matcher(arn).matches()) {
            throw validation("ManagedPolicyArn must identify an AWS managed IAM policy.");
        }
    }

    private static void validateInlinePolicy(String policy) {
        if (policy == null || policy.isEmpty() || policy.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_INLINE_POLICY_BYTES) {
            throw validation("InlinePolicy must be between 1 and 32768 bytes.");
        }
        long nonWhitespace = policy.chars().filter(ch -> !Character.isWhitespace(ch)).count();
        if (nonWhitespace > MAX_INLINE_NON_WHITESPACE) {
            throw quota("InlinePolicy exceeds the non-whitespace quota.");
        }
    }

    public SsoApplication describeApplication(JsonNode request) {
        return getApplication(validateApplicationArn(required(request, "ApplicationArn")));
    }

    public PaginatedResult<SsoApplication> listApplications(JsonNode request, String callerAccountId) {
        String instanceArn = required(request, "InstanceArn");
        SsoInstance instance = requireInstance(instanceArn);
        validateAccountId(callerAccountId);
        if (instance.accountInstance() && !callerAccountId.equals(instance.ownerAccountId())) {
            throw accessDenied("The account instance belongs to a different account.");
        }

        String applicationAccount = null;
        String applicationProvider = null;
        JsonNode filter = request == null ? null : request.get("Filter");
        if (filter != null && !filter.isNull()) {
            if (!filter.isObject() || filter.size() > 2) {
                throw validation("Filter may contain only ApplicationAccount and ApplicationProvider.");
            }
            for (java.util.Iterator<String> fields = filter.fieldNames(); fields.hasNext();) {
                String field = fields.next();
                if (!Set.of("ApplicationAccount", "ApplicationProvider").contains(field)) {
                    throw validation("Filter may contain only ApplicationAccount and ApplicationProvider.");
                }
            }
            if (filter.has("ApplicationAccount")) {
                applicationAccount = validateAccountId(required(filter, "ApplicationAccount"));
            }
            if (filter.has("ApplicationProvider")) {
                applicationProvider = required(filter, "ApplicationProvider");
                if (applicationProvider.length() > 1224 || !APPLICATION_PROVIDER_ARN.matcher(applicationProvider).matches()) {
                    throw validation("Filter.ApplicationProvider is invalid.");
                }
            }
        }
        if (!instance.accountInstance() && !callerAccountId.equals(instance.ownerAccountId())) {
            if (applicationAccount == null || !callerAccountId.equals(applicationAccount)) {
                throw accessDenied("Filter.ApplicationAccount must match the member account when listing organization-instance applications.");
            }
        }

        String finalApplicationAccount = applicationAccount;
        String finalApplicationProvider = applicationProvider;
        List<SsoApplication> matching = applications.scan(key -> true).stream()
                .map(application -> applicationUpdateOverrides.get(application.applicationArn()).orElse(application))
                .filter(application -> instanceArn.equals(application.instanceArn()))
                .filter(application -> finalApplicationAccount == null
                        || finalApplicationAccount.equals(application.applicationAccount()))
                .filter(application -> finalApplicationProvider == null
                        || finalApplicationProvider.equals(application.applicationProviderArn()))
                .sorted(Comparator.comparing(SsoApplication::applicationArn))
                .toList();
        Integer requested = optionalMaxResults(request);
        if (requested != null && requested > 100) {
            throw validation("MaxResults must be between 1 and 100.");
        }
        Integer effectivePageSize = requested == null ? null : Math.min(requested, 50);
        return Pagination.paginate(matching, SsoApplication::applicationArn,
                effectivePageSize, text(request, "NextToken"), 50, 50, "ValidationException");
    }

    SsoApplication getApplication(String applicationArn) {
        validateApplicationArn(applicationArn);
        SsoApplication base = applications.get(applicationArn)
                .orElseThrow(() -> notFound("Application not found: " + applicationArn));
        return applicationUpdateOverrides.get(applicationArn).orElse(base);
    }

    private static String validateApplicationArn(String arn) {
        if (arn == null || arn.length() > 1224 || !APPLICATION_ARN.matcher(arn).matches()) {
            throw validation("ApplicationArn is invalid.");
        }
        return arn;
    }

    private static String applicationAssignmentKey(String applicationArn, String principalId, String principalType) {
        return applicationArn + "::" + principalType + "::" + principalId;
    }

    private static boolean applicationMatches(SsoApplication application, String providerArn, String description,
                                              String name, ApplicationPortalOptions portalOptions, String status,
                                              Map<String, String> tags) {
        return java.util.Objects.equals(application.applicationProviderArn(), providerArn)
                && java.util.Objects.equals(application.description(), description)
                && java.util.Objects.equals(application.name(), name)
                && java.util.Objects.equals(application.portalOptions(), portalOptions)
                && java.util.Objects.equals(application.status(), status)
                && java.util.Objects.equals(application.tags(), tags);
    }

    private static List<AccessControlAttribute> parseAccessControlAttributes(JsonNode request) {
        JsonNode configurationNode = request == null ? null : request.get("InstanceAccessControlAttributeConfiguration");
        if (configurationNode == null || !configurationNode.isObject()) {
            throw validation("InstanceAccessControlAttributeConfiguration must be an object.");
        }
        JsonNode attributesNode = configurationNode.get("AccessControlAttributes");
        if (attributesNode == null || !attributesNode.isArray()) {
            throw validation("AccessControlAttributes must be an array.");
        }
        if (attributesNode.size() > 50) {
            throw validation("AccessControlAttributes can contain at most 50 attributes.");
        }
        List<AccessControlAttribute> attributes = new ArrayList<>();
        for (JsonNode attributeNode : attributesNode) {
            if (!attributeNode.isObject()) {
                throw validation("Each access control attribute must be an object.");
            }
            String key = required(attributeNode, "Key");
            if (key.length() > 128 || !ACCESS_CONTROL_ATTRIBUTE_KEY.matcher(key).matches()) {
                throw validation("Access control attribute Key is invalid.");
            }
            JsonNode valueNode = attributeNode.get("Value");
            if (valueNode == null || !valueNode.isObject()) {
                throw validation("Access control attribute Value must be an object.");
            }
            JsonNode sourceNode = valueNode.get("Source");
            if (sourceNode == null || !sourceNode.isArray() || sourceNode.size() != 1 || !sourceNode.get(0).isTextual()) {
                throw validation("Access control attribute Source must contain exactly one string.");
            }
            String source = sourceNode.get(0).textValue();
            if (source.length() > 256 || !ACCESS_CONTROL_ATTRIBUTE_SOURCE.matcher(source).matches()) {
                throw validation("Access control attribute Source is invalid.");
            }
            attributes.add(new AccessControlAttribute(key, source));
        }
        return attributes;
    }

    private static ApplicationPortalOptions parseUpdatePortalOptions(JsonNode node, ApplicationPortalOptions current) {
        if (!node.isObject()) {
            throw validation("PortalOptions must be an object.");
        }
        if (node.has("Visibility")) {
            throw validation("PortalOptions.Visibility cannot be updated by UpdateApplication.");
        }
        ApplicationSignInOptions signInOptions = current == null ? null : current.signInOptions();
        if (node.has("SignInOptions") && !node.get("SignInOptions").isNull()) {
            JsonNode signIn = node.get("SignInOptions");
            if (!signIn.isObject()) {
                throw validation("PortalOptions.SignInOptions must be an object.");
            }
            String origin = required(signIn, "Origin");
            if (!Set.of("IDENTITY_CENTER", "APPLICATION").contains(origin)) {
                throw validation("SignInOptions.Origin must be IDENTITY_CENTER or APPLICATION.");
            }
            String applicationUrl = text(signIn, "ApplicationUrl");
            if ("APPLICATION".equals(origin) && applicationUrl == null) {
                throw validation("SignInOptions.ApplicationUrl is required when Origin is APPLICATION.");
            }
            if (applicationUrl != null && (applicationUrl.length() > 512 || !APPLICATION_URL.matcher(applicationUrl).matches())) {
                throw validation("SignInOptions.ApplicationUrl is invalid.");
            }
            signInOptions = new ApplicationSignInOptions(origin, applicationUrl);
        }
        return new ApplicationPortalOptions(current == null ? null : current.visibility(), signInOptions);
    }

    private static ApplicationPortalOptions parsePortalOptions(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw validation("PortalOptions must be an object.");
        }
        String visibility = text(node, "Visibility");
        if (visibility != null && !Set.of("ENABLED", "DISABLED").contains(visibility)) {
            throw validation("PortalOptions.Visibility must be ENABLED or DISABLED.");
        }
        JsonNode signIn = node.get("SignInOptions");
        ApplicationSignInOptions signInOptions = null;
        if (signIn != null && !signIn.isNull()) {
            if (!signIn.isObject()) {
                throw validation("PortalOptions.SignInOptions must be an object.");
            }
            String origin = required(signIn, "Origin");
            if (!Set.of("IDENTITY_CENTER", "APPLICATION").contains(origin)) {
                throw validation("SignInOptions.Origin must be IDENTITY_CENTER or APPLICATION.");
            }
            String applicationUrl = text(signIn, "ApplicationUrl");
            if ("APPLICATION".equals(origin) && applicationUrl == null) {
                throw validation("SignInOptions.ApplicationUrl is required when Origin is APPLICATION.");
            }
            if (applicationUrl != null && (applicationUrl.length() > 512 || !APPLICATION_URL.matcher(applicationUrl).matches())) {
                throw validation("SignInOptions.ApplicationUrl is invalid.");
            }
            signInOptions = new ApplicationSignInOptions(origin, applicationUrl);
        }
        return new ApplicationPortalOptions(visibility, signInOptions);
    }

    private static String optionalInstanceName(JsonNode request) {
        if (request == null || !request.has("Name") || request.get("Name").isNull()) {
            return null;
        }
        String name = text(request, "Name");
        if (name == null || name.length() > 255 || !INSTANCE_NAME.matcher(name).matches()) {
            throw validation("Name must be at most 255 characters and match [\\w+=,.@-]+.");
        }
        return name;
    }

    private static String optionalString(JsonNode request, String field, int min, int max) {
        if (request == null || !request.has(field) || request.get(field).isNull()) {
            return null;
        }
        String value = text(request, field);
        if (value == null || value.length() < min || value.length() > max) {
            throw validation(field + " length is invalid.");
        }
        return value;
    }

    private static Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isArray() || node.size() > 75) {
            throw validation("Tags must contain at most 75 entries.");
        }
        for (JsonNode tag : node) {
            if (!tag.isObject()) {
                throw validation("Each tag must be an object.");
            }
            String key = required(tag, "Key");
            JsonNode valueNode = tag.get("Value");
            String value = valueNode != null && valueNode.isTextual() ? valueNode.textValue() : null;
            if (key.length() > 128 || !TAG_VALUE.matcher(key).matches()
                    || value == null || value.length() > 256 || !TAG_VALUE.matcher(value).matches()
                    || key.regionMatches(true, 0, "aws:", 0, 4)) {
                throw validation("Tag key or value is invalid.");
            }
            if (tags.putIfAbsent(key, value) != null) {
                throw validation("Duplicate tag key: " + key);
            }
        }
        return tags;
    }

    private static int managedPolicyCount(PermissionSet permissionSet) {
        return permissionSet.managedPolicies().size() + permissionSet.customerManagedPolicies().size();
    }

    private static String customerManagedPolicyKey(String name, String path) {
        return name.toLowerCase(java.util.Locale.ROOT) + "\n" + path;
    }


    private static <T> void deleteByPermissionSet(StorageBackend<String, T> storage,
                                                  java.util.function.Function<T, String> permissionSetArn,
                                                  Set<String> permissionSetArns) {
        for (String key : new ArrayList<>(storage.keys())) {
            T value = storage.get(key).orElse(null);
            if (value != null && permissionSetArns.contains(permissionSetArn.apply(value))) {
                storage.delete(key);
            }
        }
    }

    private static String instanceArnForPermissionSet(String permissionSetArn) {
        int serviceIndex = permissionSetArn.indexOf(":sso::");
        int instanceStart = permissionSetArn.indexOf("permissionSet/") + "permissionSet/".length();
        int instanceEnd = permissionSetArn.indexOf('/', instanceStart);
        return permissionSetArn.substring(0, serviceIndex) + ":sso:::instance/"
                + permissionSetArn.substring(instanceStart, instanceEnd);
    }

    static String applicationAccessScopeKey(String applicationArn, String scope) {
        return applicationArn + "\n" + scope;
    }

    static String applicationAuthenticationMethodKey(String applicationArn, String authenticationMethodType) {
        return applicationArn + "\n" + authenticationMethodType;
    }

    static String applicationGrantKey(String applicationArn, String grantType) {
        return applicationArn + "\n" + grantType;
    }

    private static String validateRegionName(String value) {
        if (value == null || value.length() > 32 || !REGION_NAME.matcher(value).matches()) {
            throw validation("RegionName must be 1-32 characters and use an AWS Region name format.");
        }
        return value;
    }

    private static String validateAccountId(String value) {
        if (value == null || !value.matches("\\d{12}")) {
            throw validation("AWS account identifiers must contain 12 digits.");
        }
        return value;
    }

    private static String validatePrincipalId(String value) {
        if (value == null || !PRINCIPAL_ID.matcher(value).matches()) {
            throw validation("PrincipalId is invalid.");
        }
        return value;
    }

    private static String valueOr(JsonNode request, String field, String fallback) {
        String value = text(request, field);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static String customApplicationProviderArn(String region) {
        return globalArn("sso", region, "aws", "applicationProvider/custom");
    }

    private static String globalArn(String service, String region, String accountId, String resource) {
        return new AwsArnUtils.Arn(AwsRegions.partitionFor(region), service, "", accountId, resource).toString();
    }

    private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private static AwsException notFound(String message) { return new AwsException("ResourceNotFoundException", message, 400); }
    private static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }
    private static AwsException conflict(String message) { return new AwsException("ConflictException", message, 400); }
    private static AwsException quota(String message) { return new AwsException("ServiceQuotaExceededException", message, 400); }
    private static AwsException accessDenied(String message) { return new AwsException("AccessDeniedException", message, 400); }
    private SsoInstance requireInstance(String arn) {
        if (arn == null || arn.length() < 10 || arn.length() > 1224 || !INSTANCE_ARN_PATTERN.matcher(arn).matches()) {
            throw validation("InstanceArn is invalid.");
        }
        SsoInstance instance = findInstanceByArn(arn);
        if (instance == null) {
            throw notFound("IAM Identity Center instance not found: " + arn);
        }
        return instance;
    }

    private SsoInstance findInstanceByArn(String arn) {
        if (arn == null) {
            return null;
        }
        SsoInstance stored = instances.scan(key -> true).stream()
                .filter(instance -> arn.equals(instance.instanceArn()))
                .findFirst().orElse(null);
        return stored;
    }

    @Override
    public void clear() {
        permissionSets.clear();
        assignments.clear();
        assignmentOperations.clear();
        assignmentDeletionOperations.clear();
        permissionSetProvisionings.clear();
        permissionSetProvisioningOperations.clear();
        regions.clear();
        applications.clear();
        applicationUpdateOverrides.clear();
        applicationClientTokens.clear();
        applicationAssignments.clear();
        applicationAccessScopes.clear();
        applicationAssignmentConfigurations.clear();
        applicationAuthenticationMethods.clear();
        applicationGrants.clear();
        applicationSessionConfigurations.clear();
        resourceTagOverrides.clear();
        instances.clear();
        instanceUpdateStates.clear();
        instanceClientTokens.clear();
        instanceDeletionMarkers.clear();
        accessControlAttributeConfigurations.clear();
        trustedTokenIssuers.clear();
        trustedTokenIssuerUpdateOverrides.clear();
        trustedTokenIssuerClientTokens.clear();
        ensureBootstrapInstance(defaultAccountId, defaultRegion);
    }

}
