package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OrganizationsPathRenderingTest {

    private static final String OWNER = "100623600001";
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<Organization> organizations;
    private AccountAwareStorageBackend<OrganizationAccount> accounts;
    private AccountAwareStorageBackend<OrganizationalUnit> units;
    private OrganizationsService service;
    private OrganizationsJsonHandler handler;
    private Organization organization;
    private String root;

    @BeforeEach
    void setUp() {
        organizations = spy(AccountAwareStorageBackend.inMemory(OWNER));
        accounts = spy(AccountAwareStorageBackend.inMemory(OWNER));
        units = spy(AccountAwareStorageBackend.inMemory(OWNER));
        service = spy(new OrganizationsService(mapper, new RegionResolver("us-east-1", OWNER),
                organizations, accounts, units, AccountAwareStorageBackend.inMemory(OWNER),
                AccountAwareStorageBackend.inMemory(OWNER), AccountAwareStorageBackend.inMemory(OWNER)));
        handler = new OrganizationsJsonHandler(service, mapper);
        organization = service.createOrganization(OWNER, "ALL");
        root = organization.getRoot().getId();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void liveAndCachedPathsUseTheSameRootToLeafFormat(int depth) {
        String parent = root;
        String prefix = organization.getId() + "/" + root + "/";
        assertEquals(prefix, service.organizationPath(OWNER, root));
        for (int level = 0; level < depth; level++) {
            OrganizationalUnit unit = service.createOrganizationalUnit(OWNER, parent, "Level" + level, null);
            parent = unit.getId();
            prefix += parent + "/";
            assertEquals(prefix, service.organizationPath(OWNER, parent));
            assertEquals(prefix, service.organizationPaths(OWNER).unitPath(unit));
        }
        String member = member("parity@example.test");
        if (depth > 0) {
            service.moveAccount(OWNER, member, root, parent);
        }
        String expected = prefix + member + "/";
        assertEquals(expected, service.organizationPath(OWNER, member));
        assertEquals(expected, service.organizationPaths(OWNER).accountPath(service.describeAccount(OWNER, member)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ListAccounts", "ListAccountsForParent", "ListOrganizationalUnitsForParent"})
    void selectedRowsStillRenderAfterTheirResourcesAndAncestorsAreRemoved(String action) {
        OrganizationalUnit parent = service.createOrganizationalUnit(OWNER, root, "Parent", null);
        OrganizationalUnit child = service.createOrganizationalUnit(OWNER, parent.getId(), "Child", null);
        String member = member("removed@example.test");
        service.moveAccount(OWNER, member, root, child.getId());
        ObjectNode request = mapper.createObjectNode().put("ParentId",
                action.equals("ListOrganizationalUnitsForParent") ? parent.getId() : child.getId());
        if (action.equals("ListOrganizationalUnitsForParent")) {
            doAnswer(invocation -> {
                Object selected = invocation.callRealMethod();
                removeHierarchy(member, child.getId(), parent.getId());
                return selected;
            }).when(service).listOrganizationalUnitsForParent(OWNER, parent.getId());
        } else if (action.equals("ListAccountsForParent")) {
            doAnswer(invocation -> {
                Object selected = invocation.callRealMethod();
                removeHierarchy(member, child.getId(), parent.getId());
                return selected;
            }).when(service).listAccountsForParent(OWNER, child.getId());
        } else {
            doAnswer(invocation -> {
                Object selected = invocation.callRealMethod();
                removeHierarchy(member, child.getId(), parent.getId());
                return selected;
            }).when(service).listAccounts(OWNER);
        }
        try (Response response = handler.handle(action, request, OWNER)) {
            assertEquals(200, response.getStatus(), response.getEntity().toString());
            JsonNode body = (JsonNode) response.getEntity();
            String prefix = organization.getId() + "/" + root + "/" + parent.getId() + "/" + child.getId() + "/";
            if (action.equals("ListOrganizationalUnitsForParent")) {
                assertEquals(prefix, body.path("OrganizationalUnits").get(0).path("Path").asText());
            } else {
                JsonNode selected = body.path("Accounts").findParents("Id").stream()
                        .filter(account -> member.equals(account.path("Id").asText())).findFirst().orElseThrow();
                assertEquals(prefix + member + "/", selected.path("Paths").get(0).asText());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 19})
    void accountPageRenderingDoesNotRepeatCollectionScansForEachRow(int members) {
        OrganizationalUnit parent = service.createOrganizationalUnit(OWNER, root, "Parent", null);
        for (int index = 0; index < members; index++) {
            String member = member("member" + index + "@example.test");
            service.moveAccount(OWNER, member, root, parent.getId());
        }
        clearInvocations(organizations, accounts, units);
        try (Response response = handler.handle("ListAccounts", mapper.createObjectNode().put("MaxResults", 20), OWNER)) {
            assertEquals(200, response.getStatus());
            assertEquals(members + 1, ((JsonNode) response.getEntity()).path("Accounts").size());
        }
        verify(accounts, times(1)).scanAllAccounts();
        verify(units, times(1)).scanAllAccounts();
        verify(organizations, times(2)).scanAllAccounts();
    }

    @Test
    void aParentCreatedAfterThePathMapIsCapturedCanStillBeResolved() {
        String member = member("moved@example.test");
        doAnswer(invocation -> {
            OrganizationalUnit unit = service.createOrganizationalUnit(OWNER, root, "New", null);
            service.moveAccount(OWNER, member, root, unit.getId());
            return invocation.callRealMethod();
        }).when(service).listAccounts(OWNER);
        try (Response response = handler.handle("ListAccounts", mapper.createObjectNode(), OWNER)) {
            assertEquals(200, response.getStatus(), response.getEntity().toString());
            OrganizationAccount account = service.describeAccount(OWNER, member);
            JsonNode selected = ((JsonNode) response.getEntity()).path("Accounts").findParents("Id").stream()
                    .filter(row -> member.equals(row.path("Id").asText())).findFirst().orElseThrow();
            assertEquals(organization.getId() + "/" + root + "/" + account.getParentId() + "/" + member + "/",
                    selected.path("Paths").get(0).asText());
        }
    }

    @Test
    void aRemovedRowWithANewlyCreatedAndRemovedParentDoesNotFailThePage() {
        String member = member("gone@example.test");
        doAnswer(invocation -> {
            OrganizationalUnit unit = service.createOrganizationalUnit(OWNER, root, "Temporary", null);
            service.moveAccount(OWNER, member, root, unit.getId());
            Object selected = invocation.callRealMethod();
            service.removeAccountFromOrganization(OWNER, member);
            service.deleteOrganizationalUnit(OWNER, unit.getId());
            return selected;
        }).when(service).listAccounts(OWNER);
        try (Response response = handler.handle("ListAccounts", mapper.createObjectNode(), OWNER)) {
            assertEquals(200, response.getStatus(), response.getEntity().toString());
            JsonNode rows = ((JsonNode) response.getEntity()).path("Accounts");
            assertEquals(1, rows.size());
            assertEquals(OWNER, rows.get(0).path("Id").asText());
        }
    }

    private String member(String email) {
        return service.createAccount(OWNER, email, "Member", null, false).getAccountId();
    }

    @Test
    void capturedPathsRemainBoundToTheirAuthorizedOrganization() {
        String member = member("reader@example.test");
        OrganizationsService.OrganizationPaths paths = service.organizationPaths(member);
        assertEquals(organization.getId() + "/" + root + "/" + OWNER + "/",
                paths.accountPath(service.describeAccount(OWNER, OWNER)));
        AwsException outsider = assertThrows(AwsException.class, () -> service.organizationPaths("100623600009"));
        assertEquals("AWSOrganizationsNotInUseException", outsider.getErrorCode());
        service.createOrganization("100623600009", "ALL");
        OrganizationAccount foreign = service.describeAccount("100623600009", "100623600009");
        AwsException mismatch = assertThrows(AwsException.class, () -> paths.accountPath(foreign));
        assertEquals("AccessDeniedException", mismatch.getErrorCode());
    }

    @Test
    void anUnchangedTargetWithInvalidAncestryIsNotSilentlyOmitted() {
        String member = member("invalid-parent@example.test");
        OrganizationAccount account = service.describeAccount(OWNER, member);
        account.setParentId("ou-abcd-abcdefgh");
        OrganizationsService.OrganizationPaths paths = service.organizationPaths(OWNER);
        AwsException invalid = assertThrows(AwsException.class, () -> paths.accountPath(account));
        assertEquals("OrganizationalUnitNotFoundException", invalid.getErrorCode());
    }

    @Test
    void aTargetMovedAwayFromAMissingNewParentCanBeOmitted() {
        String member = member("moving@example.test");
        OrganizationsService.OrganizationPaths paths = service.organizationPaths(OWNER);
        OrganizationalUnit lateParent = service.createOrganizationalUnit(OWNER, root, "Late", null);
        service.moveAccount(OWNER, member, root, lateParent.getId());
        OrganizationAccount selected = service.describeAccount(OWNER, member);
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                service.moveAccount(OWNER, member, lateParent.getId(), root);
                units.deleteForAccount(OWNER, lateParent.getId());
            }
            return invocation.callRealMethod();
        }).when(units).scanAllAccounts();
        assertNull(paths.accountPath(selected));
    }

    private void removeHierarchy(String member, String child, String parent) {
        service.removeAccountFromOrganization(OWNER, member);
        service.deleteOrganizationalUnit(OWNER, child);
        service.deleteOrganizationalUnit(OWNER, parent);
    }
}
