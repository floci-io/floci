package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.macie2.model.MacieMember;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MacieService implements Resettable {
    private final AccountAwareStorageBackend<MacieState> states;
    private final AccountAwareStorageBackend<MacieMember> members;

    @Inject
    public MacieService(StorageFactory storageFactory) {
        this(storageFactory.create("macie2", "macie2-state.json",
                        new TypeReference<Map<String, MacieState>>() {}),
                storageFactory.create("macie2", "macie2-members.json",
                        new TypeReference<Map<String, MacieMember>>() {}));
    }

    MacieService(AccountAwareStorageBackend<MacieState> states,
                 AccountAwareStorageBackend<MacieMember> members) {
        this.states = states;
        this.members = members;
    }

    public MacieState state(String region) {
        return states.get(region).orElseGet(MacieState::new);
    }

    public synchronized void enableOrganizationAdminAccount(String region, String accountId) {
        requireAccountId(accountId);
        MacieState state = state(region);
        if (state.getAdminAccountId() != null && !state.getAdminAccountId().equals(accountId)) {
            throw conflict("A different Macie administrator account is already configured.");
        }
        state.setAdminAccountId(accountId);
        states.put(region, state);

        MacieState delegated = states.getForAccount(accountId, region).orElseGet(MacieState::new);
        delegated.setAdminAccountId(accountId);
        delegated.setEnabled(true);
        states.putForAccount(accountId, region, delegated);
    }

    public synchronized void enableMacie(String region) {
        MacieState state = state(region);
        if (state.isEnabled()) {
            throw conflict("Macie is already enabled for this account.");
        }
        state.setEnabled(true);
        states.put(region, state);
    }

    public MacieState requireSession(String region) {
        MacieState state = state(region);
        if (!state.isEnabled()) {
            throw notFound("Macie is not enabled for this account.");
        }
        return state;
    }

    public MacieState requireAdministratorSession(String region, String callerAccountId) {
        MacieState state = states.getForAccount(callerAccountId, region).orElseGet(MacieState::new);
        if (state.getAdminAccountId() == null && !state.isEnabled()) {
            throw notFound("Macie is not enabled for this account.");
        }
        if (!callerAccountId.equals(state.getAdminAccountId())) {
            throw accessDenied();
        }
        if (!state.isEnabled()) {
            throw notFound("Macie is not enabled for this account.");
        }
        return state;
    }

    public synchronized void updateOrganizationConfiguration(
            String region, String callerAccountId, boolean autoEnable) {
        MacieState state = requireAdministratorSession(region, callerAccountId);
        state.setAutoEnable(autoEnable);
        states.putForAccount(callerAccountId, region, state);
    }

    public synchronized MacieMember createMember(
            String region, String callerAccountId, String memberAccountId, String email, Map<String, String> tags) {
        MacieState callerState = requireSessionForAccount(region, callerAccountId);
        requireAccountId(memberAccountId, "accountId");
        if (callerAccountId.equals(memberAccountId)) {
            throw validation("accountId must identify a different AWS account.");
        }
        requireEmail(email);
        Map<String, String> safeTags = validateTags(tags);

        String key = memberKey(region, memberAccountId);
        if (members.getForAccount(callerAccountId, key).isPresent()) {
            throw conflict("The account is already associated with this Macie administrator account.");
        }
        boolean associatedWithDifferentAdministrator = members.scanAllAccounts().stream()
                .anyMatch(member -> memberAccountId.equals(member.accountId())
                        && !callerAccountId.equals(member.administratorAccountId()));
        if (associatedWithDifferentAdministrator) {
            throw conflict("The account is already associated with a different Macie administrator account.");
        }

        boolean organizationAdministrator = callerAccountId.equals(callerState.getAdminAccountId());
        String now = Instant.now().toString();
        String arn = "arn:aws:macie2:" + region + ":" + callerAccountId + ":member/" + memberAccountId;
        MacieMember member = new MacieMember(
                memberAccountId,
                callerAccountId,
                callerAccountId,
                arn,
                organizationAdministrator ? null : email,
                null,
                organizationAdministrator ? "Enabled" : "Created",
                safeTags,
                now);
        members.putForAccount(callerAccountId, key, member);
        return member;
    }

    public Page<MacieMember> listMembers(
            String region, String callerAccountId, String maxResultsValue, String nextToken, String onlyAssociated) {
        requireSessionForAccount(region, callerAccountId);
        int maxResults = parseMaxResults(maxResultsValue);
        Boolean associatedOnly = parseOnlyAssociated(onlyAssociated);
        List<MacieMember> items = new ArrayList<>(members.scanForAccount(
                callerAccountId, key -> key.startsWith(region + "::")));
        if (associatedOnly == null || associatedOnly) {
            items.removeIf(member -> !isCurrentMember(member.relationshipStatus()));
        }
        items.sort(Comparator.comparing(MacieMember::accountId));
        int offset = decodeOffset(nextToken, items.size());
        int end = Math.min(items.size(), offset + maxResults);
        return new Page<>(List.copyOf(items.subList(offset, end)),
                end < items.size() ? encodeOffset(end) : null);
    }

    private MacieState requireSessionForAccount(String region, String accountId) {
        MacieState state = states.getForAccount(accountId, region).orElseGet(MacieState::new);
        if (!state.isEnabled()) {
            throw notFound("Macie is not enabled for this account.");
        }
        return state;
    }

    private static boolean isCurrentMember(String relationshipStatus) {
        return "Enabled".equals(relationshipStatus) || "Paused".equals(relationshipStatus);
    }

    private static Boolean parseOnlyAssociated(String value) {
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw validation("onlyAssociated must be true or false.");
    }

    private static int parseMaxResults(String value) {
        if (value == null) {
            return 50;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 50) {
                throw validation("maxResults must be between 1 and 50.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw validation("maxResults must be between 1 and 50.");
        }
    }

    private static int decodeOffset(String token, int size) {
        if (token == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (offset < 0 || offset > size) {
                throw validation("nextToken is invalid.");
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static String memberKey(String region, String accountId) {
        return region + "::" + accountId;
    }

    private static void requireEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 320 || email.indexOf('@') <= 0
                || email.endsWith("@")) {
            throw validation("email must be a valid email address.");
        }
    }

    private static Map<String, String> validateTags(Map<String, String> tags) {
        if (tags == null) {
            return Map.of();
        }
        if (tags.size() > 50) {
            throw validation("A member can have at most 50 tags.");
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            if (tag.getKey() == null || tag.getKey().length() > 128
                    || tag.getValue() == null || tag.getValue().length() > 256) {
                throw validation("Tag keys can be at most 128 characters and values at most 256 characters.");
            }
        }
        return Map.copyOf(tags);
    }

    public record Page<T>(List<T> items, String nextToken) {}

    @Override
    public void clear() {
        states.clear();
        members.clear();
    }

    private static void requireAccountId(String accountId) {
        requireAccountId(accountId, "adminAccountId");
    }

    private static void requireAccountId(String accountId, String fieldName) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw validation(fieldName + " must be a 12 digit account ID.");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException accessDenied() {
        return new AwsException("AccessDeniedException",
                "Only the delegated Macie administrator account can manage organization configuration.", 403);
    }
}
