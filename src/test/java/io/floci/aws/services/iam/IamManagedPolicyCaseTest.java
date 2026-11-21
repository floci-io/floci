package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iam.model.IamPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamManagedPolicyCaseTest {

    private static final String TRUST = "{\"Statement\":{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}}";

    private final IamService iam = new IamService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"), false);

    @ParameterizedTest
    @CsvSource({
        "aws, AWSXRayDaemonWriteAccess, AWSXrayDaemonWriteAccess",
        "aws, service-role/AWSLambdaBasicExecutionRole, SERVICE-ROLE/awslambdabasicexecutionrole",
        "aws-cn, AWSXRayDaemonWriteAccess, awsxraydaemonwriteaccess",
        "aws-us-gov, AWSXRayDaemonWriteAccess, AWSXRAYDAEMONWRITEACCESS"
    })
    void lookupPreservesCanonicalIdentityAndDefaultVersion(String partition, String name, String alias) {
        String prefix = AwsManagedPolicies.arnPrefix(partition) + "/";
        IamPolicy canonical = iam.getPolicy(prefix + name);
        IamPolicy observed = iam.getPolicy(prefix + alias);
        assertEquals(canonical.getArn(), observed.getArn());
        assertEquals(canonical.getPolicyId(), observed.getPolicyId());
        assertEquals(canonical.getPolicyName(), observed.getPolicyName());
        assertEquals(canonical.getDefaultDocument(),
                iam.getPolicyVersion(prefix + alias, observed.getDefaultVersionId()).getDocument());
        AwsException missing = assertThrows(AwsException.class, () -> iam.getPolicy(prefix + alias + "Missing"));
        assertEquals("NoSuchEntity", missing.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "user", "group"})
    void alternateSpellingsShareOneAttachmentAndDetach(String kind) {
        String canonical = AwsManagedPolicies.arnPrefix("aws") + "/AWSXRayDaemonWriteAccess";
        String alias = AwsManagedPolicies.arnPrefix("aws") + "/AWSXrayDaemonWriteAccess";
        iam.createRole("reader", "/", TRUST, null, 3600, null);
        iam.createUser("reader", "/");
        iam.createGroup("reader", "/");
        BiConsumer<String, String> attach = switch (kind) {
            case "role" -> iam::attachRolePolicy;
            case "user" -> iam::attachUserPolicy;
            default -> iam::attachGroupPolicy;
        };
        BiConsumer<String, String> detach = switch (kind) {
            case "role" -> iam::detachRolePolicy;
            case "user" -> iam::detachUserPolicy;
            default -> iam::detachGroupPolicy;
        };
        Supplier<List<IamPolicy>> attached = switch (kind) {
            case "role" -> () -> iam.listAttachedRolePolicies("reader", null);
            case "user" -> () -> iam.listAttachedUserPolicies("reader", null);
            default -> () -> iam.listAttachedGroupPolicies("reader", null);
        };
        attach.accept("reader", alias);
        attach.accept("reader", canonical);
        assertEquals(List.of(canonical), attached.get().stream().map(IamPolicy::getArn).toList());
        assertEquals(1, iam.getPolicy(alias).getAttachmentCount());
        assertEquals(1, iam.getPolicy(canonical).getAttachmentCount());
        IamService.PolicyEntities entities = iam.listEntitiesForPolicy(alias);
        assertEquals(1, entities.roles().size() + entities.users().size() + entities.groups().size());
        detach.accept("reader", canonical);
        assertTrue(attached.get().isEmpty());
        assertEquals(0, iam.getPolicy(alias).getAttachmentCount());
        attach.accept("reader", canonical);
        detach.accept("reader", alias);
        assertTrue(attached.get().isEmpty());
    }

    @Test
    void permissionBoundariesRetainTheCanonicalPolicyArn() {
        String canonical = AwsManagedPolicies.arnPrefix("aws") + "/AmazonS3ReadOnlyAccess";
        String alias = AwsManagedPolicies.arnPrefix("aws") + "/amazons3readonlyaccess";
        iam.createUser("bounded", "/", alias);
        iam.createRole("bounded", "/", TRUST, null, 3600, null, alias);
        assertEquals(canonical, iam.getUser("bounded").getPermissionsBoundaryArn());
        assertEquals(canonical, iam.getRole("bounded").getPermissionsBoundaryArn());
        iam.putUserPermissionsBoundary("bounded", alias);
        iam.putRolePermissionsBoundary("bounded", alias);
        assertEquals(canonical, iam.getUser("bounded").getPermissionsBoundaryArn());
        assertEquals(canonical, iam.getRole("bounded").getPermissionsBoundaryArn());
    }
    @Test
    void serviceRoleReplacementUsesCanonicalPolicyIdentitiesBeforeMutation() {
        String canonical = AwsManagedPolicies.arnPrefix("aws") + "/AmazonS3ReadOnlyAccess";
        String alias = AwsManagedPolicies.arnPrefix("aws") + "/amazons3readonlyaccess";
        String roleId = iam.createRole("replacement", "/", TRUST, null, 3600, null).getRoleId();
        iam.replaceRolePolicies("replacement", roleId, List.of(alias, canonical),
                Map.of(), alias, 3600);
        assertEquals(List.of(canonical), iam.getRole("replacement").getAttachedPolicyArns());
        assertEquals(canonical, iam.getRole("replacement").getPermissionsBoundaryArn());
        assertEquals(1, iam.getPolicy(alias).getAttachmentCount());
        AwsException missing = assertThrows(AwsException.class,
                () -> iam.replaceRolePolicies("replacement", roleId, List.of(alias + "Missing"),
                        Map.of(), null, 3600));
        assertEquals("NoSuchEntity", missing.getErrorCode());
        assertEquals(List.of(canonical), iam.getRole("replacement").getAttachedPolicyArns());
        assertEquals(canonical, iam.getRole("replacement").getPermissionsBoundaryArn());
        iam.detachRolePolicy("replacement", alias);
        assertEquals(0, iam.getPolicy(canonical).getAttachmentCount());
    }

    @ParameterizedTest
    @CsvSource({
        "create, arn:aws:iam::partner:policy/permission_boundary/registered",
        "role, arn:aws:iam::partner:policy/permission_boundary/registered",
        "user, arn:aws:iam::partner:policy/permission_boundary/registered",
        "create, arn:aws:iam::aws:policy/amazons3readonlyaccess",
        "role, arn:aws:iam::aws:policy/amazons3readonlyaccess",
        "user, arn:aws:iam::aws:policy/amazons3readonlyaccess"
    })
    void registeredBoundaryIdentityAndDocumentTakePrecedence(String operation, String boundary) {
        String cap = "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"*\",\"Resource\":\"*\"}}";
        IamService service = registeredBoundaries(Map.of(boundary, cap));
        String principal;
        if (operation.equals("create")) {
            principal = service.createRole("bounded", "/", TRUST, null, 3600, null, boundary).getArn();
            service.attachRolePolicy("bounded", AwsManagedPolicies.arnPrefix("aws") + "/AdministratorAccess");
        } else if (operation.equals("role")) {
            principal = service.createRole("bounded", "/", TRUST, null, 3600, null).getArn();
            service.attachRolePolicy("bounded", AwsManagedPolicies.arnPrefix("aws") + "/AdministratorAccess");
            service.putRolePermissionsBoundary("bounded", boundary);
        } else {
            principal = service.createUser("bounded", "/").getArn();
            service.attachUserPolicy("bounded", AwsManagedPolicies.arnPrefix("aws") + "/AdministratorAccess");
            service.putUserPermissionsBoundary("bounded", boundary);
        }
        String stored = operation.equals("user") ? service.getUser("bounded").getPermissionsBoundaryArn()
                : service.getRole("bounded").getPermissionsBoundaryArn();
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        assertEquals(IamPolicyEvaluator.Decision.DENY, evaluator.evaluate(
                service.resolvePrincipalContext(principal), null, "s3:GetObject", "arn:aws:s3:::bucket/key", null));
        assertEquals(boundary, stored);
        if (boundary.contains(":partner:")) {
            assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                    () -> service.getPolicy(boundary)).getErrorCode());
        }
    }

    private static IamService registeredBoundaries(Map<String, String> boundaries) {
        return new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), false, "floci", null, boundaries,
                Clock.systemUTC());
    }

    @Test
    void unresolvedIdentitySurvivesLookupButCannotBecomeAnUnregisteredBoundary() {
        String unknown = AwsManagedPolicies.arnPrefix("aws") + "/MissingPolicy";
        assertEquals(unknown, iam.canonicalPolicyArn(unknown));
        iam.createUser("missing", "/");
        iam.createRole("missing", "/", TRUST, null, 3600, null);
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> iam.putUserPermissionsBoundary("missing", unknown)).getErrorCode());
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> iam.putRolePermissionsBoundary("missing", unknown)).getErrorCode());
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> iam.createRole("missing-cap", "/", TRUST, null, 3600, null, unknown)).getErrorCode());
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> iam.getRole("missing-cap")).getErrorCode());
    }

    @Test
    void customerManagedPolicyLookupRetainsCaseSensitivity() {
        String canonical = iam.createPolicy("CaseSensitive", "/", null,
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}}",
                Map.of()).getArn();
        String alias = canonical.replace("CaseSensitive", "casesensitive");
        assertEquals(canonical, iam.getPolicy(canonical).getArn());
        AwsException missing = assertThrows(AwsException.class, () -> iam.getPolicy(alias));
        assertEquals("NoSuchEntity", missing.getErrorCode());
        iam.createUser("customer", "/");
        assertThrows(AwsException.class, () -> iam.attachUserPolicy("customer", alias));
        assertTrue(iam.listAttachedUserPolicies("customer", null).isEmpty());
        iam.attachUserPolicy("customer", canonical);
        assertEquals(List.of(canonical), iam.listAttachedUserPolicies("customer", null).stream()
                .map(IamPolicy::getArn).toList());
    }
}
