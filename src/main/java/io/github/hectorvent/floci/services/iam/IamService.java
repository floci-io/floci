package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SessionAccountLookup;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.AccountPasswordPolicy;
import io.github.hectorvent.floci.services.iam.model.CredentialReport;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.LoginProfile;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import io.github.hectorvent.floci.services.iam.model.OrganizationRootFeatures;
import io.github.hectorvent.floci.services.iam.model.PolicyVersion;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Core IAM business logic — users, groups, roles, policies, access keys, instance profiles.
 * IAM is a global service: resources are not region-scoped and storage keys have no region prefix.
 *
 * <p>Eagerly initialized at startup so AWS-managed policies (and the optional deployer principal)
 * are seeded under the default account before any request runs. Seeding is account-namespaced via
 * the request context, so deferring it to the first request would otherwise bind the seed data to
 * whichever account happened to make that call — a real hazard now that {@code AccountContextFilter}
 * resolves the request account through this service.
 */
@Startup
@ApplicationScoped
public class IamService implements SessionAccountLookup, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(IamService.class);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String TEMPORARY_ACCESS_KEY_PREFIX = "ASIA";
    private static final String DEFAULT_DEPLOYER_USER = "floci-deployer";
    private static final String DEFAULT_DEPLOYER_ACCESS_KEY_ID = "floci";
    private static final String DEFAULT_DEPLOYER_SECRET_ACCESS_KEY = "floci";
    private static final String ACCOUNT_ALIAS_KEY = "account-alias";
    /** As AWS documents it: no leading or trailing dash, and no two dashes in a row. */
    private static final Pattern ACCOUNT_ALIAS_PATTERN =
            Pattern.compile("^[a-z0-9]([a-z0-9]|-(?!-)){1,61}[a-z0-9]$");
    private static final int MAX_OIDC_CLIENT_IDS = 100;
    private static final int MAX_OIDC_THUMBPRINTS = 5;
    private static final int MAX_OIDC_URL_LENGTH = 255;
    private static final String ACCOUNT_PASSWORD_POLICY_KEY = "account-password-policy";
    /** AWS-documented bounds for the account password policy's numeric fields. */
    private static final int MIN_PASSWORD_LENGTH_FLOOR = 6;
    private static final int MIN_PASSWORD_LENGTH_CEILING = 128;
    private static final int MAX_PASSWORD_AGE_FLOOR = 1;
    private static final int MAX_PASSWORD_AGE_CEILING = 1095;
    private static final int PASSWORD_REUSE_PREVENTION_FLOOR = 1;
    private static final int PASSWORD_REUSE_PREVENTION_CEILING = 24;
    /**
     * AWS's documented {@code passwordType} pattern for Create/UpdateLoginProfile: printable
     * ASCII and Latin-1 Supplement (space through code point 0xFF), plus tab/LF/CR, 1-128 chars.
     */
    private static final Pattern LOGIN_PROFILE_PASSWORD_PATTERN =
            Pattern.compile("[\\t\\n\\r\\x20-\\xff]{1,128}");

    /** Guards the read-modify-write in the OIDC provider mutators. */
    private final Object oidcProviderLock = new Object();

    /**
     * Guards the tag read-modify-write on users, roles, policies and instance profiles, so two
     * requests that each fit the per-resource quota cannot together push a resource past it.
     */
    private final Object tagLock = new Object();

    /** CSPRNG for long-term secret access keys; ordinary resource IDs keep using {@link ThreadLocalRandom}. */
    private final SecureRandom secureRandom = new SecureRandom();

    private static final String SERVICE_LINKED_ROLE_PATH = "/aws-service-role/";
    private static final String SERVICE_LINKED_ROLE_NAME_PREFIX = "AWSServiceRoleFor";
    private static final Map<String, String> SERVICE_LINKED_ROLE_NAMES = Map.of(
            "autoscaling.amazonaws.com", "AutoScaling",
            "cloud9.amazonaws.com", "AWSCloud9",
            "ram.amazonaws.com", "ResourceAccessManager"
    );
    private static final String AMAZONAWS_DOMAIN = ".amazonaws.com";
    /** AWSServiceName as AWS constrains it: 1-128 characters of {@code [\w+=,.@-]}. */
    private static final Pattern SERVICE_PRINCIPAL_PATTERN = Pattern.compile("[\\w+=,.@-]{1,128}");
    /** CustomSuffix as AWS constrains it: 1-64 characters of {@code [\w+=,.@-]}. */
    private static final Pattern CUSTOM_SUFFIX_PATTERN = Pattern.compile("[\\w+=,.@-]{1,64}");
    private static final int ROLE_NAME_MAX_LENGTH = 64;
    /** groupNameType / instanceProfileNameType: 1-128 characters of {@code [\w+=,.@-]}. */
    private static final Pattern IAM_RESOURCE_NAME_PATTERN = Pattern.compile("[\\w+=,.@-]{1,128}");
    /** pathType: a bare slash, or a slash-delimited run of {@code !}-{@code ~}. */
    private static final Pattern IAM_PATH_PATTERN = Pattern.compile("(/)|(/[\\x21-\\x7E]+/)");
    private static final int IAM_PATH_MAX_LENGTH = 512;
    private static final int MAX_TAGS_PER_RESOURCE = 50;
    private static final String ROOT_FEATURES_KEY = "org-root-features";
    private static final String CREDENTIAL_REPORT_KEY = "credential-report";
    /** AWS generates a fresh report only if the most recent one is older than this. */
    private static final Duration CREDENTIAL_REPORT_MAX_AGE = Duration.ofHours(4);
    public static final String FEATURE_ROOT_CREDENTIALS = "RootCredentialsManagement";
    public static final String FEATURE_ROOT_SESSIONS = "RootSessions";

    private final StorageBackend<String, IamUser> users;
    private final StorageBackend<String, IamGroup> groups;
    private final StorageBackend<String, IamRole> roles;
    private final StorageBackend<String, IamPolicy> policies;
    private final StorageBackend<String, AccessKey> accessKeys;
    private final StorageBackend<String, InstanceProfile> instanceProfiles;
    private final StorageBackend<String, SessionCredential> sessions;
    /**
     * Holds at most one entry per account under {@link #ACCOUNT_ALIAS_KEY} — an account alias is a
     * single value, and the store is already account-namespaced, so no further keying is needed.
     */
    private final StorageBackend<String, String> accountAliases;
    /**
     * Guards the check-then-write in alias create/delete. Unlike a named resource, where two
     * racing creates carry the same name and either winner is equivalent, racing alias creates
     * carry different values — an unguarded race would report success to both callers while
     * silently keeping only one. A single lock across accounts is enough: alias writes are rare.
     */
    private final Object accountAliasLock = new Object();
    /**
     * Holds at most one entry per account under {@link #ACCOUNT_PASSWORD_POLICY_KEY} — same
     * single-value-per-account shape as {@link #accountAliases}.
     */
    private final StorageBackend<String, AccountPasswordPolicy> passwordPolicies;
    private final StorageBackend<String, LoginProfile> loginProfiles;
    private final StorageBackend<String, OpenIDConnectProvider> oidcProviders;
    /** Deletion is synchronous, so an issued task id is a completed one; the value is its role. */
    private final StorageBackend<String, String> serviceLinkedRoleDeletions;
    private final StorageBackend<String, OrganizationRootFeatures> orgRootFeatures;
    /**
     * Holds at most one entry per account under {@link #CREDENTIAL_REPORT_KEY}: the same
     * single-value-per-account shape as {@link #accountAliases}.
     */
    private final StorageBackend<String, CredentialReport> credentialReports;
    private final RegionResolver regionResolver;
    private final boolean seedDeployerPrincipal;
    private final String seededAccountAlias;
    /** Guards case-insensitive IAM name uniqueness checks and their corresponding writes. */
    private final Object resourceNameLock = new Object();

    /**
     * AWS-managed policies (arn:aws:iam::aws:policy/...), keyed by ARN. These are global —
     * not owned by any account — so they live here rather than in the account-partitioned
     * {@link #policies} store, and {@link #getPolicy} resolves them for any caller.
     */
    private final Map<String, IamPolicy> awsManagedPolicies = buildAwsManagedPolicies();

    @Inject
    public IamService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this(
            storageFactory.create("iam", "iam-users.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-groups.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-roles.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-policies.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-access-keys.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-instance-profiles.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-sessions.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-account-aliases.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-password-policy.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-login-profiles.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-oidc-providers.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-slr-deletions.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-org-root-features.json", new TypeReference<>() {}),
            storageFactory.create("iam", "iam-credential-reports.json", new TypeReference<>() {}),
            regionResolver,
            config.services().iam().seedDeployerPrincipal(),
            config.services().iam().accountAlias().orElse(null)
        );
    }

    IamService(StorageBackend<String, IamUser> users,
               StorageBackend<String, IamGroup> groups,
               StorageBackend<String, IamRole> roles,
               StorageBackend<String, IamPolicy> policies,
               StorageBackend<String, AccessKey> accessKeys,
               StorageBackend<String, InstanceProfile> instanceProfiles,
               StorageBackend<String, SessionCredential> sessions,
               RegionResolver regionResolver) {
        this(users, groups, roles, policies, accessKeys, instanceProfiles, sessions, regionResolver, false);
    }

    IamService(StorageBackend<String, IamUser> users,
               StorageBackend<String, IamGroup> groups,
               StorageBackend<String, IamRole> roles,
               StorageBackend<String, IamPolicy> policies,
               StorageBackend<String, AccessKey> accessKeys,
               StorageBackend<String, InstanceProfile> instanceProfiles,
               StorageBackend<String, SessionCredential> sessions,
               RegionResolver regionResolver,
               boolean seedDeployerPrincipal) {
        this(users, groups, roles, policies, accessKeys, instanceProfiles, sessions,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), regionResolver, seedDeployerPrincipal, null);
    }

    // 8-backend constructor (no org-root-features): kept for existing callers/tests;
    // delegates with an in-memory root-features backend.
    IamService(StorageBackend<String, IamUser> users,
               StorageBackend<String, IamGroup> groups,
               StorageBackend<String, IamRole> roles,
               StorageBackend<String, IamPolicy> policies,
               StorageBackend<String, AccessKey> accessKeys,
               StorageBackend<String, InstanceProfile> instanceProfiles,
               StorageBackend<String, SessionCredential> sessions,
               StorageBackend<String, String> accountAliases,
               StorageBackend<String, AccountPasswordPolicy> passwordPolicies,
               StorageBackend<String, OpenIDConnectProvider> oidcProviders,
               StorageBackend<String, String> serviceLinkedRoleDeletions,
               RegionResolver regionResolver,
               boolean seedDeployerPrincipal,
               String seededAccountAlias) {
        this(users, groups, roles, policies, accessKeys, instanceProfiles, sessions,
                accountAliases, passwordPolicies, new InMemoryStorage<>(), oidcProviders,
                serviceLinkedRoleDeletions, new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, seedDeployerPrincipal, seededAccountAlias);
    }

    // 9-backend constructor (no alias/OIDC/SLR backends): kept for existing callers/tests;
    // delegates with in-memory backends for the omitted stores.
    IamService(StorageBackend<String, IamUser> users,
               StorageBackend<String, IamGroup> groups,
               StorageBackend<String, IamRole> roles,
               StorageBackend<String, IamPolicy> policies,
               StorageBackend<String, AccessKey> accessKeys,
               StorageBackend<String, InstanceProfile> instanceProfiles,
               StorageBackend<String, SessionCredential> sessions,
               StorageBackend<String, AccountPasswordPolicy> passwordPolicies,
               StorageBackend<String, OrganizationRootFeatures> orgRootFeatures,
               RegionResolver regionResolver,
               boolean seedDeployerPrincipal) {
        this(users, groups, roles, policies, accessKeys, instanceProfiles, sessions,
                new InMemoryStorage<>(), passwordPolicies, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), orgRootFeatures,
                new InMemoryStorage<>(), regionResolver, seedDeployerPrincipal, null);
    }

    IamService(StorageBackend<String, IamUser> users,
               StorageBackend<String, IamGroup> groups,
               StorageBackend<String, IamRole> roles,
               StorageBackend<String, IamPolicy> policies,
               StorageBackend<String, AccessKey> accessKeys,
               StorageBackend<String, InstanceProfile> instanceProfiles,
               StorageBackend<String, SessionCredential> sessions,
               StorageBackend<String, String> accountAliases,
               StorageBackend<String, AccountPasswordPolicy> passwordPolicies,
               StorageBackend<String, LoginProfile> loginProfiles,
               StorageBackend<String, OpenIDConnectProvider> oidcProviders,
               StorageBackend<String, String> serviceLinkedRoleDeletions,
               StorageBackend<String, OrganizationRootFeatures> orgRootFeatures,
               StorageBackend<String, CredentialReport> credentialReports,
               RegionResolver regionResolver,
               boolean seedDeployerPrincipal,
               String seededAccountAlias) {
        this.users = users;
        this.groups = groups;
        this.roles = roles;
        this.policies = policies;
        this.accessKeys = accessKeys;
        this.instanceProfiles = instanceProfiles;
        this.sessions = sessions;
        this.accountAliases = accountAliases;
        this.passwordPolicies = passwordPolicies;
        this.loginProfiles = loginProfiles;
        this.oidcProviders = oidcProviders;
        this.serviceLinkedRoleDeletions = serviceLinkedRoleDeletions;
        this.orgRootFeatures = orgRootFeatures;
        this.credentialReports = credentialReports;
        this.regionResolver = regionResolver;
        this.seedDeployerPrincipal = seedDeployerPrincipal;
        this.seededAccountAlias = seededAccountAlias;
    }

    @PostConstruct
    void seedDefaults() {
        seedAwsManagedPolicies();
        if (seedDeployerPrincipal) {
            seedDefaultDeployerPrincipal();
        }
        seedConfiguredAccountAlias();
    }

    private static Map<String, IamPolicy> buildAwsManagedPolicies() {
        Map<String, IamPolicy> catalog = new LinkedHashMap<>();
        for (AwsManagedPolicies.ManagedPolicyDef def : AwsManagedPolicies.POLICIES) {
            String arn = def.arn();
            // The bundled document is the policy's current default version, served under the
            // version id AWS actually reports for it (v3 for AmazonS3ReadOnlyAccess, v1 for
            // AdministratorAccess) so GetPolicy/ListPolicyVersions match a real account.
            // Superseded versions are not bundled, so they resolve to NoSuchEntity.
            Instant now = Instant.now();
            Instant updateDate = def.updateDate() != null ? def.updateDate() : now;
            Instant createDate = def.createDate() != null ? def.createDate() : updateDate;
            PolicyVersion defaultVersion = new PolicyVersion(
                    def.defaultVersionId(), def.document(), true, updateDate);
            catalog.put(arn, new IamPolicy("ANPA" + randomId(16), def.name(), def.path(), arn,
                    def.description(), defaultVersion, createDate, updateDate));
        }
        return catalog;
    }

    /**
     * Makes the AWS-managed policy catalog available.
     *
     * <p>The catalog itself is the single source of truth: {@link #getPolicy} resolves
     * {@code arn:aws:iam::aws:policy/*} from it directly, and {@link #listPolicies} reads
     * the AWS scope from it too. Earlier versions also mirrored every entry into the
     * default-account store, but that copy was never read back — {@code listPolicies}
     * explicitly filters AWS-managed ARNs out of the local scan to avoid listing them
     * twice — and writing the full published catalog to disk on every start costs about a
     * megabyte of persisted state and several seconds of startup for nothing.
     */
    void seedAwsManagedPolicies() {
        LOG.debugv("AWS managed policy catalog available: {0} policies", awsManagedPolicies.size());
    }

    private void seedConfiguredAccountAlias() {
        if (seededAccountAlias == null || seededAccountAlias.isBlank()) {
            return;
        }
        validateAccountAlias(seededAccountAlias);
        Optional<String> stored = accountAliases.get(ACCOUNT_ALIAS_KEY);
        if (stored.isEmpty()) {
            accountAliases.put(ACCOUNT_ALIAS_KEY, seededAccountAlias);
            LOG.infov("Seeded IAM account alias: {0}", seededAccountAlias);
        } else if (!stored.get().equals(seededAccountAlias)) {
            // Under persistent storage the alias outlives the process, so a changed configuration
            // value is ignored on later starts. Say so rather than leaving it to be puzzled out.
            LOG.debugv("Configured IAM account alias {0} ignored; {1} is already stored",
                    seededAccountAlias, stored.get());
        }
    }

    private void seedDefaultDeployerPrincipal() {
        String adminPolicyArn = AwsManagedPolicies.ARN_PREFIX + "/AdministratorAccess";
        IamUser user = users.get(DEFAULT_DEPLOYER_USER)
                .orElseGet(() -> {
                    String userId = "AIDA" + randomId(16);
                    String arn = iamArn("user", "/", DEFAULT_DEPLOYER_USER);
                    IamUser seededUser = new IamUser(userId, DEFAULT_DEPLOYER_USER, "/", arn);
                    users.put(DEFAULT_DEPLOYER_USER, seededUser);
                    LOG.infov("Seeded default IAM deployer user: {0}", DEFAULT_DEPLOYER_USER);
                    return seededUser;
                });
        if (!user.getAttachedPolicyArns().contains(adminPolicyArn)) {
            user.getAttachedPolicyArns().add(adminPolicyArn);
            users.put(DEFAULT_DEPLOYER_USER, user);
        }
        if (accessKeys.get(DEFAULT_DEPLOYER_ACCESS_KEY_ID).isEmpty()) {
            accessKeys.put(DEFAULT_DEPLOYER_ACCESS_KEY_ID, new AccessKey(
                    DEFAULT_DEPLOYER_ACCESS_KEY_ID,
                    DEFAULT_DEPLOYER_SECRET_ACCESS_KEY,
                    DEFAULT_DEPLOYER_USER));
            LOG.infov("Seeded default IAM deployer access key: {0}", DEFAULT_DEPLOYER_ACCESS_KEY_ID);
        }
    }

    // =========================================================================
    // Users
    // =========================================================================

    public IamUser createUser(String userName, String path) {
        synchronized (resourceNameLock) {
            if (containsNameIgnoreCase(users, IamUser::getUserName, userName)) {
                throw new AwsException("EntityAlreadyExists",
                        "User with name " + userName + " already exists.", 409);
            }
            String userId = "AIDA" + randomId(16);
            String normalizedPath = normalizePath(path);
            String arn = iamArn("user", normalizedPath, userName);
            IamUser user = new IamUser(userId, userName, normalizedPath, arn);
            users.put(userName, user);
            LOG.infov("Created IAM user: {0}", userName);
            return user;
        }
    }

    public IamUser getUser(String userName) {
        if (userName == null) {
            throw new AwsException("NoSuchEntity",
                    "The user with name null cannot be found.", 404);
        }
        return users.get(userName)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "The user with name " + userName + " cannot be found.", 404));
    }

    /** The IAM user that owns the given access key id, when it is a real stored key. */
    public Optional<String> findUserNameByAccessKeyId(String accessKeyId) {
        if (accessKeyId == null) {
            return Optional.empty();
        }
        return accessKeys.get(accessKeyId).map(AccessKey::getUserName);
    }

    public void deleteUser(String userName) {
        IamUser user = getUser(userName);
        if (!user.getAttachedPolicyArns().isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must detach all policies first.", 409);
        }
        if (!user.getGroupNames().isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must remove from all groups first.", 409);
        }
        if (loginProfiles.get(userName).isPresent()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must delete login profile first.", 409);
        }
        if (!user.getInlinePolicies().isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must delete policies first.", 409);
        }
        if (!userAccessKeys(userName).isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must delete access keys first.", 409);
        }
        users.delete(userName);
        LOG.infov("Deleted IAM user: {0}", userName);
    }

    public List<IamUser> listUsers(String pathPrefix) {
        String prefix = pathPrefix != null ? pathPrefix : "/";
        return users.scan(k -> true).stream()
                .filter(u -> u.getPath().startsWith(prefix))
                .toList();
    }

    public void updateUser(String userName, String newUserName, String newPath) {
        updateUser(userName, newUserName, newPath, null);
    }

    /**
     * Same as {@link #updateUser(String, String, String)}, but verifies {@code expectedUserId}
     * against the resolved user's immutable ID before applying the update, atomically with the
     * name-based lookup.
     */
    public void updateUser(String userName, String newUserName, String newPath, String expectedUserId) {
        synchronized (resourceNameLock) {
            IamUser user = getUser(userName);
            if (expectedUserId != null && !expectedUserId.equals(user.getUserId())) {
                throw new AwsException("EntityAlreadyExists",
                        "User " + userName + " was replaced by a different user of the same name; "
                                + "refusing to apply an update meant for the original user.", 409);
            }
            if (newUserName != null && !newUserName.equals(userName)) {
                boolean nameTaken = resourcesInCurrentAccount(users)
                        .filter(existing -> !existing.getUserId().equals(user.getUserId()))
                        .anyMatch(existing -> existing.getUserName().equalsIgnoreCase(newUserName));
                if (nameTaken) {
                    throw new AwsException("EntityAlreadyExists",
                            "User with name " + newUserName + " already exists.", 409);
                }
                List<AccessKey> keysToMove = userAccessKeys(userName);
                users.delete(userName);
                user.setUserName(newUserName);
                if (newPath != null) user.setPath(normalizePath(newPath));
                user.setArn(iamArn("user", user.getPath(), newUserName));
                users.put(newUserName, user);
                loginProfiles.get(userName).ifPresent(profile -> {
                    loginProfiles.delete(userName);
                    profile.setUserName(newUserName);
                    loginProfiles.put(newUserName, profile);
                });
                for (AccessKey key : keysToMove) {
                    key.setUserName(newUserName);
                    accessKeys.put(key.getAccessKeyId(), key);
                }
                for (String groupName : user.getGroupNames()) {
                    groups.get(groupName).ifPresent(group -> {
                        group.getUserNames().remove(userName);
                        group.getUserNames().add(newUserName);
                        groups.put(groupName, group);
                    });
                }
            } else {
                if (newPath != null) {
                    user.setPath(normalizePath(newPath));
                    user.setArn(iamArn("user", user.getPath(), userName));
                }
                users.put(userName, user);
            }
        }
    }

    public void tagUser(String userName, Map<String, String> newTags) {
        synchronized (tagLock) {
            IamUser user = getUser(userName);
            user.setTags(mergeTagsWithinQuota(user.getTags(), newTags, "TagsPerUser"));
            users.put(userName, user);
        }
    }

    public void untagUser(String userName, List<String> tagKeys) {
        synchronized (tagLock) {
            IamUser user = getUser(userName);
            tagKeys.forEach(user.getTags()::remove);
            users.put(userName, user);
        }
    }

    public Map<String, String> listUserTags(String userName) {
        return getUser(userName).getTags();
    }

    // =========================================================================
    // Groups
    // =========================================================================

    public IamGroup createGroup(String groupName, String path) {
        synchronized (resourceNameLock) {
            if (containsNameIgnoreCase(groups, IamGroup::getGroupName, groupName)) {
                throw new AwsException("EntityAlreadyExists",
                        "Group with name " + groupName + " already exists.", 409);
            }
            String groupId = "AGPA" + randomId(16);
            String normalizedPath = normalizePath(path);
            String arn = iamArn("group", normalizedPath, groupName);
            IamGroup group = new IamGroup(groupId, groupName, normalizedPath, arn);
            groups.put(groupName, group);
            LOG.infov("Created IAM group: {0}", groupName);
            return group;
        }
    }

    public IamGroup getGroup(String groupName) {
        return groups.get(groupName)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "The group with name " + groupName + " cannot be found.", 404));
    }

    public void updateGroup(String groupName, String newGroupName, String newPath) {
        validateIamResourceName(groupName, "GroupName");
        if (newGroupName != null) {
            validateIamResourceName(newGroupName, "NewGroupName");
        }
        validateIamPath(newPath, "NewPath");
        synchronized (resourceNameLock) {
            IamGroup group = getGroup(groupName);
            if (newGroupName != null && !newGroupName.equals(groupName)) {
                boolean nameTaken = resourcesInCurrentAccount(groups)
                        .filter(existing -> !existing.getGroupId().equals(group.getGroupId()))
                        .anyMatch(existing -> existing.getGroupName().equalsIgnoreCase(newGroupName));
                if (nameTaken) {
                    throw new AwsException("EntityAlreadyExists",
                            "Group with name " + newGroupName + " already exists.", 409);
                }
                groups.delete(groupName);
                group.setGroupName(newGroupName);
                if (newPath != null) {
                    group.setPath(normalizePath(newPath));
                }
                group.setArn(iamArn("group", group.getPath(), newGroupName));
                groups.put(newGroupName, group);
                // Keep member references in sync so group policies still resolve after a rename.
                for (String memberName : group.getUserNames()) {
                    users.get(memberName).ifPresent(member -> {
                        member.getGroupNames().remove(groupName);
                        member.getGroupNames().add(newGroupName);
                        users.put(memberName, member);
                    });
                }
            } else {
                if (newPath != null) {
                    group.setPath(normalizePath(newPath));
                    group.setArn(iamArn("group", group.getPath(), groupName));
                }
                groups.put(groupName, group);
            }
        }
    }

    public void deleteGroup(String groupName) {
        IamGroup group = getGroup(groupName);
        if (!group.getAttachedPolicyArns().isEmpty() || !group.getInlinePolicies().isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must detach all policies first.", 409);
        }
        if (!group.getUserNames().isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must remove all users from group first.", 409);
        }
        groups.delete(groupName);
        LOG.infov("Deleted IAM group: {0}", groupName);
    }

    public List<IamGroup> listGroups(String pathPrefix) {
        String prefix = pathPrefix != null ? pathPrefix : "/";
        return groups.scan(k -> true).stream()
                .filter(g -> g.getPath().startsWith(prefix))
                .toList();
    }

    public void addUserToGroup(String groupName, String userName) {
        IamGroup group = getGroup(groupName);
        IamUser user = getUser(userName);
        if (!group.getUserNames().contains(userName)) {
            group.getUserNames().add(userName);
            groups.put(groupName, group);
        }
        if (!user.getGroupNames().contains(groupName)) {
            user.getGroupNames().add(groupName);
            users.put(userName, user);
        }
    }

    public void removeUserFromGroup(String groupName, String userName) {
        IamGroup group = getGroup(groupName);
        IamUser user = getUser(userName);
        group.getUserNames().remove(userName);
        groups.put(groupName, group);
        user.getGroupNames().remove(groupName);
        users.put(userName, user);
    }

    public List<IamGroup> listGroupsForUser(String userName) {
        IamUser user = getUser(userName);
        return user.getGroupNames().stream()
                .flatMap(gn -> groups.get(gn).stream())
                .toList();
    }

    // =========================================================================
    // Roles
    // =========================================================================

    public IamRole createRole(String roleName, String path, String assumeRolePolicyDocument,
                              String description, int maxSessionDuration, Map<String, String> tags) {
        synchronized (resourceNameLock) {
            if (containsNameIgnoreCase(roles, IamRole::getRoleName, roleName)) {
                throw new AwsException("EntityAlreadyExists",
                        "Role with name " + roleName + " already exists.", 409);
            }
            String roleId = "AROA" + randomId(16);
            String normalizedPath = normalizePath(path);
            String arn = iamArn("role", normalizedPath, roleName);
            IamRole role = new IamRole(roleId, roleName, normalizedPath, arn, assumeRolePolicyDocument);
            role.setDescription(description);
            if (maxSessionDuration > 0) role.setMaxSessionDuration(maxSessionDuration);
            if (tags != null) role.getTags().putAll(tags);
            roles.put(roleName, role);
            LOG.infov("Created IAM role: {0}", roleName);
            return role;
        }
    }

    public IamRole getRole(String roleName) {
        return roles.get(roleName)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "The role with name " + roleName + " cannot be found.", 404));
    }

    /**
     * Looks up a role by name in a specific account's namespace, without throwing when absent.
     *
     * <p>Roles are account-namespaced, so a cross-account caller (e.g. STS AssumeRole) must resolve
     * the role in its owning account — taken from the role ARN — rather than the request's account.
     */
    public Optional<IamRole> findRole(String accountId, String roleName) {
        if (roles instanceof AccountAwareStorageBackend<IamRole> aware) {
            return aware.getForAccount(accountId, roleName);
        }
        return roles.get(roleName);
    }

    /**
     * AWS publishes UnmodifiableEntity on twelve role actions, and its message names the linked
     * service the caller has to go through instead. This guards the eleven of them the emulator
     * implements; UpdateRoleDescription is the twelfth and has no handler here. TagRole and
     * UntagRole are deliberately not guarded — AWS does not publish the error on either, and
     * TagRole's reference says the role "can be a regular role or a service-linked role".
     * Within the IAM API, {@link #deleteServiceLinkedRole} is the only way to remove such a role.
     */
    private static void requireNotServiceLinked(IamRole role, String roleName) {
        if (role.isServiceLinkedRole()) {
            throw new AwsException("UnmodifiableEntity",
                    "Role " + roleName + " is a service-linked role for " + linkedServicePrincipal(role)
                            + "; request the change through that service.", 400);
        }
    }

    /** The linked service, recovered from the {@code /aws-service-role/<principal>/} path. */
    private static String linkedServicePrincipal(IamRole role) {
        String path = role.getPath();
        return path.substring(SERVICE_LINKED_ROLE_PATH.length(), path.length() - 1);
    }

    public void deleteRole(String roleName) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        if (!role.getAttachedPolicyArns().isEmpty() || !role.getInlinePolicies().isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete entity, must detach all policies first.", 409);
        }
        roles.delete(roleName);
        LOG.infov("Deleted IAM role: {0}", roleName);
    }

    /**
     * The linked service — not the caller and not the principal string — chooses the role-name
     * prefix, so {@code lex.amazonaws.com} yields {@code AWSServiceRoleForLexBots} and the real name
     * cannot be computed from the principal. The emulator mints a deterministic stand-in from the
     * principal's labels instead; callers need the create to succeed and the role to be readable
     * afterwards, which this satisfies, but the name will not match AWS for most services.
     *
     * <p>A {@code CustomSuffix} is joined with an underscore because that is the separator callers
     * parse back out: Terraform recovers {@code custom_suffix} by splitting the role name on
     * {@code _}, and that attribute forces replacement, so a name without one never converges.
     */
    public IamRole createServiceLinkedRole(String awsServiceName, String customSuffix, String description) {
        if (awsServiceName == null || !SERVICE_PRINCIPAL_PATTERN.matcher(awsServiceName).matches()) {
            throw new AwsException("InvalidInput",
                    "AWSServiceName must be 1-128 characters matching [\\w+=,.@-], for example es.amazonaws.com.", 400);
        }
        if (customSuffix != null && !customSuffix.isEmpty()
                && !CUSTOM_SUFFIX_PATTERN.matcher(customSuffix).matches()) {
            throw new AwsException("InvalidInput",
                    "CustomSuffix must be 1-64 characters matching [\\w+=,.@-].", 400);
        }
        String roleName = SERVICE_LINKED_ROLE_NAME_PREFIX + derivedServiceName(awsServiceName)
                + (customSuffix == null || customSuffix.isEmpty() ? "" : "_" + customSuffix);
        // AWSServiceName allows 128 characters, but AWS caps RoleName at 64 — on every action that
        // takes one, and on the Role this action returns — so a longer principal would derive a
        // name AWS could not represent.
        if (roleName.length() > ROLE_NAME_MAX_LENGTH) {
            throw new AwsException("InvalidInput",
                    "The derived role name " + roleName + " exceeds the "
                            + ROLE_NAME_MAX_LENGTH + "-character role name limit.", 400);
        }
        synchronized (resourceNameLock) {
            // createRole would answer EntityAlreadyExists, which this action does not document; the
            // duplicate-suffix case is an InvalidInput as far as its published error list is concerned.
            if (containsNameIgnoreCase(roles, IamRole::getRoleName, roleName)) {
                throw new AwsException("InvalidInput",
                        "A role named " + roleName + " already exists; supply a different CustomSuffix.", 400);
            }
            String trustPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"" + awsServiceName + "\"},\"Action\":\"sts:AssumeRole\"}]}";
            IamRole role = createRole(roleName, SERVICE_LINKED_ROLE_PATH + awsServiceName + "/",
                    trustPolicy, description, 0, Map.of());
            role.setServiceLinkedRole(true);
            roles.put(roleName, role);
            return role;
        }
    }

    /**
     * Deletion is synchronous here, so the task the caller is handed back is already complete —
     * {@link #getServiceLinkedRoleDeletionStatus} answers SUCCEEDED for it immediately.
     */
    public String deleteServiceLinkedRole(String roleName) {
        if (roleName == null) {
            throw new AwsException("NoSuchEntity", "The request must include RoleName.", 404);
        }
        IamRole role = getRole(roleName);
        // The path cannot classify a role — CreateRole will put an ordinary one under the
        // service-role prefix — so only roles minted here are deletable through this action. The
        // error is NoSuchEntity because that is what this action's published list carries.
        if (!role.isServiceLinkedRole()) {
            throw new AwsException("NoSuchEntity",
                    "There is no service-linked role with name " + roleName + ".", 404);
        }
        String servicePrincipal = linkedServicePrincipal(role);
        // Not deleteRole: that action refuses a service-linked role outright, and its
        // detach-first conflict is not in this action's published error list either.
        roles.delete(roleName);
        LOG.infov("Deleted service-linked IAM role: {0}", roleName);

        String deletionTaskId = "task" + SERVICE_LINKED_ROLE_PATH + servicePrincipal + "/"
                + roleName + "/" + UUID.randomUUID();
        serviceLinkedRoleDeletions.put(deletionTaskId, roleName);
        return deletionTaskId;
    }

    /**
     * Every dot- and hyphen-separated label contributes, because the leading one alone is not unique:
     * {@code rds.amazonaws.com} and {@code rds.application-autoscaling.amazonaws.com} are separate
     * roles on AWS, and a config declaring both must not collide on one name here.
     */
    private static String derivedServiceName(String awsServiceName) {
        String canonicalName = SERVICE_LINKED_ROLE_NAMES.get(awsServiceName);
        if (canonicalName != null) {
            return canonicalName;
        }
        String core = awsServiceName == null ? "" : awsServiceName;
        if (core.endsWith(AMAZONAWS_DOMAIN)) {
            core = core.substring(0, core.length() - AMAZONAWS_DOMAIN.length());
        }
        StringBuilder derived = new StringBuilder();
        for (String segment : core.split("[.-]")) {
            if (!segment.isEmpty()) {
                derived.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
            }
        }
        if (derived.isEmpty()) {
            throw new AwsException("InvalidInput",
                    "The request must include a valid AWSServiceName, for example es.amazonaws.com.", 400);
        }
        return derived.toString();
    }

    public String getServiceLinkedRoleDeletionStatus(String deletionTaskId) {
        if (deletionTaskId == null || serviceLinkedRoleDeletions.get(deletionTaskId).isEmpty()) {
            throw new AwsException("NoSuchEntity",
                    "The deletion task with id " + deletionTaskId + " cannot be found.", 404);
        }
        return "SUCCEEDED";
    }

    public List<IamRole> listRoles(String pathPrefix) {
        String prefix = pathPrefix != null ? pathPrefix : "/";
        return roles.scan(k -> true).stream()
                .filter(r -> r.getPath().startsWith(prefix))
                .toList();
    }

    public void updateRole(String roleName, String description, int maxSessionDuration) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        if (description != null) role.setDescription(description);
        if (maxSessionDuration > 0) role.setMaxSessionDuration(maxSessionDuration);
        roles.put(roleName, role);
    }

    public void updateAssumeRolePolicy(String roleName, String policyDocument) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        role.setAssumeRolePolicyDocument(policyDocument);
        roles.put(roleName, role);
    }

    /**
     * Same as {@link #updateAssumeRolePolicy(String, String)}, but verifies {@code expectedRoleId}
     * against the resolved role's immutable ID before applying the update, atomically with the
     * name-based lookup. For callers (e.g. CloudFormation role adoption) that already verified role
     * identity by ID earlier: without this, a role deleted and recreated under the same name between
     * that check and this call would silently receive the update meant for the original role.
     */
    public void updateAssumeRolePolicy(String roleName, String policyDocument, String expectedRoleId) {
        IamRole role = getRole(roleName);
        if (expectedRoleId != null && !expectedRoleId.equals(role.getRoleId())) {
            throw new AwsException("EntityAlreadyExists",
                    "Role " + roleName + " was replaced by a different role of the same name; "
                            + "refusing to apply an update meant for the original role.", 409);
        }
        role.setAssumeRolePolicyDocument(policyDocument);
        roles.put(roleName, role);
    }

    public void tagRole(String roleName, Map<String, String> newTags) {
        synchronized (tagLock) {
            IamRole role = getRole(roleName);
            role.setTags(mergeTagsWithinQuota(role.getTags(), newTags, "TagsPerRole"));
            roles.put(roleName, role);
        }
    }

    public void untagRole(String roleName, List<String> tagKeys) {
        synchronized (tagLock) {
            IamRole role = getRole(roleName);
            tagKeys.forEach(role.getTags()::remove);
            roles.put(roleName, role);
        }
    }

    public Map<String, String> listRoleTags(String roleName) {
        return getRole(roleName).getTags();
    }

    // =========================================================================
    // Managed Policies
    // =========================================================================

    public IamPolicy createPolicy(String policyName, String path, String description,
                                  String document, Map<String, String> tags) {
        synchronized (resourceNameLock) {
            String normalizedPath = normalizePath(path);
            String arn = iamArn("policy", normalizedPath, policyName);
            boolean nameTaken = resourcesInCurrentAccount(policies)
                    .filter(existing -> existing.getArn() == null
                            || !existing.getArn().startsWith(AwsManagedPolicies.ARN_PREFIX))
                    .map(IamPolicy::getPolicyName)
                    .anyMatch(existingName -> existingName != null && existingName.equalsIgnoreCase(policyName));
            if (nameTaken) {
                throw new AwsException("EntityAlreadyExists",
                        "Policy " + arn + " already exists.", 409);
            }
            String policyId = "ANPA" + randomId(16);
            IamPolicy policy = new IamPolicy(policyId, policyName, normalizedPath, arn, description, document);
            if (tags != null) policy.getTags().putAll(tags);
            policies.put(arn, policy);
            LOG.infov("Created IAM policy: {0}", arn);
            return policy;
        }
    }

    public IamPolicy getPolicy(String policyArn) {
        IamPolicy policy = requirePolicy(policyArn);
        if (policyArn.startsWith(AwsManagedPolicies.ARN_PREFIX)) {
            return managedPolicySnapshot(policy, managedPolicyAttachmentCount(policyArn));
        }
        return policy;
    }

    private IamPolicy requirePolicy(String policyArn) {
        return resolvePolicy(policyArn)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Policy " + policyArn + " does not exist.", 404));
    }

    /**
     * Resolves a policy by ARN without throwing, mirroring {@link #getPolicy} so that
     * AWS-managed policies (arn:aws:iam::aws:policy/...) are served from the global catalog
     * rather than the account-partitioned store. Attached-policy read paths must use this:
     * a managed policy attached to a principal owned by a non-default account is absent from
     * that account's {@link #policies} partition and would otherwise be silently dropped.
     */
    private Optional<IamPolicy> resolvePolicy(String arn) {
        if (arn != null && arn.startsWith(AwsManagedPolicies.ARN_PREFIX)) {
            // Authorization and ListAttached* need policy documents or names, not attachment counts.
            return Optional.ofNullable(awsManagedPolicies.get(arn));
        }
        return policies.get(arn);
    }

    private int managedPolicyAttachmentCount(String policyArn) {
        return managedPolicyAttachmentCounts().getOrDefault(policyArn, 0);
    }

    private Map<String, Integer> managedPolicyAttachmentCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        users.scan(k -> true).forEach(user -> tallyManagedPolicyAttachments(user.getAttachedPolicyArns(), counts));
        groups.scan(k -> true).forEach(group -> tallyManagedPolicyAttachments(group.getAttachedPolicyArns(), counts));
        roles.scan(k -> true).forEach(role -> tallyManagedPolicyAttachments(role.getAttachedPolicyArns(), counts));
        return counts;
    }

    private void tallyManagedPolicyAttachments(List<String> policyArns, Map<String, Integer> counts) {
        for (String policyArn : policyArns) {
            if (policyArn.startsWith(AwsManagedPolicies.ARN_PREFIX)) {
                counts.merge(policyArn, 1, Integer::sum);
            }
        }
    }

    private IamPolicy managedPolicySnapshot(IamPolicy catalogPolicy, int attachmentCount) {
        IamPolicy snapshot = new IamPolicy();
        snapshot.setPolicyId(catalogPolicy.getPolicyId());
        snapshot.setPolicyName(catalogPolicy.getPolicyName());
        snapshot.setPath(catalogPolicy.getPath());
        snapshot.setArn(catalogPolicy.getArn());
        snapshot.setDescription(catalogPolicy.getDescription());
        snapshot.setDefaultVersionId(catalogPolicy.getDefaultVersionId());
        snapshot.setNextVersionNumber(catalogPolicy.getNextVersionNumber());
        snapshot.setCreateDate(catalogPolicy.getCreateDate());
        snapshot.setUpdateDate(catalogPolicy.getUpdateDate());
        snapshot.setTags(new LinkedHashMap<>(catalogPolicy.getTags()));
        Map<String, PolicyVersion> versions = new LinkedHashMap<>();
        synchronized (catalogPolicy.getVersions()) {
            for (Map.Entry<String, PolicyVersion> entry : catalogPolicy.getVersions().entrySet()) {
                PolicyVersion version = entry.getValue();
                versions.put(entry.getKey(), new PolicyVersion(version.getVersionId(), version.getDocument(),
                        version.isDefaultVersion(), version.getCreateDate()));
            }
        }
        snapshot.setVersions(versions);
        snapshot.setAttachmentCount(attachmentCount);
        return snapshot;
    }

    private void incrementCustomerManagedPolicyAttachmentCount(IamPolicy policy) {
        if (policy.getArn().startsWith(AwsManagedPolicies.ARN_PREFIX)) {
            return;
        }
        policy.setAttachmentCount(policy.getAttachmentCount() + 1);
        policies.put(policy.getArn(), policy);
    }

    private void decrementCustomerManagedPolicyAttachmentCount(String policyArn) {
        if (policyArn.startsWith(AwsManagedPolicies.ARN_PREFIX)) {
            return;
        }
        policies.get(policyArn).ifPresent(policy -> {
            policy.setAttachmentCount(Math.max(0, policy.getAttachmentCount() - 1));
            policies.put(policyArn, policy);
        });
    }

    private void rejectIfAwsManaged(String policyArn) {
        if (policyArn != null && policyArn.startsWith(AwsManagedPolicies.ARN_PREFIX)) {
            throw new AwsException("AccessDenied",
                    "Cannot modify or delete AWS managed policy: " + policyArn, 403);
        }
    }

    public void deletePolicy(String policyArn) {
        rejectIfAwsManaged(policyArn);
        IamPolicy policy = getPolicy(policyArn);
        if (policy.getAttachmentCount() > 0) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete a policy attached to entities. Detach it first.", 409);
        }
        policies.delete(policyArn);
        LOG.infov("Deleted IAM policy: {0}", policyArn);
    }

    /** The roles, users and groups a managed policy is currently attached to. */
    public record PolicyEntities(List<IamRole> roles, List<IamUser> users, List<IamGroup> groups) {}

    /**
     * Lists the entities (roles, users, groups) a managed policy is attached to — the read behind
     * IAM's ListEntitiesForPolicy and what a caller must detach before {@link #deletePolicy} will
     * succeed. Attachments are tracked on the principals, so this scans them for the given ARN.
     */
    public PolicyEntities listEntitiesForPolicy(String policyArn) {
        requirePolicy(policyArn); // AWS raises NoSuchEntity for an unknown policy ARN; fail fast likewise.
        List<IamRole> attachedRoles = roles.scan(k -> true).stream()
                .filter(r -> r.getAttachedPolicyArns().contains(policyArn))
                .toList();
        List<IamUser> attachedUsers = users.scan(k -> true).stream()
                .filter(u -> u.getAttachedPolicyArns().contains(policyArn))
                .toList();
        List<IamGroup> attachedGroups = groups.scan(k -> true).stream()
                .filter(g -> g.getAttachedPolicyArns().contains(policyArn))
                .toList();
        return new PolicyEntities(attachedRoles, attachedUsers, attachedGroups);
    }

    public List<IamPolicy> listPolicies(String scope, String pathPrefix) {
        if (scope != null && !scope.isBlank()
                && !"All".equalsIgnoreCase(scope)
                && !"AWS".equalsIgnoreCase(scope)
                && !"Local".equalsIgnoreCase(scope)) {
            throw new AwsException("ValidationError",
                    "Value '" + scope + "' at 'scope' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [All, AWS, Local]", 400);
        }
        String prefix = pathPrefix != null ? pathPrefix : "/";
        boolean blankScope = scope == null || scope.isBlank();
        boolean includeAws = blankScope || "All".equalsIgnoreCase(scope) || "AWS".equalsIgnoreCase(scope);
        boolean includeLocal = blankScope || "All".equalsIgnoreCase(scope) || "Local".equalsIgnoreCase(scope);

        List<IamPolicy> result = new ArrayList<>();
        if (includeLocal) {
            // Customer-managed policies live in the account-partitioned store. Exclude any
            // AWS-managed ARNs mirrored into the default account at seed time — those are
            // served from the global catalog below so the default account does not see them twice.
            policies.scan(k -> true).stream()
                    .filter(p -> !p.getArn().startsWith(AwsManagedPolicies.ARN_PREFIX))
                    .filter(p -> p.getPath().startsWith(prefix))
                    .forEach(result::add);
        }
        if (includeAws) {
            // AWS-managed policies are global (account-less arn:aws:iam::aws:policy/... ARN), so
            // every caller sees the full set regardless of the request account — mirroring the
            // getPolicy fix, and keeping the ListPolicies and GetPolicy read paths consistent.
            Map<String, Integer> attachmentCounts = managedPolicyAttachmentCounts();
            awsManagedPolicies.values().stream()
                    .filter(p -> p.getPath().startsWith(prefix))
                    .map(p -> managedPolicySnapshot(p, attachmentCounts.getOrDefault(p.getArn(), 0)))
                    .forEach(result::add);
        }
        return result;
    }

    /**
     * Entity counts and quotas backing GetAccountSummary. Covers all 34 documented SummaryMap
     * keys; quota values are cross-checked against AWS's published IAM service quotas
     * (docs.aws.amazon.com/general/latest/gr/iam-service.html), though floci itself enforces
     * only the 5-versions-per-policy cap in {@link #createPolicyVersion}. Resources floci does
     * not track at all (MFA devices, SAML providers, server certificates, account password - all
     * stub-empty elsewhere in this handler) are reported as zero rather than omitted, so callers
     * indexing into the full AWS field set don't hit a missing-key error.
     */
    public Map<String, Long> getAccountSummary() {
        long localPolicyCount = 0;
        long policyVersionsInUse = 0;
        for (IamPolicy policy : policies.scan(k -> true)) {
            if (policy.getArn().startsWith(AwsManagedPolicies.ARN_PREFIX)) {
                continue;
            }
            localPolicyCount++;
            policyVersionsInUse += policy.getVersions().size();
        }

        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("Users", (long) listUsers(null).size());
        summary.put("UsersQuota", 5000L);
        summary.put("Groups", (long) listGroups(null).size());
        summary.put("GroupsQuota", 300L);
        summary.put("GroupsPerUserQuota", 10L);
        summary.put("Roles", (long) listRoles(null).size());
        summary.put("RolesQuota", 1000L);
        summary.put("AssumeRolePolicySizeQuota", 2048L);
        summary.put("Policies", localPolicyCount);
        summary.put("PoliciesQuota", 1500L);
        summary.put("PolicySizeQuota", 6144L);
        summary.put("PolicyVersionsInUse", policyVersionsInUse);
        summary.put("PolicyVersionsInUseQuota", 10000L);
        summary.put("VersionsPerPolicyQuota", 5L);
        summary.put("InstanceProfiles", (long) listInstanceProfiles(null).size());
        summary.put("InstanceProfilesQuota", 1000L);
        summary.put("AttachedPoliciesPerUserQuota", 10L);
        summary.put("AttachedPoliciesPerGroupQuota", 10L);
        summary.put("AttachedPoliciesPerRoleQuota", 20L);
        summary.put("GroupPolicySizeQuota", 5120L);
        summary.put("UserPolicySizeQuota", 2048L);
        summary.put("RolePolicySizeQuota", 10240L);
        summary.put("AccessKeysPerUserQuota", 2L);
        summary.put("SigningCertificatesPerUserQuota", 2L);
        summary.put("ServerCertificates", 0L);
        summary.put("ServerCertificatesQuota", 20L);
        summary.put("Providers", (long) oidcProviders.scan(k -> true).size());
        summary.put("MFADevices", 0L);
        summary.put("MFADevicesInUse", 0L);
        summary.put("AccountMFAEnabled", 0L);
        // AWS reports whether the root account has access keys, not whether IAM users do.
        // Floci does not model root access keys, so this remains false even when user keys exist.
        summary.put("AccountAccessKeysPresent", 0L);
        summary.put("AccountSigningCertificatesPresent", 0L);
        summary.put("AccountPasswordPresent", 0L);
        summary.put("GlobalEndpointTokenVersion", 1L);
        return summary;
    }

    public PolicyVersion createPolicyVersion(String policyArn, String document, boolean setAsDefault) {
        rejectIfAwsManaged(policyArn);
        IamPolicy policy = getPolicy(policyArn);
        Map<String, PolicyVersion> versions = policy.getVersions();
        PolicyVersion version;
        synchronized (versions) {
            if (versions.size() >= 5) {
                throw new AwsException("LimitExceeded",
                        "A managed policy can have up to 5 versions.", 409);
            }
            // AWS version ids are monotonic and never reissued after a DeletePolicyVersion.
            // The live keys' own max is only a floor, not the source of truth: deleting the
            // highest-numbered surviving version would otherwise let a "derive from what's left"
            // computation reissue its id. The stored high-water mark is the real counter.
            int highestSurviving = versions.keySet().stream()
                    .mapToInt(id -> Integer.parseInt(id.substring(1)))
                    .max().orElse(0);
            int nextVersionNum;
            if (policy.getNextVersionNumber() == null) {
                // A policy persisted before this field existed: the live-key floor alone can't
                // reveal a version deleted before this field was ever recorded (e.g. v1-v4 live
                // after a pre-migration DeletePolicyVersion("v5")). Since a policy can never hold
                // more than 5 concurrent versions, no more than 5 stacked deletions could have
                // happened at the top between two persists; jump the counter past that worst
                // case rather than trust the live keys' max + 1 for an unknown history.
                nextVersionNum = highestSurviving + 1 + 5;
            } else {
                nextVersionNum = Math.max(policy.getNextVersionNumber(), highestSurviving + 1);
            }
            String versionId = "v" + nextVersionNum;
            version = new PolicyVersion(versionId, document, setAsDefault);
            if (setAsDefault) {
                versions.values().forEach(v -> v.setDefaultVersion(false));
                policy.setDefaultVersionId(versionId);
            }
            versions.put(versionId, version);
            policy.setNextVersionNumber(nextVersionNum + 1);
        }
        policy.setUpdateDate(Instant.now());
        policies.put(policyArn, policy);
        return version;
    }

    public PolicyVersion getPolicyVersion(String policyArn, String versionId) {
        IamPolicy policy = requirePolicy(policyArn);
        PolicyVersion version = policy.getVersions().get(versionId);
        if (version == null) {
            throw new AwsException("NoSuchEntity",
                    "Policy version " + versionId + " does not exist.", 404);
        }
        return version;
    }

    public void deletePolicyVersion(String policyArn, String versionId) {
        rejectIfAwsManaged(policyArn);
        IamPolicy policy = getPolicy(policyArn);
        if (versionId.equals(policy.getDefaultVersionId())) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete the default version of a policy.", 409);
        }
        Map<String, PolicyVersion> versions = policy.getVersions();
        synchronized (versions) {
            if (!versions.containsKey(versionId)) {
                throw new AwsException("NoSuchEntity",
                        "Policy version " + versionId + " does not exist.", 404);
            }
            versions.remove(versionId);
        }
        policies.put(policyArn, policy);
    }

    public List<PolicyVersion> listPolicyVersions(String policyArn) {
        Map<String, PolicyVersion> versions = requirePolicy(policyArn).getVersions();
        synchronized (versions) {
            return new ArrayList<>(versions.values());
        }
    }

    public void setDefaultPolicyVersion(String policyArn, String versionId) {
        rejectIfAwsManaged(policyArn);
        IamPolicy policy = getPolicy(policyArn);
        Map<String, PolicyVersion> versions = policy.getVersions();
        synchronized (versions) {
            if (!versions.containsKey(versionId)) {
                throw new AwsException("NoSuchEntity",
                        "Policy version " + versionId + " does not exist.", 404);
            }
            versions.values().forEach(v -> v.setDefaultVersion(false));
            versions.get(versionId).setDefaultVersion(true);
        }
        policy.setDefaultVersionId(versionId);
        policies.put(policyArn, policy);
    }

    public void tagPolicy(String policyArn, Map<String, String> newTags) {
        rejectIfAwsManaged(policyArn);
        synchronized (tagLock) {
            IamPolicy policy = getPolicy(policyArn);
            policy.setTags(mergeTagsWithinQuota(policy.getTags(), newTags, "TagsPerPolicy"));
            policies.put(policyArn, policy);
        }
    }

    public void untagPolicy(String policyArn, List<String> tagKeys) {
        rejectIfAwsManaged(policyArn);
        synchronized (tagLock) {
            IamPolicy policy = getPolicy(policyArn);
            tagKeys.forEach(policy.getTags()::remove);
            policies.put(policyArn, policy);
        }
    }

    public Map<String, String> listPolicyTags(String policyArn) {
        return requirePolicy(policyArn).getTags();
    }

    // =========================================================================
    // Policy Attachments — Users
    // =========================================================================

    public void attachUserPolicy(String userName, String policyArn) {
        IamUser user = getUser(userName);
        IamPolicy policy = requirePolicy(policyArn);
        if (!user.getAttachedPolicyArns().contains(policyArn)) {
            user.getAttachedPolicyArns().add(policyArn);
            users.put(userName, user);
            incrementCustomerManagedPolicyAttachmentCount(policy);
        }
    }

    public void detachUserPolicy(String userName, String policyArn) {
        IamUser user = getUser(userName);
        if (!user.getAttachedPolicyArns().remove(policyArn)) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyArn + " is not attached to user " + userName + ".", 404);
        }
        users.put(userName, user);
        decrementCustomerManagedPolicyAttachmentCount(policyArn);
    }

    public List<IamPolicy> listAttachedUserPolicies(String userName, String pathPrefix) {
        return getUser(userName).getAttachedPolicyArns().stream()
                .flatMap(arn -> resolvePolicy(arn).stream())
                .filter(p -> pathPrefix == null || p.getPath().startsWith(pathPrefix))
                .toList();
    }

    // =========================================================================
    // Policy Attachments — Groups
    // =========================================================================

    public void attachGroupPolicy(String groupName, String policyArn) {
        IamGroup group = getGroup(groupName);
        IamPolicy policy = requirePolicy(policyArn);
        if (!group.getAttachedPolicyArns().contains(policyArn)) {
            group.getAttachedPolicyArns().add(policyArn);
            groups.put(groupName, group);
            incrementCustomerManagedPolicyAttachmentCount(policy);
        }
    }

    public void detachGroupPolicy(String groupName, String policyArn) {
        IamGroup group = getGroup(groupName);
        if (!group.getAttachedPolicyArns().remove(policyArn)) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyArn + " is not attached to group " + groupName + ".", 404);
        }
        groups.put(groupName, group);
        decrementCustomerManagedPolicyAttachmentCount(policyArn);
    }

    public List<IamPolicy> listAttachedGroupPolicies(String groupName, String pathPrefix) {
        return getGroup(groupName).getAttachedPolicyArns().stream()
                .flatMap(arn -> resolvePolicy(arn).stream())
                .filter(p -> pathPrefix == null || p.getPath().startsWith(pathPrefix))
                .toList();
    }

    // =========================================================================
    // Policy Attachments — Roles
    // =========================================================================

    public void attachRolePolicy(String roleName, String policyArn) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        IamPolicy policy = requirePolicy(policyArn);
        if (!role.getAttachedPolicyArns().contains(policyArn)) {
            role.getAttachedPolicyArns().add(policyArn);
            roles.put(roleName, role);
            incrementCustomerManagedPolicyAttachmentCount(policy);
        }
    }

    public void detachRolePolicy(String roleName, String policyArn) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        if (!role.getAttachedPolicyArns().remove(policyArn)) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyArn + " is not attached to role " + roleName + ".", 404);
        }
        roles.put(roleName, role);
        decrementCustomerManagedPolicyAttachmentCount(policyArn);
    }

    public List<IamPolicy> listAttachedRolePolicies(String roleName, String pathPrefix) {
        return getRole(roleName).getAttachedPolicyArns().stream()
                .flatMap(arn -> resolvePolicy(arn).stream())
                .filter(p -> pathPrefix == null || p.getPath().startsWith(pathPrefix))
                .toList();
    }

    // =========================================================================
    // Inline Policies — Users
    // =========================================================================

    public void putUserPolicy(String userName, String policyName, String policyDocument) {
        IamUser user = getUser(userName);
        user.getInlinePolicies().put(policyName, policyDocument);
        users.put(userName, user);
    }

    public String getUserPolicy(String userName, String policyName) {
        IamUser user = getUser(userName);
        String doc = user.getInlinePolicies().get(policyName);
        if (doc == null) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyName + " not found for user " + userName + ".", 404);
        }
        return doc;
    }

    public void deleteUserPolicy(String userName, String policyName) {
        IamUser user = getUser(userName);
        if (user.getInlinePolicies().remove(policyName) == null) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyName + " not found for user " + userName + ".", 404);
        }
        users.put(userName, user);
    }

    public List<String> listUserPolicies(String userName) {
        return new ArrayList<>(getUser(userName).getInlinePolicies().keySet());
    }

    // =========================================================================
    // Inline Policies — Groups
    // =========================================================================

    public void putGroupPolicy(String groupName, String policyName, String policyDocument) {
        IamGroup group = getGroup(groupName);
        group.getInlinePolicies().put(policyName, policyDocument);
        groups.put(groupName, group);
    }

    public String getGroupPolicy(String groupName, String policyName) {
        IamGroup group = getGroup(groupName);
        String doc = group.getInlinePolicies().get(policyName);
        if (doc == null) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyName + " not found for group " + groupName + ".", 404);
        }
        return doc;
    }

    public void deleteGroupPolicy(String groupName, String policyName) {
        IamGroup group = getGroup(groupName);
        if (group.getInlinePolicies().remove(policyName) == null) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyName + " not found for group " + groupName + ".", 404);
        }
        groups.put(groupName, group);
    }

    public List<String> listGroupPolicies(String groupName) {
        return new ArrayList<>(getGroup(groupName).getInlinePolicies().keySet());
    }

    // =========================================================================
    // Inline Policies — Roles
    // =========================================================================

    public void putRolePolicy(String roleName, String policyName, String policyDocument) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        role.getInlinePolicies().put(policyName, policyDocument);
        roles.put(roleName, role);
    }

    public String getRolePolicy(String roleName, String policyName) {
        IamRole role = getRole(roleName);
        String doc = role.getInlinePolicies().get(policyName);
        if (doc == null) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyName + " not found for role " + roleName + ".", 404);
        }
        return doc;
    }

    public void deleteRolePolicy(String roleName, String policyName) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        if (role.getInlinePolicies().remove(policyName) == null) {
            throw new AwsException("NoSuchEntity",
                    "Policy " + policyName + " not found for role " + roleName + ".", 404);
        }
        roles.put(roleName, role);
    }

    public List<String> listRolePolicies(String roleName) {
        return new ArrayList<>(getRole(roleName).getInlinePolicies().keySet());
    }

    // =========================================================================
    // Access Keys
    // =========================================================================

    public AccessKey createAccessKey(String userName) {
        getUser(userName); // validates existence
        long existingCount = accessKeys.scan(k -> true).stream()
                .filter(ak -> userName.equals(ak.getUserName()))
                .count();
        if (existingCount >= 2) {
            throw new AwsException("LimitExceeded",
                    "Cannot exceed quota for AccessKeysPerUser: 2", 409);
        }
        String keyId = "AKIA" + randomId(16);
        String secretKey = randomSecret(40);
        AccessKey key = new AccessKey(keyId, secretKey, userName);
        accessKeys.put(keyId, key);
        LOG.infov("Created access key for user: {0}", userName);
        return key;
    }

    public void deleteAccessKey(String userName, String accessKeyId) {
        AccessKey key = accessKeys.get(accessKeyId)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Access key " + accessKeyId + " not found.", 404));
        if (!key.getUserName().equals(userName)) {
            throw new AwsException("NoSuchEntity",
                    "Access key " + accessKeyId + " does not belong to user " + userName + ".", 404);
        }
        accessKeys.delete(accessKeyId);
    }

    public List<AccessKey> listAccessKeys(String userName) {
        getUser(userName); // validates existence
        return userAccessKeys(userName);
    }

    private List<AccessKey> userAccessKeys(String userName) {
        return accessKeys.scan(k -> true).stream()
                .filter(ak -> userName.equals(ak.getUserName()))
                .toList();
    }

    public void updateAccessKey(String userName, String accessKeyId, String status) {
        AccessKey key = accessKeys.get(accessKeyId)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Access key " + accessKeyId + " not found.", 404));
        if (!key.getUserName().equals(userName)) {
            throw new AwsException("NoSuchEntity",
                    "Access key " + accessKeyId + " does not belong to user " + userName + ".", 404);
        }
        if (!"Active".equals(status) && !"Inactive".equals(status)) {
            throw new AwsException("ValidationError",
                    "Status must be Active or Inactive.", 400);
        }
        key.setStatus(status);
        accessKeys.put(accessKeyId, key);
    }

    // =========================================================================
    // Centralized root access management (org-scoped, IAM Query endpoint)
    // =========================================================================

    /** Currently-enabled centralized root features, in enablement order. Empty for a fresh org. */
    public List<String> listOrganizationsFeatures() {
        return new ArrayList<>(currentRootFeatures().getEnabledFeatures());
    }

    public List<String> enableOrganizationsRootCredentialsManagement() {
        return addRootFeature(FEATURE_ROOT_CREDENTIALS);
    }

    public List<String> enableOrganizationsRootSessions() {
        return addRootFeature(FEATURE_ROOT_SESSIONS);
    }

    public List<String> disableOrganizationsRootCredentialsManagement() {
        return removeRootFeature(FEATURE_ROOT_CREDENTIALS);
    }

    public List<String> disableOrganizationsRootSessions() {
        return removeRootFeature(FEATURE_ROOT_SESSIONS);
    }

    private OrganizationRootFeatures currentRootFeatures() {
        return orgRootFeatures.get(ROOT_FEATURES_KEY).orElseGet(OrganizationRootFeatures::new);
    }

    private List<String> addRootFeature(String feature) {
        OrganizationRootFeatures features = currentRootFeatures();
        features.getEnabledFeatures().add(feature);
        orgRootFeatures.put(ROOT_FEATURES_KEY, features);
        LOG.infov("Enabled centralized root feature {0}", feature);
        return new ArrayList<>(features.getEnabledFeatures());
    }

    private List<String> removeRootFeature(String feature) {
        OrganizationRootFeatures features = currentRootFeatures();
        features.getEnabledFeatures().remove(feature);
        orgRootFeatures.put(ROOT_FEATURES_KEY, features);
        LOG.infov("Disabled centralized root feature {0}", feature);
        return new ArrayList<>(features.getEnabledFeatures());
    }

    // =========================================================================
    // Instance Profiles
    // =========================================================================

    public InstanceProfile createInstanceProfile(String instanceProfileName, String path) {
        synchronized (resourceNameLock) {
            if (containsNameIgnoreCase(
                    instanceProfiles, InstanceProfile::getInstanceProfileName, instanceProfileName)) {
                throw new AwsException("EntityAlreadyExists",
                        "Instance profile " + instanceProfileName + " already exists.", 409);
            }
            String profileId = "AIPA" + randomId(16);
            String normalizedPath = normalizePath(path);
            String arn = iamArn("instance-profile", normalizedPath, instanceProfileName);
            InstanceProfile profile = new InstanceProfile(profileId, instanceProfileName, normalizedPath, arn);
            instanceProfiles.put(instanceProfileName, profile);
            LOG.infov("Created instance profile: {0}", instanceProfileName);
            return profile;
        }
    }

    public Optional<InstanceProfile> findInstanceProfile(String accountId, String profileName) {
        if (instanceProfiles instanceof AccountAwareStorageBackend<InstanceProfile> aware) {
            return aware.getForAccount(accountId, profileName);
        }
        return instanceProfiles.get(profileName);
    }

    public InstanceProfile getInstanceProfile(String instanceProfileName) {
        return instanceProfiles.get(instanceProfileName)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Instance profile " + instanceProfileName + " cannot be found.", 404));
    }

    public void deleteInstanceProfile(String instanceProfileName) {
        InstanceProfile profile = getInstanceProfile(instanceProfileName);
        if (!profile.getRoleNames().isEmpty()) {
            throw new AwsException("DeleteConflict",
                    "Cannot delete instance profile with associated roles.", 409);
        }
        instanceProfiles.delete(instanceProfileName);
    }

    public List<InstanceProfile> listInstanceProfiles(String pathPrefix) {
        String prefix = pathPrefix != null ? pathPrefix : "/";
        return instanceProfiles.scan(k -> true).stream()
                .filter(p -> p.getPath().startsWith(prefix))
                .toList();
    }

    public void addRoleToInstanceProfile(String instanceProfileName, String roleName) {
        InstanceProfile profile = getInstanceProfile(instanceProfileName);
        requireNotServiceLinked(getRole(roleName), roleName);
        List<String> roleNames = profile.getRoleNames();
        synchronized (roleNames) {
            if (!roleNames.contains(roleName)) {
                if (!roleNames.isEmpty()) {
                    throw new AwsException("LimitExceeded",
                            "An instance profile can contain at most 1 role.", 409);
                }
                roleNames.add(roleName);
                instanceProfiles.put(instanceProfileName, profile);
            }
        }
    }

    public void removeRoleFromInstanceProfile(String instanceProfileName, String roleName) {
        InstanceProfile profile = getInstanceProfile(instanceProfileName);
        // Tolerates an already-deleted role, so guard only what is still there.
        roles.get(roleName).ifPresent(role -> requireNotServiceLinked(role, roleName));
        profile.getRoleNames().remove(roleName);
        instanceProfiles.put(instanceProfileName, profile);
    }

    public List<InstanceProfile> listInstanceProfilesForRole(String roleName) {
        getRole(roleName); // validates existence
        return instanceProfiles.scan(k -> true).stream()
                .filter(p -> p.getRoleNames().contains(roleName))
                .toList();
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (IamUser user : users.scan(k -> true)) {
            addIamResource(resources, user.getArn(), "iam:user", user.getCreateDate(), user.getTags());
        }
        for (IamRole role : roles.scan(k -> true)) {
            addIamResource(resources, role.getArn(), "iam:role", role.getCreateDate(), role.getTags());
        }
        return resources;
    }

    private void addIamResource(List<ExplorerResource> out, String arn, String type,
                                Instant createDate, Map<String, String> tags) {
        if (arn == null) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        // IAM is a global service: its ARNs carry no region. Resource Explorer reports
        // global resources with the region "global" (not an empty string).
        String region = parsed.region() == null || parsed.region().isEmpty()
                ? "global"
                : parsed.region();
        out.add(new ExplorerResource(
                arn, type, "iam",
                region, parsed.accountId(),
                createDate != null ? createDate : Instant.now(),
                tags != null ? tags : Map.of()));
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(
                new SupportedResourceType("iam:user", "iam", true),
                new SupportedResourceType("iam:role", "iam", true));
    }

    // =========================================================================
    // Account Aliases
    // ==================================================================
    public Optional<String> getAccountAlias() {
        return accountAliases.get(ACCOUNT_ALIAS_KEY);
    }

    /**
     * An account holds one alias, and AWS enforces that by replacement rather than rejection:
     * creating a free alias while another is set silently swaps it. {@code EntityAlreadyExists}
     * means the requested name is taken — globally on AWS, since aliases are unique across all
     * accounts. Only the "you already hold this one" case can arise here, because the store is
     * namespaced per account and holds no other account's aliases.
     */
    public void createAccountAlias(String alias) {
        validateAccountAlias(alias);
        synchronized (accountAliasLock) {
            Optional<String> existing = accountAliases.get(ACCOUNT_ALIAS_KEY);
            if (existing.isPresent() && existing.get().equals(alias)) {
                throw new AwsException("EntityAlreadyExists",
                        "The account alias " + alias + " already exists.", 409);
            }
            accountAliases.put(ACCOUNT_ALIAS_KEY, alias);
        }
        LOG.infov("Set IAM account alias: {0}", alias);
    }

    /**
     * AWS requires the caller to name the alias being removed and rejects a mismatch, so a stale
     * value in a delete call cannot silently clear the current alias.
     */
    public void deleteAccountAlias(String alias) {
        // AWS applies the same pattern constraint to delete as to create, so a malformed value is
        // a ValidationError rather than a miss.
        validateAccountAlias(alias);
        synchronized (accountAliasLock) {
            String existing = accountAliases.get(ACCOUNT_ALIAS_KEY)
                    .orElseThrow(() -> new AwsException("NoSuchEntity",
                            "The account alias " + alias + " cannot be found.", 404));
            if (!existing.equals(alias)) {
                throw new AwsException("NoSuchEntity",
                        "The account alias " + alias + " cannot be found.", 404);
            }
            accountAliases.delete(ACCOUNT_ALIAS_KEY);
        }
        LOG.infov("Deleted IAM account alias: {0}", alias);
    }

    private void validateAccountAlias(String alias) {
        if (alias == null || !ACCOUNT_ALIAS_PATTERN.matcher(alias).matches()) {
            throw new AwsException("ValidationError",
                    "The specified value for accountAlias is invalid. It must be a minimum length of 3 "
                            + "characters and maximum length of 63 characters, contain only digits, lowercase "
                            + "letters, and hyphens (-), but cannot begin or end with a hyphen.", 400);
        }
    }

    // =========================================================================
    // Account Password Policy
    // =========================================================================

    public Optional<AccountPasswordPolicy> getAccountPasswordPolicy() {
        return passwordPolicies.get(ACCOUNT_PASSWORD_POLICY_KEY);
    }

    /**
     * Unlike the account alias, an account password policy is set wholesale — every call replaces
     * the stored policy rather than merging into it, matching AWS: fields the caller omits reset to
     * their documented defaults instead of carrying over the previous policy's value.
     */
    public AccountPasswordPolicy updateAccountPasswordPolicy(AccountPasswordPolicy policy) {
        validateAccountPasswordPolicy(policy);
        passwordPolicies.put(ACCOUNT_PASSWORD_POLICY_KEY, policy);
        LOG.infov("Updated IAM account password policy");
        return policy;
    }

    /**
     * AWS raises NoSuchEntity when no custom policy has ever been set, rather than treating the
     * delete as a no-op — DeleteAccountAlias's mismatch case is the same shape of "there is nothing
     * here to remove."
     */
    public void deleteAccountPasswordPolicy() {
        if (passwordPolicies.get(ACCOUNT_PASSWORD_POLICY_KEY).isEmpty()) {
            throw new AwsException("NoSuchEntity",
                    "The account policy with name PasswordPolicy cannot be found.", 404);
        }
        passwordPolicies.delete(ACCOUNT_PASSWORD_POLICY_KEY);
        LOG.infov("Deleted IAM account password policy");
    }

    private void validateAccountPasswordPolicy(AccountPasswordPolicy policy) {
        int minLength = policy.getMinimumPasswordLength();
        if (minLength < MIN_PASSWORD_LENGTH_FLOOR || minLength > MIN_PASSWORD_LENGTH_CEILING) {
            throw new AwsException("ValidationError",
                    "MinimumPasswordLength must be between " + MIN_PASSWORD_LENGTH_FLOOR + " and "
                            + MIN_PASSWORD_LENGTH_CEILING + ".", 400);
        }
        Integer maxAge = policy.getMaxPasswordAge();
        if (maxAge != null && (maxAge < MAX_PASSWORD_AGE_FLOOR || maxAge > MAX_PASSWORD_AGE_CEILING)) {
            throw new AwsException("ValidationError",
                    "MaxPasswordAge must be between " + MAX_PASSWORD_AGE_FLOOR + " and "
                            + MAX_PASSWORD_AGE_CEILING + ".", 400);
        }
        Integer reusePrevention = policy.getPasswordReusePrevention();
        if (reusePrevention != null
                && (reusePrevention < PASSWORD_REUSE_PREVENTION_FLOOR
                        || reusePrevention > PASSWORD_REUSE_PREVENTION_CEILING)) {
            throw new AwsException("ValidationError",
                    "PasswordReusePrevention must be between " + PASSWORD_REUSE_PREVENTION_FLOOR + " and "
                            + PASSWORD_REUSE_PREVENTION_CEILING + ".", 400);
        }
    }

    // =========================================================================
    // Login Profiles
    // =========================================================================

    /** A user holds at most one login profile: creating a second is EntityAlreadyExists. */
    public LoginProfile createLoginProfile(String userName, String password, boolean passwordResetRequired) {
        getUser(userName);
        if (loginProfiles.get(userName).isPresent()) {
            throw new AwsException("EntityAlreadyExists",
                    "Login Profile for User " + userName + " already exists.", 409);
        }
        validateLoginProfilePassword(password);
        LoginProfile profile = new LoginProfile(userName, password, passwordResetRequired);
        loginProfiles.put(userName, profile);
        LOG.infov("Created IAM login profile for user: {0}", userName);
        return profile;
    }

    public LoginProfile getLoginProfile(String userName) {
        getUser(userName);
        return loginProfiles.get(userName)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "Login Profile for User " + userName + " cannot be found.", 404));
    }

    /**
     * Replaces the fields the caller supplies; unlike {@link #updateAccountPasswordPolicy}, an
     * omitted field here carries over its previous value rather than resetting to a default.
     * {@code Password} and {@code PasswordResetRequired} are independently optional on AWS's
     * {@code UpdateLoginProfile}, so a caller rotating only the password must not accidentally
     * clear the reset-required flag, and vice versa.
     */
    public void updateLoginProfile(String userName, String password, Boolean passwordResetRequired) {
        LoginProfile profile = getLoginProfile(userName);
        if (password != null) {
            validateLoginProfilePassword(password);
            profile.setPassword(password);
            profile.setPasswordLastChanged(Instant.now());
        }
        if (passwordResetRequired != null) {
            profile.setPasswordResetRequired(passwordResetRequired);
        }
        loginProfiles.put(userName, profile);
        LOG.infov("Updated IAM login profile for user: {0}", userName);
    }

    public void deleteLoginProfile(String userName) {
        getUser(userName);
        if (loginProfiles.get(userName).isEmpty()) {
            throw new AwsException("NoSuchEntity",
                    "Login Profile for User " + userName + " cannot be found.", 404);
        }
        loginProfiles.delete(userName);
        LOG.infov("Deleted IAM login profile for user: {0}", userName);
    }

    /**
     * Base wire validation shared by Create/UpdateLoginProfile, then, only when the account has
     * ever set one (matching {@link #getAccountPasswordPolicy}'s own NoSuchEntity-by-default
     * semantics), the account's password policy.
     */
    private void validateLoginProfilePassword(String password) {
        if (password == null || !LOGIN_PROFILE_PASSWORD_PATTERN.matcher(password).matches()) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'password' failed to satisfy constraint: "
                            + "Member must have length less than or equal to 128 and greater than or "
                            + "equal to 1", 400);
        }
        getAccountPasswordPolicy().ifPresent(policy -> checkPasswordAgainstPolicy(password, policy));
    }

    private void checkPasswordAgainstPolicy(String password, AccountPasswordPolicy policy) {
        List<String> violations = new ArrayList<>();
        if (password.length() < policy.getMinimumPasswordLength()) {
            violations.add("a minimum length of " + policy.getMinimumPasswordLength());
        }
        if (policy.isRequireUppercaseCharacters() && password.chars().noneMatch(Character::isUpperCase)) {
            violations.add("at least one uppercase letter");
        }
        if (policy.isRequireLowercaseCharacters() && password.chars().noneMatch(Character::isLowerCase)) {
            violations.add("at least one lowercase letter");
        }
        if (policy.isRequireNumbers() && password.chars().noneMatch(Character::isDigit)) {
            violations.add("at least one number");
        }
        if (policy.isRequireSymbols() && password.chars().allMatch(Character::isLetterOrDigit)) {
            violations.add("at least one non-alphanumeric character");
        }
        if (!violations.isEmpty()) {
            throw new AwsException("PasswordPolicyViolation",
                    "Password does not conform to the account password policy. It must contain: "
                            + String.join(", ", violations) + ".", 400);
        }
    }

    private void validateIamResourceName(String value, String paramName) {
        if (value == null || !IAM_RESOURCE_NAME_PATTERN.matcher(value).matches()) {
            throw new AwsException("ValidationError",
                    paramName + " must be 1-128 characters matching [\\w+=,.@-].", 400);
        }
    }

    private void validateIamPath(String value, String paramName) {
        if (value == null) return;
        if (value.length() > IAM_PATH_MAX_LENGTH || !IAM_PATH_PATTERN.matcher(value).matches()) {
            throw new AwsException("ValidationError",
                    paramName + " must be at most " + IAM_PATH_MAX_LENGTH
                            + " characters, either a bare forward slash or a string that begins and ends "
                            + "with a forward slash.", 400);
        }
    }

    // OIDC Identity Providers
    // =========================================================================

    /**
     * AWS identifies a provider by URL, so the ARN is derived from it rather than from a random
     * id, and the scheme is stripped: {@code https://host/path} becomes
     * {@code arn:aws:iam::<account>:oidc-provider/host/path}. Creating the same URL twice is
     * therefore a duplicate resource, not a second provider.
     */
    public OpenIDConnectProvider createOpenIDConnectProvider(String url, List<String> clientIdList,
                                                             List<String> thumbprintList,
                                                             Map<String, String> providerTags) {
        if (url == null || url.isBlank()) {
            throw new AwsException("ValidationError", "The request must contain the parameter Url.", 400);
        }
        if (!url.startsWith("https://")) {
            throw new AwsException("ValidationError",
                    "The OpenID Connect provider URL must begin with https://.", 400);
        }
        if (url.length() > MAX_OIDC_URL_LENGTH) {
            throw new AwsException("ValidationError",
                    "The OpenID Connect provider URL must be at most "
                            + MAX_OIDC_URL_LENGTH + " characters.", 400);
        }
        String normalizedUrl = url.substring("https://".length());
        if (normalizedUrl.isBlank()) {
            throw new AwsException("ValidationError", "The OpenID Connect provider URL is not valid.", 400);
        }
        if (thumbprintList != null && thumbprintList.size() > MAX_OIDC_THUMBPRINTS) {
            throw new AwsException("InvalidInput",
                    "Thumbprint list must contain fewer than " + MAX_OIDC_THUMBPRINTS + " entries.", 400);
        }
        if (clientIdList != null && clientIdList.size() > MAX_OIDC_CLIENT_IDS) {
            throw new AwsException("LimitExceeded",
                    "Cannot exceed quota for ClientIdsPerOpenIdConnectProvider: " + MAX_OIDC_CLIENT_IDS, 409);
        }

        String arn = iamArn("oidc-provider", "/", normalizedUrl);
        OpenIDConnectProvider provider = new OpenIDConnectProvider();
        provider.setArn(arn);
        provider.setUrl(normalizedUrl);
        provider.setClientIdList(clientIdList == null ? new ArrayList<>() : new ArrayList<>(clientIdList));
        provider.setThumbprintList(thumbprintList == null ? new ArrayList<>() : new ArrayList<>(thumbprintList));
        provider.setCreateDate(Instant.now());
        if (providerTags != null && !providerTags.isEmpty()) {
            provider.setTags(providerTags);
        }
        // Racing creates collide only on the same URL, but they can carry different client IDs,
        // thumbprints and tags, so an unguarded check-then-write would report success to every
        // caller while storing one arbitrary payload.
        synchronized (oidcProviderLock) {
            if (oidcProviders.get(arn).isPresent()) {
                throw new AwsException("EntityAlreadyExists",
                        "Provider with url " + url + " already exists.", 409);
            }
            oidcProviders.put(arn, provider);
        }
        LOG.infov("Created OIDC provider: {0}", arn);
        return provider;
    }

    public OpenIDConnectProvider getOpenIDConnectProvider(String arn) {
        requireProviderArn(arn);
        return oidcProviders.get(arn)
                .orElseThrow(() -> new AwsException("NoSuchEntity",
                        "OpenIDConnect Provider not found for arn " + arn, 404));
    }

    public List<OpenIDConnectProvider> listOpenIDConnectProviders() {
        return oidcProviders.scan(k -> true);
    }

    public void deleteOpenIDConnectProvider(String arn) {
        requireProviderArn(arn);
        synchronized (oidcProviderLock) {
            if (oidcProviders.get(arn).isEmpty()) {
                throw new AwsException("NoSuchEntity",
                        "OpenId connect Provider " + arn + " cannot be found.", 404);
            }
            oidcProviders.delete(arn);
        }
        LOG.infov("Deleted OIDC provider: {0}", arn);
    }

    public void addClientIdToOpenIDConnectProvider(String arn, String clientId) {
        requireProviderArn(arn);
        requireClientId(clientId);
        synchronized (oidcProviderLock) {
            OpenIDConnectProvider provider = getOpenIDConnectProvider(arn);
            // AWS treats adding a client ID that is already present as a no-op success, so this
            // returns rather than reporting a conflict.
            if (provider.getClientIdList().contains(clientId)) {
                return;
            }
            if (provider.getClientIdList().size() >= MAX_OIDC_CLIENT_IDS) {
                throw new AwsException("LimitExceeded",
                        "Cannot exceed quota for ClientIdsPerOpenIdConnectProvider: " + MAX_OIDC_CLIENT_IDS, 409);
            }
            List<String> updated = new ArrayList<>(provider.getClientIdList());
            updated.add(clientId);
            provider.setClientIdList(updated);
            oidcProviders.put(arn, provider);
        }
    }

    public void removeClientIdFromOpenIDConnectProvider(String arn, String clientId) {
        requireProviderArn(arn);
        requireClientId(clientId);
        synchronized (oidcProviderLock) {
            OpenIDConnectProvider provider = getOpenIDConnectProvider(arn);
            // Removing a client ID that is not present is a no-op success on AWS, not an error.
            if (!provider.getClientIdList().contains(clientId)) {
                return;
            }
            List<String> updated = new ArrayList<>(provider.getClientIdList());
            updated.remove(clientId);
            provider.setClientIdList(updated);
            oidcProviders.put(arn, provider);
        }
    }

    private void requireProviderArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationError",
                    "The request must contain the parameter OpenIDConnectProviderArn.", 400);
        }
    }

    private void requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new AwsException("ValidationError",
                    "The request must contain the parameter ClientID.", 400);
        }
    }

    public void updateOpenIDConnectProviderThumbprint(String arn, List<String> thumbprintList) {
        requireProviderArn(arn);
        if (thumbprintList == null || thumbprintList.isEmpty()) {
            throw new AwsException("ValidationError",
                    "The request must contain the parameter ThumbprintList.", 400);
        }
        if (thumbprintList.size() > MAX_OIDC_THUMBPRINTS) {
            throw new AwsException("InvalidInput",
                    "Thumbprint list must contain fewer than " + MAX_OIDC_THUMBPRINTS + " entries.", 400);
        }
        synchronized (oidcProviderLock) {
            OpenIDConnectProvider provider = getOpenIDConnectProvider(arn);
            provider.setThumbprintList(new ArrayList<>(thumbprintList));
            oidcProviders.put(arn, provider);
        }
    }

    // AWS rejects an empty tag map rather than treating it as a no-op, and reports InvalidInput
    // rather than ValidationError. Both verified against a live account.
    public void tagOpenIDConnectProvider(String arn, Map<String, String> newTags) {
        requireProviderArn(arn);
        if (newTags == null || newTags.isEmpty()) {
            throw new AwsException("InvalidInput", "The provided tag map must not be null/empty.", 400);
        }
        synchronized (oidcProviderLock) {
            OpenIDConnectProvider provider = getOpenIDConnectProvider(arn);
            provider.setTags(mergeTagsWithinQuota(provider.getTags(), newTags, "TagsPerOpenIdConnectProvider"));
            oidcProviders.put(arn, provider);
        }
    }

    public void untagOpenIDConnectProvider(String arn, List<String> tagKeys) {
        requireProviderArn(arn);
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("InvalidInput", "The provided tag keys must not be null/empty.", 400);
        }
        synchronized (oidcProviderLock) {
            OpenIDConnectProvider provider = getOpenIDConnectProvider(arn);
            Map<String, String> remaining = new LinkedHashMap<>(provider.getTags());
            tagKeys.forEach(remaining::remove);
            provider.setTags(remaining);
            oidcProviders.put(arn, provider);
        }
    }

    public Map<String, String> listOpenIDConnectProviderTags(String arn) {
        requireProviderArn(arn);
        return getOpenIDConnectProvider(arn).getTags();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Returns the secret for an active access key or an unexpired temporary session. */
    public Optional<String> findSecretKey(String accessKeyId) {
        return activeAccessKeySecret(accessKeyId)
                .or(() -> currentSession(accessKeyId).map(SessionCredential::getSecretAccessKey));
    }

    /** Returns a temporary session secret only when the issued session token also matches. */
    public Optional<String> findSecretKey(String accessKeyId, String sessionToken) {
        return activeAccessKeySecret(accessKeyId).or(() -> currentSession(accessKeyId)
                .filter(session -> hasMatchingSessionToken(session, sessionToken))
                .map(SessionCredential::getSecretAccessKey));
    }

    /**
     * The session token issued alongside a temporary credential, for callers that must tell a
     * missing token apart from one that does not match.
     *
     * <p>Empty means the token cannot be verified, not that any token is acceptable: the key is
     * not a temporary credential, names no session Floci minted, or names a session stored before
     * tokens were recorded. A caller that treats the token as required must check
     * {@link #isTemporaryAccessKey(String)} separately. Where that distinction does not matter,
     * {@link #findSecretKey(String, String)} resolves the secret and matches the token in one step.
     */
    public Optional<String> findSessionToken(String accessKeyId) {
        return currentSession(accessKeyId)
                .map(SessionCredential::getSessionToken)
                .filter(token -> !token.isBlank());
    }

    /** Whether an access key ID identifies Floci's documented public deployer credential. */
    public boolean isSeededDeployerAccessKey(String accessKeyId) {
        return DEFAULT_DEPLOYER_ACCESS_KEY_ID.equals(accessKeyId);
    }

    private Optional<String> activeAccessKeySecret(String accessKeyId) {
        return accessKeys.get(accessKeyId)
                .filter(accessKey -> "Active".equals(accessKey.getStatus()))
                .map(AccessKey::getSecretAccessKey);
    }

    public record EksSessionIdentity(String accountId, String roleArn, String roleId, String instanceId) {}

    /** Returns only identity metadata, never the session secret or token. */
    public Optional<EksSessionIdentity> findEksSessionIdentity(String accessKeyId) {
        return currentSession(accessKeyId).map(session -> new EksSessionIdentity(
                session.getOriginAccountId(), session.getRoleArn(), session.getEc2RoleId(), session.getEc2InstanceId()));
    }

    private Optional<SessionCredential> currentSession(String accessKeyId) {
        return findSessionAnyAccount(accessKeyId)
                .filter(session -> session.getExpiration() == null || Instant.now().isBefore(session.getExpiration()));
    }

    private static boolean hasMatchingSessionToken(SessionCredential session, String sessionToken) {
        if (sessionToken == null || session.getSessionToken() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                session.getSessionToken().getBytes(StandardCharsets.UTF_8),
                sessionToken.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<AccessKey> findAccessKey(String accessKeyId) {
        return accessKeys.get(accessKeyId);
    }

    public Optional<IamUser> findUser(String userName) {
        return users.get(userName);
    }

    /**
     * Looks up a user by name in a specific account's namespace, without throwing when absent.
     * Mirrors {@link #findRole(String, String)} — users are account-namespaced the same way roles
     * are, so a caller resolving a user from an ARN must use that ARN's account, not the ambient one.
     */
    public Optional<IamUser> findUser(String accountId, String userName) {
        if (users instanceof AccountAwareStorageBackend<IamUser> aware) {
            return aware.getForAccount(accountId, userName);
        }
        return users.get(userName);
    }

    // =========================================================================
    // IAM Enforcement — session tracking and policy collection
    // =========================================================================

    /**
     * Stores an assumed-role session so the enforcement filter can resolve its policies.
     */
    public void registerSession(String sessionAccessKeyId, String roleArn, Instant expiration) {
        sessions.put(sessionAccessKeyId, new SessionCredential(sessionAccessKeyId, roleArn, expiration));
    }

    /**
     * Stores an assumed-role session with an optional inline session policy document.
     */
    public void registerSession(String sessionAccessKeyId, String roleArn, Instant expiration,
                                String sessionPolicyDocument) {
        sessions.put(sessionAccessKeyId,
                new SessionCredential(sessionAccessKeyId, roleArn, expiration, sessionPolicyDocument));
    }

    /**
     * Stores an assumed-role session including the temporary secret access key. Token-aware
     * authentication paths use the overload that also records the session token.
     */
    public void registerSession(String sessionAccessKeyId, String secretAccessKey, String roleArn,
                                Instant expiration, String sessionPolicyDocument) {
        registerSession(sessionAccessKeyId, secretAccessKey, null, roleArn, expiration, sessionPolicyDocument);
    }

    /** Stores a temporary credential including the session token required for authentication. */
    public void registerSession(String sessionAccessKeyId, String secretAccessKey, String sessionToken,
                                String roleArn, Instant expiration, String sessionPolicyDocument) {
        sessions.put(sessionAccessKeyId,
                new SessionCredential(sessionAccessKeyId, secretAccessKey, sessionToken, roleArn,
                        expiration, sessionPolicyDocument));
    }

    /**
     * Stores an assumed-role session and records {@code originAccountId} — the account of the
     * caller that minted it. The origin lets {@link #resolveAccountId(String)} route temporary
     * credentials that carry no role ARN (e.g. GetSessionToken) back to the caller's account.
     */
    public void registerSession(String sessionAccessKeyId, String secretAccessKey, String roleArn,
                                Instant expiration, String sessionPolicyDocument,
                                String originAccountId) {
        registerSession(sessionAccessKeyId, secretAccessKey, null, roleArn, expiration, sessionPolicyDocument,
                originAccountId);
    }

    /** Stores a temporary credential and its origin account. */
    public void registerSession(String sessionAccessKeyId, String secretAccessKey, String sessionToken,
                                String roleArn, Instant expiration, String sessionPolicyDocument,
                                String originAccountId) {
        registerSession(sessionAccessKeyId, secretAccessKey, sessionToken, roleArn, expiration,
                sessionPolicyDocument, originAccountId, null, null);
    }

    /** Stores the identity returned to the caller when STS creates an assumed-role session. */
    public void registerSession(String sessionAccessKeyId, String secretAccessKey, String sessionToken,
                                String roleArn, Instant expiration, String sessionPolicyDocument,
                                String originAccountId, String roleSessionName, String assumedRoleId) {
        SessionCredential session = new SessionCredential(sessionAccessKeyId, secretAccessKey, sessionToken,
                roleArn, expiration, sessionPolicyDocument, originAccountId);
        session.setRoleSessionName(roleSessionName);
        session.setAssumedRoleId(assumedRoleId);
        sessions.put(sessionAccessKeyId, session);
    }

    /** Stores a temporary session in an explicit account namespace. */
    public void registerSessionForAccount(String accountId, String sessionAccessKeyId, String secretAccessKey,
                                          String roleArn, Instant expiration,
                                          String sessionPolicyDocument) {
        registerSessionForAccount(accountId, sessionAccessKeyId, secretAccessKey, null, roleArn, expiration,
                sessionPolicyDocument);
    }

    /** Stores a temporary credential in an explicit account namespace. */
    public void registerSessionForAccount(String accountId, String sessionAccessKeyId, String secretAccessKey,
                                          String sessionToken, String roleArn, Instant expiration,
                                          String sessionPolicyDocument) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Session account ID must not be blank");
        }
        SessionCredential session = new SessionCredential(
                sessionAccessKeyId, secretAccessKey, sessionToken, roleArn, expiration, sessionPolicyDocument,
                accountId);
        if (sessions instanceof AccountAwareStorageBackend<SessionCredential> aware) {
            aware.putForAccount(accountId, sessionAccessKeyId, session);
        } else {
            sessions.put(sessionAccessKeyId, session);
        }
    }

    /**
     * Stores a non-expiring session for a Lambda execution role under the function's account.
     * Lambda launches can happen outside request scope, so the account namespace must be explicit.
     */
    public void registerLambdaExecutionRoleSession(String accountId, String sessionAccessKeyId,
                                                   String secretAccessKey, String roleArn) {
        registerLambdaExecutionRoleSession(accountId, sessionAccessKeyId, secretAccessKey, null, roleArn);
    }

    /** Stores a non-expiring Lambda execution-role session with its session token. */
    public void registerLambdaExecutionRoleSession(String accountId, String sessionAccessKeyId,
                                                   String secretAccessKey, String sessionToken, String roleArn) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Lambda function account ID must not be blank");
        }
        SessionCredential session = new SessionCredential(
                sessionAccessKeyId, secretAccessKey, sessionToken, roleArn, null, null, accountId);
        session.setLambdaExecutionRole(true);
        if (sessions instanceof AccountAwareStorageBackend<SessionCredential> aware) {
            aware.putForAccount(accountId, sessionAccessKeyId, session);
        } else {
            sessions.put(sessionAccessKeyId, session);
        }
        LOG.debugv("Registered Lambda execution-role session {0} under account {1} for {2}",
                sessionAccessKeyId, accountId, roleArn);
    }

    /** Registers an IMDS session in the profile's account, outside request scope. */
    public void registerEc2InstanceSession(SessionCredential session) {
        if (session.getOriginAccountId() == null || session.getOriginAccountId().isBlank()
                || session.getEc2InstanceId() == null || session.getEc2InstanceId().isBlank()) {
            throw new IllegalArgumentException("EC2 session account and instance ID must not be blank");
        }
        if (sessions instanceof AccountAwareStorageBackend<SessionCredential> aware) {
            aware.putForAccount(session.getOriginAccountId(), session.getAccessKeyId(), session);
        } else {
            sessions.put(session.getAccessKeyId(), session);
        }
    }

    /** IMDS registrations are rebuilt on startup; discard credentials from the previous server. */
    public int sweepOrphanedEc2InstanceSessions() {
        List<SessionCredential> stored = sessions instanceof AccountAwareStorageBackend<SessionCredential> aware
                ? aware.scanAllAccounts() : sessions.scan(key -> true);
        int removed = 0;
        for (SessionCredential session : stored) {
            if (session.getEc2InstanceId() != null) {
                deleteSession(session.getAccessKeyId(), session);
                removed++;
            }
        }
        return removed;
    }

    /** Registers an ECS task-role session, outside request scope. */
    public void registerEcsTaskRoleSession(SessionCredential session) {
        if (session.getOriginAccountId() == null || session.getOriginAccountId().isBlank()
                || session.getEcsTaskArn() == null || session.getEcsTaskArn().isBlank()) {
            throw new IllegalArgumentException("ECS task session account and task ARN must not be blank");
        }
        if (sessions instanceof AccountAwareStorageBackend<SessionCredential> aware) {
            aware.putForAccount(session.getOriginAccountId(), session.getAccessKeyId(), session);
        } else {
            sessions.put(session.getAccessKeyId(), session);
        }
    }

    /** No ECS task survives a Floci restart; discard credentials from the previous process. */
    public int sweepOrphanedEcsTaskRoleSessions() {
        List<SessionCredential> stored = sessions instanceof AccountAwareStorageBackend<SessionCredential> aware
                ? aware.scanAllAccounts() : sessions.scan(key -> true);
        int removed = 0;
        for (SessionCredential session : stored) {
            if (session.getEcsTaskArn() != null) {
                deleteSession(session.getAccessKeyId(), session);
                removed++;
            }
        }
        return removed;
    }

    /** Removes a session from an explicit account namespace. */
    public void unregisterSession(String accountId, String sessionAccessKeyId) {
        if (sessionAccessKeyId == null || sessionAccessKeyId.isBlank()) {
            return;
        }
        if (accountId != null && !accountId.isBlank()
                && sessions instanceof AccountAwareStorageBackend<SessionCredential> aware) {
            aware.deleteForAccount(accountId, sessionAccessKeyId);
        } else {
            sessions.delete(sessionAccessKeyId);
        }
        LOG.debugv("Unregistered session {0} from account {1}", sessionAccessKeyId, accountId);
    }

    /**
     * Removes persisted Lambda-owned sessions left behind by a previous process. No Lambda
     * containers survive a Floci restart, so every marked session is orphaned at startup.
     */
    public int sweepOrphanedLambdaExecutionRoleSessions() {
        List<SessionCredential> storedSessions = sessions instanceof AccountAwareStorageBackend<SessionCredential> aware
                ? aware.scanAllAccounts()
                : sessions.scan(key -> true);
        int removed = 0;
        for (SessionCredential session : storedSessions) {
            if (!session.isLambdaExecutionRole()) {
                continue;
            }
            deleteSession(session.getAccessKeyId(), session);
            removed++;
        }
        return removed;
    }

    /**
     * Resolves the account an IAM or temporary access key belongs to. Long-term IAM access keys
     * resolve from their owning account namespace. Temporary credentials resolve from the account
     * encoded in the session's role (or federated-user) ARN when present, otherwise the caller
     * account captured at mint time. Returns empty for unknown, inactive, or expired credentials.
     */
    @Override
    public Optional<String> resolveAccountId(String accessKeyId) {
        if (!isTemporaryAccessKey(accessKeyId)) {
            if (accessKeyId == null || !(accessKeys instanceof AccountAwareStorageBackend<AccessKey> aware)) {
                return Optional.empty();
            }
            return aware.scanAllAccountEntries(accessKeyId::equals).stream()
                    .filter(entry -> "Active".equals(entry.value().getStatus()))
                    .map(AccountAwareStorageBackend.AccountEntry::accountId)
                    .findFirst();
        }
        Optional<SessionCredential> sessionOpt = findSessionAnyAccount(accessKeyId);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }
        SessionCredential session = sessionOpt.get();
        if (session.getExpiration() != null && session.getExpiration().isBefore(Instant.now())) {
            return Optional.empty();
        }
        String account = AwsArnUtils.accountOrDefault(session.getRoleArn(), session.getOriginAccountId());
        return account == null || account.isBlank() ? Optional.empty() : Optional.of(account);
    }

    /**
     * Looks up a session by its temporary access key ID independent of the request's account.
     *
     * <p>Sessions are keyed by a globally-unique access key (e.g. {@code ASIA...}) but stored in
     * the minting account's namespace. Account routing must resolve the session <em>before</em> the
     * request's account is known, so a normal account-scoped {@code get} would miss it. The
     * lookup spans all accounts; the access key's global uniqueness keeps the result unambiguous.
     */
    private Optional<SessionCredential> findSessionAnyAccount(String accessKeyId) {
        if (!isTemporaryAccessKey(accessKeyId)) {
            return Optional.empty();
        }
        if (sessions instanceof AccountAwareStorageBackend<SessionCredential> aware) {
            return aware.findAnyAccount(accessKeyId);
        }
        return sessions.get(accessKeyId);
    }

    /**
     * Resolves the full caller context for the given access key, including identity policies,
     * optional session policy, and optional permission boundary.
     *
     * <p>Returns {@code null} if the access key is unknown (bypass — backward-compatible).
     */
    public CallerContext resolveCallerContext(String accessKeyId) {
        // Check user access keys
        Optional<AccessKey> akOpt = accessKeys.get(accessKeyId);
        if (akOpt.isPresent()) {
            String userName = akOpt.get().getUserName();
            List<String> identityPolicies = collectUserPolicies(userName);
            String boundaryDoc = resolveUserBoundaryDocument(userName);
            return new CallerContext(identityPolicies, null, boundaryDoc);
        }

        // Check assumed-role sessions. These can be stored under the account that minted the
        // session, while request routing for temporary credentials uses the role's account.
        Optional<SessionCredential> sessionOpt = findSessionForCallerContext(accessKeyId);
        if (sessionOpt.isPresent()) {
            SessionCredential session = sessionOpt.get();
            if (session.getExpiration() != null && session.getExpiration().isBefore(Instant.now())) {
                deleteSession(accessKeyId, session);
                return null; // expired — unknown key → bypass
            }

            if (session.getRoleArn() == null) {
                return null; // identity session without mapped caller context — preserve historical bypass
            }
            List<String> identityPolicies = collectRolePolicies(session.getRoleArn());
            String boundaryDoc = resolveRoleBoundaryDocument(session.getRoleArn());
            return new CallerContext(identityPolicies, session.getSessionPolicyDocument(), boundaryDoc);
        }

        // Unknown key — bypass
        return null;
    }

    /**
     * True when this access key exists anywhere in the emulator: an IAM user's long-term key in
     * any account, or a session credential.
     *
     * <p>{@link #resolveCallerContext} returns null both for a key that does not exist and for a
     * key it cannot map to policies (a session carrying no role ARN). Only the first of those is
     * an unauthenticated caller, so enforcement needs to tell them apart. The lookup spans every
     * account deliberately: a key belonging to another account is a real credential, and denying
     * it here would be a false rejection rather than a closed hole.
     */
    public boolean isKnownAccessKey(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return false;
        }
        if (findSessionForCallerContext(accessKeyId).isPresent()) {
            return true;
        }
        if (accessKeys.get(accessKeyId).isPresent()) {
            return true;
        }
        return accessKeys instanceof AccountAwareStorageBackend<AccessKey> aware
                && !aware.scanAllAccountEntries(accessKeyId::equals).isEmpty();
    }

    private Optional<SessionCredential> findSessionForCallerContext(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return Optional.empty();
        }
        Optional<SessionCredential> session = sessions.get(accessKeyId);
        if (session.isPresent()) {
            return session;
        }
        if (!isTemporaryAccessKey(accessKeyId)) {
            return Optional.empty();
        }
        return findSessionAnyAccount(accessKeyId);
    }

    /**
     * Collects all identity-based policy documents applicable to the caller identified
     * by {@code accessKeyId}.
     *
     * <p>Returns {@code null} if the access key is unknown (bypass — backward-compatible).
     * Returns an empty list if the key is known but has no policies attached (implicit deny).
     *
     * <p>Order: inline policies first, then attached managed policies.
     */
    public List<String> resolveCallerPolicies(String accessKeyId) {
        CallerContext ctx = resolveCallerContext(accessKeyId);
        return ctx == null ? null : ctx.identityPolicies();
    }

    public Optional<String> resolveCallerArn(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return Optional.empty();
        }

        Optional<AccessKey> akOpt = accessKeys.get(accessKeyId);
        if (akOpt.isPresent()) {
            String userName = akOpt.get().getUserName();
            return users.get(userName).map(IamUser::getArn);
        }

        Optional<SessionCredential> sessionOpt = findSessionForCallerContext(accessKeyId);
        if (sessionOpt.isPresent()) {
            SessionCredential session = sessionOpt.get();
            if (session.getExpiration() != null && session.getExpiration().isBefore(Instant.now())) {
                deleteSession(accessKeyId, session);
                return Optional.empty();
            }
            String roleArn = session.getRoleArn();
            if (roleArn == null) {
                return Optional.empty();
            }
            String roleName = roleArn.contains("/") ? roleArn.substring(roleArn.lastIndexOf('/') + 1) : "UnknownRole";
            String accountId = AwsArnUtils.accountOrDefault(roleArn, regionResolver.getAccountId());
            String sessionName = session.getRoleSessionName();
            if (sessionName == null) {
                sessionName = session.getEc2InstanceId() != null
                        ? session.getEc2InstanceId() : "floci-session";
            }
            return Optional.of(AwsArnUtils.Arn.of("sts", "", accountId, "assumed-role/" + roleName + "/"
                    + sessionName).toString());
        }

        return Optional.empty();
    }

    public Optional<String> resolveCallerUserId(String accessKeyId) {
        Optional<SessionCredential> sessionOpt = findSessionForCallerContext(accessKeyId);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }
        SessionCredential session = sessionOpt.get();
        if (session.getExpiration() != null && session.getExpiration().isBefore(Instant.now())) {
            deleteSession(accessKeyId, session);
            return Optional.empty();
        }
        return Optional.ofNullable(session.getAssumedRoleId());
    }

    /** Temporary credentials are the ones STS mints, distinguished by the {@code ASIA} prefix. */
    public static boolean isTemporaryAccessKey(String accessKeyId) {
        return accessKeyId != null && accessKeyId.startsWith(TEMPORARY_ACCESS_KEY_PREFIX);
    }

    private void deleteSession(String accessKeyId, SessionCredential session) {
        String originAccountId = session.getOriginAccountId();
        if (originAccountId != null && !originAccountId.isBlank()
                && sessions instanceof AccountAwareStorageBackend<SessionCredential> aware) {
            aware.deleteForAccount(originAccountId, accessKeyId);
            return;
        }
        sessions.delete(accessKeyId);
    }

    public CallerContext resolvePrincipalContext(String principalArn) {
        if (principalArn == null || principalArn.isBlank()) {
            throw new AwsException("ValidationError", "PolicySourceArn is required.", 400);
        }
        if (principalArn.contains(":user/")) {
            String userName = principalArn.substring(principalArn.lastIndexOf('/') + 1);
            List<String> identityPolicies = collectUserPolicies(principalArn);
            if (identityPolicies == null) {
                throw new AwsException("NoSuchEntity", "User " + userName + " cannot be found.", 404);
            }
            return new CallerContext(identityPolicies, null, resolveUserBoundaryDocument(principalArn));
        }
        if (principalArn.contains(":role/")) {
            String roleName = principalArn.substring(principalArn.lastIndexOf('/') + 1);
            List<String> identityPolicies = collectRolePolicies(principalArn);
            if (identityPolicies == null) {
                throw new AwsException("NoSuchEntity", "Role " + roleName + " cannot be found.", 404);
            }
            return new CallerContext(identityPolicies, null, resolveRoleBoundaryDocument(principalArn));
        }
        throw new AwsException("InvalidInput", "PolicySourceArn must identify an IAM user or role.", 400);
    }

    private String resolveUserBoundaryDocument(String userArnOrName) {
        return userFromArnOrName(userArnOrName)
                .map(IamUser::getPermissionsBoundaryArn)
                .flatMap(this::resolvePolicy)
                .map(IamPolicy::getDefaultDocument)
                .orElse(null);
    }

    private String resolveRoleBoundaryDocument(String roleArn) {
        if (roleArn == null) {
            return null;
        }
        return roleFromArnOrName(roleArn)
                .map(IamRole::getPermissionsBoundaryArn)
                .flatMap(this::resolvePolicy)
                .map(IamPolicy::getDefaultDocument)
                .orElse(null);
    }

    // =========================================================================
    // Permission Boundaries
    // =========================================================================

    public void putUserPermissionsBoundary(String userName, String permissionsBoundaryArn) {
        requirePolicy(permissionsBoundaryArn); // validate policy exists
        IamUser user = getUser(userName);
        user.setPermissionsBoundaryArn(permissionsBoundaryArn);
        users.put(userName, user);
        LOG.infov("Set permissions boundary for user {0}: {1}", userName, permissionsBoundaryArn);
    }

    public void deleteUserPermissionsBoundary(String userName) {
        IamUser user = getUser(userName);
        if (user.getPermissionsBoundaryArn() == null) {
            throw new AwsException("NoSuchEntity",
                    "User " + userName + " does not have a permissions boundary.", 404);
        }
        user.setPermissionsBoundaryArn(null);
        users.put(userName, user);
        LOG.infov("Deleted permissions boundary for user: {0}", userName);
    }

    public void putRolePermissionsBoundary(String roleName, String permissionsBoundaryArn) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        requirePolicy(permissionsBoundaryArn); // validate policy exists
        role.setPermissionsBoundaryArn(permissionsBoundaryArn);
        roles.put(roleName, role);
        LOG.infov("Set permissions boundary for role {0}: {1}", roleName, permissionsBoundaryArn);
    }

    public void deleteRolePermissionsBoundary(String roleName) {
        IamRole role = getRole(roleName);
        requireNotServiceLinked(role, roleName);
        if (role.getPermissionsBoundaryArn() == null) {
            throw new AwsException("NoSuchEntity",
                    "Role " + roleName + " does not have a permissions boundary.", 404);
        }
        role.setPermissionsBoundaryArn(null);
        roles.put(roleName, role);
        LOG.infov("Deleted permissions boundary for role: {0}", roleName);
    }

    /**
     * Resolves a user for policy collection. Given an ARN, the lookup is scoped to the account the
     * ARN itself names: two accounts can hold a same-named user, and evaluating account A's ARN
     * against account B's policies is a cross-account authorization bypass. Given a bare name —
     * the access-key path, where no ARN exists — the ambient request account still applies.
     */
    private Optional<IamUser> userFromArnOrName(String userArnOrName) {
        if (userArnOrName == null) {
            return Optional.empty();
        }
        if (!userArnOrName.contains("/")) {
            return users.get(userArnOrName);
        }
        String userName = userArnOrName.substring(userArnOrName.lastIndexOf('/') + 1);
        return findUser(AwsArnUtils.accountOrDefault(userArnOrName, regionResolver.getAccountId()), userName);
    }

    /** Role-side counterpart of {@link #userFromArnOrName(String)}. */
    private Optional<IamRole> roleFromArnOrName(String roleArnOrName) {
        if (roleArnOrName == null) {
            return Optional.empty();
        }
        if (!roleArnOrName.contains("/")) {
            return roles.get(roleArnOrName);
        }
        String roleName = roleArnOrName.substring(roleArnOrName.lastIndexOf('/') + 1);
        return findRole(AwsArnUtils.accountOrDefault(roleArnOrName, regionResolver.getAccountId()), roleName);
    }

    private List<String> collectUserPolicies(String userArnOrName) {
        Optional<IamUser> userOpt = userFromArnOrName(userArnOrName);
        if (userOpt.isEmpty()) {
            return null;
        }
        IamUser user = userOpt.get();

        // User inline policies
        List<String> docs = new ArrayList<>(user.getInlinePolicies().values());

        // User attached managed policies
        for (String arn : user.getAttachedPolicyArns()) {
            Optional<IamPolicy> p = resolvePolicy(arn);
            if (p.isPresent() && p.get().getDefaultDocument() != null) {
                docs.add(p.get().getDefaultDocument());
            }
        }

        // Group policies
        for (String groupName : user.getGroupNames()) {
            Optional<IamGroup> groupOpt = groups.get(groupName);
            if (groupOpt.isEmpty()) continue;
            IamGroup group = groupOpt.get();
            docs.addAll(group.getInlinePolicies().values());
            for (String arn : group.getAttachedPolicyArns()) {
                Optional<IamPolicy> p = resolvePolicy(arn);
                if (p.isPresent() && p.get().getDefaultDocument() != null) {
                    docs.add(p.get().getDefaultDocument());
                }
            }
        }

        return docs;
    }

    private List<String> collectRolePolicies(String roleArn) {
        if (roleArn == null) {
            return null;
        }
        Optional<IamRole> roleOpt = roleFromArnOrName(roleArn);
        if (roleOpt.isEmpty()) {
            return null;
        }
        IamRole role = roleOpt.get();
        List<String> docs = new ArrayList<>();

        // Role inline policies
        docs.addAll(role.getInlinePolicies().values());

        // Role attached managed policies
        for (String arn : role.getAttachedPolicyArns()) {
            Optional<IamPolicy> p = resolvePolicy(arn);
            if (p.isPresent() && p.get().getDefaultDocument() != null) {
                docs.add(p.get().getDefaultDocument());
            }
        }

        return docs;
    }

    private String iamArn(String resourceType, String path, String name) {
        return AwsArnUtils.Arn.of("iam", "", regionResolver.getAccountId(), resourceType + path + name).toString();
    }

    private static <T> boolean containsNameIgnoreCase(StorageBackend<String, T> storage,
                                                       Function<T, String> nameExtractor,
                                                       String requestedName) {
        return resourcesInCurrentAccount(storage)
                .map(nameExtractor)
                .anyMatch(existingName -> existingName != null && existingName.equalsIgnoreCase(requestedName));
    }

    private static <T> Stream<T> resourcesInCurrentAccount(StorageBackend<String, T> storage) {
        if (storage instanceof AccountAwareStorageBackend<T> accountAware) {
            String accountId = accountAware.accountId();
            // Include unmigrated legacy names, which belong to the configured default account.
            return accountAware.scanAllAccountEntries(key -> true).stream()
                    .filter(entry -> accountId.equals(entry.accountId()))
                    .map(entry -> entry.value());
        }
        return storage.scan(key -> true).stream();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String p = path;
        if (!p.startsWith("/")) p = "/" + p;
        if (!p.endsWith("/")) p = p + "/";
        return p;
    }

    private static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(ThreadLocalRandom.current().nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String randomSecret(int length) {
        String secretChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(secretChars.charAt(secureRandom.nextInt(secretChars.length())));
        }
        return sb.toString();
    }

    public void tagInstanceProfile(String instanceProfileName, Map<String, String> newTags) {
        // Name shape before resource lookup, matching untagInstanceProfile.
        validateIamResourceName(instanceProfileName, "InstanceProfileName");
        synchronized (tagLock) {
            InstanceProfile profile = getInstanceProfile(instanceProfileName);
            profile.setTags(mergeTagsWithinQuota(profile.getTags(), newTags, "TagsPerInstanceProfile"));
            instanceProfiles.put(instanceProfileName, profile);
        }
    }

    private static Map<String, String> mergeTagsWithinQuota(Map<String, String> current,
            Map<String, String> newTags, String quota) {
        Map<String, String> merged = new LinkedHashMap<>(current);
        merged.putAll(newTags == null ? Map.of() : newTags);
        if (merged.size() > MAX_TAGS_PER_RESOURCE) {
            throw new AwsException("LimitExceeded",
                    "Cannot exceed quota for " + quota + ": " + MAX_TAGS_PER_RESOURCE, 409);
        }
        return merged;
    }

    public void untagInstanceProfile(String instanceProfileName, List<String> tagKeys) {
        validateIamResourceName(instanceProfileName, "InstanceProfileName");
        synchronized (tagLock) {
            InstanceProfile profile = getInstanceProfile(instanceProfileName);
            tagKeys.forEach(profile.getTags()::remove);
            instanceProfiles.put(instanceProfileName, profile);
        }
    }

    public Map<String, String> listInstanceProfileTags(String instanceProfileName) {
        return getInstanceProfile(instanceProfileName).getTags();
    }

    /**
     * Every user, group and role in the account, plus the policies relevant to them: every
     * local (customer-managed) policy, and every AWS-managed policy actually attached to or
     * used as a boundary by something in the account. Backs GetAccountAuthorizationDetails.
     *
     * <p>{@code attachmentCounts} and {@code permissionsBoundaryUsageCounts} are computed by
     * scanning this account's own users, groups and roles, not read off {@link
     * IamPolicy#getAttachmentCount()}. For an AWS-managed policy that field is shared process-wide
     * across every account (see {@link #awsManagedPolicies}), so trusting it here would leak one
     * account's attachments into another's response. A local policy's own counter is already
     * account-scoped and would agree with this scan; computing it uniformly for both avoids
     * special-casing and keeps this method independent of that stored field either way.
     */
    public AccountAuthorizationDetails getAccountAuthorizationDetails() {
        List<IamUser> allUsers = listUsers(null);
        List<IamGroup> allGroups = listGroups(null);
        List<IamRole> allRoles = listRoles(null);

        Map<String, Integer> attachmentCounts = new LinkedHashMap<>();
        Map<String, Integer> boundaryUsageCounts = new LinkedHashMap<>();
        Set<String> referencedAwsManagedArns = new LinkedHashSet<>();

        for (IamUser user : allUsers) {
            tallyAttachments(user.getAttachedPolicyArns(), attachmentCounts, referencedAwsManagedArns);
            tallyBoundaryUsage(user.getPermissionsBoundaryArn(), boundaryUsageCounts, referencedAwsManagedArns);
        }
        for (IamGroup group : allGroups) {
            tallyAttachments(group.getAttachedPolicyArns(), attachmentCounts, referencedAwsManagedArns);
        }
        for (IamRole role : allRoles) {
            tallyAttachments(role.getAttachedPolicyArns(), attachmentCounts, referencedAwsManagedArns);
            tallyBoundaryUsage(role.getPermissionsBoundaryArn(), boundaryUsageCounts, referencedAwsManagedArns);
        }

        List<IamPolicy> allPolicies = new ArrayList<>(listPolicies("Local", null));
        for (String arn : referencedAwsManagedArns) {
            allPolicies.add(getPolicy(arn));
        }

        return new AccountAuthorizationDetails(
                allUsers, allGroups, allRoles, allPolicies, attachmentCounts, boundaryUsageCounts);
    }

    private void tallyAttachments(List<String> attachedPolicyArns, Map<String, Integer> attachmentCounts,
                                   Set<String> referencedAwsManagedArns) {
        for (String arn : attachedPolicyArns) {
            attachmentCounts.merge(arn, 1, Integer::sum);
            if (arn.startsWith(AwsManagedPolicies.ARN_PREFIX)) {
                referencedAwsManagedArns.add(arn);
            }
        }
    }

    private void tallyBoundaryUsage(String permissionsBoundaryArn, Map<String, Integer> boundaryUsageCounts,
                                     Set<String> referencedAwsManagedArns) {
        if (permissionsBoundaryArn == null) {
            return;
        }
        boundaryUsageCounts.merge(permissionsBoundaryArn, 1, Integer::sum);
        if (permissionsBoundaryArn.startsWith(AwsManagedPolicies.ARN_PREFIX)) {
            referencedAwsManagedArns.add(permissionsBoundaryArn);
        }
    }

    /**
     * @param attachmentCounts arn -> number of users, groups and roles it is attached to
     * @param permissionsBoundaryUsageCounts arn -> number of users and roles using it as a boundary
     */
    public record AccountAuthorizationDetails(
            List<IamUser> users,
            List<IamGroup> groups,
            List<IamRole> roles,
            List<IamPolicy> policies,
            Map<String, Integer> attachmentCounts,
            Map<String, Integer> permissionsBoundaryUsageCounts) {
    }

    // =========================================================================
    // Credential Report
    // =========================================================================

    public record CredentialReportGeneration(String state, String description) {}

    public record CredentialReportContent(String base64Content, String reportFormat, Instant generatedTime) {}

    /**
     * AWS generates a fresh report only if the most recent one is older than
     * {@link #CREDENTIAL_REPORT_MAX_AGE}; otherwise it downloads the existing one. Building the
     * CSV here is effectively instant, so unlike real AWS this never actually returns
     * {@code INPROGRESS}: a caller polling {@code GetCredentialReport} after this finds the
     * report ready immediately. The {@code STARTED} state and its description text still match
     * AWS's own documented example response for the no-report-exists case.
     */
    public CredentialReportGeneration generateCredentialReport() {
        Instant now = Instant.now();
        Optional<CredentialReport> existing = credentialReports.get(CREDENTIAL_REPORT_KEY);
        if (existing.isPresent() && now.isBefore(existing.get().getGeneratedTime().plus(CREDENTIAL_REPORT_MAX_AGE))) {
            return new CredentialReportGeneration("COMPLETE",
                    "Current report has already been generated within the past 4 hours.");
        }
        String csv = buildCredentialReportCsv();
        String base64Content = Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8));
        credentialReports.put(CREDENTIAL_REPORT_KEY, new CredentialReport(base64Content, now));
        String description = existing.isEmpty()
                ? "No report exists. Starting a new report generation task"
                : "The previous report has expired. Starting a new report generation task";
        return new CredentialReportGeneration("STARTED", description);
    }

    public CredentialReportContent getCredentialReport() {
        CredentialReport report = credentialReports.get(CREDENTIAL_REPORT_KEY)
                .orElseThrow(() -> new AwsException("ReportNotPresent",
                        "The request was rejected because the credential report does not exist. "
                                + "To generate a credential report, use GenerateCredentialReport.", 410));
        if (Instant.now().isAfter(report.getGeneratedTime().plus(CREDENTIAL_REPORT_MAX_AGE))) {
            throw new AwsException("ReportExpired",
                    "The request was rejected because the most recent credential report has expired. "
                            + "To generate a new credential report, use GenerateCredentialReport.", 410);
        }
        return new CredentialReportContent(report.getBase64Content(), "text/csv", report.getGeneratedTime());
    }

    /**
     * The 23 columns AWS documents for the credential report, in order, always led by the
     * {@code <root_account>} row. Floci does not model root account credentials at all (see
     * {@code GetAccountSummary}'s {@code AccountPasswordPresent}/{@code AccountAccessKeysPresent},
     * always zero), so that row is always unused/not-present placeholders. MFA devices and X.509
     * signing certificates are not modeled for IAM users either, so those columns are always
     * {@code FALSE}/{@code N/A} for every row; access key last-used tracking (date, region,
     * service) is not modeled, so those three columns are always {@code N/A} too.
     */
    private String buildCredentialReportCsv() {
        StringBuilder csv = new StringBuilder(
                "user,arn,user_creation_time,password_enabled,password_last_used,password_last_changed,"
                + "password_next_rotation,mfa_active,access_key_1_active,access_key_1_last_rotated,"
                + "access_key_1_last_used_date,access_key_1_last_used_region,access_key_1_last_used_service,"
                + "access_key_2_active,access_key_2_last_rotated,access_key_2_last_used_date,"
                + "access_key_2_last_used_region,access_key_2_last_used_service,cert_1_active,"
                + "cert_1_last_rotated,cert_2_active,cert_2_last_rotated,additional_credentials_info\n");
        csv.append(rootAccountReportRow()).append('\n');
        for (IamUser user : listUsers(null)) {
            csv.append(userReportRow(user)).append('\n');
        }
        return csv.toString();
    }

    private String rootAccountReportRow() {
        String arn = AwsArnUtils.Arn.of("iam", "", regionResolver.getAccountId(), "root").toString();
        return String.join(",",
                "<root_account>", arn, "N/A",
                "FALSE", "N/A", "N/A", "not_supported",
                "FALSE",
                "FALSE", "N/A", "N/A", "N/A", "N/A",
                "FALSE", "N/A", "N/A", "N/A", "N/A",
                "FALSE", "N/A", "FALSE", "N/A", "");
    }

    private String userReportRow(IamUser user) {
        List<AccessKey> keys = userAccessKeys(user.getUserName());
        AccessKey key1 = keys.size() > 0 ? keys.get(0) : null;
        AccessKey key2 = keys.size() > 1 ? keys.get(1) : null;
        Optional<LoginProfile> loginProfile = loginProfiles.get(user.getUserName());
        boolean passwordEnabled = loginProfile.isPresent();

        return String.join(",",
                user.getUserName(),
                user.getArn(),
                isoDate(user.getCreateDate()),
                passwordEnabled ? "TRUE" : "FALSE",
                passwordLastUsedField(user, passwordEnabled),
                passwordEnabled ? isoDate(passwordLastChanged(loginProfile.get())) : "N/A",
                passwordNextRotationField(loginProfile, passwordEnabled),
                "FALSE",
                accessKeyActiveField(key1), accessKeyRotatedField(key1), "N/A", "N/A", "N/A",
                accessKeyActiveField(key2), accessKeyRotatedField(key2), "N/A", "N/A", "N/A",
                "FALSE", "N/A", "FALSE", "N/A",
                additionalCredentialsInfoField(keys));
    }

    /**
     * AWS documents this as naming the count of extra access keys or certificates and the
     * actions to list them, but not the exact wording, so this is Floci's own text, not a
     * verified match. In practice this branch is unreachable through the API: {@link
     * #createAccessKey} already enforces the real 2-key-per-user quota, so more than two keys
     * can only happen through directly-edited persisted state, not anything a caller can do.
     */
    private String additionalCredentialsInfoField(List<AccessKey> keys) {
        return keys.size() > 2 ? (keys.size() - 2) + " additional access key(s)" : "";
    }

    private String passwordLastUsedField(IamUser user, boolean passwordEnabled) {
        if (!passwordEnabled) {
            return "N/A";
        }
        return user.getPasswordLastUsed() != null ? isoDate(user.getPasswordLastUsed()) : "no_information";
    }

    /** AWS documents this as always {@code not_supported} for root; blank when no rotation policy is set. */
    private String passwordNextRotationField(Optional<LoginProfile> loginProfile, boolean passwordEnabled) {
        if (!passwordEnabled) {
            return "N/A";
        }
        return getAccountPasswordPolicy()
                .map(AccountPasswordPolicy::getMaxPasswordAge)
                .map(maxAge -> isoDate(passwordLastChanged(loginProfile.get()).plus(Duration.ofDays(maxAge))))
                .orElse("");
    }

    /** Falls back to createDate for a profile persisted before passwordLastChanged existed. */
    private Instant passwordLastChanged(LoginProfile profile) {
        return profile.getPasswordLastChanged() != null ? profile.getPasswordLastChanged() : profile.getCreateDate();
    }

    private String accessKeyActiveField(AccessKey key) {
        return key != null && "Active".equals(key.getStatus()) ? "TRUE" : "FALSE";
    }

    private String accessKeyRotatedField(AccessKey key) {
        return key != null ? isoDate(key.getCreateDate()) : "N/A";
    }

    private String isoDate(Instant instant) {
        return instant == null ? "" : DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
