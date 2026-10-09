package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElastiCacheAuthProxyTest {

    @Test
    void authModeIsReadFromTheCacheOnEachConnection() {
        AtomicReference<AuthMode> cacheAuthMode = new AtomicReference<>();
        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public AuthMode authMode() {
                return cacheAuthMode.get();
            }
        };
        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-live", AuthMode.IAM, "127.0.0.1", 6379,
                passwordValidator, mock(SigV4Validator.class));

        assertTrue(proxy.authRequired(), "the mode the proxy started with applies until the cache reports one");

        cacheAuthMode.set(AuthMode.NO_AUTH);
        assertFalse(proxy.authRequired(), "a cache whose last user group was removed has no access control");
    }

    @Test
    void groupWithIamAuthModeAuthenticatesPasswordUserWhenAssociated() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return "app-user".equals(username) && "app-pass".equals(password);
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return "app-user".equals(username) ? AuthMode.PASSWORD : null;
            }
        };

        // Replication group created with TransitEncryptionEnabled and no AuthToken -> AuthMode.IAM
        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-1", AuthMode.IAM, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);

        assertTrue(proxy.authenticate("app-user", "app-pass"),
                "password user must authenticate even if replication group mode is IAM");
        assertFalse(proxy.authenticate("app-user", "wrong-pass"));
        assertFalse(proxy.authenticate("outsider", "any-pass"));
    }

    @Test
    void groupWithPasswordAuthModeAuthenticatesIamUserWhenAssociated() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        when(sigV4Validator.validate("valid-sigv4", "grp-2", "iam-user")).thenReturn(true);
        when(sigV4Validator.validate("bad-sigv4", "grp-2", "iam-user")).thenReturn(false);

        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return "iam-user".equals(username) ? AuthMode.IAM : null;
            }
        };

        // Replication group created with AuthToken -> AuthMode.PASSWORD
        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-2", AuthMode.PASSWORD, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);

        assertTrue(proxy.authenticate("iam-user", "valid-sigv4"),
                "IAM user must authenticate with SigV4 token even if replication group mode is PASSWORD");
        assertFalse(proxy.authenticate("iam-user", "bad-sigv4"));
    }

    @Test
    void groupWithIamAuthModeAuthenticatesNoAuthUserWhenAssociated() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return "nopass-user".equals(username);
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return "nopass-user".equals(username) ? AuthMode.NO_AUTH : null;
            }
        };

        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-nopass", AuthMode.IAM, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);

        assertTrue(proxy.authenticate("nopass-user", "any-password"));
        assertTrue(proxy.authenticate("nopass-user", ""));
    }

    @Test
    void singleArgAuthFallsBackToDefaultMemberOrAuthToken() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                if (username == null && "token123".equals(password)) {
                    return true;
                }
                return (username == null || "default".equals(username)) && "def-pass".equals(password);
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return (username == null || "default".equals(username)) ? AuthMode.PASSWORD : null;
            }
        };

        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-3", AuthMode.IAM, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);

        assertTrue(proxy.authenticate(null, "def-pass"), "single-arg AUTH matches member named default");
        assertFalse(proxy.authenticate(null, "wrong-pass"));
    }

    @Test
    void groupWithMembersNormalizesEmptyUsernameToDefaultForIamValidation() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        when(sigV4Validator.validate("valid-sigv4", "grp-empty", "default")).thenReturn(true);

        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return (username == null || username.isEmpty()) ? AuthMode.IAM : null;
            }
        };

        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-empty", AuthMode.IAM, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);

        assertTrue(proxy.authenticate("", "valid-sigv4"),
                "AUTH \"\" <token> must validate against the default member's IAM token, not an empty username");
    }

    @Test
    void groupWithNoMembersRequiresDefaultUserForSingleArgIamAuth() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        when(sigV4Validator.validate("valid-sigv4", "grp-legacy-iam", "default")).thenReturn(true);

        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return false;
            }
        };

        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-legacy-iam", AuthMode.IAM, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);

        assertTrue(proxy.authenticate(null, "valid-sigv4"),
                "single-arg AUTH on an unassociated IAM cache must validate the token's User against default");
    }

    @Test
    void groupWithNoMembersPreservesLegacyIamAndAuthTokenBehaviour() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        when(sigV4Validator.validate("valid-sigv4", "grp-4", "anyone")).thenReturn(true);

        ElastiCacheAuthProxy.PasswordValidator passwordValidator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return "group-token".equals(password);
            }

            @Override
            public boolean hasMembers() {
                return false;
            }
        };

        ElastiCacheAuthProxy iamProxy = new ElastiCacheAuthProxy("grp-4", AuthMode.IAM, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);
        assertTrue(iamProxy.authenticate("anyone", "valid-sigv4"),
                "unassociated IAM cache admits any valid IAM user");

        ElastiCacheAuthProxy passProxy = new ElastiCacheAuthProxy("grp-5", AuthMode.PASSWORD, "127.0.0.1", 6379,
                passwordValidator, sigV4Validator);
        assertTrue(passProxy.authenticate(null, "group-token"),
                "unassociated password cache admits group auth token");
    }

    @Test
    void authRequiredDependsOnDefaultMemberAuthModeWhenClusterHasMembers() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);

        ElastiCacheAuthProxy.PasswordValidator validatorNoAuthDefault = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return "default".equals(username) ? AuthMode.NO_AUTH : AuthMode.PASSWORD;
            }
        };

        ElastiCacheAuthProxy.PasswordValidator validatorPasswordDefault = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return "default".equals(username) ? AuthMode.PASSWORD : null;
            }
        };

        ElastiCacheAuthProxy.PasswordValidator validatorIamDefault = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return "default".equals(username) ? AuthMode.IAM : null;
            }
        };

        ElastiCacheAuthProxy.PasswordValidator validatorWithoutMembers = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return false;
            }
        };

        ElastiCacheAuthProxy proxyNoAuth = new ElastiCacheAuthProxy("grp-noauth", AuthMode.NO_AUTH,
                "127.0.0.1", 6379, validatorNoAuthDefault, sigV4Validator);
        assertFalse(proxyNoAuth.authRequired(), "authRequired must be false when default member is NO_AUTH");

        ElastiCacheAuthProxy proxyPassword = new ElastiCacheAuthProxy("grp-pass", AuthMode.NO_AUTH,
                "127.0.0.1", 6379, validatorPasswordDefault, sigV4Validator);
        assertTrue(proxyPassword.authRequired(), "authRequired must be true when default member is PASSWORD");

        ElastiCacheAuthProxy proxyIam = new ElastiCacheAuthProxy("grp-iam", AuthMode.NO_AUTH,
                "127.0.0.1", 6379, validatorIamDefault, sigV4Validator);
        assertTrue(proxyIam.authRequired(), "authRequired must be true when default member is IAM");

        ElastiCacheAuthProxy proxyNoMembersNoAuth = new ElastiCacheAuthProxy("grp-plain-noauth", AuthMode.NO_AUTH,
                "127.0.0.1", 6379, validatorWithoutMembers, sigV4Validator);
        assertFalse(proxyNoMembersNoAuth.authRequired(), "authRequired must be false if NO_AUTH and no members");

        ElastiCacheAuthProxy proxyNoMembersPass = new ElastiCacheAuthProxy("grp-plain-pass", AuthMode.PASSWORD,
                "127.0.0.1", 6379, validatorWithoutMembers, sigV4Validator);
        assertTrue(proxyNoMembersPass.authRequired(), "authRequired must be true if PASSWORD and no members");
    }

    @Test
    void explicitDefaultUserAuthenticatesWithGroupAuthTokenWhenGroupHasMembers() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        ElastiCacheAuthProxy.PasswordValidator validator = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                boolean isDefault = (username == null || username.isEmpty() || "default".equals(username));
                return isDefault && "group-secret-token".equals(password);
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                // Group has members (e.g. app-user), but no member is named "default"
                return "app-user".equals(username) ? AuthMode.PASSWORD : null;
            }
        };

        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-token-members", AuthMode.PASSWORD,
                "127.0.0.1", 6379, validator, sigV4Validator);

        assertTrue(proxy.authenticate("default", "group-secret-token"),
                "AUTH default <token> must authenticate with group auth token even when group has members");
        assertTrue(proxy.authenticate("", "group-secret-token"),
                "AUTH \"\" <token> must authenticate with group auth token");
        assertTrue(proxy.authenticate(null, "group-secret-token"),
                "AUTH <token> must authenticate with group auth token");
        assertFalse(proxy.authenticate("default", "wrong-token"));
    }

    @Test
    void authRequiredIsTrueWhenGroupHasMembersWithoutDefaultMember() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        ElastiCacheAuthProxy.PasswordValidator validatorWithoutDefault = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                return false;
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                return "app-user".equals(username) ? AuthMode.PASSWORD : null;
            }
        };

        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-no-default", AuthMode.NO_AUTH,
                "127.0.0.1", 6379, validatorWithoutDefault, sigV4Validator);
        assertTrue(proxy.authRequired(),
                "authRequired must be true when group has members but no default member, even if replication group is NO_AUTH");
    }

    @Test
    void authRequiredIsTrueWhenDefaultMemberIsDisabled() {
        SigV4Validator sigV4Validator = mock(SigV4Validator.class);
        ElastiCacheAuthProxy.PasswordValidator validatorWithDisabledDefault = new ElastiCacheAuthProxy.PasswordValidator() {
            @Override
            public boolean validatePassword(String username, String password) {
                if ("app-user".equals(username) && "secret-123".equals(password)) {
                    return true;
                }
                return false;
            }

            @Override
            public boolean hasMembers() {
                return true;
            }

            @Override
            public AuthMode memberAuthMode(String username) {
                // Disabled default user returns null mode; active password user returns PASSWORD
                return "app-user".equals(username) ? AuthMode.PASSWORD : null;
            }
        };

        ElastiCacheAuthProxy proxy = new ElastiCacheAuthProxy("grp-disabled-default", AuthMode.NO_AUTH,
                "127.0.0.1", 6379, validatorWithDisabledDefault, sigV4Validator);

        assertTrue(proxy.authRequired(),
                "authRequired must be true when default member is disabled, keeping auth required");
        assertFalse(proxy.authenticate("default", "any-pass"),
                "AUTH default <pass> must fail when default user is disabled");
        assertFalse(proxy.authenticate(null, "any-pass"),
                "AUTH <pass> must fail when default user is disabled");
        assertFalse(proxy.authenticate("", "any-pass"),
                "AUTH \"\" <pass> must fail when default user is disabled");
        assertTrue(proxy.authenticate("app-user", "secret-123"),
                "Configured password user must be able to authenticate");
    }
}
