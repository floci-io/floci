package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AWS-managed policies (arn:aws:iam::aws:policy/...) are global in AWS: seeded once under the
 * default account at startup, they must still resolve for callers in any other account.
 * Regression guard for the account-scoping bug where {@code data.aws_iam_policy} / GetPolicy
 * returned NoSuchEntity for a seeded managed policy when the request ran under a non-default
 * account (e.g. an assumed-role / 12-digit access-key credential).
 */
class IamManagedPolicyAccountScopeTest {

    private static final String DEFAULT_ACCT = "000000000000";
    private static final String REQUEST_ACCT = "111111111111";

    @SuppressWarnings("unchecked")
    private static Instance<RequestContext> requestContextFor(String accountId) {
        RequestContext rc = mock(RequestContext.class);
        when(rc.getAccountId()).thenReturn(accountId);
        Instance<RequestContext> inst = mock(Instance.class);
        when(inst.get()).thenReturn(rc);
        return inst;
    }

    @Test
    void managedPolicyResolvesFromAnyAccountWhileCustomerPolicyStaysScoped() {
        // The policies store sees the current request as account 111..., default is 000...
        InMemoryStorage<String, IamPolicy> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<IamPolicy> policies =
                new AccountAwareStorageBackend<>(raw, requestContextFor(REQUEST_ACCT), DEFAULT_ACCT);

        // A customer policy stored under the default account (control for account isolation).
        String customerArn = "arn:aws:iam::" + DEFAULT_ACCT + ":policy/customer-policy";
        policies.putForAccount(DEFAULT_ACCT, customerArn,
                new IamPolicy("ANPACUSTOMER0001", "customer-policy", "/", customerArn,
                        "customer", AwsManagedPolicies.PERMISSIVE_DOCUMENT));

        IamService service = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                policies,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));

        // A managed policy resolves from any account context (served from the global catalog,
        // not the account-partitioned store) — the request runs as account 111...
        String managedArn = AwsManagedPolicies.ARN_PREFIX + "/service-role/AWSLambdaBasicExecutionRole";
        IamPolicy managed = service.getPolicy(managedArn);
        assertEquals(managedArn, managed.getArn());
        // GetPolicyVersion routes through getPolicy, so the document is reachable too.
        assertNotNull(service.getPolicyVersion(managedArn, "v1"));

        // Control: a customer (account-scoped) policy under the default account is NOT visible
        // from account 111... — only managed policies cross the account boundary.
        assertThrows(AwsException.class, () -> service.getPolicy(customerArn));
    }

    @Test
    void catalogIncludesPoliciesNeededByCommonExecutionRoles() {
        List<String> arns = AwsManagedPolicies.POLICIES.stream()
                .map(AwsManagedPolicies.ManagedPolicyDef::arn)
                .toList();
        assertTrue(arns.contains(AwsManagedPolicies.ARN_PREFIX + "/AWSXRayDaemonWriteAccess"));
        assertTrue(arns.contains(AwsManagedPolicies.ARN_PREFIX + "/AWSCloudFormationFullAccess"));
        assertTrue(arns.contains(AwsManagedPolicies.ARN_PREFIX + "/AmazonElasticFileSystemClientFullAccess"));
    }

    @Test
    void listPoliciesScopeAwsAndAllReturnManagedCatalogFromAnyAccount() {
        // Request runs as account 111..., default is 000... Managed policies are only mirrored
        // into the default account at seed time, so a Scope=AWS scan of the request account's
        // partition was empty before this fix — the same account-scoping bug GetPolicy had.
        AccountAwareStorageBackend<IamPolicy> policies = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), requestContextFor(REQUEST_ACCT), DEFAULT_ACCT);
        IamService service = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                policies,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));

        int catalogSize = AwsManagedPolicies.POLICIES.size();
        String lambdaBasicArn = AwsManagedPolicies.ARN_PREFIX + "/service-role/AWSLambdaBasicExecutionRole";

        // Scope=AWS now serves the full global catalog from a non-default account.
        List<IamPolicy> aws = service.listPolicies("AWS", null);
        assertEquals(catalogSize, aws.size());
        assertTrue(aws.stream().anyMatch(p -> lambdaBasicArn.equals(p.getArn())));

        // The data.aws_iam_policy name-lookup path (ListPolicies + name filter) now resolves
        // managed policies for a caller outside the default account.
        assertTrue(aws.stream().anyMatch(p -> "AWSLambdaBasicExecutionRole".equals(p.getPolicyName())));

        // Scope=All and the default (blank) scope also include the managed catalog.
        assertEquals(catalogSize, service.listPolicies("All", null).size());
        assertEquals(catalogSize, service.listPolicies(null, null).size());
    }

    @Test
    void listPoliciesScopesLocalToCallerAndDoesNotDuplicateMirroredManaged() {
        InMemoryStorage<String, IamPolicy> raw = new InMemoryStorage<>();

        // A customer policy owned by the request account 111...
        AccountAwareStorageBackend<IamPolicy> reqPolicies =
                new AccountAwareStorageBackend<>(raw, requestContextFor(REQUEST_ACCT), DEFAULT_ACCT);
        String customerArn = "arn:aws:iam::" + REQUEST_ACCT + ":policy/app-policy";
        reqPolicies.putForAccount(REQUEST_ACCT, customerArn,
                new IamPolicy("ANPAAPP000000001", "app-policy", "/", customerArn,
                        "app", AwsManagedPolicies.PERMISSIVE_DOCUMENT));

        // Simulate the seed-time mirror: a managed policy copied into the default account store.
        String mirroredManagedArn = AwsManagedPolicies.ARN_PREFIX + "/AdministratorAccess";
        reqPolicies.putForAccount(DEFAULT_ACCT, mirroredManagedArn,
                new IamPolicy("ANPAADMIN0000001", "AdministratorAccess", "/", mirroredManagedArn,
                        "admin", AwsManagedPolicies.PERMISSIVE_DOCUMENT));

        IamService reqService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                reqPolicies,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));

        // Scope=Local returns only the caller's customer policy — never AWS-managed policies.
        List<IamPolicy> local = reqService.listPolicies("Local", null);
        assertEquals(1, local.size());
        assertEquals(customerArn, local.get(0).getArn());

        // From the default account, the mirrored managed policy must appear once, not twice
        // (once from the account store and again from the catalog).
        AccountAwareStorageBackend<IamPolicy> defPolicies =
                new AccountAwareStorageBackend<>(raw, requestContextFor(DEFAULT_ACCT), DEFAULT_ACCT);
        IamService defService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                defPolicies,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));
        List<IamPolicy> defAll = defService.listPolicies("All", null);
        assertEquals(AwsManagedPolicies.POLICIES.size(), defAll.size());
        assertEquals(1L, defAll.stream().filter(p -> mirroredManagedArn.equals(p.getArn())).count());
    }

    @Test
    void attachedManagedPolicyResolvesForUserInNonDefaultAccount() {
        // Request runs as account 111...; managed policies are only mirrored into the default
        // account at seed time. A managed policy attached to a user owned by 111... was silently
        // dropped by the attached-policy read paths, which resolved straight from the
        // account-partitioned store instead of the global catalog.
        Instance<RequestContext> ctx = requestContextFor(REQUEST_ACCT);
        InMemoryStorage<String, IamUser> rawUsers = new InMemoryStorage<>();
        InMemoryStorage<String, IamPolicy> rawPolicies = new InMemoryStorage<>();
        AccountAwareStorageBackend<IamUser> users = new AccountAwareStorageBackend<>(rawUsers, ctx, DEFAULT_ACCT);
        AccountAwareStorageBackend<IamPolicy> policies =
                new AccountAwareStorageBackend<>(rawPolicies, ctx, DEFAULT_ACCT);

        String managedArn = AwsManagedPolicies.ARN_PREFIX + "/service-role/AWSLambdaBasicExecutionRole";
        // A customer policy attached to the same user — must stay account-scoped (control).
        String customerArn = "arn:aws:iam::" + REQUEST_ACCT + ":policy/app-policy";
        policies.putForAccount(REQUEST_ACCT, customerArn,
                new IamPolicy("ANPAAPP000000001", "app-policy", "/", customerArn,
                        "app", AwsManagedPolicies.PERMISSIVE_DOCUMENT));

        IamUser user = new IamUser("AIDAUSER00000001", "app-user", "/",
                "arn:aws:iam::" + REQUEST_ACCT + ":user/app-user");
        user.getAttachedPolicyArns().add(managedArn);
        user.getAttachedPolicyArns().add(customerArn);
        users.putForAccount(REQUEST_ACCT, "app-user", user);

        IamService service = new IamService(
                users, new InMemoryStorage<>(), new InMemoryStorage<>(),
                policies,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));

        // ListAttachedUserPolicies returns both the managed (catalog) and customer (scoped) policies.
        List<IamPolicy> attached = service.listAttachedUserPolicies("app-user", null);
        assertEquals(2, attached.size());
        assertTrue(attached.stream().anyMatch(p -> managedArn.equals(p.getArn())));
        assertTrue(attached.stream().anyMatch(p -> customerArn.equals(p.getArn())));

        // SimulatePrincipalPolicy (resolvePrincipalContext -> collectUserPolicies) now picks up
        // the attached managed policy's document for a non-default-account principal.
        CallerContext caller = service.resolvePrincipalContext(
                "arn:aws:iam::" + REQUEST_ACCT + ":user/app-user");
        assertTrue(caller.identityPolicies().contains(AwsManagedPolicies.PERMISSIVE_DOCUMENT));

        // Control: the customer policy is genuinely account-scoped — invisible from another account.
        Instance<RequestContext> otherCtx = requestContextFor("222222222222");
        IamService otherService = new IamService(
                new AccountAwareStorageBackend<>(rawUsers, otherCtx, DEFAULT_ACCT),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawPolicies, otherCtx, DEFAULT_ACCT),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));
        // The user does not exist under account 222..., so the customer policy never leaks; assert
        // the catalog policy still resolves directly regardless of account.
        assertFalse(otherService.listPolicies("Local", null).stream()
                .anyMatch(p -> customerArn.equals(p.getArn())));
        assertNotNull(otherService.getPolicy(managedArn));
    }

    @Test
    void attachedManagedPolicyResolvesForGroupInNonDefaultAccount() {
        // Symmetric with the user/role cases: a managed policy attached to a group owned by a
        // non-default account must resolve from the global catalog (the resolvePolicy fix applied
        // to listAttachedGroupPolicies), while a customer policy attached to the same group stays
        // account-scoped.
        Instance<RequestContext> ctx = requestContextFor(REQUEST_ACCT);
        InMemoryStorage<String, IamGroup> rawGroups = new InMemoryStorage<>();
        InMemoryStorage<String, IamPolicy> rawPolicies = new InMemoryStorage<>();
        AccountAwareStorageBackend<IamGroup> groups = new AccountAwareStorageBackend<>(rawGroups, ctx, DEFAULT_ACCT);
        AccountAwareStorageBackend<IamPolicy> policies =
                new AccountAwareStorageBackend<>(rawPolicies, ctx, DEFAULT_ACCT);

        String managedArn = AwsManagedPolicies.ARN_PREFIX + "/service-role/AWSLambdaBasicExecutionRole";
        String customerArn = "arn:aws:iam::" + REQUEST_ACCT + ":policy/group-policy";
        policies.putForAccount(REQUEST_ACCT, customerArn,
                new IamPolicy("ANPAGRP000000001", "group-policy", "/", customerArn,
                        "grp", AwsManagedPolicies.PERMISSIVE_DOCUMENT));

        IamGroup group = new IamGroup("AGPAGROUP0000001", "app-group", "/",
                "arn:aws:iam::" + REQUEST_ACCT + ":group/app-group");
        group.getAttachedPolicyArns().add(managedArn);
        group.getAttachedPolicyArns().add(customerArn);
        groups.putForAccount(REQUEST_ACCT, "app-group", group);

        IamService service = new IamService(
                new InMemoryStorage<>(), groups, new InMemoryStorage<>(),
                policies,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));

        // ListAttachedGroupPolicies returns both the managed (catalog) and customer (scoped) policies.
        List<IamPolicy> attached = service.listAttachedGroupPolicies("app-group", null);
        assertEquals(2, attached.size());
        assertTrue(attached.stream().anyMatch(p -> managedArn.equals(p.getArn())));
        assertTrue(attached.stream().anyMatch(p -> customerArn.equals(p.getArn())));
    }

    @Test
    void attachedManagedPolicyResolvesForRoleInNonDefaultAccountIncludingBoundary() {
        Instance<RequestContext> ctx = requestContextFor(REQUEST_ACCT);
        InMemoryStorage<String, IamRole> rawRoles = new InMemoryStorage<>();
        AccountAwareStorageBackend<IamRole> roles = new AccountAwareStorageBackend<>(rawRoles, ctx, DEFAULT_ACCT);
        AccountAwareStorageBackend<IamPolicy> policies =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), ctx, DEFAULT_ACCT);

        String managedArn = AwsManagedPolicies.ARN_PREFIX + "/service-role/AWSLambdaBasicExecutionRole";
        String boundaryArn = AwsManagedPolicies.ARN_PREFIX + "/PowerUserAccess";

        IamRole role = new IamRole("AROLE00000000001", "task-role", "/",
                "arn:aws:iam::" + REQUEST_ACCT + ":role/task-role", "{}");
        role.getAttachedPolicyArns().add(managedArn);
        role.setPermissionsBoundaryArn(boundaryArn);
        roles.putForAccount(REQUEST_ACCT, "task-role", role);

        IamService service = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), roles,
                policies,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", DEFAULT_ACCT));

        // ListAttachedRolePolicies resolves the managed policy from the catalog for account 111...
        List<IamPolicy> attached = service.listAttachedRolePolicies("task-role", null);
        assertEquals(1, attached.size());
        assertEquals(managedArn, attached.get(0).getArn());

        // resolvePrincipalContext (collectRolePolicies + resolveRoleBoundaryDocument) resolves both
        // the attached managed policy and the managed permissions boundary for a non-default account.
        CallerContext caller = service.resolvePrincipalContext(
                "arn:aws:iam::" + REQUEST_ACCT + ":role/task-role");
        assertTrue(caller.identityPolicies().contains(AwsManagedPolicies.PERMISSIVE_DOCUMENT));
        assertEquals(AwsManagedPolicies.PERMISSIVE_DOCUMENT, caller.boundaryPolicyDocument());
    }
}
