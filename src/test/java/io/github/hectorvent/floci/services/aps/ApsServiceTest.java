package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import io.github.hectorvent.floci.services.aps.model.RuleGroupsNamespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ApsServiceTest {

    private static final String US_EAST_1 = "us-east-1";
    private static final String EU_WEST_1 = "eu-west-1";
    private static final String RULES = Base64.getEncoder().encodeToString(
            "groups:\n- name: alerts\n  rules: []\n".getBytes(StandardCharsets.UTF_8));

    private ApsService service;
    private final Map<String, StorageBackend<String, ?>> backendsByFile = new HashMap<>();

    @BeforeEach
    void setUp() {
        backendsByFile.clear();
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    StorageBackend<String, ?> backend = AccountAwareStorageBackend.inMemory("000000000000");
                    backendsByFile.put(invocation.getArgument(1), backend);
                    return backend;
                });

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
        assertEquals("ValidationException", ex.getErrorCode());
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

    private String workspaceWithNamespace(String namespaceName) {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, namespaceName, RULES, null);
        return workspaceId;
    }

    @Test
    void createRuleGroupsNamespaceIsActiveWithArnAndRoundTripsData() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();

        RuleGroupsNamespace namespace = service.createRuleGroupsNamespace(
                US_EAST_1, workspaceId, "alerts", RULES, Map.of("team", "devops"));

        assertEquals("alerts", namespace.getName());
        assertEquals("ACTIVE", namespace.getStatus());
        assertEquals("arn:aws:aps:us-east-1:000000000000:rulegroupsnamespace/" + workspaceId + "/alerts",
                namespace.getArn());
        assertEquals(RULES, namespace.getEncodedData());
        assertNotNull(namespace.getCreatedAt());
        assertNotNull(namespace.getModifiedAt());
        assertEquals("devops", namespace.getTags().get("team"));
    }

    @Test
    void createRuleGroupsNamespaceUnknownWorkspaceThrowsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.createRuleGroupsNamespace(US_EAST_1, "ws-missing", "alerts", RULES, null));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void createRuleGroupsNamespaceRejectsDuplicateNameWithConflict() {
        String workspaceId = workspaceWithNamespace("alerts");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", RULES, null));
        assertEquals("ConflictException", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    void createRuleGroupsNamespaceRejectsMissingData() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", null, null));
        assertEquals("ValidationException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void putRuleGroupsNamespaceReplacesTheStoredData() {
        String workspaceId = workspaceWithNamespace("alerts");
        String updated = Base64.getEncoder().encodeToString(
                "groups:\n- name: updated\n  rules: []\n".getBytes(StandardCharsets.UTF_8));

        service.putRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", updated);

        assertEquals(updated,
                service.describeRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts").getEncodedData());
    }

    @Test
    void putRuleGroupsNamespaceUnknownNameThrowsResourceNotFound() {
        String workspaceId = workspaceWithNamespace("alerts");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.putRuleGroupsNamespace(US_EAST_1, workspaceId, "missing", RULES));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void listRuleGroupsNamespacesFiltersByNamePrefixAndPaginates() {
        String workspaceId = workspaceWithNamespace("prod-alerts");
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "prod-records", RULES, null);
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "staging-alerts", RULES, null);

        assertEquals(3, service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, null, null, null)
                .items().size());
        assertEquals(2, service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, "prod-", null, null)
                .items().size());

        PaginatedResult<RuleGroupsNamespace> firstPage =
                service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, null, 2, null);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());
        assertEquals(1, service.listRuleGroupsNamespaces(US_EAST_1, workspaceId, null, 2,
                firstPage.nextToken()).items().size());
    }

    @Test
    void ruleGroupsNamespacesAreScopedToTheirWorkspace() {
        String workspaceId = workspaceWithNamespace("alerts");
        String otherWorkspaceId =
                service.createWorkspace(US_EAST_1, "other", null, null).getWorkspaceId();

        assertEquals(0, service.listRuleGroupsNamespaces(US_EAST_1, otherWorkspaceId, null, null, null)
                .items().size());
        AwsException ex = assertThrows(AwsException.class, () ->
                service.describeRuleGroupsNamespace(US_EAST_1, otherWorkspaceId, "alerts"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals("alerts",
                service.describeRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts").getName());
    }

    @Test
    void deleteRuleGroupsNamespaceThenDescribeThrowsResourceNotFound() {
        String workspaceId = workspaceWithNamespace("alerts");

        service.deleteRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.describeRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void deleteWorkspaceRemovesItsRuleGroupsNamespaces() {
        String workspaceId = workspaceWithNamespace("alerts");
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "records", RULES, null);

        service.deleteWorkspace(US_EAST_1, workspaceId);

        assertTrue(backendsByFile.get("aps-rule-groups-namespaces.json").scan(k -> true).isEmpty());
    }

    @Test
    void createRuleGroupsNamespaceRejectsNamesThatBreakPathAddressing() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();

        for (String invalid : List.of("nested/name", "", "!!!", "x".repeat(129))) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    service.createRuleGroupsNamespace(US_EAST_1, workspaceId, invalid, RULES, null));
            assertEquals("ValidationException", ex.getErrorCode(), "name: " + invalid);
            assertEquals(400, ex.getHttpStatus());
        }
    }

    @Test
    void tagHandlerRejectsArnsFromAnotherAccountOrRegion() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();
        service.createRuleGroupsNamespace(US_EAST_1, workspaceId, "alerts", RULES, null);

        String foreignAccount = "arn:aws:aps:us-east-1:999999999999:rulegroupsnamespace/"
                + workspaceId + "/alerts";
        AwsException byAccount = assertThrows(AwsException.class,
                () -> service.listTags(US_EAST_1, foreignAccount));
        assertEquals("ValidationException", byAccount.getErrorCode());

        String foreignRegion = "arn:aws:aps:eu-west-1:000000000000:rulegroupsnamespace/"
                + workspaceId + "/alerts";
        AwsException byRegion = assertThrows(AwsException.class,
                () -> service.listTags(US_EAST_1, foreignRegion));
        assertEquals("ValidationException", byRegion.getErrorCode());

        String foreignService = "arn:aws:ecs:us-east-1:000000000000:rulegroupsnamespace/"
                + workspaceId + "/alerts";
        AwsException byService = assertThrows(AwsException.class,
                () -> service.listTags(US_EAST_1, foreignService));
        assertEquals("ValidationException", byService.getErrorCode());
    }

    @Test
    void tagHandlerRoundTripsRuleGroupsNamespaceTagsByArn() {
        String workspaceId = service.createWorkspace(US_EAST_1, "rules", null, null).getWorkspaceId();
        String arn = service.createRuleGroupsNamespace(
                US_EAST_1, workspaceId, "alerts", RULES, Map.of("env", "test")).getArn();

        assertEquals(Map.of("env", "test"), service.listTags(US_EAST_1, arn));

        service.tagResource(US_EAST_1, arn, Map.of("team", "devops"));
        assertEquals(Map.of("env", "test", "team", "devops"), service.listTags(US_EAST_1, arn));

        service.untagResource(US_EAST_1, arn, List.of("env"));
        assertEquals(Map.of("team", "devops"), service.listTags(US_EAST_1, arn));
    }
}
