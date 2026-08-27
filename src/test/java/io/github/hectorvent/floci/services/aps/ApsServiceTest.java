package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ApsServiceTest {

    private ApsService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");

        service = new ApsService(storageFactory, config, new RegionResolver("us-east-1", "000000000000"));
    }

    @Test
    void createWorkspaceIsActiveWithArnAndEndpoint() {
        PrometheusWorkspace workspace = service.createWorkspace("my-workspace", Map.of("team", "devops"), null);

        assertTrue(workspace.getWorkspaceId().startsWith("ws-"));
        assertEquals("ACTIVE", workspace.getStatus());
        assertEquals("arn:aws:aps:us-east-1:000000000000:workspace/" + workspace.getWorkspaceId(),
                workspace.getArn());
        assertEquals("https://aps-workspaces.us-east-1.amazonaws.com/workspaces/"
                + workspace.getWorkspaceId() + "/", workspace.getPrometheusEndpoint());
        assertNotNull(workspace.getCreatedAt());
        assertEquals("devops", workspace.getTags().get("team"));
    }

    @Test
    void describeWorkspaceUnknownIdThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () -> service.describeWorkspace("ws-missing"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void describeWorkspaceAfterDeleteThrowsResourceNotFound() {
        PrometheusWorkspace workspace = service.createWorkspace("doomed", null, null);
        service.deleteWorkspace(workspace.getWorkspaceId());

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeWorkspace(workspace.getWorkspaceId()));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void listWorkspacesFiltersByAliasPrefix() {
        service.createWorkspace("prod-metrics", null, null);
        service.createWorkspace("prod-traces", null, null);
        service.createWorkspace("staging-metrics", null, null);

        assertEquals(2, service.listWorkspaces("prod-", null, null).items().size());
        assertEquals(3, service.listWorkspaces(null, null, null).items().size());
        assertEquals(0, service.listWorkspaces("missing", null, null).items().size());
    }

    @Test
    void listWorkspacesPaginates() {
        service.createWorkspace("a", null, null);
        service.createWorkspace("b", null, null);
        service.createWorkspace("c", null, null);

        PaginatedResult<PrometheusWorkspace> firstPage = service.listWorkspaces(null, 2, null);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        PaginatedResult<PrometheusWorkspace> secondPage = service.listWorkspaces(null, 2, firstPage.nextToken());
        assertEquals(1, secondPage.items().size());
        assertNull(secondPage.nextToken());
    }

    @Test
    void listWorkspacesRejectsZeroMaxResultsWithValidationException() {
        AwsException ex = assertThrows(AwsException.class, () -> service.listWorkspaces(null, 0, null));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void updateWorkspaceAliasPersists() {
        PrometheusWorkspace workspace = service.createWorkspace("old-alias", null, null);
        service.updateWorkspaceAlias(workspace.getWorkspaceId(), "new-alias");
        assertEquals("new-alias", service.describeWorkspace(workspace.getWorkspaceId()).getAlias());
    }

    @Test
    void tagHandlerRoundTripsTagsByArn() {
        PrometheusWorkspace workspace = service.createWorkspace("tagged", Map.of("env", "test"), null);
        String arn = workspace.getArn();

        assertEquals("aps", service.serviceKey());
        assertEquals(Map.of("env", "test"), service.listTags("us-east-1", arn));

        service.tagResource("us-east-1", arn, Map.of("team", "devops"));
        assertEquals(Map.of("env", "test", "team", "devops"), service.listTags("us-east-1", arn));

        service.untagResource("us-east-1", arn, List.of("env"));
        assertEquals(Map.of("team", "devops"), service.listTags("us-east-1", arn));
    }

    @Test
    void tagHandlerUnknownWorkspaceArnThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.listTags("us-east-1", "arn:aws:aps:us-east-1:000000000000:workspace/ws-missing"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void tagHandlerMalformedArnThrowsValidationException() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.listTags("us-east-1", "arn:aws:aps:us-east-1:000000000000:workspace"));
        assertEquals("ValidationException", ex.getErrorCode());
    }
}
