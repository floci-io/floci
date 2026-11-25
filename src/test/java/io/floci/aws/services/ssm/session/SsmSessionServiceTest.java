package io.floci.aws.services.ssm.session;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.ssmmessages.SsmMessageGateway;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SsmSessionServiceTest {
    @Test
    void resolvedCallerInAnotherAccountCannotFallBackToRoot() {
        String account = "123456789012";
        String region = "us-east-1";
        String target = "i-fedcba98765432100";
        RequestContext context = mock(RequestContext.class);
        IamService iam = mock(IamService.class);
        SsmMessageGateway gateway = mock(SsmMessageGateway.class);
        when(context.getAccessKeyId()).thenReturn("foreign-user-key");
        when(iam.resolveCallerArn("foreign-user-key"))
                .thenReturn(Optional.of("arn:aws:iam::999999999999:user/foreign"));
        when(gateway.isConnected(new Node(account, region, target))).thenReturn(true);
        SsmSessionService service = new SsmSessionService(AccountAwareStorageBackend.inMemory(account), gateway,
                mock(SessionDocumentResolver.class), new RegionResolver(region, account), context, iam,
                Clock.systemUTC(), URI.create("http://localhost:4566"));

        AwsException error = assertThrows(AwsException.class,
                () -> service.start(region, target, null, null, Map.of(), null));

        assertEquals("AccessDeniedException", error.getErrorCode());
        assertTrue(service.describe(region, "Active", List.of(), null, null).items().isEmpty());
        assertTrue(service.describe(region, "History", List.of(), null, null).items().isEmpty());
    }
}
