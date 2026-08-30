package io.github.hectorvent.floci.services.aps;

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

    private static final String US_EAST_1 = "us-east-1";
    private static final String EU_WEST_1 = "eu-west-1";

    private ApsService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));

        service = new ApsService(storageFactory, new RegionResolver(US_EAST_1, "000000000000"));
    }

    @Test
    void createWorkspaceIsActiveWithArnAndEndpoint() {
        PrometheusWorkspace workspace =
                service.createWorkspace(US_EAST_1, "my-workspace", Map.of("team", "devops"), null);

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
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeWorkspace(US_EAST_1, "ws-missing"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void describeWorkspaceAfterDeleteThrowsResourceNotFound() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "doomed", null, null);
        service.deleteWorkspace(US_EAST_1, workspace.getWorkspaceId());

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void workspacesAreScopedToTheirRegion() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "regional", null, null);

        AwsException describe = assertThrows(AwsException.class,
                () -> service.describeWorkspace(EU_WEST_1, workspace.getWorkspaceId()));
        assertEquals("ResourceNotFoundException", describe.getErrorCode());

        assertEquals(0, service.listWorkspaces(EU_WEST_1, null, null, null).items().size());
        assertEquals(1, service.listWorkspaces(US_EAST_1, null, null, null).items().size());

        AwsException delete = assertThrows(AwsException.class,
                () -> service.deleteWorkspace(EU_WEST_1, workspace.getWorkspaceId()));
        assertEquals("ResourceNotFoundException", delete.getErrorCode());
        // The cross-region delete must not have touched the real workspace.
        assertEquals(workspace.getWorkspaceId(),
                service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()).getWorkspaceId());
    }

    @Test
    void listWorkspacesFiltersByAliasPrefix() {
        service.createWorkspace(US_EAST_1, "prod-metrics", null, null);
        service.createWorkspace(US_EAST_1, "prod-traces", null, null);
        service.createWorkspace(US_EAST_1, "staging-metrics", null, null);

        assertEquals(2, service.listWorkspaces(US_EAST_1, "prod-", null, null).items().size());
        assertEquals(3, service.listWorkspaces(US_EAST_1, null, null, null).items().size());
        assertEquals(0, service.listWorkspaces(US_EAST_1, "missing", null, null).items().size());
    }

    @Test
    void aliasesAreStrippedOnCreateUpdateAndListFilter() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, " prod ", null, null);
        assertEquals("prod", workspace.getAlias());

        // AWS strips the filter value too, so " prod " round-trips against a "prod" alias.
        assertEquals(1, service.listWorkspaces(US_EAST_1, " prod ", null, null).items().size());

        service.updateWorkspaceAlias(US_EAST_1, workspace.getWorkspaceId(), "  renamed  ");
        assertEquals("renamed",
                service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()).getAlias());
    }

    @Test
    void listWorkspacesPaginates() {
        service.createWorkspace(US_EAST_1, "a", null, null);
        service.createWorkspace(US_EAST_1, "b", null, null);
        service.createWorkspace(US_EAST_1, "c", null, null);

        PaginatedResult<PrometheusWorkspace> firstPage =
                service.listWorkspaces(US_EAST_1, null, 2, null);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        PaginatedResult<PrometheusWorkspace> secondPage =
                service.listWorkspaces(US_EAST_1, null, 2, firstPage.nextToken());
        assertEquals(1, secondPage.items().size());
        assertNull(secondPage.nextToken());
    }

    @Test
    void listWorkspacesDefaultsToPagesOf100() {
        for (int i = 0; i < 101; i++) {
            service.createWorkspace(US_EAST_1, "bulk-" + i, null, null);
        }

        PaginatedResult<PrometheusWorkspace> page = service.listWorkspaces(US_EAST_1, null, null, null);
        assertEquals(100, page.items().size());
        assertNotNull(page.nextToken());
    }

    @Test
    void listWorkspacesRejectsZeroMaxResultsWithValidationException() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.listWorkspaces(US_EAST_1, null, 0, null));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    @Test
    void updateWorkspaceAliasPersists() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "old-alias", null, null);
        service.updateWorkspaceAlias(US_EAST_1, workspace.getWorkspaceId(), "new-alias");
        assertEquals("new-alias",
                service.describeWorkspace(US_EAST_1, workspace.getWorkspaceId()).getAlias());
    }

    @Test
    void tagHandlerRoundTripsTagsByArn() {
        PrometheusWorkspace workspace =
                service.createWorkspace(US_EAST_1, "tagged", Map.of("env", "test"), null);
        String arn = workspace.getArn();

        assertEquals("aps", service.serviceKey());
        assertEquals(Map.of("env", "test"), service.listTags(US_EAST_1, arn));

        service.tagResource(US_EAST_1, arn, Map.of("team", "devops"));
        assertEquals(Map.of("env", "test", "team", "devops"), service.listTags(US_EAST_1, arn));

        service.untagResource(US_EAST_1, arn, List.of("env"));
        assertEquals(Map.of("team", "devops"), service.listTags(US_EAST_1, arn));
    }

    @Test
    void tagOperationsAreScopedToTheRequestRegion() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "tagged", null, null);

        // A tag call served by another region must not see (or mutate) this workspace.
        AwsException ex = assertThrows(AwsException.class,
                () -> service.tagResource(EU_WEST_1, workspace.getArn(), Map.of("team", "devops")));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertTrue(service.listTags(US_EAST_1, workspace.getArn()).isEmpty());
    }

    @Test
    void tagResourceRejectsReservedAwsKeyPrefix() {
        PrometheusWorkspace workspace = service.createWorkspace(US_EAST_1, "tagged", null, null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.tagResource(US_EAST_1, workspace.getArn(), Map.of("aws:cloudformation:stack", "x")));
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void tagHandlerUnknownWorkspaceArnThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.listTags(US_EAST_1, "arn:aws:aps:us-east-1:000000000000:workspace/ws-missing"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void tagHandlerMalformedArnThrowsValidationException() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.listTags(US_EAST_1, "arn:aws:aps:us-east-1:000000000000:workspace"));
        assertEquals("ValidationException", ex.getErrorCode());
    }
}
