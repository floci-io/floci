package io.floci.aws.services.ssm.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.ssm.session.SessionDocumentResolver.ResolvedDocument;
import io.floci.aws.services.ssmmessages.SsmMessageGateway;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.testing.MutableClock;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void startingASessionDeletesHistoryOlderThanItsRetention() {
        String account = "123456789012";
        String region = "us-east-1";
        String target = "i-fedcba98765432100";
        RequestContext context = mock(RequestContext.class);
        IamService iam = mock(IamService.class);
        SsmMessageGateway gateway = mock(SsmMessageGateway.class);
        SessionDocumentResolver documents = mock(SessionDocumentResolver.class);
        when(context.getAccessKeyId()).thenReturn("operator-key");
        when(iam.resolveCallerArn("operator-key")).thenReturn(Optional.of("arn:aws:iam::123456789012:user/operator"));
        when(gateway.isConnected(new Node(account, region, target))).thenReturn(true);
        when(gateway.startSession(any(), any())).thenReturn("token");
        when(documents.resolve(region, null, Map.of())).thenReturn(new ResolvedDocument(
                "SSM-SessionManagerRunShell", "arn:aws:ssm:us-east-1:123456789012:document/SSM-SessionManagerRunShell",
                new ObjectMapper().createObjectNode(), Map.of()));
        AccountAwareStorageBackend<SsmSession> storage = AccountAwareStorageBackend.inMemory(account);
        MutableClock clock = new MutableClock();
        SsmSessionService service = new SsmSessionService(storage, gateway, documents,
                new RegionResolver(region, account), context, iam, clock, URI.create("http://localhost:4566"));

        String first = service.start(region, target, null, null, Map.of(), null).sessionId();
        service.terminate(region, first);
        clock.advance(Duration.ofDays(29));
        String second = service.start(region, target, null, null, Map.of(), null).sessionId();
        assertEquals(2, storage.keys().size(), "a session that ended 29 days ago is still History");

        clock.advance(Duration.ofDays(2));
        String third = service.start(region, target, null, null, Map.of(), null).sessionId();

        assertEquals(Set.of(region + "|" + second, region + "|" + third), storage.keys());
    }
}
