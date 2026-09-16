package io.github.hectorvent.floci.services.ssoportal;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.ssoadmin.SsoAdminService;
import io.github.hectorvent.floci.services.ssooidc.SsoOidcException;
import io.github.hectorvent.floci.services.ssooidc.SsoOidcService;
import io.github.hectorvent.floci.services.ssooidc.model.TokenSession;
import io.github.hectorvent.floci.services.ssoportal.model.PortalAccountInfo;
import io.github.hectorvent.floci.services.ssoportal.model.PortalRoleInfo;
import io.github.hectorvent.floci.services.ssoportal.model.PortalRoleCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SsoPortalService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String SECRET_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private final SsoOidcService oidcService;
    private final SsoAdminService ssoAdminService;
    private final OrganizationsService organizationsService;
    private final IamService iamService;

    @Inject
    public SsoPortalService(SsoOidcService oidcService,
                            SsoAdminService ssoAdminService,
                            OrganizationsService organizationsService,
                            IamService iamService) {
        this.oidcService = oidcService;
        this.ssoAdminService = ssoAdminService;
        this.organizationsService = organizationsService;
        this.iamService = iamService;
    }

    public PaginatedResult<PortalAccountInfo> listAccounts(
            String accessToken, String maxResults, String nextToken) {
        TokenSession session = requirePortalSession(accessToken);
        Map<String, PortalAccountInfo> accounts = new LinkedHashMap<>();
        ssoAdminService.portalAssignmentsForUser(session.principalId()).forEach(assignment -> {
            if (accounts.containsKey(assignment.accountId())) {
                return;
            }
            PortalAccountInfo info = organizationsService.findAccountForPortal(assignment.accountId())
                    .map(account -> new PortalAccountInfo(account.getId(), account.getName(), account.getEmail()))
                    .orElseGet(() -> new PortalAccountInfo(assignment.accountId(), null, null));
            accounts.put(assignment.accountId(), info);
        });
        Integer pageSize = Pagination.parseMaxResults(maxResults, "InvalidRequestException");
        List<PortalAccountInfo> values = accounts.values().stream().toList();
        return Pagination.paginate(values, PortalAccountInfo::accountId,
                pageSize, nextToken, 100, 100, "InvalidRequestException");
    }

    public PaginatedResult<PortalRoleInfo> listAccountRoles(
            String accessToken, String accountId, String maxResults, String nextToken) {
        TokenSession session = requirePortalSession(accessToken);
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("InvalidRequestException", "accountId must be a 12-digit AWS account identifier.", 400);
        }
        Map<String, PortalRoleInfo> roles = new LinkedHashMap<>();
        ssoAdminService.portalAssignmentsForUser(session.principalId()).stream()
                .filter(assignment -> accountId.equals(assignment.accountId()))
                .forEach(assignment -> {
                    var permissionSet = ssoAdminService.permissionSetForPortal(assignment.permissionSetArn());
                    roles.putIfAbsent(assignment.permissionSetArn(),
                            new PortalRoleInfo(accountId, permissionSet.name()));
                });
        Integer pageSize = Pagination.parseMaxResults(maxResults, "InvalidRequestException");
        List<PortalRoleInfo> values = roles.values().stream().toList();
        return Pagination.paginate(values, PortalRoleInfo::roleName,
                pageSize, nextToken, 100, 100, "InvalidRequestException");
    }

    public PortalRoleCredentials getRoleCredentials(String accessToken, String accountId, String roleName) {
        TokenSession session = requirePortalSession(accessToken);
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new AwsException("InvalidRequestException", "accountId must be a 12-digit AWS account identifier.", 400);
        }
        if (roleName == null || roleName.isBlank()) {
            throw new AwsException("InvalidRequestException", "roleName is required.", 400);
        }
        var assignment = ssoAdminService.portalAssignmentsForUser(session.principalId()).stream()
                .filter(candidate -> accountId.equals(candidate.accountId()))
                .filter(candidate -> roleName.equals(
                        ssoAdminService.permissionSetForPortal(candidate.permissionSetArn()).name()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The requested account role is not assigned to this user.", 404));
        var permissionSet = ssoAdminService.permissionSetForPortal(assignment.permissionSetArn());
        Instant expiration = Instant.now().plus(Duration.parse(permissionSet.sessionDuration()));
        String accessKeyId = "ASIA" + random(UPPER_ALPHANUMERIC, 16);
        String secretAccessKey = random(SECRET_CHARACTERS, 40);
        String sessionToken = random(SECRET_CHARACTERS, 200);
        String roleArn = AwsArnUtils.Arn.of("iam", "", accountId,
                "role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_" + roleName + "_floci").toString();
        iamService.registerSessionForAccount(accountId, accessKeyId, secretAccessKey, sessionToken,
                roleArn, expiration, null);
        return new PortalRoleCredentials(accessKeyId, expiration.toEpochMilli(), secretAccessKey, sessionToken);
    }

    public void logout(String accessToken) {
        requirePortalSession(accessToken);
        oidcService.revokeAccessTokenSession(accessToken);
    }

    public TokenSession requirePortalSession(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw unauthorized("The access token is missing or invalid.");
        }
        try {
            TokenSession session = oidcService.requireAccessToken(accessToken);
            if (session.principalId() == null || session.principalId().isBlank()) {
                throw unauthorized("The access token is not associated with an authenticated user.");
            }
            return session;
        } catch (SsoOidcException e) {
            throw unauthorized("The access token is missing, invalid, or expired.");
        }
    }

    private static AwsException unauthorized(String message) {
        return new AwsException("UnauthorizedException", message, 401);
    }

    private static String random(String characters, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(characters.charAt(RANDOM.nextInt(characters.length())));
        }
        return value.toString();
    }
}
