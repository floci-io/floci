package io.github.hectorvent.floci.services.macie2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.macie2.model.MacieMember;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacieServiceTest {
    private static final String REGION = "us-east-1";
    private static final String MANAGEMENT_ACCOUNT = "222222222222";
    private static final String ADMIN_ACCOUNT = "111111111111";

    private MacieService service;

    @BeforeEach
    void setUp() {
        service = new MacieService(
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT),
                AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT));
    }

    @Test
    void delegationEnablesMacieForAdministratorAccount() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        MacieState delegated = service.requireAdministratorSession(REGION, ADMIN_ACCOUNT);
        assertTrue(delegated.isEnabled());
        assertEquals(ADMIN_ACCOUNT, delegated.getAdminAccountId());
    }

    @Test
    void managementAccountCannotUpdateAdministratorConfiguration() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        service.enableMacie(REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateOrganizationConfiguration(REGION, MANAGEMENT_ACCOUNT, true));
        assertEquals("AccessDeniedException", error.getErrorCode());
    }

    @Test
    void delegatedAdministratorCanUpdateConfiguration() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        service.updateOrganizationConfiguration(REGION, ADMIN_ACCOUNT, true);

        assertTrue(service.requireAdministratorSession(REGION, ADMIN_ACCOUNT).isAutoEnable());
    }

    @Test
    void conflictingAdministratorDesignationIsRejected() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        AwsException error = assertThrows(AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, "333333333333"));
        assertEquals("ConflictException", error.getErrorCode());
    }


    @Test
    void delegatedAdministratorCreatesEnabledMember() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        MacieMember member = service.createMember(
                REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of("team", "security"));

        assertEquals("Enabled", member.relationshipStatus());
        assertEquals("arn:aws:macie2:us-east-1:111111111111:member/333333333333", member.arn());
        assertEquals("security", member.tags().get("team"));
        List<MacieMember> members = service.listMembers(REGION, ADMIN_ACCOUNT, null, null, null).items();
        assertEquals(1, members.size());
        assertEquals("333333333333", members.getFirst().accountId());
    }

    @Test
    void standaloneAdministratorCreatesAssociationExcludedFromDefaultMemberList() {
        service.enableMacie(REGION);

        service.createMember(
                REGION, MANAGEMENT_ACCOUNT, "333333333333", "member@example.com", Map.of());

        assertTrue(service.listMembers(REGION, MANAGEMENT_ACCOUNT, null, null, null).items().isEmpty());
        List<MacieMember> all = service.listMembers(
                REGION, MANAGEMENT_ACCOUNT, null, null, "false").items();
        assertEquals(1, all.size());
        assertEquals("Created", all.getFirst().relationshipStatus());
    }

    @Test
    void duplicateMemberAssociationIsConflict() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of());

        AwsException error = assertThrows(AwsException.class,
                () -> service.createMember(
                        REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of()));

        assertEquals("ConflictException", error.getErrorCode());
    }

    @Test
    void memberCannotBeAssociatedWithDifferentAdministrator() {
        AccountAwareStorageBackend<MacieState> states = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        AccountAwareStorageBackend<MacieMember> members = AccountAwareStorageBackend.inMemory(MANAGEMENT_ACCOUNT);
        MacieService localService = new MacieService(states, members);

        localService.enableMacie(REGION);
        localService.createMember(
                REGION, MANAGEMENT_ACCOUNT, "333333333333", "member@example.com", Map.of());

        MacieState secondAdmin = new MacieState();
        secondAdmin.setEnabled(true);
        secondAdmin.setAdminAccountId(ADMIN_ACCOUNT);
        states.putForAccount(ADMIN_ACCOUNT, REGION, secondAdmin);

        AwsException error = assertThrows(AwsException.class,
                () -> localService.createMember(
                        REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", Map.of()));

        assertEquals("ConflictException", error.getErrorCode());
    }

    @Test
    void listMembersPaginatesAndValidatesNextToken() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333331", "one@example.com", Map.of());
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333332", "two@example.com", Map.of());
        service.createMember(REGION, ADMIN_ACCOUNT, "333333333333", "three@example.com", Map.of());

        MacieService.Page<MacieMember> first = service.listMembers(
                REGION, ADMIN_ACCOUNT, "2", null, null);
        assertEquals(List.of("333333333331", "333333333332"),
                first.items().stream().map(MacieMember::accountId).toList());
        assertTrue(first.nextToken() != null && !first.nextToken().isBlank());

        MacieService.Page<MacieMember> second = service.listMembers(
                REGION, ADMIN_ACCOUNT, "2", first.nextToken(), null);
        assertEquals(List.of("333333333333"), second.items().stream().map(MacieMember::accountId).toList());
        assertNull(second.nextToken());

        AwsException error = assertThrows(AwsException.class,
                () -> service.listMembers(REGION, ADMIN_ACCOUNT, "2", "not-base64", null));
        assertEquals("ValidationException", error.getErrorCode());
    }

    @Test
    void createMemberValidatesAccountEmailAndTagQuota() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);

        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createMember(REGION, ADMIN_ACCOUNT, "bad", "member@example.com", Map.of()))
                .getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createMember(REGION, ADMIN_ACCOUNT, "333333333333", "not-an-email", Map.of()))
                .getErrorCode());
        Map<String, String> tooManyTags = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) {
            tooManyTags.put("k" + i, "v");
        }
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createMember(
                        REGION, ADMIN_ACCOUNT, "333333333333", "member@example.com", tooManyTags))
                .getErrorCode());
    }

    @Test
    void clearRemovesMacieState() {
        service.enableOrganizationAdminAccount(REGION, ADMIN_ACCOUNT);
        assertTrue(service.requireAdministratorSession(REGION, ADMIN_ACCOUNT).isEnabled());

        service.clear();

        assertFalse(service.state(REGION).isEnabled());
        AwsException error = assertThrows(AwsException.class,
                () -> service.requireAdministratorSession(REGION, ADMIN_ACCOUNT));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }
}
