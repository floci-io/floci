package io.github.hectorvent.floci.services.ssoportal;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.ssoadmin.SsoAdminService;
import io.github.hectorvent.floci.services.ssoadmin.model.Assignment;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSet;
import io.github.hectorvent.floci.services.ssooidc.SsoOidcException;
import io.github.hectorvent.floci.services.ssooidc.SsoOidcService;
import io.github.hectorvent.floci.services.ssooidc.model.TokenSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoPortalServiceTest {
    private SsoOidcService oidcService;
    private SsoAdminService ssoAdminService;
    private OrganizationsService organizationsService;
    private IamService iamService;
    private SsoPortalService service;

    @BeforeEach
    void setUp() {
        oidcService = mock(SsoOidcService.class);
        ssoAdminService = mock(SsoAdminService.class);
        organizationsService = mock(OrganizationsService.class);
        iamService = mock(IamService.class);
        service = new SsoPortalService(oidcService, ssoAdminService, organizationsService, iamService);
    }

    @Test
    void listsAssignedAccountsAndRolesAndIssuesCredentials() {
        String accessToken = "access-token";
        String principalId = "11111111-2222-3333-4444-555555555555";
        String accountId = "123456789012";
        String permissionSetArn = "arn:aws:sso:::permissionSet/ssoins-7223b02a5d9f7c8e/ps-1234567890abcdef";
        when(oidcService.requireAccessToken(accessToken)).thenReturn(new TokenSession(
                accessToken, "refresh-token", "client-id", List.of("sso:account:access"),
                Instant.now().plusSeconds(3600).getEpochSecond(), Instant.now().plusSeconds(7200).getEpochSecond(), principalId));
        when(ssoAdminService.portalAssignmentsForUser(principalId)).thenReturn(List.of(
                new Assignment(accountId, permissionSetArn, principalId, "USER")));
        when(organizationsService.findAccountForPortal(accountId)).thenReturn(Optional.empty());
        when(ssoAdminService.permissionSetForPortal(permissionSetArn)).thenReturn(new PermissionSet(
                permissionSetArn, "PlatformAdmins", null, "PT1H", Map.of(), Map.of(), null, null, Map.of()));

        var accounts = service.listAccounts(accessToken, null, null);
        assertEquals(List.of(accountId), accounts.items().stream().map(a -> a.accountId()).toList());

        var roles = service.listAccountRoles(accessToken, accountId, null, null);
        assertEquals("PlatformAdmins", roles.items().getFirst().roleName());

        var credentials = service.getRoleCredentials(accessToken, accountId, "PlatformAdmins");
        assertTrue(credentials.accessKeyId().startsWith("ASIA"));
        assertTrue(credentials.expiration() > System.currentTimeMillis());

        ArgumentCaptor<String> roleArn = ArgumentCaptor.forClass(String.class);
        verify(iamService).registerSessionForAccount(eq(accountId), any(), any(), any(), roleArn.capture(), any(), eq(null));
        assertTrue(roleArn.getValue().contains(":iam::" + accountId + ":role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_PlatformAdmins_floci"));
    }

    @Test
    void logoutRevokesTheAccessTokenSession() {
        when(oidcService.requireAccessToken("access-token")).thenReturn(new TokenSession(
                "access-token", null, "client-id", List.of(),
                Instant.now().plusSeconds(3600).getEpochSecond(), 0, "principal"));

        service.logout("access-token");

        verify(oidcService).revokeAccessTokenSession("access-token");
    }

    @Test
    void rejectsInvalidOrExpiredTokens() {
        when(oidcService.requireAccessToken("bad-token")).thenThrow(new SsoOidcException("invalid_token", "expired", 401));

        var error = assertThrows(io.github.hectorvent.floci.core.common.AwsException.class,
                () -> service.listAccounts("bad-token", null, null));
        assertEquals("UnauthorizedException", error.getErrorCode());
    }
}
