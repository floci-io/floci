package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ssoadmin.model.Assignment;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoAdminServiceTest {
    private static final String ACCOUNT_ID = "123456789012";
    private static final String PRINCIPAL_ID = "11111111-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();
    private SsoAdminService service;

    @BeforeEach
    void setUp() {
        service = new SsoAdminService(
                new InMemoryStorage<String, PermissionSet>(),
                new InMemoryStorage<String, Assignment>(),
                new InMemoryStorage<String, AssignmentOperation>());
    }

    @Test
    void managedPolicyPaginationHonorsMaxResultsAndNextToken() {
        PermissionSet permissionSet = createPermissionSet("PlatformAdmins");
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), "arn:aws:iam::aws:policy/ReadOnlyAccess");
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), "arn:aws:iam::aws:policy/SecurityAudit");
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), "arn:aws:iam::aws:policy/ViewOnlyAccess");

        ObjectNode firstRequest = managedPoliciesRequest(permissionSet.arn());
        firstRequest.put("MaxResults", 1);
        var first = service.listManagedPolicies(firstRequest);

        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());

        ObjectNode secondRequest = managedPoliciesRequest(permissionSet.arn());
        secondRequest.put("MaxResults", 1);
        secondRequest.put("NextToken", first.nextToken());
        var second = service.listManagedPolicies(secondRequest);

        assertEquals(1, second.items().size());
        assertNotNull(second.nextToken());
        assertFalse(first.items().get(0).getKey().equals(second.items().get(0).getKey()));
    }

    @Test
    void managedPolicyPaginationRejectsInvalidInputs() {
        PermissionSet permissionSet = createPermissionSet("AuditAdmins");

        ObjectNode badLimit = managedPoliciesRequest(permissionSet.arn());
        badLimit.put("MaxResults", 101);
        assertError("ValidationException", () -> service.listManagedPolicies(badLimit));

        ObjectNode badToken = managedPoliciesRequest(permissionSet.arn());
        badToken.put("NextToken", "not%base64");
        assertError("ValidationException", () -> service.listManagedPolicies(badToken));
    }

    @Test
    void duplicateManagedPolicyReturnsConflict() {
        PermissionSet permissionSet = createPermissionSet("SecurityAdmins");
        String policyArn = "arn:aws:iam::aws:policy/SecurityAudit";
        service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), policyArn);

        assertError("ConflictException",
                () -> service.attachPolicy(service.getInstanceArn(), permissionSet.arn(), policyArn));
    }

    @Test
    void assignmentValidationAndDuplicateDetectionAreModeled() {
        PermissionSet permissionSet = createPermissionSet("AssignmentAdmins");
        ObjectNode request = assignmentRequest(permissionSet.arn());
        AssignmentOperation created = service.createAssignment(request);
        assertEquals("SUCCEEDED", created.status());

        assertError("ConflictException", () -> service.createAssignment(request));

        ObjectNode invalidPrincipalType = assignmentRequest(permissionSet.arn());
        invalidPrincipalType.put("PrincipalType", "ROLE");
        invalidPrincipalType.put("PrincipalId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertError("ValidationException", () -> service.createAssignment(invalidPrincipalType));
    }

    @Test
    void clearRemovesPersistedServiceState() {
        PermissionSet permissionSet = createPermissionSet("ResetAdmins");
        AssignmentOperation operation = service.createAssignment(assignmentRequest(permissionSet.arn()));
        assertFalse(service.listPermissionSets(service.getInstanceArn()).isEmpty());
        assertNotNull(service.getAssignmentOperation(service.getInstanceArn(), operation.requestId()));

        service.clear();

        assertTrue(service.listPermissionSets(service.getInstanceArn()).isEmpty());
        assertError("ResourceNotFoundException",
                () -> service.getAssignmentOperation(service.getInstanceArn(), operation.requestId()));
    }

    private PermissionSet createPermissionSet(String name) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("Name", name);
        return service.createPermissionSet(request);
    }

    private ObjectNode managedPoliciesRequest(String permissionSetArn) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("PermissionSetArn", permissionSetArn);
        return request;
    }

    private ObjectNode assignmentRequest(String permissionSetArn) {
        ObjectNode request = mapper.createObjectNode();
        request.put("InstanceArn", service.getInstanceArn());
        request.put("TargetId", ACCOUNT_ID);
        request.put("TargetType", "AWS_ACCOUNT");
        request.put("PermissionSetArn", permissionSetArn);
        request.put("PrincipalType", "GROUP");
        request.put("PrincipalId", PRINCIPAL_ID);
        return request;
    }

    private static void assertError(String expectedCode, Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals(expectedCode, error.getErrorCode());
    }
}
