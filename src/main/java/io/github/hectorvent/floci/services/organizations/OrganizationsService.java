package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.Handshake;
import io.github.hectorvent.floci.services.organizations.model.HandshakeParty;
import io.github.hectorvent.floci.services.organizations.model.HandshakeResource;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.organizations.model.OrganizationPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import io.github.hectorvent.floci.services.organizations.model.PolicyTypeSummary;
import io.github.hectorvent.floci.services.organizations.model.Root;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * AWS Organizations service implementation for the local emulator.
 *
 * <p>Organizations is a global, inherently cross-account service, which shapes how state is
 * stored here. Every record lives under the <em>management</em> account's storage prefix via the
 * explicit {@code *ForAccount} methods on {@link AccountAwareStorageBackend}, never via the
 * request-context-derived {@code get}/{@code put}. That is deliberate: a member account calling
 * {@code DescribeOrganization} has a different request account than the one that owns the data,
 * so binding reads to the caller's prefix would make the organization invisible to exactly the
 * callers AWS expects to see it. The caller's account id is used for <em>authorization</em>
 * ({@link #requireManagementAccount} vs {@link #requireOrganizationForCaller}) rather than for
 * addressing storage.
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/Welcome.html">AWS Organizations API Reference</a>
 */
@ApplicationScoped
public class OrganizationsService implements ScpProvider {

    private static final Logger LOG = Logger.getLogger(OrganizationsService.class);

    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    static final String FULL_AWS_ACCESS_POLICY_ID = "p-FullAWSAccess";
    private static final String FULL_AWS_ACCESS_CONTENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    static final String SERVICE_CONTROL_POLICY = "SERVICE_CONTROL_POLICY";
    private static final String RESOURCE_CONTROL_POLICY = "RESOURCE_CONTROL_POLICY";

    /**
     * Policy types AWS accepts on CreatePolicy and Enable/DisablePolicyType.
     *
     * <p>Declared as an ordered {@link List} because the validation error message renders it:
     * {@code Set.of}'s iteration order is unspecified and salted per JVM run, so joining a set
     * would make the same rejection read differently from one run to the next. The companion
     * {@code *_SET} keeps membership checks O(1).
     */
    private static final List<String> POLICY_TYPES = List.of(
            SERVICE_CONTROL_POLICY,
            RESOURCE_CONTROL_POLICY,
            "TAG_POLICY",
            "BACKUP_POLICY",
            "AISERVICES_OPT_OUT_POLICY",
            "CHATBOT_POLICY",
            "DECLARATIVE_POLICY_EC2",
            "SECURITYHUB_POLICY");

    private static final Set<String> POLICY_TYPE_SET = Set.copyOf(POLICY_TYPES);

    /**
     * {@code DescribeEffectivePolicy} is defined only for the inheritable policy types. AWS
     * rejects the two access-control types outright — they are evaluated as a deny-by-intersection
     * chain rather than merged into a single effective document, so there is nothing to return.
     *
     * <p>This is floci's rendering of the model's {@code EffectivePolicyType} shape; the last five
     * entries were added to the shape after it was first written here. Ordered for the same reason
     * as {@link #POLICY_TYPES}.
     */
    private static final List<String> EFFECTIVE_POLICY_TYPES = List.of(
            "TAG_POLICY",
            "BACKUP_POLICY",
            "AISERVICES_OPT_OUT_POLICY",
            "CHATBOT_POLICY",
            "DECLARATIVE_POLICY_EC2",
            "SECURITYHUB_POLICY",
            "INSPECTOR_POLICY",
            "UPGRADE_ROLLOUT_POLICY",
            "BEDROCK_POLICY",
            "S3_POLICY",
            "NETWORK_SECURITY_DIRECTOR_POLICY");

    private static final Set<String> EFFECTIVE_POLICY_TYPE_SET = Set.copyOf(EFFECTIVE_POLICY_TYPES);

    private static final String FEATURE_SET_ALL = "ALL";
    private static final String FEATURE_SET_CONSOLIDATED_BILLING = "CONSOLIDATED_BILLING";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_PENDING_CLOSURE = "PENDING_CLOSURE";

    private static final String HANDSHAKE_REQUESTED = "REQUESTED";
    private static final String HANDSHAKE_ACCEPTED = "ACCEPTED";
    private static final String HANDSHAKE_DECLINED = "DECLINED";
    private static final String HANDSHAKE_CANCELED = "CANCELED";
    private static final String HANDSHAKE_EXPIRED = "EXPIRED";
    private static final String HANDSHAKE_INVITE = "INVITE";
    private static final String HANDSHAKE_ENABLE_ALL_FEATURES = "ENABLE_ALL_FEATURES";

    /** AWS holds an unaccepted handshake open for 15 days. */
    private static final int HANDSHAKE_TTL_DAYS = 15;

    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("\\d{12}");
    private static final Pattern ROOT_ID_PATTERN = Pattern.compile("r-[0-9a-z]{4,32}");
    private static final Pattern OU_ID_PATTERN = Pattern.compile("ou-[0-9a-z]{4,32}-[0-9a-z]{8,32}");
    private static final Pattern POLICY_ID_PATTERN = Pattern.compile("p-[0-9a-zA-Z_]{8,128}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern SERVICE_PRINCIPAL_PATTERN = Pattern.compile("[\\w+=,.@-]{1,128}");

    private static final int MAX_ACCOUNT_NAME_LENGTH = 50;
    private static final int MAX_OU_NAME_LENGTH = 128;
    private static final int MAX_POLICY_NAME_LENGTH = 128;
    private static final int MAX_POLICY_DESCRIPTION_LENGTH = 512;
    private static final int MAX_TAG_KEY_LENGTH = 128;
    private static final int MAX_TAG_VALUE_LENGTH = 256;

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper objectMapper;

    private final AccountAwareStorageBackend<Organization> organizations;
    private final AccountAwareStorageBackend<OrganizationAccount> accounts;
    private final AccountAwareStorageBackend<OrganizationalUnit> organizationalUnits;
    private final AccountAwareStorageBackend<OrganizationPolicy> policies;
    private final AccountAwareStorageBackend<CreateAccountStatus> createAccountStatuses;
    private final AccountAwareStorageBackend<Handshake> handshakes;
    private final boolean scpEnforcementEnabled;

    @Inject
    public OrganizationsService(StorageFactory storageFactory, ObjectMapper objectMapper,
                                EmulatorConfig config) {
        this.scpEnforcementEnabled = config.services().organizations().scpEnforcementEnabled();
        this.objectMapper = objectMapper;
        this.organizations = storageFactory.create("organizations", "organizations-organizations.json",
                new TypeReference<Map<String, Organization>>() {});
        this.accounts = storageFactory.create("organizations", "organizations-accounts.json",
                new TypeReference<Map<String, OrganizationAccount>>() {});
        this.organizationalUnits = storageFactory.create("organizations", "organizations-ous.json",
                new TypeReference<Map<String, OrganizationalUnit>>() {});
        this.policies = storageFactory.create("organizations", "organizations-policies.json",
                new TypeReference<Map<String, OrganizationPolicy>>() {});
        this.createAccountStatuses = storageFactory.create("organizations", "organizations-create-account-status.json",
                new TypeReference<Map<String, CreateAccountStatus>>() {});
        this.handshakes = storageFactory.create("organizations", "organizations-handshakes.json",
                new TypeReference<Map<String, Handshake>>() {});
    }

    OrganizationsService(ObjectMapper objectMapper,
                         AccountAwareStorageBackend<Organization> organizations,
                         AccountAwareStorageBackend<OrganizationAccount> accounts,
                         AccountAwareStorageBackend<OrganizationalUnit> organizationalUnits,
                         AccountAwareStorageBackend<OrganizationPolicy> policies,
                         AccountAwareStorageBackend<CreateAccountStatus> createAccountStatuses,
                         AccountAwareStorageBackend<Handshake> handshakes) {
        this(objectMapper, organizations, accounts, organizationalUnits, policies,
                createAccountStatuses, handshakes, true);
    }

    OrganizationsService(ObjectMapper objectMapper,
                         AccountAwareStorageBackend<Organization> organizations,
                         AccountAwareStorageBackend<OrganizationAccount> accounts,
                         AccountAwareStorageBackend<OrganizationalUnit> organizationalUnits,
                         AccountAwareStorageBackend<OrganizationPolicy> policies,
                         AccountAwareStorageBackend<CreateAccountStatus> createAccountStatuses,
                         AccountAwareStorageBackend<Handshake> handshakes,
                         boolean scpEnforcementEnabled) {
        this.objectMapper = objectMapper;
        this.organizations = organizations;
        this.accounts = accounts;
        this.organizationalUnits = organizationalUnits;
        this.policies = policies;
        this.createAccountStatuses = createAccountStatuses;
        this.handshakes = handshakes;
        this.scpEnforcementEnabled = scpEnforcementEnabled;
    }

    /** A parent reference as returned by {@code ListParents}. */
    public record ParentRef(String id, String type) {}

    /** A child reference as returned by {@code ListChildren}. */
    public record ChildRef(String id, String type) {}

    /** A policy attachment as returned by {@code ListTargetsForPolicy}. */
    public record PolicyTarget(String targetId, String arn, String name, String type) {}

    /** A trusted-access entry as returned by {@code ListAWSServiceAccessForOrganization}. */
    public record EnabledServicePrincipal(String servicePrincipal, Instant dateEnabled) {}

    /** A delegation entry as returned by {@code ListDelegatedServicesForAccount}. */
    public record DelegatedService(String servicePrincipal, Instant delegationEnabledDate) {}

    /** The organization resource policy as returned by {@code PutResourcePolicy}. */
    public record ResourcePolicyView(String id, String arn, String content, Map<String, String> tags) {}

    /** The merged inheritance chain as returned by {@code DescribeEffectivePolicy}. */
    public record EffectivePolicy(String policyContent, String policyType, String targetId,
                                  Instant lastUpdatedTimestamp) {}

    // ──────────────────────────── Organization lifecycle ────────────────────────────

    public Organization createOrganization(String callerAccountId, String featureSet) {
        String resolvedFeatureSet = featureSet == null || featureSet.isEmpty() ? FEATURE_SET_ALL : featureSet;
        if (!FEATURE_SET_ALL.equals(resolvedFeatureSet)
                && !FEATURE_SET_CONSOLIDATED_BILLING.equals(resolvedFeatureSet)) {
            throw invalidInput("FeatureSet must be ALL or CONSOLIDATED_BILLING.");
        }
        findOrganizationForAccount(callerAccountId).ifPresent(existing -> {
            throw new AwsException("AlreadyInOrganizationException",
                    "The provided account is already a member of an organization.", 400);
        });

        String organizationId = "o-" + randomId(10);
        String rootId = "r-" + randomId(4);

        Organization organization = new Organization();
        organization.setId(organizationId);
        organization.setArn(arn(callerAccountId, "organization/" + organizationId));
        organization.setFeatureSet(resolvedFeatureSet);
        organization.setMasterAccountId(callerAccountId);
        organization.setMasterAccountArn(arn(callerAccountId, "account/" + organizationId + "/" + callerAccountId));
        organization.setMasterAccountEmail("master@" + callerAccountId + ".example.com");
        organization.setCreatedTimestamp(Instant.now());

        Root root = new Root();
        root.setId(rootId);
        root.setArn(arn(callerAccountId, "root/" + organizationId + "/" + rootId));
        root.setName("Root");
        if (FEATURE_SET_ALL.equals(resolvedFeatureSet)) {
            root.getPolicyTypes().add(new PolicyTypeSummary(SERVICE_CONTROL_POLICY, "ENABLED"));
        }
        organization.setRoot(root);

        organizations.putForAccount(callerAccountId, organizationId, organization);

        OrganizationAccount master = new OrganizationAccount();
        master.setId(callerAccountId);
        master.setArn(organization.getMasterAccountArn());
        master.setEmail(organization.getMasterAccountEmail());
        master.setName("management-account");
        master.setStatus(STATUS_ACTIVE);
        master.setJoinedMethod("INVITED");
        master.setJoinedTimestamp(organization.getCreatedTimestamp());
        master.setOrganizationId(organizationId);
        master.setParentId(rootId);
        accounts.putForAccount(callerAccountId, callerAccountId, master);

        createFullAwsAccessPolicy(organization, rootId, callerAccountId);

        LOG.infov("Created organization {0} with root {1}", organizationId, rootId);
        return organization;
    }

    public Organization describeOrganization(String callerAccountId) {
        return requireOrganizationForCaller(callerAccountId);
    }

    public void deleteOrganization(String callerAccountId) {
        Organization organization = requireManagementAccount(callerAccountId);
        List<OrganizationAccount> members = accountsIn(organization);
        if (members.size() > 1) {
            throw new AwsException("OrganizationNotEmptyException",
                    "The organization still contains accounts other than the management account. "
                            + "Remove them before deleting the organization.", 400);
        }
        if (!organizationalUnitsIn(organization).isEmpty()) {
            throw new AwsException("OrganizationNotEmptyException",
                    "The organization still contains organizational units. "
                            + "Delete them before deleting the organization.", 400);
        }

        String master = organization.getMasterAccountId();
        policiesIn(organization).forEach(policy -> policies.deleteForAccount(master, policy.getId()));
        members.forEach(account -> accounts.deleteForAccount(master, account.getId()));
        handshakesIn(organization).forEach(handshake -> handshakes.deleteForAccount(master, handshake.getId()));
        createAccountStatusesIn(organization)
                .forEach(status -> createAccountStatuses.deleteForAccount(master, status.getId()));
        organizations.deleteForAccount(master, organization.getId());

        LOG.infov("Deleted organization {0}", organization.getId());
    }

    /**
     * Promotes a CONSOLIDATED_BILLING organization to ALL features. AWS models this as a handshake
     * that every member account must approve; with no member accounts the handshake completes
     * immediately, which is the common emulator case.
     */
    public Handshake enableAllFeatures(String callerAccountId) {
        Organization organization = requireManagementAccount(callerAccountId);
        if (FEATURE_SET_ALL.equals(organization.getFeatureSet())) {
            throw new AwsException("HandshakeConstraintViolationException",
                    "The organization is already set to support all features.", 400);
        }

        Handshake handshake = newHandshake(organization, HANDSHAKE_ENABLE_ALL_FEATURES);
        handshake.getParties().add(new HandshakeParty(organization.getId(), "ORGANIZATION"));
        handshake.getResources().add(new HandshakeResource(organization.getId(), "ORGANIZATION"));

        boolean hasMembers = accountsIn(organization).size() > 1;
        if (!hasMembers) {
            handshake.setState(HANDSHAKE_ACCEPTED);
            applyEnableAllFeatures(organization);
        }
        handshakes.putForAccount(organization.getMasterAccountId(), handshake.getId(), handshake);
        return handshake;
    }

    public List<Root> listRoots(String callerAccountId) {
        return List.of(requireOrganizationForCaller(callerAccountId).getRoot());
    }

    // ──────────────────────────── Organizational units ────────────────────────────

    public OrganizationalUnit createOrganizationalUnit(String callerAccountId, String parentId, String name,
                                                       Map<String, String> tags) {
        Organization organization = requireManagementAccount(callerAccountId);
        requireParent(organization, parentId);
        validateName(name, "Name", MAX_OU_NAME_LENGTH);
        validateTags(tags);

        boolean duplicate = organizationalUnitsIn(organization).stream()
                .anyMatch(ou -> parentId.equals(ou.getParentId()) && name.equals(ou.getName()));
        if (duplicate) {
            throw new AwsException("DuplicateOrganizationalUnitException",
                    "An OU with the name " + name + " already exists under this parent.", 400);
        }

        String rootSuffix = organization.getRoot().getId().substring(2);
        String ouId = "ou-" + rootSuffix + "-" + randomId(8);

        OrganizationalUnit unit = new OrganizationalUnit();
        unit.setId(ouId);
        unit.setArn(arn(organization.getMasterAccountId(), "ou/" + organization.getId() + "/" + ouId));
        unit.setName(name);
        unit.setOrganizationId(organization.getId());
        unit.setParentId(parentId);
        if (tags != null) {
            unit.getTags().putAll(tags);
        }
        organizationalUnits.putForAccount(organization.getMasterAccountId(), ouId, unit);

        attachFullAwsAccess(organization, ouId);
        return unit;
    }

    public OrganizationalUnit updateOrganizationalUnit(String callerAccountId, String ouId, String name) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationalUnit unit = requireOrganizationalUnit(organization, ouId);
        if (name == null) {
            return unit;
        }
        validateName(name, "Name", MAX_OU_NAME_LENGTH);
        boolean duplicate = organizationalUnitsIn(organization).stream()
                .anyMatch(other -> !other.getId().equals(ouId)
                        && unit.getParentId().equals(other.getParentId())
                        && name.equals(other.getName()));
        if (duplicate) {
            throw new AwsException("DuplicateOrganizationalUnitException",
                    "An OU with the name " + name + " already exists under this parent.", 400);
        }
        unit.setName(name);
        organizationalUnits.putForAccount(organization.getMasterAccountId(), ouId, unit);
        return unit;
    }

    public void deleteOrganizationalUnit(String callerAccountId, String ouId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationalUnit unit = requireOrganizationalUnit(organization, ouId);

        boolean hasChildOus = organizationalUnitsIn(organization).stream()
                .anyMatch(other -> ouId.equals(other.getParentId()));
        boolean hasAccounts = accountsIn(organization).stream()
                .anyMatch(account -> ouId.equals(account.getParentId()));
        if (hasChildOus || hasAccounts) {
            throw new AwsException("OrganizationalUnitNotEmptyException",
                    "The organizational unit " + ouId + " still contains accounts or other OUs.", 400);
        }

        detachAllPoliciesFrom(organization, ouId);
        organizationalUnits.deleteForAccount(organization.getMasterAccountId(), unit.getId());
    }

    public OrganizationalUnit describeOrganizationalUnit(String callerAccountId, String ouId) {
        return requireOrganizationalUnit(requireOrganizationForCaller(callerAccountId), ouId);
    }

    public List<OrganizationalUnit> listOrganizationalUnitsForParent(String callerAccountId, String parentId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        requireParent(organization, parentId);
        return organizationalUnitsIn(organization).stream()
                .filter(ou -> parentId.equals(ou.getParentId()))
                .sorted(Comparator.comparing(OrganizationalUnit::getId))
                .toList();
    }

    public List<ParentRef> listParents(String callerAccountId, String childId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        String parentId = parentOf(organization, childId);
        return List.of(new ParentRef(parentId, parentType(organization, parentId)));
    }

    /**
     * The organization path of a root, OU or account:
     * {@code o-<org>/r-<root>[/ou-<ou>]*[/<accountId>]/}, trailing slash included. This is the
     * value the Organizations API reports as {@code OrganizationalUnit.Path} and as the single
     * entry of {@code Account.Paths}, and what {@code Fn::GetAtt} returns for the same keys.
     */
    public String organizationPath(String callerAccountId, String resourceId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        return organization.getId() + "/" + String.join("/", ancestryOf(organization, resourceId)) + "/";
    }

    public List<ChildRef> listChildren(String callerAccountId, String parentId, String childType) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        requireParent(organization, parentId);
        if (!"ACCOUNT".equals(childType) && !"ORGANIZATIONAL_UNIT".equals(childType)) {
            throw invalidInput("ChildType must be ACCOUNT or ORGANIZATIONAL_UNIT.");
        }
        if ("ACCOUNT".equals(childType)) {
            return accountsIn(organization).stream()
                    .filter(account -> parentId.equals(account.getParentId()))
                    .sorted(Comparator.comparing(OrganizationAccount::getId))
                    .map(account -> new ChildRef(account.getId(), "ACCOUNT"))
                    .toList();
        }
        return organizationalUnitsIn(organization).stream()
                .filter(ou -> parentId.equals(ou.getParentId()))
                .sorted(Comparator.comparing(OrganizationalUnit::getId))
                .map(ou -> new ChildRef(ou.getId(), "ORGANIZATIONAL_UNIT"))
                .toList();
    }

    // ──────────────────────────── Accounts ────────────────────────────

    public CreateAccountStatus createAccount(String callerAccountId, String email, String accountName,
                                             Map<String, String> tags, boolean govCloud) {
        Organization organization = requireManagementAccount(callerAccountId);
        validateEmail(email);
        validateName(accountName, "AccountName", MAX_ACCOUNT_NAME_LENGTH);
        validateTags(tags);

        boolean emailTaken = accountsIn(organization).stream()
                .anyMatch(account -> email.equalsIgnoreCase(account.getEmail()));

        CreateAccountStatus status = new CreateAccountStatus();
        status.setId("car-" + randomId(8));
        status.setAccountName(accountName);
        status.setRequestedTimestamp(Instant.now());
        status.setOrganizationId(organization.getId());

        if (emailTaken) {
            status.setState("FAILED");
            status.setFailureReason("EMAIL_ALREADY_EXISTS");
            status.setCompletedTimestamp(Instant.now());
            createAccountStatuses.putForAccount(organization.getMasterAccountId(), status.getId(), status);
            return status;
        }

        String newAccountId = allocateAccountId(organization);
        OrganizationAccount account = newMemberAccount(organization, newAccountId, email, accountName, "CREATED");
        accounts.putForAccount(organization.getMasterAccountId(), newAccountId, account);
        attachFullAwsAccess(organization, newAccountId);

        status.setState("SUCCEEDED");
        status.setAccountId(newAccountId);
        status.setCompletedTimestamp(Instant.now());
        if (govCloud) {
            status.setGovCloudAccountId(allocateAccountId(organization));
        }
        createAccountStatuses.putForAccount(organization.getMasterAccountId(), status.getId(), status);

        LOG.infov("Created account {0} ({1}) in organization {2}", newAccountId, accountName, organization.getId());
        return status;
    }

    public CreateAccountStatus describeCreateAccountStatus(String callerAccountId, String requestId) {
        Organization organization = requireManagementAccount(callerAccountId);
        return createAccountStatuses.getForAccount(organization.getMasterAccountId(), requestId)
                .filter(status -> organization.getId().equals(status.getOrganizationId()))
                .orElseThrow(() -> new AwsException("CreateAccountStatusNotFoundException",
                        "We can't find a create account request with the id " + requestId + ".", 400));
    }

    public List<CreateAccountStatus> listCreateAccountStatus(String callerAccountId, List<String> states) {
        Organization organization = requireManagementAccount(callerAccountId);
        return createAccountStatusesIn(organization).stream()
                .filter(status -> states == null || states.isEmpty() || states.contains(status.getState()))
                .sorted(Comparator.comparing(CreateAccountStatus::getId))
                .toList();
    }

    public OrganizationAccount describeAccount(String callerAccountId, String accountId) {
        return requireAccount(requireOrganizationForCaller(callerAccountId), accountId);
    }

    public List<OrganizationAccount> listAccounts(String callerAccountId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        return accountsIn(organization).stream()
                .sorted(Comparator.comparing(OrganizationAccount::getId))
                .toList();
    }

    public List<OrganizationAccount> listAccountsForParent(String callerAccountId, String parentId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        requireParent(organization, parentId);
        return accountsIn(organization).stream()
                .filter(account -> parentId.equals(account.getParentId()))
                .sorted(Comparator.comparing(OrganizationAccount::getId))
                .toList();
    }

    public void moveAccount(String callerAccountId, String accountId, String sourceParentId,
                            String destinationParentId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationAccount account = requireAccount(organization, accountId);
        requireParent(organization, sourceParentId);
        if (!accountParentExists(organization, destinationParentId)) {
            throw new AwsException("DestinationParentNotFoundException",
                    "We can't find the destination parent " + destinationParentId + ".", 400);
        }
        if (!account.getParentId().equals(sourceParentId)) {
            throw new AwsException("SourceParentNotMatchedException",
                    "The account " + accountId + " is not in the source parent " + sourceParentId + ".", 400);
        }
        if (sourceParentId.equals(destinationParentId)) {
            throw new AwsException("DuplicateAccountException",
                    "The account " + accountId + " is already in " + destinationParentId + ".", 400);
        }
        account.setParentId(destinationParentId);
        accounts.putForAccount(organization.getMasterAccountId(), accountId, account);
    }

    public void removeAccountFromOrganization(String callerAccountId, String accountId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationAccount account = requireAccount(organization, accountId);
        if (accountId.equals(organization.getMasterAccountId())) {
            throw new AwsException("MasterCannotLeaveOrganizationException",
                    "The management account can't be removed from the organization.", 400);
        }
        detachAllPoliciesFrom(organization, account.getId());
        accounts.deleteForAccount(organization.getMasterAccountId(), account.getId());
    }

    public void leaveOrganization(String callerAccountId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        if (callerAccountId.equals(organization.getMasterAccountId())) {
            throw new AwsException("MasterCannotLeaveOrganizationException",
                    "The management account can't leave the organization.", 400);
        }
        OrganizationAccount account = requireAccount(organization, callerAccountId);
        detachAllPoliciesFrom(organization, account.getId());
        accounts.deleteForAccount(organization.getMasterAccountId(), account.getId());
    }

    public OrganizationAccount closeAccount(String callerAccountId, String accountId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationAccount account = requireAccount(organization, accountId);
        if (accountId.equals(organization.getMasterAccountId())) {
            throw new AwsException("ConstraintViolationException",
                    "The management account can't be closed.", 400);
        }
        account.setStatus(STATUS_PENDING_CLOSURE);
        accounts.putForAccount(organization.getMasterAccountId(), accountId, account);
        return account;
    }

    // ──────────────────────────── Policies ────────────────────────────

    public OrganizationPolicy createPolicy(String callerAccountId, String content, String description,
                                           String name, String type, Map<String, String> tags) {
        Organization organization = requireManagementAccount(callerAccountId);
        validatePolicyType(type);
        requirePolicyTypeEnabled(organization, type);
        validateName(name, "Name", MAX_POLICY_NAME_LENGTH);
        if (content == null || content.isBlank()) {
            throw invalidInput("Content must not be empty.");
        }
        if (description != null && description.length() > MAX_POLICY_DESCRIPTION_LENGTH) {
            throw invalidInput("Description must be " + MAX_POLICY_DESCRIPTION_LENGTH + " characters or fewer.");
        }
        validateTags(tags);

        boolean duplicate = policiesIn(organization).stream()
                .anyMatch(policy -> name.equals(policy.getName()) && type.equals(policy.getType()));
        if (duplicate) {
            throw new AwsException("DuplicatePolicyException",
                    "A policy with the name " + name + " already exists.", 400);
        }

        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setId("p-" + randomId(8));
        policy.setName(name);
        policy.setDescription(description);
        policy.setType(type);
        policy.setContent(content);
        policy.setOrganizationId(organization.getId());
        policy.setArn(policyArn(organization, policy.getId(), type));
        if (tags != null) {
            policy.getTags().putAll(tags);
        }
        policies.putForAccount(organization.getMasterAccountId(), policy.getId(), policy);
        return policy;
    }

    public OrganizationPolicy updatePolicy(String callerAccountId, String policyId, String name,
                                           String description, String content) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationPolicy policy = requirePolicy(organization, policyId);
        requireNotAwsManaged(policy);

        if (name != null) {
            validateName(name, "Name", MAX_POLICY_NAME_LENGTH);
            boolean duplicate = policiesIn(organization).stream()
                    .anyMatch(other -> !other.getId().equals(policyId)
                            && name.equals(other.getName())
                            && policy.getType().equals(other.getType()));
            if (duplicate) {
                throw new AwsException("DuplicatePolicyException",
                        "A policy with the name " + name + " already exists.", 400);
            }
            policy.setName(name);
        }
        if (description != null) {
            if (description.length() > MAX_POLICY_DESCRIPTION_LENGTH) {
                throw invalidInput("Description must be " + MAX_POLICY_DESCRIPTION_LENGTH + " characters or fewer.");
            }
            policy.setDescription(description);
        }
        if (content != null) {
            if (content.isBlank()) {
                throw invalidInput("Content must not be empty.");
            }
            policy.setContent(content);
        }
        policies.putForAccount(organization.getMasterAccountId(), policyId, policy);
        return policy;
    }

    public void deletePolicy(String callerAccountId, String policyId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationPolicy policy = requirePolicy(organization, policyId);
        requireNotAwsManaged(policy);
        if (!policy.getTargets().isEmpty()) {
            throw new AwsException("PolicyInUseException",
                    "The policy " + policyId + " is still attached to " + policy.getTargets().size()
                            + " target(s). Detach it before deleting.", 400);
        }
        policies.deleteForAccount(organization.getMasterAccountId(), policyId);
    }

    public OrganizationPolicy describePolicy(String callerAccountId, String policyId) {
        return requirePolicy(requireOrganizationForCaller(callerAccountId), policyId);
    }

    public List<OrganizationPolicy> listPolicies(String callerAccountId, String filter) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        validatePolicyType(filter);
        return policiesIn(organization).stream()
                .filter(policy -> filter.equals(policy.getType()))
                .sorted(Comparator.comparing(OrganizationPolicy::getId))
                .toList();
    }

    public void attachPolicy(String callerAccountId, String policyId, String targetId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationPolicy policy = requirePolicy(organization, policyId);
        requireTarget(organization, targetId);
        requirePolicyTypeEnabled(organization, policy.getType());
        if (!policy.getTargets().add(targetId)) {
            throw new AwsException("DuplicatePolicyAttachmentException",
                    "The policy " + policyId + " is already attached to " + targetId + ".", 400);
        }
        policies.putForAccount(organization.getMasterAccountId(), policyId, policy);
    }

    public void detachPolicy(String callerAccountId, String policyId, String targetId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationPolicy policy = requirePolicy(organization, policyId);
        requireTarget(organization, targetId);
        if (!policy.getTargets().contains(targetId)) {
            throw new AwsException("PolicyNotAttachedException",
                    "The policy " + policyId + " is not attached to " + targetId + ".", 400);
        }
        // AWS requires every target to keep at least one SCP; detaching the last one would leave
        // it with no allow statement at all, so the API rejects it rather than silently locking
        // the target out.
        if (SERVICE_CONTROL_POLICY.equals(policy.getType())
                && countAttachedPolicies(organization, targetId, SERVICE_CONTROL_POLICY) <= 1) {
            throw new AwsException("ConstraintViolationException",
                    "You can't detach the last service control policy from " + targetId + ".", 400);
        }
        policy.getTargets().remove(targetId);
        policies.putForAccount(organization.getMasterAccountId(), policyId, policy);
    }

    public List<OrganizationPolicy> listPoliciesForTarget(String callerAccountId, String targetId, String filter) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        requireTarget(organization, targetId);
        validatePolicyType(filter);
        return policiesIn(organization).stream()
                .filter(policy -> filter.equals(policy.getType()) && policy.getTargets().contains(targetId))
                .sorted(Comparator.comparing(OrganizationPolicy::getId))
                .toList();
    }

    public List<PolicyTarget> listTargetsForPolicy(String callerAccountId, String policyId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        OrganizationPolicy policy = requirePolicy(organization, policyId);
        List<PolicyTarget> targets = new ArrayList<>();
        for (String targetId : policy.getTargets()) {
            targets.add(describeTarget(organization, targetId));
        }
        targets.sort(Comparator.comparing(PolicyTarget::targetId));
        return targets;
    }

    public Root enablePolicyType(String callerAccountId, String rootId, String policyType) {
        Organization organization = requireManagementAccount(callerAccountId);
        Root root = requireRoot(organization, rootId);
        validatePolicyType(policyType);
        if (!FEATURE_SET_ALL.equals(organization.getFeatureSet())) {
            throw new AwsException("PolicyTypeNotAvailableForOrganizationException",
                    "Policy type " + policyType + " requires an organization with all features enabled. "
                            + "Call EnableAllFeatures first.", 400);
        }
        Optional<PolicyTypeSummary> existing = findPolicyType(root, policyType);
        if (existing.isPresent() && "ENABLED".equals(existing.get().getStatus())) {
            throw new AwsException("PolicyTypeAlreadyEnabledException",
                    "The policy type " + policyType + " is already enabled.", 400);
        }
        if (existing.isPresent()) {
            existing.get().setStatus("ENABLED");
        } else {
            root.getPolicyTypes().add(new PolicyTypeSummary(policyType, "ENABLED"));
        }
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
        return root;
    }

    public Root disablePolicyType(String callerAccountId, String rootId, String policyType) {
        Organization organization = requireManagementAccount(callerAccountId);
        Root root = requireRoot(organization, rootId);
        validatePolicyType(policyType);
        PolicyTypeSummary summary = findPolicyType(root, policyType)
                .filter(entry -> "ENABLED".equals(entry.getStatus()))
                .orElseThrow(() -> new AwsException("PolicyTypeNotEnabledException",
                        "The policy type " + policyType + " is not enabled for this root.", 400));
        root.getPolicyTypes().remove(summary);
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
        return root;
    }

    /**
     * Rejects anything outside the {@code EffectivePolicyType} enum, which both
     * DescribeEffectivePolicy and ListAccountsWithInvalidEffectivePolicy draw their required
     * PolicyType from. Without it the latter answers 200 with an empty account list for a
     * policy type that does not exist, which reads as "nothing is broken".
     */
    private void validateEffectivePolicyType(String policyType) {
        if (policyType == null || !EFFECTIVE_POLICY_TYPE_SET.contains(policyType)) {
            throw invalidInput("PolicyType must be one of " + String.join(", ", EFFECTIVE_POLICY_TYPES) + ".");
        }
    }

    /**
     * Floci does not evaluate effective policies, so no account can be reported as carrying an
     * invalid one. The operation still has to validate its required PolicyType before it can
     * honestly answer "none".
     *
     * <p>The model restricts this to the management account or a delegated administrator, but
     * names no service that delegation must be scoped to and {@code EffectivePolicyType} maps to
     * none — there is nothing to check a delegated admin's registration against, because
     * delegated administration of Organizations' own operations is granted through the
     * organization's resource-based delegation policy ({@code PutResourcePolicy}), not through
     * {@code RegisterDelegatedAdministrator}'s per-service map. Floci exposes the resource-policy
     * CRUD but nothing reads it for authorization yet, so the concept genuinely isn't modelled
     * here — if it ever is, the resource policy is the hook. Management-only is stricter than the
     * model text, matching how every other operation carrying this same boilerplate phrase is
     * gated here ({@link #attachPolicy}, {@link #enablePolicyType},
     * {@link #registerDelegatedAdministrator}, {@link #listDelegatedAdministrators}).
     */
    public List<OrganizationAccount> listAccountsWithInvalidEffectivePolicy(String callerAccountId,
                                                                           String policyType) {
        requireManagementAccount(callerAccountId);
        validateEffectivePolicyType(policyType);
        return List.of();
    }

    /**
     * Merges every policy of {@code policyType} down the inheritance chain root → OU(s) → target,
     * with the closest ancestor taking precedence on conflicting keys.
     */
    public EffectivePolicy describeEffectivePolicy(String callerAccountId, String policyType, String targetId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        validateEffectivePolicyType(policyType);
        String effectiveTarget = targetId == null || targetId.isEmpty() ? callerAccountId : targetId;
        requireTarget(organization, effectiveTarget);

        List<String> chain = ancestryOf(organization, effectiveTarget);
        ObjectNode merged = objectMapper.createObjectNode();
        boolean found = false;
        for (String node : chain) {
            for (OrganizationPolicy policy : policiesIn(organization)) {
                if (!policyType.equals(policy.getType()) || !policy.getTargets().contains(node)) {
                    continue;
                }
                merged = deepMerge(merged, parsePolicyContent(policy));
                found = true;
            }
        }
        if (!found) {
            throw new AwsException("EffectivePolicyNotFoundException",
                    "No policy of type " + policyType + " applies to " + effectiveTarget + ".", 400);
        }
        return new EffectivePolicy(merged.toString(), policyType, effectiveTarget, Instant.now());
    }

    // ──────────────────────────── Control Tower guardrails ────────────────────────────

    static final String CONTROL_TOWER_GUARDRAIL_ID = "p-flocictguardrail";
    static final String CONTROL_TOWER_GUARDRAIL_NAME = "aws-guardrails-FlociControlTowerBaseline";

    /**
     * Reconciles the Organizations side effect of Control Tower OU registration. Real Control
     * Tower attaches customer-managed SCPs named {@code aws-guardrails-*}; LZA 1.14 uses that
     * observable contract to validate top-level OU governance. The Security OU is governed by
     * the landing zone itself and therefore may not appear in the enabled-baseline targets.
     */
    public void ensureControlTowerGuardrails(String callerAccountId, Set<String> registeredOuIds) {
        Organization organization;
        try {
            organization = requireManagementAccount(callerAccountId);
        } catch (AwsException e) {
            return;
        }
        Set<String> targetIds = new java.util.LinkedHashSet<>(registeredOuIds);
        organizationalUnitsIn(organization).stream()
                .filter(ou -> "Security".equals(ou.getName()))
                .map(OrganizationalUnit::getId)
                .forEach(targetIds::add);
        if (targetIds.isEmpty()) {
            return;
        }
        targetIds.forEach(targetId -> requireOrganizationalUnit(organization, targetId));

        OrganizationPolicy guardrail = policiesIn(organization).stream()
                .filter(policy -> SERVICE_CONTROL_POLICY.equals(policy.getType()))
                .filter(policy -> CONTROL_TOWER_GUARDRAIL_NAME.equals(policy.getName()))
                .findFirst()
                .orElseGet(() -> {
                    OrganizationPolicy policy = new OrganizationPolicy();
                    policy.setId(CONTROL_TOWER_GUARDRAIL_ID);
                    policy.setName(CONTROL_TOWER_GUARDRAIL_NAME);
                    policy.setDescription("Control Tower governance marker for registered OUs");
                    policy.setType(SERVICE_CONTROL_POLICY);
                    policy.setContent(FULL_AWS_ACCESS_CONTENT);
                    policy.setAwsManaged(false);
                    policy.setOrganizationId(organization.getId());
                    policy.setArn(policyArn(organization, CONTROL_TOWER_GUARDRAIL_ID, SERVICE_CONTROL_POLICY));
                    return policy;
                });

        if (guardrail.getTargets().addAll(targetIds) || guardrail.getTargets().isEmpty()) {
            policies.putForAccount(organization.getMasterAccountId(), guardrail.getId(), guardrail);
        }
    }

    // ──────────────────────────── SCP provider ────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Levels are ordered root → OUs on the path → account. Only bites when
     * {@code floci.services.organizations.scp-enforcement-enabled} is set (and, in practice,
     * IAM enforcement too — {@code IamEnforcementFilter} is the only consumer). The management
     * account is exempt, matching AWS.</p>
     */
    @Override
    public List<List<String>> effectiveScpLevels(String accountId) {
        if (!scpEnforcementEnabled) {
            return null;
        }
        Organization organization;
        try {
            organization = requireOrganizationForCaller(accountId);
        } catch (AwsException e) {
            return null;
        }
        if (accountId.equals(organization.getMasterAccountId())) {
            return null;
        }
        boolean scpEnabled = organization.getRoot().getPolicyTypes().stream()
                .anyMatch(t -> SERVICE_CONTROL_POLICY.equals(t.getType()) && "ENABLED".equals(t.getStatus()));
        if (!scpEnabled) {
            return null;
        }
        List<OrganizationPolicy> organizationPolicies = policiesIn(organization);
        List<List<String>> levels = new ArrayList<>();
        for (String node : ancestryOf(organization, accountId)) {
            List<String> documents = organizationPolicies.stream()
                    .filter(policy -> SERVICE_CONTROL_POLICY.equals(policy.getType())
                            && policy.getTargets().contains(node))
                    .map(OrganizationPolicy::getContent)
                    .toList();
            if (!documents.isEmpty()) {
                levels.add(documents);
            }
        }
        return levels.isEmpty() ? null : levels;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String callerAccountId, String resourceId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        return new LinkedHashMap<>(resolveTagOwner(organization, resourceId).tags());
    }

    public void tagResource(String callerAccountId, String resourceId, Map<String, String> tags) {
        Organization organization = requireManagementAccount(callerAccountId);
        validateTags(tags);
        mutateTags(organization, resourceId, target -> {
            if (tags != null) {
                target.putAll(tags);
            }
        });
    }

    public void untagResource(String callerAccountId, String resourceId, List<String> tagKeys) {
        Organization organization = requireManagementAccount(callerAccountId);
        mutateTags(organization, resourceId, target -> {
            if (tagKeys != null) {
                tagKeys.forEach(target::remove);
            }
        });
    }

    // ──────────────────────────── Trusted access and delegation ────────────────────────────

    public void enableAWSServiceAccess(String callerAccountId, String servicePrincipal) {
        Organization organization = requireManagementAccount(callerAccountId);
        validateServicePrincipal(servicePrincipal);
        organization.getEnabledServicePrincipals().putIfAbsent(servicePrincipal, Instant.now());
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
    }

    public void disableAWSServiceAccess(String callerAccountId, String servicePrincipal) {
        Organization organization = requireManagementAccount(callerAccountId);
        validateServicePrincipal(servicePrincipal);
        boolean stillDelegated = accountsIn(organization).stream()
                .anyMatch(account -> account.getDelegatedServices().containsKey(servicePrincipal));
        if (stillDelegated) {
            throw new AwsException("ConstraintViolationException",
                    "You must deregister the delegated administrators for " + servicePrincipal
                            + " before disabling trusted access.", 400);
        }
        organization.getEnabledServicePrincipals().remove(servicePrincipal);
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
    }

    public List<EnabledServicePrincipal> listAWSServiceAccessForOrganization(String callerAccountId) {
        Organization organization = requireManagementAccount(callerAccountId);
        return organization.getEnabledServicePrincipals().entrySet().stream()
                .map(entry -> new EnabledServicePrincipal(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(EnabledServicePrincipal::servicePrincipal))
                .toList();
    }

    public void registerDelegatedAdministrator(String callerAccountId, String accountId, String servicePrincipal) {
        Organization organization = requireManagementAccount(callerAccountId);
        validateServicePrincipal(servicePrincipal);
        OrganizationAccount account = requireAccount(organization, accountId);
        if (accountId.equals(organization.getMasterAccountId())) {
            throw new AwsException("ConstraintViolationException",
                    "The management account can't be registered as a delegated administrator.", 400);
        }
        if (account.getDelegatedServices().containsKey(servicePrincipal)) {
            throw new AwsException("AccountAlreadyRegisteredException",
                    "The account " + accountId + " is already a delegated administrator for "
                            + servicePrincipal + ".", 400);
        }
        organization.getEnabledServicePrincipals().putIfAbsent(servicePrincipal, Instant.now());
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
        account.getDelegatedServices().put(servicePrincipal, Instant.now());
        accounts.putForAccount(organization.getMasterAccountId(), accountId, account);
    }

    public void deregisterDelegatedAdministrator(String callerAccountId, String accountId, String servicePrincipal) {
        Organization organization = requireManagementAccount(callerAccountId);
        validateServicePrincipal(servicePrincipal);
        OrganizationAccount account = requireAccount(organization, accountId);
        if (account.getDelegatedServices().remove(servicePrincipal) == null) {
            throw new AwsException("AccountNotRegisteredException",
                    "The account " + accountId + " is not a delegated administrator for "
                            + servicePrincipal + ".", 400);
        }
        accounts.putForAccount(organization.getMasterAccountId(), accountId, account);
    }

    public List<OrganizationAccount> listDelegatedAdministrators(String callerAccountId, String servicePrincipal) {
        Organization organization = requireManagementAccount(callerAccountId);
        if (servicePrincipal != null) {
            validateServicePrincipal(servicePrincipal);
        }
        return accountsIn(organization).stream()
                .filter(account -> servicePrincipal == null
                        ? !account.getDelegatedServices().isEmpty()
                        : account.getDelegatedServices().containsKey(servicePrincipal))
                .sorted(Comparator.comparing(OrganizationAccount::getId))
                .toList();
    }

    public List<DelegatedService> listDelegatedServicesForAccount(String callerAccountId, String accountId) {
        Organization organization = requireManagementAccount(callerAccountId);
        OrganizationAccount account = requireAccount(organization, accountId);
        if (account.getDelegatedServices().isEmpty()) {
            throw new AwsException("AccountNotRegisteredException",
                    "The account " + accountId + " is not a delegated administrator for any service.", 400);
        }
        return account.getDelegatedServices().entrySet().stream()
                .map(entry -> new DelegatedService(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(DelegatedService::servicePrincipal))
                .toList();
    }

    // ──────────────────────────── Resource policy ────────────────────────────

    public ResourcePolicyView putResourcePolicy(String callerAccountId, String content, Map<String, String> tags) {
        Organization organization = requireManagementAccount(callerAccountId);
        if (content == null || content.isBlank()) {
            throw invalidInput("Content must not be empty.");
        }
        validateTags(tags);
        if (organization.getResourcePolicyId() == null) {
            String policyId = "rp-" + randomId(8);
            organization.setResourcePolicyId(policyId);
            organization.setResourcePolicyArn(
                    arn(organization.getMasterAccountId(), "resourcepolicy/" + organization.getId() + "/" + policyId));
        }
        organization.setResourcePolicyContent(content);
        if (tags != null) {
            // Put replaces rather than merges: a caller that supplies Tags is stating the full set,
            // so a key it no longer lists is dropped. The resource policy is not addressable by
            // TagResource/UntagResource the way accounts, OUs, roots and policies are, so this call
            // is the only way CloudFormation can converge Tags on an update. Omitting Tags
            // entirely (null) leaves the existing ones untouched.
            organization.getResourcePolicyTags().clear();
            organization.getResourcePolicyTags().putAll(tags);
        }
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
        return resourcePolicyView(organization);
    }

    public ResourcePolicyView describeResourcePolicy(String callerAccountId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        if (organization.getResourcePolicyContent() == null) {
            throw new AwsException("ResourcePolicyNotFoundException",
                    "The organization doesn't have a resource policy.", 400);
        }
        return resourcePolicyView(organization);
    }

    public void deleteResourcePolicy(String callerAccountId) {
        Organization organization = requireManagementAccount(callerAccountId);
        if (organization.getResourcePolicyContent() == null) {
            throw new AwsException("ResourcePolicyNotFoundException",
                    "The organization doesn't have a resource policy.", 400);
        }
        organization.setResourcePolicyId(null);
        organization.setResourcePolicyArn(null);
        organization.setResourcePolicyContent(null);
        organization.getResourcePolicyTags().clear();
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
    }

    // ──────────────────────────── Handshakes ────────────────────────────

    public Handshake inviteAccountToOrganization(String callerAccountId, String targetId, String targetType,
                                                 String notes) {
        Organization organization = requireManagementAccount(callerAccountId);
        if (targetId == null || targetId.isEmpty()) {
            throw invalidInput("Target.Id is required.");
        }
        String resolvedType = targetType == null || targetType.isEmpty() ? "ACCOUNT" : targetType;
        if (!"ACCOUNT".equals(resolvedType) && !"EMAIL".equals(resolvedType)) {
            throw invalidInput("Target.Type must be ACCOUNT or EMAIL.");
        }

        String invitedAccountId = null;
        String invitedEmail = null;
        if ("ACCOUNT".equals(resolvedType)) {
            if (!ACCOUNT_ID_PATTERN.matcher(targetId).matches()) {
                throw invalidInput("Target.Id must be a 12-digit account id when Target.Type is ACCOUNT.");
            }
            invitedAccountId = targetId;
            if (accountsIn(organization).stream().anyMatch(a -> a.getId().equals(targetId))) {
                throw new AwsException("AccountAlreadyRegisteredException",
                        "The account " + targetId + " is already a member of this organization.", 400);
            }
            findOrganizationForAccount(targetId).ifPresent(other -> {
                throw new AwsException("HandshakeConstraintViolationException",
                        "The account " + targetId + " is already a member of another organization.", 400);
            });
        } else {
            validateEmail(targetId);
            invitedEmail = targetId;
        }

        String finalInvitedAccountId = invitedAccountId;
        String finalInvitedEmail = invitedEmail;
        boolean duplicate = handshakesIn(organization).stream()
                .filter(existing -> HANDSHAKE_REQUESTED.equals(effectiveState(existing)))
                .anyMatch(existing -> (finalInvitedAccountId != null
                        && finalInvitedAccountId.equals(existing.getTargetAccountId()))
                        || (finalInvitedEmail != null && finalInvitedEmail.equals(existing.getTargetEmail())));
        if (duplicate) {
            throw new AwsException("DuplicateHandshakeException",
                    "An open invitation to " + targetId + " already exists.", 400);
        }

        Handshake handshake = newHandshake(organization, HANDSHAKE_INVITE);
        handshake.setTargetAccountId(invitedAccountId);
        handshake.setTargetEmail(invitedEmail);
        handshake.getParties().add(new HandshakeParty(organization.getId(), "ORGANIZATION"));
        handshake.getParties().add(new HandshakeParty(targetId, resolvedType));

        // MASTER_EMAIL and MASTER_NAME are the two child resources AWS defines on an INVITE's
        // ORGANIZATION resource. HandshakeResourceType is a closed enum, so emitting anything
        // outside it (an account id, say) deserializes as UNKNOWN_TO_SDK_VERSION in the SDK.
        HandshakeResource organizationResource = new HandshakeResource(organization.getId(), "ORGANIZATION");
        organizationResource.getResources()
                .add(new HandshakeResource(organization.getMasterAccountEmail(), "MASTER_EMAIL"));
        organizationResource.getResources()
                .add(new HandshakeResource(managementAccountName(organization), "MASTER_NAME"));
        handshake.getResources().add(organizationResource);
        handshake.getResources().add(new HandshakeResource(targetId, resolvedType));
        if (notes != null && !notes.isEmpty()) {
            handshake.getResources().add(new HandshakeResource(notes, "NOTES"));
        }

        handshakes.putForAccount(organization.getMasterAccountId(), handshake.getId(), handshake);
        return handshake;
    }

    public Handshake acceptHandshake(String callerAccountId, String handshakeId) {
        Handshake handshake = requireHandshake(handshakeId);
        Organization organization = requireOrganizationById(handshake.getOrganizationId());
        requireOpen(handshake);

        if (HANDSHAKE_ENABLE_ALL_FEATURES.equals(handshake.getAction())) {
            requireCallerInOrganization(organization, callerAccountId);
            applyEnableAllFeatures(organization);
        } else {
            requireInvitee(handshake, callerAccountId);
            if (accountsIn(organization).stream().anyMatch(a -> a.getId().equals(callerAccountId))) {
                throw new AwsException("AccountAlreadyRegisteredException",
                        "The account " + callerAccountId + " is already a member of this organization.", 400);
            }
            findOrganizationForAccount(callerAccountId).ifPresent(other -> {
                throw new AwsException("HandshakeConstraintViolationException",
                        "The account " + callerAccountId + " is already a member of another organization.", 400);
            });
            String email = handshake.getTargetEmail() != null
                    ? handshake.getTargetEmail()
                    : "account-" + callerAccountId + "@example.com";
            OrganizationAccount account = newMemberAccount(
                    organization, callerAccountId, email, "invited-" + callerAccountId, "INVITED");
            accounts.putForAccount(organization.getMasterAccountId(), callerAccountId, account);
            attachFullAwsAccess(organization, callerAccountId);
            handshake.setTargetAccountId(callerAccountId);
        }

        handshake.setState(HANDSHAKE_ACCEPTED);
        handshakes.putForAccount(organization.getMasterAccountId(), handshakeId, handshake);
        return handshake;
    }

    public Handshake declineHandshake(String callerAccountId, String handshakeId) {
        Handshake handshake = requireHandshake(handshakeId);
        Organization organization = requireOrganizationById(handshake.getOrganizationId());
        requireOpen(handshake);
        requireInvitee(handshake, callerAccountId);
        handshake.setState(HANDSHAKE_DECLINED);
        handshakes.putForAccount(organization.getMasterAccountId(), handshakeId, handshake);
        return handshake;
    }

    public Handshake cancelHandshake(String callerAccountId, String handshakeId) {
        Handshake handshake = requireHandshake(handshakeId);
        Organization organization = requireOrganizationById(handshake.getOrganizationId());
        requireOpen(handshake);
        if (!organization.getMasterAccountId().equals(callerAccountId)) {
            throw accessDenied("Only the account that sent the invitation can cancel it.");
        }
        handshake.setState(HANDSHAKE_CANCELED);
        handshakes.putForAccount(organization.getMasterAccountId(), handshakeId, handshake);
        return handshake;
    }

    public Handshake describeHandshake(String callerAccountId, String handshakeId) {
        Handshake handshake = requireHandshake(handshakeId);
        Organization organization = requireOrganizationById(handshake.getOrganizationId());
        // AWS scopes DescribeHandshake to the two parties — the originating account and the
        // recipient — not to the organization at large.
        if (!organization.getMasterAccountId().equals(callerAccountId)
                && !isInvitee(handshake, callerAccountId)) {
            throw accessDenied("You don't have permission to view this handshake.");
        }
        handshake.setState(effectiveState(handshake));
        return handshake;
    }

    public List<Handshake> listHandshakesForAccount(String callerAccountId, List<String> states,
                                                    String actionFilter) {
        // The organizations a handshake could belong to are scanned once, not once per handshake:
        // organizationById is itself a full scan, so calling it in the loop made this quadratic.
        Map<String, String> masterByOrganizationId = new LinkedHashMap<>();
        for (Organization organization : organizations.scanAllAccounts()) {
            masterByOrganizationId.put(organization.getId(), organization.getMasterAccountId());
        }

        List<Handshake> matching = new ArrayList<>();
        for (Handshake handshake : handshakes.scanAllAccounts()) {
            boolean visible = isInvitee(handshake, callerAccountId)
                    || callerAccountId.equals(masterByOrganizationId.get(handshake.getOrganizationId()));
            if (visible) {
                matching.add(handshake);
            }
        }
        return filterHandshakes(matching, states, actionFilter);
    }

    public List<Handshake> listHandshakesForOrganization(String callerAccountId, List<String> states,
                                                         String actionFilter) {
        Organization organization = requireManagementAccount(callerAccountId);
        return filterHandshakes(handshakesIn(organization), states, actionFilter);
    }

    // ──────────────────────────── Authorization ────────────────────────────

    /**
     * Resolves the organization the caller belongs to, whether as the management account or as a
     * member. A caller outside any organization gets {@code AWSOrganizationsNotInUseException},
     * which is what the AWS SDK surfaces before {@code CreateOrganization} has been called.
     */
    Organization requireOrganizationForCaller(String callerAccountId) {
        return findOrganizationForAccount(callerAccountId)
                .orElseThrow(() -> new AwsException("AWSOrganizationsNotInUseException",
                        "Your account is not a member of an organization.", 400));
    }

    /** Every mutating action is management-account only, matching AWS. */
    Organization requireManagementAccount(String callerAccountId) {
        Organization organization = requireOrganizationForCaller(callerAccountId);
        if (!organization.getMasterAccountId().equals(callerAccountId)) {
            throw accessDenied("This operation can be performed only by the management account of the organization.");
        }
        return organization;
    }

    /**
     * The management account that owns the given organization, root, OU, account, policy or
     * resource-policy id, or empty when nothing matches.
     *
     * <p>CloudFormation's delete path carries no caller identity — {@code CfnResourceProvisioner}
     * hands over a physical id and a region only — so the owner has to be recovered from the
     * resource itself before a management-account-scoped operation can run against it.
     */
    public Optional<String> findManagementAccountForResource(String resourceId) {
        if (resourceId == null || resourceId.isEmpty()) {
            return Optional.empty();
        }
        for (Organization organization : organizations.scanAllAccounts()) {
            boolean owns = resourceId.equals(organization.getId())
                    || resourceId.equals(organization.getRoot().getId())
                    || resourceId.equals(organization.getResourcePolicyId());
            if (owns) {
                return Optional.of(organization.getMasterAccountId());
            }
        }
        if (ACCOUNT_ID_PATTERN.matcher(resourceId).matches()) {
            return accounts.scanAllAccounts().stream()
                    .filter(account -> resourceId.equals(account.getId()))
                    .findFirst()
                    .flatMap(account -> organizationById(account.getOrganizationId()))
                    .map(Organization::getMasterAccountId);
        }
        if (OU_ID_PATTERN.matcher(resourceId).matches()) {
            return organizationalUnits.scanAllAccounts().stream()
                    .filter(unit -> resourceId.equals(unit.getId()))
                    .findFirst()
                    .flatMap(unit -> organizationById(unit.getOrganizationId()))
                    .map(Organization::getMasterAccountId);
        }
        if (POLICY_ID_PATTERN.matcher(resourceId).matches()) {
            return policies.scanAllAccounts().stream()
                    .filter(policy -> resourceId.equals(policy.getId()))
                    .findFirst()
                    .flatMap(policy -> organizationById(policy.getOrganizationId()))
                    .map(Organization::getMasterAccountId);
        }
        return Optional.empty();
    }

    private Optional<Organization> findOrganizationForAccount(String accountId) {
        for (Organization organization : organizations.scanAllAccounts()) {
            if (accountId.equals(organization.getMasterAccountId())) {
                return Optional.of(organization);
            }
        }
        for (OrganizationAccount account : accounts.scanAllAccounts()) {
            if (accountId.equals(account.getId())) {
                Optional<Organization> organization = organizationById(account.getOrganizationId());
                if (organization.isPresent()) {
                    return organization;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Organization> organizationById(String organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return organizations.scanAllAccounts().stream()
                .filter(organization -> organizationId.equals(organization.getId()))
                .findFirst();
    }

    private Organization requireOrganizationById(String organizationId) {
        return organizationById(organizationId)
                .orElseThrow(() -> new AwsException("AWSOrganizationsNotInUseException",
                        "The organization no longer exists.", 400));
    }

    // ──────────────────────────── Collection helpers ────────────────────────────

    private List<OrganizationAccount> accountsIn(Organization organization) {
        return accounts.scanAllAccounts().stream()
                .filter(account -> organization.getId().equals(account.getOrganizationId()))
                .toList();
    }

    private List<OrganizationalUnit> organizationalUnitsIn(Organization organization) {
        return organizationalUnits.scanAllAccounts().stream()
                .filter(ou -> organization.getId().equals(ou.getOrganizationId()))
                .toList();
    }

    private List<OrganizationPolicy> policiesIn(Organization organization) {
        return policies.scanAllAccounts().stream()
                .filter(policy -> organization.getId().equals(policy.getOrganizationId()))
                .toList();
    }

    private List<Handshake> handshakesIn(Organization organization) {
        return handshakes.scanAllAccounts().stream()
                .filter(handshake -> organization.getId().equals(handshake.getOrganizationId()))
                .toList();
    }

    private List<CreateAccountStatus> createAccountStatusesIn(Organization organization) {
        return createAccountStatuses.scanAllAccounts().stream()
                .filter(status -> organization.getId().equals(status.getOrganizationId()))
                .toList();
    }

    // ──────────────────────────── Lookup helpers ────────────────────────────

    private OrganizationAccount requireAccount(Organization organization, String accountId) {
        if (accountId == null || !ACCOUNT_ID_PATTERN.matcher(accountId).matches()) {
            throw invalidInput("Account id must be 12 digits.");
        }
        return accountsIn(organization).stream()
                .filter(account -> accountId.equals(account.getId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("AccountNotFoundException",
                        "We can't find an AWS account with the AccountId " + accountId + ".", 400));
    }

    private OrganizationalUnit requireOrganizationalUnit(Organization organization, String ouId) {
        if (ouId == null || !OU_ID_PATTERN.matcher(ouId).matches()) {
            throw invalidInput("OrganizationalUnitId must match ou-[0-9a-z]{4,32}-[0-9a-z]{8,32}.");
        }
        return organizationalUnitsIn(organization).stream()
                .filter(ou -> ouId.equals(ou.getId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("OrganizationalUnitNotFoundException",
                        "We can't find an OU with the OrganizationalUnitId " + ouId + ".", 400));
    }

    private OrganizationPolicy requirePolicy(Organization organization, String policyId) {
        if (policyId == null || !POLICY_ID_PATTERN.matcher(policyId).matches()) {
            throw invalidInput("PolicyId must match p-[0-9a-zA-Z_]{8,128}.");
        }
        return policiesIn(organization).stream()
                .filter(policy -> policyId.equals(policy.getId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("PolicyNotFoundException",
                        "We can't find a policy with the PolicyId " + policyId + ".", 400));
    }

    private Root requireRoot(Organization organization, String rootId) {
        if (rootId == null || !ROOT_ID_PATTERN.matcher(rootId).matches()) {
            throw invalidInput("RootId must match r-[0-9a-z]{4,32}.");
        }
        if (!organization.getRoot().getId().equals(rootId)) {
            throw new AwsException("RootNotFoundException",
                    "We can't find a root with the RootId " + rootId + ".", 400);
        }
        return organization.getRoot();
    }

    private Handshake requireHandshake(String handshakeId) {
        if (handshakeId == null || handshakeId.isEmpty()) {
            throw invalidInput("HandshakeId is required.");
        }
        return handshakes.scanAllAccounts().stream()
                .filter(handshake -> handshakeId.equals(handshake.getId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("HandshakeNotFoundException",
                        "We can't find a handshake with the HandshakeId " + handshakeId + ".", 400));
    }

    /** A parent is the root or an OU; anything else is rejected before it can be used as one. */
    private void requireParent(Organization organization, String parentId) {
        if (parentId == null || parentId.isEmpty()) {
            throw invalidInput("ParentId is required.");
        }
        if (ROOT_ID_PATTERN.matcher(parentId).matches()) {
            requireRoot(organization, parentId);
            return;
        }
        if (OU_ID_PATTERN.matcher(parentId).matches()) {
            requireOrganizationalUnit(organization, parentId);
            return;
        }
        throw new AwsException("ParentNotFoundException",
                "We can't find a parent with the ParentId " + parentId + ".", 400);
    }

    private boolean accountParentExists(Organization organization, String parentId) {
        if (parentId == null) {
            return false;
        }
        if (organization.getRoot().getId().equals(parentId)) {
            return true;
        }
        return organizationalUnitsIn(organization).stream().anyMatch(ou -> parentId.equals(ou.getId()));
    }

    /** A policy target is the root, an OU, or an account. */
    private void requireTarget(Organization organization, String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            throw invalidInput("TargetId is required.");
        }
        if (ACCOUNT_ID_PATTERN.matcher(targetId).matches()) {
            requireAccount(organization, targetId);
            return;
        }
        if (ROOT_ID_PATTERN.matcher(targetId).matches()) {
            requireRoot(organization, targetId);
            return;
        }
        if (OU_ID_PATTERN.matcher(targetId).matches()) {
            requireOrganizationalUnit(organization, targetId);
            return;
        }
        throw new AwsException("TargetNotFoundException",
                "We can't find a target with the TargetId " + targetId + ".", 400);
    }

    private PolicyTarget describeTarget(Organization organization, String targetId) {
        if (ACCOUNT_ID_PATTERN.matcher(targetId).matches()) {
            OrganizationAccount account = requireAccount(organization, targetId);
            return new PolicyTarget(account.getId(), account.getArn(), account.getName(), "ACCOUNT");
        }
        if (organization.getRoot().getId().equals(targetId)) {
            Root root = organization.getRoot();
            return new PolicyTarget(root.getId(), root.getArn(), root.getName(), "ROOT");
        }
        OrganizationalUnit unit = requireOrganizationalUnit(organization, targetId);
        return new PolicyTarget(unit.getId(), unit.getArn(), unit.getName(), "ORGANIZATIONAL_UNIT");
    }

    private String parentOf(Organization organization, String childId) {
        if (childId == null || childId.isEmpty()) {
            throw invalidInput("ChildId is required.");
        }
        if (ACCOUNT_ID_PATTERN.matcher(childId).matches()) {
            return requireAccount(organization, childId).getParentId();
        }
        if (OU_ID_PATTERN.matcher(childId).matches()) {
            return requireOrganizationalUnit(organization, childId).getParentId();
        }
        throw new AwsException("ChildNotFoundException",
                "We can't find a child with the ChildId " + childId + ".", 400);
    }

    private String parentType(Organization organization, String parentId) {
        return organization.getRoot().getId().equals(parentId) ? "ROOT" : "ORGANIZATIONAL_UNIT";
    }

    /** The chain from the root down to (and including) {@code targetId}. */
    private List<String> ancestryOf(Organization organization, String targetId) {
        List<String> chain = new ArrayList<>();
        String current = targetId;
        while (current != null && !organization.getRoot().getId().equals(current)) {
            chain.add(current);
            current = parentOf(organization, current);
        }
        chain.add(organization.getRoot().getId());
        java.util.Collections.reverse(chain);
        return chain;
    }

    // ──────────────────────────── Tag helpers ────────────────────────────

    /** A taggable resource resolved once: its live tag map plus how to write the owner back. */
    private record TagOwner(Map<String, String> tags, Runnable persist) {}

    /**
     * Resolves a taggable resource to the object that actually holds its tags. Resolving and
     * persisting have to work on the same instance — looking the owner up a second time to save it
     * would write back a copy that never saw the mutation under any non-in-memory storage mode.
     */
    private TagOwner resolveTagOwner(Organization organization, String resourceId) {
        if (resourceId == null || resourceId.isEmpty()) {
            throw invalidInput("ResourceId is required.");
        }
        String master = organization.getMasterAccountId();
        if (ACCOUNT_ID_PATTERN.matcher(resourceId).matches()) {
            OrganizationAccount account = requireAccount(organization, resourceId);
            return new TagOwner(account.getTags(),
                    () -> accounts.putForAccount(master, resourceId, account));
        }
        if (ROOT_ID_PATTERN.matcher(resourceId).matches()) {
            Root root = requireRoot(organization, resourceId);
            return new TagOwner(root.getTags(),
                    () -> organizations.putForAccount(master, organization.getId(), organization));
        }
        if (OU_ID_PATTERN.matcher(resourceId).matches()) {
            OrganizationalUnit unit = requireOrganizationalUnit(organization, resourceId);
            return new TagOwner(unit.getTags(),
                    () -> organizationalUnits.putForAccount(master, resourceId, unit));
        }
        if (POLICY_ID_PATTERN.matcher(resourceId).matches()) {
            OrganizationPolicy policy = requirePolicy(organization, resourceId);
            return new TagOwner(policy.getTags(),
                    () -> policies.putForAccount(master, resourceId, policy));
        }
        throw new AwsException("TargetNotFoundException",
                "We can't find a resource with the ResourceId " + resourceId + ".", 400);
    }

    private void mutateTags(Organization organization, String resourceId,
                            Consumer<Map<String, String>> mutation) {
        TagOwner owner = resolveTagOwner(organization, resourceId);
        mutation.accept(owner.tags());
        owner.persist().run();
    }

    // ──────────────────────────── Internal helpers ────────────────────────────

    private void createFullAwsAccessPolicy(Organization organization, String rootId, String masterAccountId) {
        OrganizationPolicy policy = new OrganizationPolicy();
        policy.setId(FULL_AWS_ACCESS_POLICY_ID);
        policy.setName("FullAWSAccess");
        policy.setDescription("Allows access to every operation");
        policy.setType(SERVICE_CONTROL_POLICY);
        policy.setAwsManaged(true);
        policy.setContent(FULL_AWS_ACCESS_CONTENT);
        policy.setOrganizationId(organization.getId());
        policy.setArn(policyArn(organization, FULL_AWS_ACCESS_POLICY_ID, SERVICE_CONTROL_POLICY));
        policy.getTargets().add(rootId);
        policy.getTargets().add(masterAccountId);
        policies.putForAccount(masterAccountId, FULL_AWS_ACCESS_POLICY_ID, policy);
    }

    /**
     * AWS attaches FullAWSAccess to every new OU and account so the target is not implicitly
     * denied everything the moment it is created.
     */
    private void attachFullAwsAccess(Organization organization, String targetId) {
        policies.getForAccount(organization.getMasterAccountId(), FULL_AWS_ACCESS_POLICY_ID)
                .ifPresent(policy -> {
                    policy.getTargets().add(targetId);
                    policies.putForAccount(organization.getMasterAccountId(), FULL_AWS_ACCESS_POLICY_ID, policy);
                });
    }

    private void detachAllPoliciesFrom(Organization organization, String targetId) {
        for (OrganizationPolicy policy : policiesIn(organization)) {
            if (policy.getTargets().remove(targetId)) {
                policies.putForAccount(organization.getMasterAccountId(), policy.getId(), policy);
            }
        }
    }

    private int countAttachedPolicies(Organization organization, String targetId, String type) {
        return (int) policiesIn(organization).stream()
                .filter(policy -> type.equals(policy.getType()) && policy.getTargets().contains(targetId))
                .count();
    }

    private OrganizationAccount newMemberAccount(Organization organization, String accountId, String email,
                                                 String name, String joinedMethod) {
        OrganizationAccount account = new OrganizationAccount();
        account.setId(accountId);
        account.setArn(arn(organization.getMasterAccountId(),
                "account/" + organization.getId() + "/" + accountId));
        account.setEmail(email);
        account.setName(name);
        account.setStatus(STATUS_ACTIVE);
        account.setJoinedMethod(joinedMethod);
        account.setJoinedTimestamp(Instant.now());
        account.setOrganizationId(organization.getId());
        account.setParentId(organization.getRoot().getId());
        return account;
    }

    private String allocateAccountId(Organization organization) {
        List<OrganizationAccount> existing = accountsIn(organization);
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder builder = new StringBuilder(12);
            builder.append(1 + random.nextInt(9));
            for (int i = 1; i < 12; i++) {
                builder.append(random.nextInt(10));
            }
            String candidate = builder.toString();
            boolean taken = existing.stream().anyMatch(account -> candidate.equals(account.getId()));
            if (!taken) {
                return candidate;
            }
        }
        throw new AwsException("ConstraintViolationException",
                "Unable to allocate a unique account id.", 400);
    }

    /** The management account's display name, falling back to its id if the record is missing. */
    private String managementAccountName(Organization organization) {
        return accountsIn(organization).stream()
                .filter(account -> account.getId().equals(organization.getMasterAccountId()))
                .map(OrganizationAccount::getName)
                .findFirst()
                .orElse(organization.getMasterAccountId());
    }

    private Handshake newHandshake(Organization organization, String action) {
        Instant now = Instant.now();
        Handshake handshake = new Handshake();
        handshake.setId("h-" + randomId(8));
        handshake.setAction(action);
        handshake.setState(HANDSHAKE_REQUESTED);
        handshake.setRequestedTimestamp(now);
        handshake.setExpirationTimestamp(now.plus(HANDSHAKE_TTL_DAYS, ChronoUnit.DAYS));
        handshake.setOrganizationId(organization.getId());
        handshake.setArn(arn(organization.getMasterAccountId(),
                "handshake/" + organization.getId() + "/" + action.toLowerCase(java.util.Locale.ROOT)
                        + "/" + handshake.getId()));
        return handshake;
    }

    private void applyEnableAllFeatures(Organization organization) {
        organization.setFeatureSet(FEATURE_SET_ALL);
        if (findPolicyType(organization.getRoot(), SERVICE_CONTROL_POLICY).isEmpty()) {
            organization.getRoot().getPolicyTypes().add(new PolicyTypeSummary(SERVICE_CONTROL_POLICY, "ENABLED"));
        }
        organizations.putForAccount(organization.getMasterAccountId(), organization.getId(), organization);
    }

    /** A handshake left open past its expiry reports EXPIRED without needing a sweeper. */
    private String effectiveState(Handshake handshake) {
        if (HANDSHAKE_REQUESTED.equals(handshake.getState())
                && handshake.getExpirationTimestamp() != null
                && Instant.now().isAfter(handshake.getExpirationTimestamp())) {
            return HANDSHAKE_EXPIRED;
        }
        return handshake.getState();
    }

    private void requireOpen(Handshake handshake) {
        String state = effectiveState(handshake);
        if (!HANDSHAKE_REQUESTED.equals(state)) {
            throw new AwsException("InvalidHandshakeTransitionException",
                    "The handshake " + handshake.getId() + " is in state " + state
                            + " and can no longer be acted on.", 400);
        }
    }

    private boolean isInvitee(Handshake handshake, String accountId) {
        return accountId != null && accountId.equals(handshake.getTargetAccountId());
    }

    private void requireInvitee(Handshake handshake, String callerAccountId) {
        // An EMAIL-targeted invitation names no account up front, so any account outside the
        // organization may claim it — which is exactly how the real console flow behaves.
        if (handshake.getTargetAccountId() == null && handshake.getTargetEmail() != null) {
            return;
        }
        if (!isInvitee(handshake, callerAccountId)) {
            throw accessDenied("This handshake was not sent to your account.");
        }
    }

    private boolean isMember(Organization organization, String accountId) {
        return accountsIn(organization).stream().anyMatch(account -> account.getId().equals(accountId));
    }

    private void requireCallerInOrganization(Organization organization, String callerAccountId) {
        if (!isMember(organization, callerAccountId)) {
            throw accessDenied("Your account is not a member of this organization.");
        }
    }

    private List<Handshake> filterHandshakes(List<Handshake> candidates, List<String> states, String actionFilter) {
        for (Handshake handshake : candidates) {
            handshake.setState(effectiveState(handshake));
        }
        return candidates.stream()
                .filter(handshake -> states == null || states.isEmpty() || states.contains(handshake.getState()))
                .filter(handshake -> actionFilter == null || actionFilter.equals(handshake.getAction()))
                .sorted(Comparator.comparing(Handshake::getId))
                .toList();
    }

    private ResourcePolicyView resourcePolicyView(Organization organization) {
        return new ResourcePolicyView(
                organization.getResourcePolicyId(),
                organization.getResourcePolicyArn(),
                organization.getResourcePolicyContent(),
                new LinkedHashMap<>(organization.getResourcePolicyTags()));
    }

    private Optional<PolicyTypeSummary> findPolicyType(Root root, String policyType) {
        return root.getPolicyTypes().stream()
                .filter(entry -> entry.getType().equals(policyType))
                .findFirst();
    }

    private void requirePolicyTypeEnabled(Organization organization, String policyType) {
        boolean enabled = findPolicyType(organization.getRoot(), policyType)
                .map(entry -> "ENABLED".equals(entry.getStatus()))
                .orElse(false);
        if (!enabled) {
            throw new AwsException("PolicyTypeNotEnabledException",
                    "The policy type " + policyType + " is not enabled for this organization. "
                            + "Call EnablePolicyType on the root first.", 400);
        }
    }

    private void requireNotAwsManaged(OrganizationPolicy policy) {
        if (policy.isAwsManaged()) {
            throw new AwsException("ConstraintViolationException",
                    "The policy " + policy.getId() + " is managed by AWS and can't be modified.", 400);
        }
    }

    private ObjectNode parsePolicyContent(OrganizationPolicy policy) {
        try {
            JsonNode parsed = objectMapper.readTree(policy.getContent());
            if (parsed instanceof ObjectNode objectNode) {
                return objectNode;
            }
        } catch (Exception e) {
            LOG.debugv(e, "Policy {0} content is not a JSON object; skipping it in the effective policy",
                    policy.getId());
        }
        return objectMapper.createObjectNode();
    }

    /** Child values win, matching how AWS resolves a conflicting key further down the tree. */
    private ObjectNode deepMerge(ObjectNode parent, ObjectNode child) {
        ObjectNode merged = parent.deepCopy();
        child.fields().forEachRemaining(entry -> {
            JsonNode existing = merged.get(entry.getKey());
            if (existing instanceof ObjectNode existingObject && entry.getValue() instanceof ObjectNode childObject) {
                merged.set(entry.getKey(), deepMerge(existingObject, childObject));
            } else {
                merged.set(entry.getKey(), entry.getValue());
            }
        });
        return merged;
    }

    private String policyArn(Organization organization, String policyId, String type) {
        return arn(organization.getMasterAccountId(),
                "policy/" + organization.getId() + "/" + type.toLowerCase(java.util.Locale.ROOT) + "/" + policyId);
    }

    /** Organizations ARNs carry no region, so the region segment is deliberately empty. */
    private String arn(String accountId, String resource) {
        return AwsArnUtils.Arn.of("organizations", "", accountId, resource).toString();
    }

    private String randomId(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return builder.toString();
    }

    // ──────────────────────────── Validation ────────────────────────────

    private void validatePolicyType(String type) {
        if (type == null || !POLICY_TYPE_SET.contains(type)) {
            throw invalidInput("PolicyType must be one of " + String.join(", ", POLICY_TYPES) + ".");
        }
    }

    private void validateName(String name, String field, int maxLength) {
        if (name == null || name.isEmpty()) {
            throw invalidInput(field + " is required.");
        }
        if (name.length() > maxLength) {
            throw invalidInput(field + " must be " + maxLength + " characters or fewer.");
        }
    }

    private void validateEmail(String email) {
        if (email == null || email.isEmpty()) {
            throw invalidInput("Email is required.");
        }
        if (email.length() < 6 || email.length() > 64 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw invalidInput("Email must be a valid address between 6 and 64 characters.");
        }
    }

    private void validateServicePrincipal(String servicePrincipal) {
        if (servicePrincipal == null || !SERVICE_PRINCIPAL_PATTERN.matcher(servicePrincipal).matches()) {
            throw invalidInput("ServicePrincipal must match [\\w+=,.@-]{1,128}.");
        }
    }

    private void validateTags(Map<String, String> tags) {
        if (tags == null) {
            return;
        }
        tags.forEach((key, value) -> {
            if (key == null || key.isEmpty() || key.length() > MAX_TAG_KEY_LENGTH) {
                throw invalidInput("Tag keys must be between 1 and " + MAX_TAG_KEY_LENGTH + " characters.");
            }
            if (value != null && value.length() > MAX_TAG_VALUE_LENGTH) {
                throw invalidInput("Tag values must be " + MAX_TAG_VALUE_LENGTH + " characters or fewer.");
            }
        });
    }

    private static AwsException invalidInput(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException accessDenied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }
}
