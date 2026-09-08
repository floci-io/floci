package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftIamDbUserResolverTest {

    private IamService iamService;
    private AccountResolver accountResolver;
    private RedshiftIamDbUserResolver resolver;

    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        accountResolver = mock(AccountResolver.class);
        resolver = new RedshiftIamDbUserResolver(iamService, accountResolver);
    }

    @Test
    void mapsIamUserArnToIamPrefix() {
        when(accountResolver.extractAccessKeyId("auth")).thenReturn("AKIA123");
        when(iamService.resolveCallerArn("AKIA123"))
                .thenReturn(Optional.of("arn:aws:iam::000000000000:user/alice"));

        assertEquals("IAM:alice", resolver.resolveDbUser("auth"));
    }

    @Test
    void mapsAssumedRoleArnToIamrPrefix() {
        when(accountResolver.extractAccessKeyId("auth")).thenReturn("ASIA123");
        when(iamService.resolveCallerArn("ASIA123"))
                .thenReturn(Optional.of("arn:aws:sts::000000000000:assumed-role/Deployer/session-1"));

        assertEquals("IAMR:Deployer", resolver.resolveDbUser("auth"));
    }

    @Test
    void fallsBackWhenCallerUnresolved() {
        when(accountResolver.extractAccessKeyId("auth")).thenReturn("AKIA123");
        when(iamService.resolveCallerArn("AKIA123")).thenReturn(Optional.empty());

        assertEquals("IAMR:floci", resolver.resolveDbUser("auth"));
    }

    @Test
    void fallsBackWhenHeaderMissing() {
        when(accountResolver.extractAccessKeyId(null)).thenReturn(null);

        assertEquals("IAMR:floci", resolver.resolveDbUser(null));
    }
}
