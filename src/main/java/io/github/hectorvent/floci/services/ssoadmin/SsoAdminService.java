package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssoadmin.model.Assignment;
import io.github.hectorvent.floci.services.ssoadmin.model.AssignmentOperation;
import io.github.hectorvent.floci.services.ssoadmin.model.PermissionSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class SsoAdminService implements Resettable {
    private static final String INSTANCE_ARN = "arn:aws:sso:::instance/ssoins-7223b02a5d9f7c8e";
    private static final String IDENTITY_STORE_ID = "d-9067f2a3c1";
    private static final Pattern PERMISSION_SET_NAME = Pattern.compile("[\\w+=,.@-]+");
    private static final Pattern PERMISSION_SET_ARN = Pattern.compile("arn:aws(?:-[a-z]{1,5}){0,3}:sso:::permissionSet/(?:sso)?ins-[a-zA-Z0-9-.]{16}/ps-[a-zA-Z0-9-./]{16}");
    private static final Pattern PRINCIPAL_ID = Pattern.compile("([0-9a-f]{10}-|)[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}");
    private static final Pattern MANAGED_POLICY_ARN = Pattern.compile("arn:aws:iam::aws:policy/.+");
    private static final int PERMISSION_SET_QUOTA = 3500;
    private static final int MANAGED_POLICY_QUOTA = 25;
    private static final int MAX_INLINE_POLICY_BYTES = 32_768;
    private static final int MAX_INLINE_NON_WHITESPACE = 10_240;
    private static final Set<String> PRINCIPAL_TYPES = Set.of("USER", "GROUP");

    private final StorageBackend<String, PermissionSet> permissionSets;
    private final StorageBackend<String, Assignment> assignments;
    private final StorageBackend<String, AssignmentOperation> assignmentOperations;

    @Inject
    public SsoAdminService(StorageFactory storageFactory) {
        this(
                storageFactory.create("ssoadmin", "ssoadmin-permission-sets.json", new TypeReference<Map<String, PermissionSet>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-assignments.json", new TypeReference<Map<String, Assignment>>() {}),
                storageFactory.create("ssoadmin", "ssoadmin-assignment-operations.json", new TypeReference<Map<String, AssignmentOperation>>() {}));
    }

    SsoAdminService(StorageBackend<String, PermissionSet> permissionSets,
                    StorageBackend<String, Assignment> assignments,
                    StorageBackend<String, AssignmentOperation> assignmentOperations) {
        this.permissionSets = permissionSets;
        this.assignments = assignments;
        this.assignmentOperations = assignmentOperations;
    }

    public String getInstanceArn() { return INSTANCE_ARN; }
    public String getIdentityStoreId() { return IDENTITY_STORE_ID; }

    public PaginatedResult<PermissionSet> listPermissionSets(JsonNode request) {
        requireInstance(required(request, "InstanceArn"));
        return Pagination.paginate(permissionSets.scan(key -> true), PermissionSet::arn,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public List<PermissionSet> listPermissionSets(String instanceArn) {
        requireInstance(instanceArn);
        return permissionSets.scan(key -> true).stream().sorted(Comparator.comparing(PermissionSet::name)).toList();
    }

    public PaginatedResult<Map.Entry<String, String>> listManagedPolicies(JsonNode request) {
        PermissionSet permissionSet = getPermissionSet(
                required(request, "InstanceArn"), required(request, "PermissionSetArn"));
        List<Map.Entry<String, String>> policies = permissionSet.managedPolicies().entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        return Pagination.paginate(policies, Map.Entry::getKey,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public synchronized PermissionSet createPermissionSet(JsonNode request) {
        requireInstance(required(request, "InstanceArn"));
        String name = validateName(required(request, "Name"));
        String description = optionalDescription(request);
        String sessionDuration = validateSession(valueOr(request, "SessionDuration", "PT1H"));
        if (permissionSets.scan(key -> true).stream().anyMatch(p -> name.equals(p.name()))) {
            throw conflict("Permission set already exists: " + name);
        }
        if (permissionSets.scan(key -> true).size() >= PERMISSION_SET_QUOTA) {
            throw quota("The IAM Identity Center permission set quota has been exceeded.");
        }
        String arn = "arn:aws:sso:::permissionSet/ssoins-7223b02a5d9f7c8e/ps-" + shortId();
        PermissionSet permissionSet = new PermissionSet(arn, name, description, sessionDuration,
                new LinkedHashMap<>(), null);
        permissionSets.put(arn, permissionSet);
        return permissionSet;
    }

    public PermissionSet getPermissionSet(String instanceArn, String arn) {
        requireInstance(instanceArn);
        validatePermissionSetArn(arn);
        return permissionSets.get(arn).orElseThrow(() -> notFound("Permission set not found: " + arn));
    }

    public synchronized PermissionSet updatePermissionSet(JsonNode request) {
        PermissionSet current = getPermissionSet(required(request, "InstanceArn"), required(request, "PermissionSetArn"));
        String description = request.has("Description") ? optionalDescription(request) : current.description();
        String session = request.has("SessionDuration")
                ? validateSession(required(request, "SessionDuration")) : current.sessionDuration();
        PermissionSet updated = new PermissionSet(current.arn(), current.name(), description, session,
                new LinkedHashMap<>(current.managedPolicies()), current.inlinePolicy());
        permissionSets.put(updated.arn(), updated);
        return updated;
    }

    public synchronized void attachPolicy(String instanceArn, String arn, String policyArn) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        validateManagedPolicyArn(policyArn);
        if (current.managedPolicies().containsKey(policyArn)) {
            throw conflict("The managed policy is already attached to the permission set.");
        }
        if (current.managedPolicies().size() >= MANAGED_POLICY_QUOTA) {
            throw quota("A permission set can have at most 25 managed policies.");
        }
        current.managedPolicies().put(policyArn, policyArn.substring(policyArn.lastIndexOf('/') + 1));
        permissionSets.put(arn, current);
    }

    public synchronized void detachPolicy(String instanceArn, String arn, String policyArn) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        validateManagedPolicyArn(policyArn);
        if (current.managedPolicies().remove(policyArn) == null) {
            throw conflict("The managed policy is not attached to the permission set.");
        }
        permissionSets.put(arn, current);
    }

    public synchronized void putInlinePolicy(String instanceArn, String arn, String policy) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        validateInlinePolicy(policy);
        permissionSets.put(arn, new PermissionSet(current.arn(), current.name(), current.description(),
                current.sessionDuration(), new LinkedHashMap<>(current.managedPolicies()), policy));
    }

    public synchronized void deleteInlinePolicy(String instanceArn, String arn) {
        PermissionSet current = getPermissionSet(instanceArn, arn);
        permissionSets.put(arn, new PermissionSet(current.arn(), current.name(), current.description(),
                current.sessionDuration(), new LinkedHashMap<>(current.managedPolicies()), null));
    }

    public PaginatedResult<Assignment> listAssignments(JsonNode request) {
        String instanceArn = required(request, "InstanceArn");
        requireInstance(instanceArn);
        String accountId = validateAccountId(required(request, "AccountId"));
        String permissionSetArn = required(request, "PermissionSetArn");
        getPermissionSet(instanceArn, permissionSetArn);
        List<Assignment> matching = assignments.scan(key -> true).stream()
                .filter(a -> accountId.equals(a.accountId()) && permissionSetArn.equals(a.permissionSetArn()))
                .toList();
        return Pagination.paginate(matching, Assignment::principalId,
                optionalMaxResults(request), text(request, "NextToken"), 50, 100, "ValidationException");
    }

    public List<Assignment> listAssignments(String instanceArn, String accountId, String permissionSetArn) {
        requireInstance(instanceArn);
        validateAccountId(accountId);
        getPermissionSet(instanceArn, permissionSetArn);
        return assignments.scan(key -> true).stream()
                .filter(a -> accountId.equals(a.accountId()) && permissionSetArn.equals(a.permissionSetArn()))
                .sorted(Comparator.comparing(Assignment::principalId)).toList();
    }

    public synchronized AssignmentOperation createAssignment(JsonNode request) {
        requireInstance(required(request, "InstanceArn"));
        String account = validateAccountId(required(request, "TargetId"));
        if (!"AWS_ACCOUNT".equals(required(request, "TargetType"))) {
            throw validation("TargetType must be AWS_ACCOUNT.");
        }
        String permission = required(request, "PermissionSetArn");
        getPermissionSet(INSTANCE_ARN, permission);
        String principal = validatePrincipalId(required(request, "PrincipalId"));
        String principalType = required(request, "PrincipalType");
        if (!PRINCIPAL_TYPES.contains(principalType)) {
            throw validation("PrincipalType must be USER or GROUP.");
        }
        String key = account + "::" + permission + "::" + principal;
        if (assignments.get(key).isPresent()) {
            throw conflict("The account assignment already exists.");
        }
        Assignment assignment = new Assignment(account, permission, principal, principalType);
        assignments.put(key, assignment);
        String requestId = UUID.randomUUID().toString();
        AssignmentOperation operation = new AssignmentOperation(requestId, "SUCCEEDED", account, permission,
                principal, principalType, null);
        assignmentOperations.put(requestId, operation);
        return operation;
    }

    public AssignmentOperation getAssignmentOperation(String instanceArn, String requestId) {
        requireInstance(instanceArn);
        if (requestId == null || !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw validation("AccountAssignmentCreationRequestId must be a UUID.");
        }
        return assignmentOperations.get(requestId).orElseThrow(() -> notFound("Assignment operation not found: " + requestId));
    }

    static String required(JsonNode request, String field) {
        String value = text(request, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " must be a non-empty string.");
        }
        return value;
    }

    static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static Integer optionalMaxResults(JsonNode request) {
        JsonNode node = request == null ? null : request.get("MaxResults");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw validation("MaxResults must be an integer.");
        }
        return node.intValue();
    }

    private static String validateName(String name) {
        if (name.length() > 32 || !PERMISSION_SET_NAME.matcher(name).matches()) {
            throw validation("Name must be 1-32 characters and match [\\w+=,.@-]+.");
        }
        return name;
    }

    private static String optionalDescription(JsonNode request) {
        if (!request.has("Description") || request.get("Description").isNull()) {
            return null;
        }
        String description = text(request, "Description");
        if (description == null || description.length() < 1 || description.length() > 700) {
            throw validation("Description must be between 1 and 700 characters.");
        }
        return description;
    }

    private static String validateSession(String value) {
        if (value == null || value.length() > 100) {
            throw validation("SessionDuration is invalid.");
        }
        try {
            Duration duration = Duration.parse(value);
            if (duration.compareTo(Duration.ofHours(1)) < 0 || duration.compareTo(Duration.ofHours(12)) > 0) {
                throw validation("SessionDuration must be between 1 and 12 hours.");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw validation("SessionDuration must use ISO-8601 duration syntax.");
        }
        return value;
    }

    private static void validatePermissionSetArn(String arn) {
        if (arn == null || !PERMISSION_SET_ARN.matcher(arn).matches()) {
            throw validation("PermissionSetArn is invalid.");
        }
    }

    private static void validateManagedPolicyArn(String arn) {
        if (arn == null || arn.length() > 2048 || !MANAGED_POLICY_ARN.matcher(arn).matches()) {
            throw validation("ManagedPolicyArn must identify an AWS managed IAM policy.");
        }
    }

    private static void validateInlinePolicy(String policy) {
        if (policy == null || policy.isEmpty() || policy.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_INLINE_POLICY_BYTES) {
            throw validation("InlinePolicy must be between 1 and 32768 bytes.");
        }
        long nonWhitespace = policy.chars().filter(ch -> !Character.isWhitespace(ch)).count();
        if (nonWhitespace > MAX_INLINE_NON_WHITESPACE) {
            throw quota("InlinePolicy exceeds the non-whitespace quota.");
        }
    }

    private static String validateAccountId(String value) {
        if (value == null || !value.matches("\\d{12}")) {
            throw validation("AWS account identifiers must contain 12 digits.");
        }
        return value;
    }

    private static String validatePrincipalId(String value) {
        if (value == null || !PRINCIPAL_ID.matcher(value).matches()) {
            throw validation("PrincipalId is invalid.");
        }
        return value;
    }

    private static String valueOr(JsonNode request, String field, String fallback) {
        String value = text(request, field);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private static AwsException notFound(String message) { return new AwsException("ResourceNotFoundException", message, 400); }
    private static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }
    private static AwsException conflict(String message) { return new AwsException("ConflictException", message, 400); }
    private static AwsException quota(String message) { return new AwsException("ServiceQuotaExceededException", message, 400); }
    private static void requireInstance(String arn) {
        if (arn == null || !arn.equals(INSTANCE_ARN)) {
            throw notFound("IAM Identity Center instance not found: " + arn);
        }
    }

    @Override
    public void clear() {
        permissionSets.clear();
        assignments.clear();
        assignmentOperations.clear();
    }

}
