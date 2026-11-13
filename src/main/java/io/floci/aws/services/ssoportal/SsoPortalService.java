package io.floci.aws.services.ssoportal;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.services.ssoadmin.model.Assignment;
import io.floci.aws.services.ssoadmin.model.PermissionSet;
import io.floci.aws.services.ssooidc.SsoOidcException;
import io.floci.aws.services.ssooidc.SsoOidcService;
import io.floci.aws.services.ssooidc.model.TokenSession;
import io.floci.aws.services.ssoportal.model.PortalAccountInfo;
import io.floci.aws.services.ssoportal.model.PortalRoleCredentials;
import io.floci.aws.services.ssoportal.model.PortalRoleInfo;
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
    private final RegionResolver regionResolver;

    @Inject
    public SsoPortalService(SsoOidcService oidcService,
                            SsoAdminService ssoAdminService,
                            OrganizationsService organizationsService,
                            IamService iamService,
                            RegionResolver regionResolver) {
        this.oidcService = oidcService;
        this.ssoAdminService = ssoAdminService;
        this.organizationsService = organizationsService;
        this.iamService = iamService;
        this.regionResolver = regionResolver;
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
                    PermissionSet permissionSet = ssoAdminService.permissionSetForPortal(assignment.permissionSetArn());
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
        Assignment assignment = ssoAdminService.portalAssignmentsForUser(session.principalId()).stream()
                .filter(candidate -> accountId.equals(candidate.accountId()))
                .filter(candidate -> roleName.equals(
                        ssoAdminService.permissionSetForPortal(candidate.permissionSetArn()).name()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The requested account role is not assigned to this user.", 404));
        PermissionSet permissionSet = ssoAdminService.permissionSetForPortal(assignment.permissionSetArn());
        Instant expiration = Instant.now().plus(Duration.parse(permissionSet.sessionDuration()));
        String accessKeyId = "ASIA" + random(UPPER_ALPHANUMERIC, 16);
        String secretAccessKey = random(SECRET_CHARACTERS, 40);
        String sessionToken = random(SECRET_CHARACTERS, 200);
        String roleArn = regionResolver.buildGlobalArn("iam", accountId,
                "role/aws-reserved/" + ServicePrincipals.of("sso") + "/AWSReservedSSO_" + roleName + "_floci");
        // IAM Identity Center names the role session after the user, as GetCallerIdentity and EKS then report it.
        String sessionName = ssoAdminService.portalSessionName(assignment, session.principalId()).orElse(null);
        iamService.registerSessionForAccount(accountId, accessKeyId, secretAccessKey, sessionToken,
                roleArn, expiration, null, sessionName);
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
