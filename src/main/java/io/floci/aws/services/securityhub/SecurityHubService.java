package io.floci.aws.services.securityhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.securityhub.model.SecurityHubAssociation;
import io.floci.aws.services.securityhub.model.SecurityHubMember;
import io.floci.aws.services.securityhub.model.SecurityHubState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class SecurityHubService implements Resettable {
    private static final String SELF_MANAGED = "SELF_MANAGED_SECURITY_HUB";
    private static final String MEMBER_CREATED = "Created";
    private static final String MEMBER_ENABLED = "Enabled";
    private static final String MEMBER_REMOVED = "Removed";
    private static final List<String> LINKING_MODES = List.of(
            "ALL_REGIONS", "ALL_REGIONS_EXCEPT_SPECIFIED", "SPECIFIED_REGIONS", "NO_REGIONS");

    private final AccountAwareStorageBackend<SecurityHubState> states;
    private final RegionResolver regionResolver;
    private final OrganizationsService organizationsService;

    @Inject
    public SecurityHubService(StorageFactory storageFactory, RegionResolver regionResolver,
                              OrganizationsService organizationsService) {
        this(storageFactory.create("securityhub", "securityhub-state.json",
                new TypeReference<Map<String, SecurityHubState>>() {}), regionResolver, organizationsService);
    }

    SecurityHubService(AccountAwareStorageBackend<SecurityHubState> states, RegionResolver regionResolver,
                       OrganizationsService organizationsService) {
        this.states = states;
        this.regionResolver = regionResolver;
        this.organizationsService = organizationsService;
    }

    public SecurityHubState state(String region) {
        return states.get(region).orElseGet(SecurityHubState::new);
    }

    public SecurityHubState organizationAdminState(String region) {
        String callerAccountId = regionResolver.getAccountId();
        String managementAccountId = organizationsService.findManagementAccountForResource(callerAccountId)
                .orElseThrow(() -> invalidAccess("Only the organization management account can perform this operation."));
        if (!callerAccountId.equals(managementAccountId)) {
            throw invalidAccess("Only the organization management account can perform this operation.");
        }
        return state(region);
    }

    public synchronized SecurityHubState enableOrganizationAdminAccount(String region, String adminAccountId,
                                                                        String feature) {
        requireAccountId(adminAccountId, "AdminAccountId");
        String requestedFeature = normalizeFeature(feature);
        requireOrganizationManagementAccount(adminAccountId);
        SecurityHubState management = state(region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(adminAccountId)) {
            throw conflict("A different Security Hub administrator account is already configured.");
        }
        management.setAdminAccountId(adminAccountId);
        management.setAdminFeature(requestedFeature);
        save(region, management);

        SecurityHubState delegated = states.getForAccount(adminAccountId, region).orElseGet(SecurityHubState::new);
        delegated.setAdminAccountId(adminAccountId);
        delegated.setAdminFeature(requestedFeature);
        if ("SecurityHub".equals(requestedFeature)) {
            delegated.setEnabled(true);
        }
        states.putForAccount(adminAccountId, region, delegated);
        return management;
    }

    /**
     * DisableOrganizationAdminAccount: removes the delegation. The former administrator keeps Security Hub
     * enabled, but loses its organization role, so the organization members it managed become Removed.
     */
    public synchronized void disableOrganizationAdminAccount(String region, String adminAccountId, String feature) {
        requireAccountId(adminAccountId, "AdminAccountId");
        String requestedFeature = normalizeFeature(feature);
        String managementAccountId = requireCallerManagementAccount();
        SecurityHubState management = state(region);
        if (!adminAccountId.equals(management.getAdminAccountId())
                || !requestedFeature.equals(management.getAdminFeature())) {
            throw invalid("Account " + adminAccountId + " is not the " + requestedFeature
                    + " administrator account for the organization.");
        }
        // The management account can delegate to itself, and then both states are the same stored record.
        boolean selfDelegated = adminAccountId.equals(managementAccountId);
        SecurityHubState delegated = selfDelegated
                ? management : states.getForAccount(adminAccountId, region).orElse(null);
        String now = Instant.now().toString();
        if (delegated != null) {
            delegated.setAdminAccountId(null);
            delegated.setAdminFeature("SecurityHub");
            for (SecurityHubMember member : delegated.getMembers().values()) {
                if (member.isOrganizationMember() && !MEMBER_REMOVED.equals(member.getMemberStatus())) {
                    member.setMemberStatus(MEMBER_REMOVED);
                    member.setUpdatedAt(now);
                }
            }
        }
        management.setAdminAccountId(null);
        management.setAdminFeature("SecurityHub");
        save(region, management);
        if (delegated != null && !selfDelegated) {
            states.putForAccount(adminAccountId, region, delegated);
        }
    }

    // ── Members ───────────────────────────────────────────────────────────────

    /** A CreateMembers or DeleteMembers entry Security Hub did not act on, with the reason. */
    public record UnprocessedAccount(String accountId, String processingResult) {}

    private record AccountDetail(String accountId, String email) {}

    /**
     * CreateMembers: an account in the caller's organization, added by the delegated administrator, becomes
     * an Enabled member at once and has Security Hub and its default standards enabled for it. Any other
     * account is Created and would need an invitation. Accounts Security Hub cannot add are reported back
     * rather than failing the batch; a malformed entry fails it before anything is changed.
     */
    public synchronized List<UnprocessedAccount> createMembers(String region, JsonNode request) {
        SecurityHubState administrator = requireSubscribed(region);
        JsonNode details = request == null ? null : request.get("AccountDetails");
        if (details == null || !details.isArray() || details.isEmpty() || details.size() > 50) {
            throw invalid("AccountDetails must hold 1 to 50 accounts.");
        }
        // Every entry is checked before any is applied: applying one enables Security Hub in that account
        // and changes the live stored state, so a later malformed entry must not leave a half-applied batch.
        List<AccountDetail> accounts = new ArrayList<>(details.size());
        for (JsonNode detail : details) {
            String accountId = requireText(detail, "AccountId", "InvalidInputException");
            String email = text(detail, "Email");
            if (email != null && email.isBlank()) {
                throw invalid("Email must contain a non-whitespace character.");
            }
            accounts.add(new AccountDetail(accountId, email));
        }
        String administratorId = regionResolver.getAccountId();
        boolean delegatedAdministrator = administratorId.equals(administrator.getAdminAccountId())
                && "SecurityHub".equals(administrator.getAdminFeature());
        String organization = delegatedAdministrator
                ? organizationsService.findManagementAccountForResource(administratorId).orElse(null) : null;
        List<UnprocessedAccount> unprocessed = new ArrayList<>();
        String now = Instant.now().toString();
        for (AccountDetail account : accounts) {
            String accountId = account.accountId();
            String email = account.email();
            if (!accountId.matches("\\d{12}")) {
                unprocessed.add(new UnprocessedAccount(accountId, "Invalid account ID."));
                continue;
            }
            if (accountId.equals(administratorId)) {
                unprocessed.add(new UnprocessedAccount(accountId,
                        "The administrator account cannot be added as a member account."));
                continue;
            }
            SecurityHubMember existing = administrator.getMembers().get(accountId);
            boolean organizationMember = organization != null && organization.equals(
                    organizationsService.findManagementAccountForResource(accountId).orElse(null));
            // A member stays as it is, unless it was Removed, or was Created before it joined the organization.
            boolean joinedOrganization = existing != null && organizationMember
                    && MEMBER_CREATED.equals(existing.getMemberStatus());
            if (existing != null && !MEMBER_REMOVED.equals(existing.getMemberStatus()) && !joinedOrganization) {
                continue;
            }
            if (organizationMember && !enableForOrganizationMember(region, accountId, organization)) {
                unprocessed.add(new UnprocessedAccount(accountId, "Security Hub CSPM cannot be enabled "
                        + "automatically for the organization management account. Enable it in that account first."));
                continue;
            }
            administrator.getMembers().put(accountId, new SecurityHubMember(
                    accountId,
                    email != null ? email : existing == null ? null : existing.getEmail(),
                    administratorId,
                    organizationMember ? MEMBER_ENABLED : MEMBER_CREATED,
                    organizationMember,
                    organizationMember ? now : null,
                    now));
        }
        save(region, administrator);
        return unprocessed;
    }

    /** Enables Security Hub with its default standards for an organization account; false if Security Hub may not. */
    private boolean enableForOrganizationMember(String region, String accountId, String managementAccountId) {
        SecurityHubState member = states.getForAccount(accountId, region).orElseGet(SecurityHubState::new);
        if (member.isEnabled()) {
            return true;
        }
        if (accountId.equals(managementAccountId)) {
            return false;
        }
        member.setEnabled(true);
        for (Standard standard : STANDARDS) {
            if (standard.enabledByDefault()) {
                member.getStandardsSubscriptions().put(
                        subscriptionArn(region, accountId, standard), standardsArn(region, standard));
            }
        }
        states.putForAccount(accountId, region, member);
        return true;
    }

    /** ListMembers: only Enabled members unless OnlyAssociated is false, in account ID order. */
    public synchronized PaginatedResult<SecurityHubMember> memberPage(String region, String onlyAssociatedValue,
                                                                     String maxResultsValue, String nextToken) {
        SecurityHubState administrator = requireSubscribed(region);
        boolean onlyAssociated = true;
        if (onlyAssociatedValue != null) {
            if (!"true".equalsIgnoreCase(onlyAssociatedValue) && !"false".equalsIgnoreCase(onlyAssociatedValue)) {
                throw invalid("OnlyAssociated must be true or false.");
            }
            onlyAssociated = Boolean.parseBoolean(onlyAssociatedValue);
        }
        Integer maxResults = Pagination.parseMaxResults(maxResultsValue, "InvalidInputException");
        if (maxResults != null && (maxResults < 1 || maxResults > 50)) {
            throw invalid("MaxResults must be between 1 and 50.");
        }
        boolean associatedOnly = onlyAssociated;
        List<SecurityHubMember> members = administrator.getMembers().values().stream()
                .filter(member -> !associatedOnly || MEMBER_ENABLED.equals(member.getMemberStatus()))
                .sorted(Comparator.comparing(SecurityHubMember::getAccountId))
                .map(SecurityHubMember::copy)
                .toList();
        return Pagination.paginate(members, SecurityHubMember::getAccountId, maxResults, nextToken,
                50, 50, "InvalidInputException", "NextToken");
    }

    /** GetMembers: the named members whatever their status; an account that is not one is reported back. */
    public synchronized MemberLookup getMembers(String region, JsonNode request) {
        SecurityHubState administrator = requireSubscribed(region);
        List<SecurityHubMember> found = new ArrayList<>();
        List<UnprocessedAccount> unprocessed = new ArrayList<>();
        for (String accountId : accountIds(request)) {
            SecurityHubMember member = administrator.getMembers().get(accountId);
            if (member == null) {
                unprocessed.add(new UnprocessedAccount(accountId, "Account is not a member of this administrator."));
            } else {
                found.add(member.copy());
            }
        }
        return new MemberLookup(found, unprocessed);
    }

    public record MemberLookup(List<SecurityHubMember> members, List<UnprocessedAccount> unprocessed) {}

    /**
     * DisassociateMembers: the named members become Removed. Every account is checked before any changes,
     * so an account that is not a member fails the whole request with ResourceNotFoundException.
     */
    public synchronized void disassociateMembers(String region, JsonNode request) {
        SecurityHubState administrator = requireSubscribed(region);
        List<String> accountIds = accountIds(request);
        for (String accountId : accountIds) {
            if (!administrator.getMembers().containsKey(accountId)) {
                throw notFound("Account " + accountId + " is not a member of this administrator.");
            }
        }
        String now = Instant.now().toString();
        for (String accountId : accountIds) {
            SecurityHubMember member = administrator.getMembers().get(accountId);
            if (!MEMBER_REMOVED.equals(member.getMemberStatus())) {
                member.setMemberStatus(MEMBER_REMOVED);
                member.setUpdatedAt(now);
            }
        }
        save(region, administrator);
    }

    /**
     * DeleteMembers: removes invited members. An organization account cannot be deleted, only disassociated,
     * so it is reported back, as is an account that is not a member.
     */
    public synchronized List<UnprocessedAccount> deleteMembers(String region, JsonNode request) {
        SecurityHubState administrator = requireSubscribed(region);
        List<UnprocessedAccount> unprocessed = new ArrayList<>();
        for (String accountId : accountIds(request)) {
            SecurityHubMember member = administrator.getMembers().get(accountId);
            if (member == null) {
                unprocessed.add(new UnprocessedAccount(accountId, "Account is not a member of this administrator."));
            } else if (member.isOrganizationMember()) {
                unprocessed.add(new UnprocessedAccount(accountId,
                        "An account that belongs to the organization cannot be deleted; disassociate it instead."));
            } else {
                administrator.getMembers().remove(accountId);
            }
        }
        save(region, administrator);
        return unprocessed;
    }

    private static List<String> accountIds(JsonNode request) {
        JsonNode ids = request == null ? null : request.get("AccountIds");
        if (ids == null || !ids.isArray()) {
            throw invalid("AccountIds is required.");
        }
        List<String> accountIds = new ArrayList<>(ids.size());
        for (JsonNode id : ids) {
            if (!id.isTextual()) {
                throw invalid("AccountIds must contain account ID strings.");
            }
            accountIds.add(id.textValue());
        }
        return accountIds;
    }

    public synchronized void enableSecurityHub(String region, JsonNode request) {
        SecurityHubState state = state(region);
        if (state.isEnabled()) {
            throw conflict("Security Hub is already enabled for this account.");
        }
        String controlFindingGenerator = text(request, "ControlFindingGenerator");
        if (controlFindingGenerator != null
                && !"SECURITY_CONTROL".equals(controlFindingGenerator)
                && !"STANDARD_CONTROL".equals(controlFindingGenerator)) {
            throw invalid("ControlFindingGenerator must be SECURITY_CONTROL or STANDARD_CONTROL.");
        }
        validateTags(request == null ? null : request.get("Tags"));
        JsonNode defaults = request == null ? null : request.get("EnableDefaultStandards");
        if (defaults != null && !defaults.isNull() && !defaults.isBoolean()) {
            throw invalid("EnableDefaultStandards must be a boolean.");
        }
        state.setEnabled(true);
        if (controlFindingGenerator != null) {
            state.setControlFindingGenerator(controlFindingGenerator);
        }
        state.setHubTags(readTags(request == null ? null : request.get("Tags")));
        // EnableDefaultStandards is true unless the caller says otherwise, as on AWS.
        if (defaults == null || defaults.isNull() || defaults.booleanValue()) {
            for (Standard standard : STANDARDS) {
                if (standard.enabledByDefault()) {
                    state.getStandardsSubscriptions().put(subscriptionArn(region, standard), standardsArn(region, standard));
                }
            }
        }
        save(region, state);
    }

    // ── Standards ─────────────────────────────────────────────────────────────

    /** A standard Security Hub offers: its path after the ARN's resource type, name, and scope. */
    public record Standard(String path, String name, boolean global, boolean enabledByDefault) {}

    public static final List<Standard> STANDARDS = List.of(
            new Standard("ruleset/cis-aws-foundations-benchmark/v/1.2.0",
                    "CIS AWS Foundations Benchmark v1.2.0", true, true),
            new Standard("standards/aws-foundational-security-best-practices/v/1.0.0",
                    "AWS Foundational Security Best Practices v1.0.0", false, true),
            new Standard("standards/cis-aws-foundations-benchmark/v/1.4.0",
                    "CIS AWS Foundations Benchmark v1.4.0", false, false),
            new Standard("standards/cis-aws-foundations-benchmark/v/3.0.0",
                    "CIS AWS Foundations Benchmark v3.0.0", false, false),
            new Standard("standards/cis-aws-foundations-benchmark/v/5.0.0",
                    "CIS AWS Foundations Benchmark v5.0.0", false, false),
            new Standard("standards/nist-800-53/v/5.0.0",
                    "NIST Special Publication 800-53 Revision 5", false, false),
            new Standard("standards/pci-dss/v/3.2.1", "PCI DSS v3.2.1", false, false),
            new Standard("standards/pci-dss/v/4.0.1", "PCI DSS v4.0.1", false, false),
            new Standard("standards/aws-resource-tagging-standard/v/1.0.0",
                    "AWS Resource Tagging Standard v1.0.0", false, false));

    public String standardsArn(String region, Standard standard) {
        return standard.global()
                ? AwsArnUtils.Arn.global(regionResolver.partitionForRegion(region), "securityhub", "", standard.path()).toString()
                : regionResolver.buildArn("securityhub", region, "", standard.path());
    }

    public String subscriptionArn(String region, Standard standard) {
        return subscriptionArn(region, regionResolver.getAccountId(), standard);
    }

    private String subscriptionArn(String region, String accountId, Standard standard) {
        return regionResolver.buildArn("securityhub", region, accountId,
                "subscription/" + standard.path().substring(standard.path().indexOf('/') + 1));
    }

    /** BatchEnableStandards: subscribes each requested standard; enabling one twice is not an error. */
    public synchronized Map<String, String> enableStandards(String region, JsonNode request) {
        SecurityHubState state = requireSubscribed(region);
        JsonNode requests = request == null ? null : request.get("StandardsSubscriptionRequests");
        if (requests == null || !requests.isArray() || requests.isEmpty() || requests.size() > 25) {
            throw invalid("StandardsSubscriptionRequests must hold 1 to 25 requests.");
        }
        Map<String, String> enabled = new LinkedHashMap<>();
        for (JsonNode entry : requests) {
            String arn = text(entry, "StandardsArn");
            Standard standard = STANDARDS.stream().filter(s -> standardsArn(region, s).equals(arn)).findFirst()
                    .orElseThrow(() -> invalid("StandardsArn " + arn + " is not a standard Security Hub offers in "
                            + region + "."));
            enabled.put(subscriptionArn(region, standard), arn);
        }
        state.getStandardsSubscriptions().putAll(enabled);
        save(region, state);
        return enabled;
    }

    /** BatchDisableStandards: removes the named subscriptions and reports each as DELETING. */
    public synchronized Map<String, String> disableStandards(String region, JsonNode request) {
        SecurityHubState state = requireSubscribed(region);
        JsonNode arns = request == null ? null : request.get("StandardsSubscriptionArns");
        if (arns == null || !arns.isArray() || arns.isEmpty() || arns.size() > 25) {
            throw invalid("StandardsSubscriptionArns must hold 1 to 25 ARNs.");
        }
        // Every ARN is checked before any is removed: the state is the live stored object, so a
        // removal before a later ARN failed would stick in memory without ever being saved.
        Map<String, String> removed = new LinkedHashMap<>();
        for (JsonNode arn : arns) {
            String standardsArn = state.getStandardsSubscriptions().get(arn.asText());
            if (standardsArn == null) {
                throw new AwsException("ResourceNotFoundException",
                        "Standards subscription " + arn.asText() + " was not found.", 404);
            }
            removed.put(arn.asText(), standardsArn);
        }
        removed.keySet().forEach(state.getStandardsSubscriptions()::remove);
        save(region, state);
        return removed;
    }

    /** GetEnabledStandards: every subscription, or only the named ones. */
    public synchronized Map<String, String> enabledStandards(String region, JsonNode request) {
        SecurityHubState state = requireSubscribed(region);
        JsonNode arns = request == null ? null : request.get("StandardsSubscriptionArns");
        Map<String, String> result = new LinkedHashMap<>();
        state.getStandardsSubscriptions().forEach((subscription, standard) -> {
            boolean wanted = arns == null || !arns.isArray() || arns.isEmpty();
            if (!wanted) {
                for (JsonNode arn : arns) {
                    wanted |= subscription.equals(arn.asText());
                }
            }
            if (wanted) {
                result.put(subscription, standard);
            }
        });
        return result;
    }

    private SecurityHubState requireSubscribed(String region) {
        SecurityHubState state = state(region);
        if (!state.isEnabled()) {
            throw invalidAccess("Account " + regionResolver.getAccountId() + " is not subscribed to AWS Security Hub");
        }
        return state;
    }

    public synchronized void updateSecurityHubConfiguration(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        JsonNode autoEnableControls = request == null ? null : request.get("AutoEnableControls");
        if (autoEnableControls != null && !autoEnableControls.isBoolean()) {
            throw invalid("AutoEnableControls must be a boolean.");
        }
        String controlFindingGenerator = text(request, "ControlFindingGenerator");
        if (controlFindingGenerator != null
                && !"SECURITY_CONTROL".equals(controlFindingGenerator)
                && !"STANDARD_CONTROL".equals(controlFindingGenerator)) {
            throw invalid("ControlFindingGenerator must be SECURITY_CONTROL or STANDARD_CONTROL.");
        }
        if (autoEnableControls != null) {
            state.setAutoEnableControls(autoEnableControls.booleanValue());
        }
        if (controlFindingGenerator != null) {
            state.setControlFindingGenerator(controlFindingGenerator);
        }
        save(region, state);
    }

    public void requireEnabled(String region) {
        if (!state(region).isEnabled()) {
            throw notFound("Security Hub is not enabled for this account.");
        }
    }

    public String normalizeFeature(String feature) {
        String requestedFeature = feature == null || feature.isBlank() ? "SecurityHub" : feature;
        if (!"SecurityHub".equals(requestedFeature) && !"SecurityHubV2".equals(requestedFeature)) {
            throw invalid("Feature must be SecurityHub or SecurityHubV2.");
        }
        return requestedFeature;
    }

    public synchronized SecurityHubState createFindingAggregator(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        if (state.getAggregatorArn() != null) {
            throw new AwsException("LimitExceededException",
                    "Only one finding aggregator can exist for an account.", 429);
        }
        String mode = requireLinkingMode(request);
        JsonNode regions = validateAggregatorRegions(mode, request.get("Regions"));
        state.setAggregatorArn(regionResolver.buildArn("securityhub", region,
                "finding-aggregator/" + UUID.randomUUID()));
        state.setRegionLinkingMode(mode);
        state.setRegions(regions);
        save(region, state);
        return state;
    }

    public SecurityHubState getFindingAggregator(String region, String findingAggregatorArn) {
        SecurityHubState state = state(region);
        if (state.getAggregatorArn() == null || findingAggregatorArn == null
                || !state.getAggregatorArn().equals(findingAggregatorArn)) {
            throw notFound("The finding aggregator was not found.");
        }
        return state;
    }

    public synchronized SecurityHubState updateFindingAggregator(String region, JsonNode request) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        String arn = requireText(request, "FindingAggregatorArn", "InvalidInputException");
        if (state.getAggregatorArn() == null || !state.getAggregatorArn().equals(arn)) {
            throw notFound("The finding aggregator was not found.");
        }
        String mode = requireLinkingMode(request);
        JsonNode regions = validateAggregatorRegions(mode, request.get("Regions"));
        state.setRegionLinkingMode(mode);
        state.setRegions(regions);
        save(region, state);
        return state;
    }

    public synchronized void updateOrganizationConfiguration(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        JsonNode autoEnable = request.get("AutoEnable");
        if (autoEnable == null || !autoEnable.isBoolean()) {
            throw invalid("AutoEnable is required.");
        }
        String autoStandards = text(request, "AutoEnableStandards");
        if (autoStandards != null && !"DEFAULT".equals(autoStandards) && !"NONE".equals(autoStandards)) {
            throw invalid("AutoEnableStandards must be DEFAULT or NONE.");
        }
        JsonNode organizationConfiguration = request.get("OrganizationConfiguration");
        String type = organizationConfiguration != null && organizationConfiguration.isObject()
                ? text(organizationConfiguration, "ConfigurationType") : null;
        if (type != null && !"CENTRAL".equals(type) && !"LOCAL".equals(type)) {
            throw invalid("ConfigurationType must be CENTRAL or LOCAL.");
        }
        String desiredType = type == null ? state.getOrganizationConfigurationType() : type;
        if (desiredType == null) {
            desiredType = "LOCAL";
        }
        state.setOrganizationConfigurationType(desiredType);
        if ("CENTRAL".equals(desiredType)) {
            state.setAutoEnable(false);
            state.setAutoEnableStandards("NONE");
            state.setOrganizationConfigurationStatus("PENDING");
            state.setOrganizationConfigurationPendingPollsRemaining(1);
        } else {
            state.setAutoEnable(autoEnable.booleanValue());
            if (autoStandards != null) {
                state.setAutoEnableStandards(autoStandards);
            }
            state.setOrganizationConfigurationStatus("ENABLED");
            state.setOrganizationConfigurationPendingPollsRemaining(0);
        }
        save(region, state);
    }

    public synchronized SecurityHubState organizationConfiguration(String region) {
        SecurityHubState state = requireAdministrator(region);
        if ("PENDING".equals(state.getOrganizationConfigurationStatus())
                && state.getOrganizationConfigurationPendingPollsRemaining() > 0) {
            SecurityHubState response = copyConfigurationState(state);
            state.setOrganizationConfigurationPendingPollsRemaining(
                    state.getOrganizationConfigurationPendingPollsRemaining() - 1);
            save(region, state);
            return response;
        }
        if ("PENDING".equals(state.getOrganizationConfigurationStatus())) {
            state.setOrganizationConfigurationStatus("ENABLED");
            save(region, state);
        }
        return state;
    }

    public synchronized String createConfigurationPolicy(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        String name = requireText(request, "Name", "InvalidInputException");
        validatePolicyName(name);
        boolean duplicate = state.getPolicies().values().stream()
                .anyMatch(policy -> name.equals(text(policy, "Name")));
        if (duplicate) {
            throw conflict("A configuration policy with the same name already exists.");
        }
        validateConfigurationPolicy(request.get("ConfigurationPolicy"));
        validateTags(request.get("Tags"));
        String id = UUID.randomUUID().toString();
        ObjectNode stored = (ObjectNode) request.deepCopy();
        String now = Instant.now().toString();
        stored.put("CreatedAt", now);
        stored.put("UpdatedAt", now);
        state.getPolicies().put(id, stored);
        save(region, state);
        return id;
    }

    public JsonNode getConfigurationPolicy(String region, String identifier) {
        String id = normalizePolicyId(identifier);
        JsonNode policy = requireAdministrator(region).getPolicies().get(id);
        if (policy == null) {
            throw notFound("The configuration policy was not found.");
        }
        return policy;
    }

    public synchronized JsonNode updateConfigurationPolicy(String region, String identifier, JsonNode request) {
        String id = normalizePolicyId(identifier);
        SecurityHubState state = requireAdministrator(region);
        JsonNode current = state.getPolicies().get(id);
        if (current == null) {
            throw notFound("The configuration policy was not found.");
        }
        String newName = text(request, "Name");
        if (newName != null) {
            validatePolicyName(newName);
            boolean duplicate = state.getPolicies().entrySet().stream()
                    .anyMatch(entry -> !entry.getKey().equals(id)
                            && newName.equals(text(entry.getValue(), "Name")));
            if (duplicate) {
                throw conflict("A configuration policy with the same name already exists.");
            }
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        if (newName != null) {
            merged.put("Name", newName);
        }
        if (request.has("Description")) {
            merged.set("Description", request.get("Description"));
        }
        if (request.has("ConfigurationPolicy")) {
            validateConfigurationPolicy(request.get("ConfigurationPolicy"));
            merged.set("ConfigurationPolicy", request.get("ConfigurationPolicy").deepCopy());
        }
        merged.put("UpdatedAt", Instant.now().toString());
        state.getPolicies().put(id, merged);
        save(region, state);
        return merged;
    }

    public synchronized SecurityHubAssociation associate(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        TargetRef target = organizationTarget(request);
        String policyIdentifier = requireText(request, "ConfigurationPolicyIdentifier", "InvalidInputException");
        String policyId = normalizePolicyId(policyIdentifier);
        if (!SELF_MANAGED.equals(policyId) && !state.getPolicies().containsKey(policyId)) {
            throw notFound("The configuration policy was not found.");
        }
        SecurityHubAssociation existing = state.getAssociations().get(target.id());
        if (existing != null && !existing.isDisassociating()
                && policyId.equals(existing.getPolicyId())) {
            throw conflict("The target is already associated with the specified configuration policy.");
        }
        SecurityHubAssociation association = new SecurityHubAssociation(
                target.id(), target.type(), policyId, "PENDING", 1);
        state.getAssociations().put(target.id(), association);
        save(region, state);
        return association.copy();
    }

    public synchronized SecurityHubAssociation association(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        TargetRef target = organizationTarget(request);
        SecurityHubAssociation association = state.getAssociations().get(target.id());
        if (association == null) {
            throw notFound("The configuration policy association was not found.");
        }
        if ("PENDING".equals(association.getStatus()) && association.getPendingPollsRemaining() > 0) {
            SecurityHubAssociation response = association.copy();
            association.setPendingPollsRemaining(association.getPendingPollsRemaining() - 1);
            save(region, state);
            return response;
        }
        if ("PENDING".equals(association.getStatus())) {
            if (association.isDisassociating()) {
                state.getAssociations().remove(target.id());
                save(region, state);
                throw notFound("The configuration policy association was not found.");
            }
            association.setStatus("SUCCESS");
            association.setUpdatedAt(Instant.now().toString());
            applyPolicyToAccountTarget(region, state, association);
            save(region, state);
        }
        return association.copy();
    }

    public synchronized void disassociate(String region, JsonNode request) {
        SecurityHubState state = requireAdministrator(region);
        TargetRef target = organizationTarget(request);
        String policyIdentifier = requireText(request, "ConfigurationPolicyIdentifier", "InvalidInputException");
        String requestedPolicyId = normalizePolicyId(policyIdentifier);
        SecurityHubAssociation association = state.getAssociations().get(target.id());
        if (association == null || !requestedPolicyId.equals(association.getPolicyId())) {
            throw notFound("The configuration policy association was not found.");
        }
        if (association.isDisassociating()) {
            throw conflict("The configuration policy association is already being removed.");
        }
        association.setStatus("PENDING");
        association.setUpdatedAt(Instant.now().toString());
        association.setPendingPollsRemaining(1);
        association.setDisassociating(true);
        save(region, state);
    }

    private void applyPolicyToAccountTarget(String region, SecurityHubState administrator,
                                            SecurityHubAssociation association) {
        if (!"ACCOUNT".equals(association.getTargetType()) || SELF_MANAGED.equals(association.getPolicyId())) {
            return;
        }
        JsonNode policy = administrator.getPolicies().get(association.getPolicyId());
        JsonNode securityHub = policy == null ? null : policy.path("ConfigurationPolicy").path("SecurityHub");
        if (securityHub == null || !securityHub.isObject() || !securityHub.path("ServiceEnabled").isBoolean()) {
            return;
        }
        SecurityHubState target = states.getForAccount(association.getTargetId(), region)
                .orElseGet(SecurityHubState::new);
        target.setEnabled(securityHub.path("ServiceEnabled").asBoolean());
        states.putForAccount(association.getTargetId(), region, target);
    }

    public List<SecurityHubAssociation> associations(String region) {
        return requireAdministrator(region).getAssociations().values().stream()
                .map(SecurityHubAssociation::copy)
                .toList();
    }

    public PaginatedResult<SecurityHubAssociation> associationPage(String region, JsonNode request) {
        JsonNode filters = request == null ? null : request.get("Filters");
        if (filters != null && !filters.isObject()) {
            throw invalid("Filters must be an object.");
        }
        String policyId = filters == null ? null : text(filters, "ConfigurationPolicyId");
        String associationType = filters == null ? null : text(filters, "AssociationType");
        String associationStatus = filters == null ? null : text(filters, "AssociationStatus");
        if (associationType != null && !"APPLIED".equals(associationType) && !"INHERITED".equals(associationType)) {
            throw invalid("AssociationType must be APPLIED or INHERITED.");
        }
        if (associationStatus != null && !List.of("PENDING", "SUCCESS", "FAILED").contains(associationStatus)) {
            throw invalid("AssociationStatus must be PENDING, SUCCESS, or FAILED.");
        }
        Integer maxResults = integer(request, "MaxResults");
        String nextToken = text(request, "NextToken");
        List<SecurityHubAssociation> filtered = associations(region).stream()
                .filter(item -> policyId == null || normalizePolicyId(policyId).equals(item.getPolicyId()))
                .filter(item -> associationType == null || "APPLIED".equals(associationType))
                .filter(item -> associationStatus == null || associationStatus.equals(item.getStatus()))
                .toList();
        return Pagination.paginate(filtered,
                item -> item.getTargetType() + "\u0000" + item.getTargetId(),
                maxResults, nextToken, 100, 100, "InvalidInputException");
    }

    public List<Map.Entry<String, JsonNode>> policies(String region) {
        return List.copyOf(requireAdministrator(region).getPolicies().entrySet());
    }

    public PaginatedResult<Map.Entry<String, JsonNode>> policyPage(String region, Integer maxResults,
                                                                   String nextToken) {
        return Pagination.paginate(policies(region), Map.Entry::getKey,
                maxResults, nextToken, 100, 100, "InvalidInputException");
    }

    public Map<String, String> tagsForResource(String region, String arn) {
        SecurityHubState state = state(region);
        validateResourceArn(region, arn);
        if (arn.equals(hubArn(region))) {
            if (!state.isEnabled()) {
                throw notFound("The specified Security Hub resource was not found.");
            }
            return Map.copyOf(state.getHubTags());
        }
        JsonNode resource = taggedPolicy(state, region, arn);
        JsonNode tags = resource.get("Tags");
        if (tags == null || !tags.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                result.put(entry.getKey(), entry.getValue().textValue());
            }
        });
        return result;
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        SecurityHubState state = state(region);
        validateResourceArn(region, arn);
        if (arn.equals(hubArn(region))) {
            if (!state.isEnabled()) {
                throw notFound("The specified Security Hub resource was not found.");
            }
            Map<String, String> merged = new LinkedHashMap<>(state.getHubTags());
            merged.putAll(tags);
            validateTags(merged);
            state.setHubTags(merged);
            save(region, state);
            return;
        }
        ObjectNode resource = policyResource(state, region, arn);
        ObjectNode current = resource.withObject("Tags");
        tags.forEach(current::put);
        validateTags(current);
        save(region, state);
    }

    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        SecurityHubState state = state(region);
        validateResourceArn(region, arn);
        if (arn.equals(hubArn(region))) {
            if (!state.isEnabled()) {
                throw notFound("The specified Security Hub resource was not found.");
            }
            Map<String, String> updated = new LinkedHashMap<>(state.getHubTags());
            tagKeys.forEach(updated::remove);
            state.setHubTags(updated);
            save(region, state);
            return;
        }
        ObjectNode resource = policyResource(state, region, arn);
        ObjectNode tags = resource.withObject("Tags");
        tagKeys.forEach(tags::remove);
        save(region, state);
    }

    private JsonNode taggedPolicy(SecurityHubState state, String region, String arn) {
        validateResourceArn(region, arn);
        if (arn.equals(state.getAggregatorArn())) {
            throw notFound("The specified Security Hub resource was not found.");
        }
        String id = normalizePolicyId(arn);
        JsonNode policy = state.getPolicies().get(id);
        if (policy == null) {
            throw notFound("The specified Security Hub resource was not found.");
        }
        return policy;
    }

    private void validateResourceArn(String region, String arn) {
        if (!AwsArnUtils.isArnFor(arn, "securityhub")) {
            throw notFound("The specified Security Hub resource was not found.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!regionResolver.getPartition().equals(parsed.partition())
                || !region.equals(parsed.region()) || !regionResolver.getAccountId().equals(parsed.accountId())) {
            throw notFound("The specified Security Hub resource was not found.");
        }
    }

    private ObjectNode policyResource(SecurityHubState state, String region, String arn) {
        JsonNode resource = taggedPolicy(state, region, arn);
        if (!(resource instanceof ObjectNode object)) {
            throw notFound("The specified Security Hub resource does not support mutable tags in this emulator.");
        }
        return object;
    }

    public String hubArn(String region) {
        return regionResolver.buildArn("securityhub", region, "hub/default");
    }

    public String policyArn(String region, String id) {
        return regionResolver.buildArn("securityhub", region, "configuration-policy/" + id);
    }

    private static SecurityHubState copyConfigurationState(SecurityHubState source) {
        SecurityHubState copy = new SecurityHubState();
        copy.setAdminAccountId(source.getAdminAccountId());
        copy.setAdminFeature(source.getAdminFeature());
        copy.setEnabled(source.isEnabled());
        copy.setAutoEnable(source.isAutoEnable());
        copy.setAutoEnableStandards(source.getAutoEnableStandards());
        copy.setOrganizationConfigurationType(source.getOrganizationConfigurationType());
        copy.setOrganizationConfigurationStatus(source.getOrganizationConfigurationStatus());
        return copy;
    }

    private static JsonNode validateAggregatorRegions(String mode, JsonNode regions) {
        if (regions != null && !regions.isArray()) {
            throw invalid("Regions must be an array.");
        }
        if ("SPECIFIED_REGIONS".equals(mode) && (regions == null || regions.isEmpty())) {
            throw invalid("Regions is required when RegionLinkingMode is SPECIFIED_REGIONS.");
        }
        if (("NO_REGIONS".equals(mode) || "ALL_REGIONS".equals(mode))
                && regions != null && !regions.isEmpty()) {
            throw invalid("Regions must be empty for the selected RegionLinkingMode.");
        }
        if (regions != null) {
            for (JsonNode candidate : regions) {
                if (!candidate.isTextual() || candidate.textValue().isBlank()) {
                    throw invalid("Regions must contain non-blank Region identifiers.");
                }
            }
        }
        return regions == null ? null : regions.deepCopy();
    }

    private static String requireLinkingMode(JsonNode request) {
        String mode = requireText(request, "RegionLinkingMode", "InvalidInputException");
        if (!LINKING_MODES.contains(mode)) {
            throw invalid("RegionLinkingMode is invalid.");
        }
        return mode;
    }

    private static void validateTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (!tags.isObject() || tags.size() > 50) {
            throw invalid("Tags must be an object with at most 50 entries.");
        }
        tags.fields().forEachRemaining(entry -> validateTag(entry.getKey(), entry.getValue()));
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags.size() > 50) {
            throw invalid("Tags must contain at most 50 entries.");
        }
        tags.forEach((key, value) -> validateTag(
                key, value == null ? null : new TextNode(value)));
    }

    private static void validateTag(String key, JsonNode value) {
        if (key == null || key.isBlank() || key.length() > 128 || key.startsWith("aws:")
                || !key.matches("[a-zA-Z+\\-=._:/]+")
                || value == null || !value.isTextual() || value.textValue().length() > 256) {
            throw invalid("Tags contain an invalid key or value.");
        }
    }

    private static Map<String, String> readTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return new LinkedHashMap<>();
        }
        validateTags(tags);
        Map<String, String> result = new LinkedHashMap<>();
        tags.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().textValue()));
        return result;
    }

    private SecurityHubState requireAdministrator(String region) {
        requireEnabled(region);
        SecurityHubState state = state(region);
        if (state.getAdminAccountId() == null || !regionResolver.getAccountId().equals(state.getAdminAccountId())) {
            throw new AwsException("InvalidAccessException",
                    "Only the delegated Security Hub administrator account can manage central configuration.", 401);
        }
        return state;
    }

    private void save(String region, SecurityHubState state) {
        states.put(region, state);
    }

    private static String normalizePolicyId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw invalid("Identifier is required.");
        }
        if (SELF_MANAGED.equals(identifier)) {
            return SELF_MANAGED;
        }
        int slash = identifier.lastIndexOf('/');
        return slash >= 0 ? identifier.substring(slash + 1) : identifier;
    }

    private String requireCallerManagementAccount() {
        String callerAccountId = regionResolver.getAccountId();
        String managementAccountId = organizationsService.findManagementAccountForResource(callerAccountId)
                .orElseThrow(() -> new AwsException("AccessDeniedException",
                        "Only the organization management account can perform this operation.", 403));
        if (!callerAccountId.equals(managementAccountId)) {
            throw new AwsException("AccessDeniedException",
                    "Only the organization management account can perform this operation.", 403);
        }
        return managementAccountId;
    }

    private void requireOrganizationManagementAccount(String targetAccountId) {
        String managementAccountId = requireCallerManagementAccount();
        String targetManagementAccountId = organizationsService.findManagementAccountForResource(targetAccountId)
                .orElseThrow(() -> invalid("AdminAccountId must belong to the caller's AWS organization."));
        if (!managementAccountId.equals(targetManagementAccountId)) {
            throw invalid("AdminAccountId must belong to the caller's AWS organization.");
        }
    }

    private TargetRef organizationTarget(JsonNode input) {
        TargetRef target = target(input);
        String callerAccountId = regionResolver.getAccountId();
        String managementAccountId = organizationsService.findManagementAccountForResource(callerAccountId)
                .orElseThrow(() -> new AwsException("InvalidAccessException",
                        "The delegated administrator must belong to an AWS organization.", 401));
        String targetManagementAccountId = organizationsService.findManagementAccountForResource(target.id())
                .orElseThrow(() -> notFound("The specified organization target was not found."));
        if (!managementAccountId.equals(targetManagementAccountId)) {
            throw notFound("The specified organization target was not found.");
        }
        return target;
    }

    private static void validatePolicyName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("Name must contain a non-whitespace character.");
        }
        if (name.length() > 128) {
            throw invalid("Name exceeds the maximum length.");
        }
    }

    private static void validateConfigurationPolicy(JsonNode configurationPolicy) {
        if (configurationPolicy == null || !configurationPolicy.isObject()
                || configurationPolicy.size() != 1 || !configurationPolicy.has("SecurityHub")) {
            throw invalid("ConfigurationPolicy must contain exactly one SecurityHub policy.");
        }
        JsonNode securityHub = configurationPolicy.get("SecurityHub");
        if (securityHub == null || !securityHub.isObject()) {
            throw invalid("ConfigurationPolicy.SecurityHub must be an object.");
        }
        JsonNode serviceEnabled = securityHub.get("ServiceEnabled");
        if (serviceEnabled != null && !serviceEnabled.isBoolean()) {
            throw invalid("ConfigurationPolicy.SecurityHub.ServiceEnabled must be a boolean.");
        }
        JsonNode standards = securityHub.get("EnabledStandardIdentifiers");
        if (standards != null) {
            if (!standards.isArray()) {
                throw invalid("EnabledStandardIdentifiers must be an array.");
            }
            for (JsonNode standard : standards) {
                if (!standard.isTextual() || standard.textValue().isBlank()) {
                    throw invalid("EnabledStandardIdentifiers must contain non-blank strings.");
                }
            }
        }
        JsonNode controls = securityHub.get("SecurityControlsConfiguration");
        if (controls != null && !controls.isObject()) {
            throw invalid("SecurityControlsConfiguration must be an object.");
        }
    }

    private static TargetRef target(JsonNode input) {
        JsonNode target = input == null ? null : input.get("Target");
        if (target == null || !target.isObject()) {
            throw invalid("Target is required.");
        }
        TargetRef found = null;
        for (Map.Entry<String, String> candidate : Map.of(
                "AccountId", "ACCOUNT", "OrganizationalUnitId", "ORGANIZATIONAL_UNIT", "RootId", "ROOT").entrySet()) {
            String value = text(target, candidate.getKey());
            if (value != null) {
                if (found != null) {
                    throw invalid("Target must contain exactly one identifier.");
                }
                found = new TargetRef(value, candidate.getValue());
            }
        }
        if (found == null) {
            throw invalid("Target identifier is required.");
        }
        if ("ACCOUNT".equals(found.type()) && !found.id().matches("\\d{12}")) {
            throw invalid("AccountId must be a 12 digit account ID.");
        }
        if ("ORGANIZATIONAL_UNIT".equals(found.type()) && !found.id().matches("ou-[a-z0-9]{4,32}-[a-z0-9]{8,32}")) {
            throw invalid("OrganizationalUnitId is invalid.");
        }
        if ("ROOT".equals(found.type()) && !found.id().matches("r-[a-z0-9]{4,32}")) {
            throw invalid("RootId is invalid.");
        }
        return found;
    }

    private static void requireAccountId(String value, String field) {
        if (value == null || !value.matches("\\d{12}")) {
            throw invalid(field + " must be a 12 digit account ID.");
        }
    }

    private static String requireText(JsonNode input, String field, String errorCode) {
        String value = text(input, field);
        if (value == null || value.isBlank()) {
            throw new AwsException(errorCode, field + " is required.", 400);
        }
        return value;
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static Integer integer(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(field + " must be an integer.");
        }
        return node.intValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    private static AwsException invalidAccess(String message) {
        return new AwsException("InvalidAccessException", message, 401);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ResourceConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    @Override
    public void clear() {
        states.clear();
    }

    private record TargetRef(String id, String type) {}
}
