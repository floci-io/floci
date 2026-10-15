package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.IamPolicyCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.IamUserCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamLegacyQuotaRollbackTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path directory;
    private PersistentStorage<String, IamUser> users;
    private PersistentStorage<String, IamGroup> groups;
    private IamService iam;

    @BeforeEach
    void reload() {
        users = new PersistentStorage<>(directory.resolve("users.json"), new TypeReference<>() {});
        groups = new PersistentStorage<>(directory.resolve("groups.json"), new TypeReference<>() {});
        users.load();
        groups.load();
        iam = new IamService(users, groups, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), false);
    }

    private void saveHistoricalDocument(String kind, String original) {
        // Saved shape accepted by the pre-quota service, loaded through real JSON storage.
        if ("User".equals(kind)) {
            IamUser user = iam.getUser("first");
            user.getInlinePolicies().put("shared", original);
            users.put("first", user);
        } else {
            IamGroup group = iam.getGroup("first");
            group.getInlinePolicies().put("shared", original);
            groups.put("first", group);
        }
        reload();
        assertEquals(original, get(kind, "first", "shared"));
    }

    @ParameterizedTest
    @CsvSource({"User, 2048", "Group, 5120"})
    void preservesHistoricalOversizedDocumentAfterLaterTargetFailure(String kind, int limit) throws Exception {
        create(kind, "first");
        create(kind, "second");
        String original = policy(limit + 1);
        saveHistoricalDocument(kind, original);
        put(kind, "second", "shared", policy(500));
        put(kind, "second", "other", policy(limit - 1000));
        assertEquals(original, get(kind, "first", "shared"));
        StackResource resource = resource("AWS::IAM::Policy", "shared");
        resource.getAttributes().put("Inline" + kind + "Targets", "first\nsecond");
        JsonNode props = mapper.readTree("{\"PolicyName\":\"shared\",\"PolicyDocument\":"
                + policy(1100) + ",\"" + kind + "s\":[\"first\",\"second\"]}");
        AwsException error = assertThrows(AwsException.class,
                () -> new IamPolicyCfnProvisioner(iam).provision(resource, props, context("shared")));
        assertEquals("LimitExceeded", error.getErrorCode());
        assertEquals(original, get(kind, "first", "shared"));
        assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        reload();
        assertEquals(original, get(kind, "first", "shared"));
        assertEquals("LimitExceeded", assertThrows(AwsException.class,
                () -> put(kind, "first", "other", policy(200))).getErrorCode());
    }

    @ParameterizedTest
    @CsvSource({"User, 2048", "Group, 5120"})
    void failedRenameRestoresSavedOversizedDocumentAndAllowsRetry(String kind, int limit) throws Exception {
        create(kind, "first");
        create(kind, "second");
        String original = policy(limit + 1);
        saveHistoricalDocument(kind, original);
        put(kind, "second", "other", policy(200));
        StackResource resource = resource("AWS::IAM::Policy", "shared");
        resource.getAttributes().put("Inline" + kind + "Targets", "first");
        JsonNode props = mapper.readTree("{\"PolicyName\":\"new\",\"PolicyDocument\":"
                + policy(limit) + ",\"" + kind + "s\":[\"first\",\"second\"]}");
        AwsException error = assertThrows(AwsException.class,
                () -> new IamPolicyCfnProvisioner(iam).provision(resource, props, context("shared")));
        assertEquals("LimitExceeded", error.getErrorCode());
        assertEquals(original, get(kind, "first", "shared"));
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> get(kind, "first", "new")).getErrorCode());
        assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        reload();
        assertEquals(original, get(kind, "first", "shared"));
        if ("User".equals(kind)) {
            iam.deleteUserPolicy("second", "other");
        } else {
            iam.deleteGroupPolicy("second", "other");
        }
        new IamPolicyCfnProvisioner(iam).provision(resource, props, context("shared"));
        reload();
        assertEquals(policy(limit), get(kind, "first", "new"));
        assertEquals(policy(limit), get(kind, "second", "new"));
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> get(kind, "first", "shared")).getErrorCode());
    }

    @Test
    void preservesHistoricalUserPolicyAfterRejectedReplacement() throws Exception {
        create("User", "first");
        String original = policy(2049);
        saveHistoricalDocument("User", original);
        assertEquals(original, get("User", "first", "shared"));
        StackResource resource = resource("AWS::IAM::User", "first");
        resource.getAttributes().put("__FlociInlinePolicyNames", "shared");
        resource.getAttributes().put("__FlociUserId", iam.getUser("first").getUserId());
        JsonNode props = mapper.readTree("{\"UserName\":\"first\",\"Policies\":[{\"PolicyName\":\"shared\",\"PolicyDocument\":"
                + policy(2050) + "}]}");
        AwsException error = assertThrows(AwsException.class,
                () -> new IamUserCfnProvisioner(iam)
                        .provision(resource, props, context("first")));
        assertEquals("LimitExceeded", error.getErrorCode());
        assertEquals(original, iam.getUser("first").getInlinePolicies().get("shared"));
        assertNull(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        reload();
        assertEquals(Map.of("shared", original), iam.getUser("first").getInlinePolicies());
        assertEquals("LimitExceeded", assertThrows(AwsException.class,
                () -> iam.putUserPolicy("first", "shared", policy(2050))).getErrorCode());
    }

    private StackResource resource(String type, String id) {
        StackResource r = new StackResource();
        r.setLogicalId("Resource");
        r.setResourceType(type);
        r.setPhysicalId(id);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ProvisionContext context(String previous) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> ((JsonNode) inv.getArgument(0)).asText());
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        return new ProvisionContext(engine, "us-east-1", "000000000000", "stack", previous);
    }

    private void create(String kind, String name) {
        switch (kind) {
            case "User" -> iam.createUser(name, "/");
            case "Group" -> iam.createGroup(name, "/");
            case "Role" -> iam.createRole(name, "/", "{}", null, 3600, null);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private void put(String kind, String name, String policyName, String document) {
        switch (kind) {
            case "User" -> iam.putUserPolicy(name, policyName, document);
            case "Group" -> iam.putGroupPolicy(name, policyName, document);
            case "Role" -> iam.putRolePolicy(name, policyName, document);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private String get(String kind, String name, String policyName) {
        return switch (kind) {
            case "User" -> iam.getUserPolicy(name, policyName);
            case "Group" -> iam.getGroupPolicy(name, policyName);
            case "Role" -> iam.getRolePolicy(name, policyName);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String policy(int length) {
        String prefix = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"";
        String suffix = "\",\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        return prefix + "x".repeat(length - prefix.length() - suffix.length()) + suffix;
    }
}
