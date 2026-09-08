package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.detective.model.DetectiveMember;
import io.github.hectorvent.floci.services.detective.model.DetectiveState;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DetectiveService implements Resettable {
    private static final int MAX_MEMBERS = 1200;
    private static final String ACCEPTED_BUT_DISABLED = "ACCEPTED_BUT_DISABLED";
    private static final String ENABLED = "ENABLED";

    private final AccountAwareStorageBackend<DetectiveState> states;
    private final RegionResolver regionResolver;
    private final OrganizationsService organizationsService;

    @Inject
    public DetectiveService(StorageFactory storageFactory, RegionResolver regionResolver,
                            OrganizationsService organizationsService) {
        this(storageFactory.create("detective", "detective-state.json",
                new TypeReference<Map<String, DetectiveState>>() {}), regionResolver, organizationsService);
    }

    DetectiveService(AccountAwareStorageBackend<DetectiveState> states, RegionResolver regionResolver,
                     OrganizationsService organizationsService) {
        this.states = states;
        this.regionResolver = regionResolver;
        this.organizationsService = organizationsService;
    }

    public DetectiveState state(String region) {
        return states.get(region).orElseGet(DetectiveState::new);
    }

    public String graphArn(String region) {
        return graphArnForAccount(regionResolver.getAccountId(), region);
    }

    public synchronized void enableAdmin(String region, String callerAccountId, String accountId) {
        requireAccountId(accountId);
        var organization = organizationsService.describeOrganization(callerAccountId);
        if (!callerAccountId.equals(organization.getMasterAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the organization management account can designate the Detective administrator account.", 403);
        }
        boolean accountInOrganization = organizationsService.listAccounts(callerAccountId).stream()
                .anyMatch(account -> accountId.equals(account.getId()));
        if (!accountInOrganization) {
            throw new AwsException("ValidationException",
                    "AccountId must identify an account in the organization.", 400);
        }
        DetectiveState management = state(region);
        if (management.getAdminAccountId() != null && !management.getAdminAccountId().equals(accountId)) {
            throw new AwsException("ConflictException",
                    "A different Detective administrator account is already configured.", 409);
        }
        management.setAdminAccountId(accountId);
        states.put(region, management);

        DetectiveState delegated = states.getForAccount(accountId, region).orElseGet(DetectiveState::new);
        delegated.setAdminAccountId(accountId);
        delegated.setGraph(true);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void updateOrganizationConfiguration(String region, String graphArn, Boolean autoEnable) {
        requireGraphArn(region, graphArn);
        DetectiveState state = requireGraph(region);
        if (autoEnable != null) {
            state.setAutoEnable(autoEnable);
            states.put(region, state);
        }
    }

    public synchronized DetectiveMember createMember(String region, String graphArn,
                                                      String accountId, String emailAddress) {
        requireGraphArn(region, graphArn);
        requireAccountId(accountId);
        DetectiveState state = requireGraph(region);
        if (state.getMembers().containsKey(accountId)) {
            throw new AwsException("ConflictException",
                    "The account is already a member of the behavior graph.", 409);
        }
        if (state.getMembers().size() >= MAX_MEMBERS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The behavior graph member quota has been exceeded.", 402);
        }
        DetectiveMember member = new DetectiveMember(accountId, emailAddress, ACCEPTED_BUT_DISABLED);
        state.getMembers().put(accountId, member);
        states.put(region, state);
        return member;
    }

    public synchronized void validateCreateMembers(String region, String graphArn, List<String> accountIds) {
        requireGraphArn(region, graphArn);
        DetectiveState state = requireGraph(region);
        int newMembers = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String accountId : accountIds) {
            requireAccountId(accountId);
            if (seen.add(accountId) && !state.getMembers().containsKey(accountId)) {
                newMembers++;
            }
        }
        if (state.getMembers().size() + newMembers > MAX_MEMBERS) {
            throw new AwsException("ServiceQuotaExceededException",
                    "The behavior graph member quota has been exceeded.", 402);
        }
    }

    public synchronized DetectiveMember startMonitoring(String region, String accountId, String graphArn) {
        requireGraphArn(region, graphArn);
        requireAccountId(accountId);
        DetectiveState state = requireGraph(region);
        DetectiveMember member = state.getMembers().get(accountId);
        if (member == null) {
            throw new AwsException("ResourceNotFoundException", "Member account not found.", 404);
        }
        if (ENABLED.equals(member.getStatus())) {
            throw new AwsException("ConflictException",
                    "The member account is already contributing data to the behavior graph.", 409);
        }
        if (!ACCEPTED_BUT_DISABLED.equals(member.getStatus())) {
            throw new AwsException("ConflictException",
                    "The member account is not in a state that can start monitoring.", 409);
        }
        member.setStatus(ENABLED);
        states.put(region, state);
        return member;
    }

    public List<DetectiveMember> listMembers(String region, String graphArn) {
        requireGraphArn(region, graphArn);
        return requireGraph(region).getMembers().values().stream()
                .sorted(java.util.Comparator.comparing(DetectiveMember::getAccountId))
                .toList();
    }

    @Override
    public void clear() {
        states.clear();
    }

    public DetectiveState requireGraph(String region) {
        DetectiveState state = state(region);
        if (!state.isGraph()) {
            throw new AwsException("ResourceNotFoundException", "Behavior graph not found.", 404);
        }
        return state;
    }

    public void requireGraphArn(String region, String graphArn) {
        if (graphArn == null || !graphArnForAccount(regionResolver.getAccountId(), region).equals(graphArn)) {
            throw new AwsException("ResourceNotFoundException", "Behavior graph not found.", 404);
        }
    }

    private static String graphArnForAccount(String accountId, String region) {
        return "arn:aws:detective:" + region + ":" + accountId
                + ":graph:00000000000000000000000000000001";
    }

    private static void requireAccountId(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("ValidationException", "AccountId must be a 12 digit account ID.", 400);
        }
    }
}
