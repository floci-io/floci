package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.IamPolicyCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext;
import io.github.hectorvent.floci.services.iam.IamService.InlinePolicyChange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class IamPolicyQuotaRollbackTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver resolver = spy(new RegionResolver("us-east-1", "000000000000"));
    private final IamService iam = spy(new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(),
            resolver, false));

    @ParameterizedTest
    @CsvSource({"User, 2048, owned", "Group, 5120, owned", "Role, 10240, owned",
            "User, 2048, added", "Group, 5120, added", "Role, 10240, added",
            "User, 2048, absent", "Group, 5120, absent", "Role, 10240, absent",
            "User, 2048, failure", "Group, 5120, failure", "Role, 10240, failure"})
    void failedLaterTargetRestoresEarlierSameNameDocument(String kind, int limit, String state) throws Exception {
        create(kind, "first");
        create(kind, "second");
        String original = policy(500);
        if (!"absent".equals(state)) {
            put(kind, "first", "shared", original);
        }
        if ("failure".equals(state)) {
            failRestore(kind, original);
        }
        put(kind, "second", "shared", original);
        String sibling = policy(limit - 1000);
        put(kind, "second", "other", sibling);
        StackResource resource = new StackResource();
        resource.setLogicalId("Policy");
        resource.setResourceType("AWS::IAM::Policy");
        resource.setPhysicalId("shared");
        resource.setAttributes(new HashMap<>());
        resource.getAttributes().put("Inline" + kind + "Targets",
                "added".equals(state) ? "second" : "first\nsecond");
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> ((JsonNode) inv.getArgument(0)).asText());
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "000000000000", "stack", "shared");
        JsonNode properties = mapper.readTree("{\"PolicyName\":\"shared\",\"PolicyDocument\":"
                + policy(1100) + ",\"" + kind + "s\":[\"first\",\"second\"]}");

        AwsException error = assertThrows(AwsException.class,
                () -> new IamPolicyCfnProvisioner(iam).provision(resource, properties, context));

        assertEquals("LimitExceeded", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        if ("absent".equals(state)) {
            assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                    () -> get(kind, "first", "shared")).getErrorCode());
        } else {
            assertEquals("failure".equals(state) ? policy(1100) : original, get(kind, "first", "shared"));
        }
        assertEquals(original, get(kind, "second", "shared"));
        assertEquals(sibling, get(kind, "second", "other"));
        if ("failure".equals(state)) {
            assertNull(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
            assertNotNull(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
            assertEquals(1, error.getSuppressed().length);
        } else {
            assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        }
    }

    @ParameterizedTest
    @CsvSource({"User", "Group", "Role"})
    void failureDeletingOldNameRestoresDeletedAndOverwrittenDocuments(String kind) throws Exception {
        create(kind, "first");
        create(kind, "second");
        String original = policy(500);
        put(kind, "first", "shared", original);
        put(kind, "second", "shared", original);
        String collision = policy(200);
        put(kind, "first", "new", collision);
        AwsException deletionFailure = new AwsException("AccessDenied", "injected delete failure", 403);
        switch (kind) {
            case "User" -> doThrow(deletionFailure).when(iam).deleteUserPolicy("second", "shared");
            case "Group" -> doThrow(deletionFailure).when(iam).deleteGroupPolicy("second", "shared");
            case "Role" -> doThrow(deletionFailure).when(iam).deleteRolePolicy("second", "shared");
            default -> throw new IllegalArgumentException(kind);
        }
        StackResource resource = new StackResource();
        resource.setLogicalId("Policy");
        resource.setResourceType("AWS::IAM::Policy");
        resource.setPhysicalId("shared");
        resource.setAttributes(new HashMap<>());
        resource.getAttributes().put("Inline" + kind + "Targets", "first\nsecond");
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> ((JsonNode) inv.getArgument(0)).asText());
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "000000000000", "stack", "shared");
        JsonNode properties = mapper.readTree("{\"PolicyName\":\"new\",\"PolicyDocument\":"
                + policy(800) + ",\"" + kind + "s\":[\"first\",\"second\"]}");

        assertEquals(deletionFailure, assertThrows(AwsException.class,
                () -> new IamPolicyCfnProvisioner(iam).provision(resource, properties, context)));
        assertEquals(original, get(kind, "first", "shared"));
        assertEquals(original, get(kind, "second", "shared"));
        assertEquals(collision, get(kind, "first", "new"));
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> get(kind, "second", "new")).getErrorCode());
        assertEquals("shared", resource.getPhysicalId());
        assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
    }

    @ParameterizedTest
    @CsvSource({"User, 2048, false", "Group, 5120, false", "Role, 10240, false",
            "User, 2048, true", "Group, 5120, true", "Role, 10240, true"})
    void compensationCannotConsumeCapacityAdmittedToAnotherPolicy(String kind, int limit, boolean deletion) {
        create(kind, "first");
        put(kind, "first", "shared", policy(limit - 548));
        InlinePolicyChange change = deletion ? remove(kind, "first", "shared")
                : update(kind, "first", "shared", policy(500));
        String sibling = policy(limit - 648);
        put(kind, "first", "other", sibling);
        AwsException error = assertThrows(AwsException.class, () -> change.rollback().run());
        assertEquals("ConcurrentModification", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals(sibling, get(kind, "first", "other"));
        if (deletion) {
            assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                    () -> get(kind, "first", "shared")).getErrorCode());
        } else {
            assertEquals(policy(500), get(kind, "first", "shared"));
        }
    }

    @ParameterizedTest
    @CsvSource({"User", "Group", "Role"})
    void uncontestedCompensationPreservesAnUnrelatedPolicyWhenQuotaAllows(String kind) {
        create(kind, "first");
        put(kind, "first", "shared", policy(500));
        InlinePolicyChange change = update(kind, "first", "shared", policy(200));
        put(kind, "first", "other", policy(300));
        change.rollback().run();
        assertEquals(policy(500), get(kind, "first", "shared"));
        assertEquals(policy(300), get(kind, "first", "other"));
    }

    @ParameterizedTest
    @CsvSource({"User, false", "Group, false", "Role, false",
            "User, true", "Group, true", "Role, true"})
    void refusedCompensationDoesNotQueueDeletionOfAnotherWritersPolicy(String kind, boolean replace) throws Exception {
        create(kind, "first");
        create(kind, "second");
        put(kind, "second", "shared", policy(500));
        AwsException failure = new AwsException("AccessDenied", "later target refused", 403);
        Runnable concurrentWrite = () -> {
            if (replace) {
                remove(kind, "first", "shared");
                switch (kind) {
                    case "User" -> iam.deleteUser("first");
                    case "Group" -> iam.deleteGroup("first");
                    case "Role" -> iam.deleteRole("first");
                    default -> throw new IllegalArgumentException(kind);
                }
                create(kind, "first");
            }
            put(kind, "first", "shared", policy(300));
        };
        switch (kind) {
            case "User" -> doAnswer(call -> { concurrentWrite.run(); throw failure; })
                    .when(iam).putUserPolicy("second", "shared", policy(200));
            case "Group" -> doAnswer(call -> { concurrentWrite.run(); throw failure; })
                    .when(iam).putGroupPolicy("second", "shared", policy(200));
            case "Role" -> doAnswer(call -> { concurrentWrite.run(); throw failure; })
                    .when(iam).putRolePolicy("second", "shared", policy(200));
            default -> throw new IllegalArgumentException(kind);
        }
        StackResource resource = new StackResource();
        resource.setLogicalId("Policy");
        resource.setResourceType("AWS::IAM::Policy");
        resource.setPhysicalId("shared");
        resource.setAttributes(new HashMap<>());
        resource.getAttributes().put("Inline" + kind + "Targets", "second");
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> ((JsonNode) call.getArgument(0)).asText());
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(call -> call.getArgument(0).toString());
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "000000000000", "stack", "shared");
        JsonNode props = mapper.readTree("{\"PolicyName\":\"shared\",\"PolicyDocument\":"
                + policy(200) + ",\"" + kind + "s\":[\"first\",\"second\"]}");
        IamPolicyCfnProvisioner provisioner = new IamPolicyCfnProvisioner(iam);
        assertEquals(failure, assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, context)));
        assertEquals("ConcurrentModification", ((AwsException) failure.getSuppressed()[0]).getErrorCode());
        assertNotNull(resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertNull(resource.getAttributes().get("__FlociInlineCleanupPolicyName"));
        provisioner.delete(resource, "us-east-1");
        assertEquals(policy(300), get(kind, "first", "shared"));
    }

    @ParameterizedTest
    @CsvSource({"User", "Group", "Role"})
    void compensationRefusesADifferentAmbientAccount(String kind) {
        create(kind, "first");
        put(kind, "first", "shared", policy(500));
        InlinePolicyChange change = update(kind, "first", "shared", policy(200));
        when(resolver.getAccountId()).thenReturn("111122223333");
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> change.rollback().run()).getErrorCode());
        assertEquals(policy(200), get(kind, "first", "shared"));
        when(resolver.getAccountId()).thenReturn("000000000000");
        change.rollback().run();
        assertEquals(policy(500), get(kind, "first", "shared"));
    }

    @ParameterizedTest
    @CsvSource({"User, false", "Group, false", "Role, false",
            "User, true", "Group, true", "Role, true"})
    void compensationRefusesAnotherWritersRoundTrip(String kind, boolean deletion) {
        create(kind, "first");
        put(kind, "first", "shared", policy(500));
        InlinePolicyChange change = deletion ? remove(kind, "first", "shared")
                : update(kind, "first", "shared", policy(200));
        put(kind, "first", "shared", policy(300));
        if (deletion) {
            delete(kind, "first", "shared");
        } else {
            put(kind, "first", "shared", policy(200));
        }
        AwsException error = assertThrows(AwsException.class, () -> change.rollback().run());
        assertEquals("ConcurrentModification", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        if (deletion) {
            assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                    () -> get(kind, "first", "shared")).getErrorCode());
        } else {
            assertEquals(policy(200), get(kind, "first", "shared"));
        }
    }

    @ParameterizedTest
    @CsvSource({"User", "Group", "Role"})
    void equalDocumentWriteStillTakesOwnership(String kind) {
        create(kind, "first");
        put(kind, "first", "shared", policy(500));
        InlinePolicyChange change = update(kind, "first", "shared", policy(200));
        put(kind, "first", "shared", policy(200));
        AwsException error = assertThrows(AwsException.class, () -> change.rollback().run());
        assertEquals("ConcurrentModification", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("Cannot restore inline policy shared on first"
                + " because its rollback ownership or state changed after the update.", error.getMessage());
        assertEquals(policy(200), get(kind, "first", "shared"));
    }

    @ParameterizedTest
    @CsvSource({"User", "Group", "Role"})
    void nestedReverseCompensationRestoresItsPredecessorOwnership(String kind) {
        create(kind, "first");
        put(kind, "first", "shared", policy(500));
        InlinePolicyChange removed = remove(kind, "first", "shared");
        InlinePolicyChange written = update(kind, "first", "shared", policy(200));
        put(kind, "first", "unrelated", policy(200));
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> removed.rollback().run()).getErrorCode());
        written.rollback().run();
        removed.rollback().run();
        assertEquals(policy(500), get(kind, "first", "shared"));
        assertEquals(policy(200), get(kind, "first", "unrelated"));
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> written.rollback().run()).getErrorCode());
    }

    @ParameterizedTest
    @CsvSource({"User", "Group", "Role"})
    void equalDocumentCompensationCannotRunTwice(String kind) {
        create(kind, "first");
        put(kind, "first", "shared", policy(200));
        InlinePolicyChange change = update(kind, "first", "shared", policy(200));
        change.rollback().run();
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> change.rollback().run()).getErrorCode());
        assertEquals(policy(200), get(kind, "first", "shared"));
    }

    @ParameterizedTest
    @CsvSource({"User, missing, false", "Group, missing, false", "Role, missing, false",
            "User, missing, true", "Group, missing, true", "Role, missing, true",
            "Role, bad/name, false", "Role, bad/name, true"})
    void capturedWritesPreserveOrdinaryPrincipalErrors(String kind, String principal, boolean deletion) {
        AwsException ordinary = assertThrows(AwsException.class, () -> {
            if (deletion) {
                delete(kind, principal, "shared");
            } else {
                put(kind, principal, "shared", policy(200));
            }
        });
        AwsException captured = assertThrows(AwsException.class, () -> {
            if (deletion) {
                remove(kind, principal, "shared");
            } else {
                update(kind, principal, "shared", policy(200));
            }
        });
        assertEquals(ordinary.getErrorCode(), captured.getErrorCode());
        assertEquals(ordinary.getHttpStatus(), captured.getHttpStatus());
        assertEquals(ordinary.getMessage(), captured.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"User, 2048", "Group, 5120", "Role, 10240"})
    void rejectedWriteAndOtherPrincipalDoNotInvalidateCompensation(String kind, int limit) {
        create(kind, "first");
        create(kind, "second");
        put(kind, "first", "shared", policy(500));
        InlinePolicyChange change = update(kind, "first", "shared", policy(200));
        assertEquals("LimitExceeded", assertThrows(AwsException.class,
                () -> put(kind, "first", "shared", policy(limit + 1))).getErrorCode());
        put(kind, "second", "shared", policy(200));
        change.rollback().run();
        assertEquals(policy(500), get(kind, "first", "shared"));
        assertEquals(policy(200), get(kind, "second", "shared"));
    }

    @ParameterizedTest
    @CsvSource({"User, 2048, false, false", "Group, 5120, false, false", "Role, 10240, false, false",
            "User, 2048, true, false", "Group, 5120, true, false", "Role, 10240, true, false",
            "User, 2048, false, true", "Group, 5120, false, true", "Role, 10240, false, true",
            "User, 2048, true, true", "Group, 5120, true, true", "Role, 10240, true, true"})
    void renameAtQuotaRetainsFinalStateAndRestoresActualCollisionOnFailure(
            String kind, int limit, boolean collision, boolean failure) throws Exception {
        create(kind, "first");
        create(kind, "second");
        create(kind, "removed");
        String original = policy(limit - (collision ? 200 : 0));
        put(kind, "first", "shared", original);
        put(kind, "removed", "shared", original);
        if (collision) {
            put(kind, "first", "new", policy(200));
        }
        AwsException injected = new AwsException("AccessDenied", "later target refused", 403);
        if (failure) {
            switch (kind) {
                case "User" -> doThrow(injected).when(iam).putUserPolicy("second", "new", policy(limit));
                case "Group" -> doThrow(injected).when(iam).putGroupPolicy("second", "new", policy(limit));
                case "Role" -> doThrow(injected).when(iam).putRolePolicy("second", "new", policy(limit));
                default -> throw new IllegalArgumentException(kind);
            }
        }
        StackResource resource = new StackResource();
        resource.setLogicalId("Policy");
        resource.setResourceType("AWS::IAM::Policy");
        resource.setPhysicalId("shared");
        resource.setAttributes(new HashMap<>());
        resource.getAttributes().put("Inline" + kind + "Targets", "first\nremoved");
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> ((JsonNode) call.getArgument(0)).asText());
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(call -> call.getArgument(0).toString());
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "000000000000", "stack", "shared");
        JsonNode props = mapper.readTree("{\"PolicyName\":\"new\",\"PolicyDocument\":"
                + policy(limit) + ",\"" + kind + "s\":[\"first\",\"second\"]}");
        IamPolicyCfnProvisioner provisioner = new IamPolicyCfnProvisioner(iam);
        if (failure) {
            assertEquals(injected, assertThrows(AwsException.class,
                    () -> provisioner.provision(resource, props, context)));
            assertEquals(original, get(kind, "first", "shared"));
            assertEquals(original, get(kind, "removed", "shared"));
            if (collision) {
                assertEquals(policy(200), get(kind, "first", "new"));
            } else {
                assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                        () -> get(kind, "first", "new")).getErrorCode());
            }
            assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                    () -> get(kind, "second", "new")).getErrorCode());
            assertEquals("true", resource.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
            assertEquals("shared", resource.getPhysicalId());
        } else {
            provisioner.provision(resource, props, context);
            assertEquals(policy(limit), get(kind, "first", "new"));
            assertEquals(policy(limit), get(kind, "second", "new"));
            assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                    () -> get(kind, "first", "shared")).getErrorCode());
            assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                    () -> get(kind, "removed", "shared")).getErrorCode());
            assertEquals("new", resource.getPhysicalId());
            ProvisionContext retry = new ProvisionContext(engine, "us-east-1", "000000000000", "stack", "new");
            provisioner.provision(resource, props, retry);
            assertEquals(policy(limit), get(kind, "first", "new"));
        }
    }

    private void delete(String kind, String principal, String name) {
        switch (kind) {
            case "User" -> iam.deleteUserPolicy(principal, name);
            case "Group" -> iam.deleteGroupPolicy(principal, name);
            case "Role" -> iam.deleteRolePolicy(principal, name);
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private InlinePolicyChange update(String kind, String principal, String name, String document) {
        return switch (kind) {
            case "User" -> iam.putUserPolicyWithRollback(principal, name, document);
            case "Group" -> iam.putGroupPolicyWithRollback(principal, name, document);
            case "Role" -> iam.putRolePolicyWithRollback(principal, name, document);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private InlinePolicyChange remove(String kind, String principal, String name) {
        return switch (kind) {
            case "User" -> iam.deleteUserPolicyWithRollback(principal, name);
            case "Group" -> iam.deleteGroupPolicyWithRollback(principal, name);
            case "Role" -> iam.deleteRolePolicyWithRollback(principal, name);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private void failRestore(String kind, String original) {
        AwsException failure = new AwsException("AccessDenied", "injected restoration failure", 403);
        switch (kind) {
            case "User" -> doAnswer(call -> {
                InlinePolicyChange change = (InlinePolicyChange) call.callRealMethod();
                return new InlinePolicyChange(change.previousDocument(), () -> { throw failure; });
            }).when(iam).putUserPolicyWithRollback("first", "shared", policy(1100));
            case "Group" -> doAnswer(call -> {
                InlinePolicyChange change = (InlinePolicyChange) call.callRealMethod();
                return new InlinePolicyChange(change.previousDocument(), () -> { throw failure; });
            }).when(iam).putGroupPolicyWithRollback("first", "shared", policy(1100));
            case "Role" -> doAnswer(call -> {
                InlinePolicyChange change = (InlinePolicyChange) call.callRealMethod();
                return new InlinePolicyChange(change.previousDocument(), () -> { throw failure; });
            }).when(iam).putRolePolicyWithRollback("first", "shared", policy(1100));
            default -> throw new IllegalArgumentException(kind);
        }
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
