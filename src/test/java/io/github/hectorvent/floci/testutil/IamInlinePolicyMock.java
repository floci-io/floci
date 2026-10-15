package io.github.hectorvent.floci.testutil;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.InlinePolicyChange;
import io.github.hectorvent.floci.services.iam.model.IamUser;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/** Adapts existing provisioner mocks; real compensation is exercised in the IAM rollback tests. */
public final class IamInlinePolicyMock {
    private IamInlinePolicyMock() {}

    public static void configure(IamService iam) {
        lenient().when(iam.putUserPolicyWithRollback(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String principal = call.getArgument(0);
            String name = call.getArgument(1);
            String document = call.getArgument(2);
            String previous = previous(() -> userDocument(iam, principal, name));
            iam.putUserPolicy(principal, name, document);
            return change(previous, () -> iam.deleteUserPolicy(principal, name),
                    restored -> iam.putUserPolicy(principal, name, restored));
        });
        lenient().when(iam.deleteUserPolicyWithRollback(anyString(), anyString())).thenAnswer(call -> {
            String principal = call.getArgument(0);
            String name = call.getArgument(1);
            String previous = previous(() -> userDocument(iam, principal, name));
            iam.deleteUserPolicy(principal, name);
            return change(previous, () -> iam.deleteUserPolicy(principal, name),
                    restored -> iam.putUserPolicy(principal, name, restored));
        });
        lenient().when(iam.putGroupPolicyWithRollback(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String principal = call.getArgument(0);
            String name = call.getArgument(1);
            String document = call.getArgument(2);
            String previous = previous(() -> iam.getGroupPolicy(principal, name));
            iam.putGroupPolicy(principal, name, document);
            return change(previous, () -> iam.deleteGroupPolicy(principal, name),
                    restored -> iam.putGroupPolicy(principal, name, restored));
        });
        lenient().when(iam.deleteGroupPolicyWithRollback(anyString(), anyString())).thenAnswer(call -> {
            String principal = call.getArgument(0);
            String name = call.getArgument(1);
            String previous = previous(() -> iam.getGroupPolicy(principal, name));
            iam.deleteGroupPolicy(principal, name);
            return change(previous, () -> iam.deleteGroupPolicy(principal, name),
                    restored -> iam.putGroupPolicy(principal, name, restored));
        });
        lenient().when(iam.putRolePolicyWithRollback(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String principal = call.getArgument(0);
            String name = call.getArgument(1);
            String document = call.getArgument(2);
            String previous = previous(() -> iam.getRolePolicy(principal, name));
            iam.putRolePolicy(principal, name, document);
            return change(previous, () -> iam.deleteRolePolicy(principal, name),
                    restored -> iam.putRolePolicy(principal, name, restored));
        });
        lenient().when(iam.deleteRolePolicyWithRollback(anyString(), anyString())).thenAnswer(call -> {
            String principal = call.getArgument(0);
            String name = call.getArgument(1);
            String previous = previous(() -> iam.getRolePolicy(principal, name));
            iam.deleteRolePolicy(principal, name);
            return change(previous, () -> iam.deleteRolePolicy(principal, name),
                    restored -> iam.putRolePolicy(principal, name, restored));
        });
    }

    private static String userDocument(IamService iam, String principal, String name) {
        IamUser user = iam.getUser(principal);
        return user == null ? iam.getUserPolicy(principal, name) : user.getInlinePolicies().get(name);
    }

    private static String previous(Supplier<String> read) {
        try {
            return read.get();
        } catch (AwsException error) {
            if ("NoSuchEntity".equals(error.getErrorCode())) {
                return null;
            }
            throw error;
        }
    }

    private static InlinePolicyChange change(String previous, Runnable delete, Consumer<String> write) {
        return new InlinePolicyChange(previous, () -> {
            if (previous == null) {
                delete.run();
            } else {
                write.accept(previous);
            }
        });
    }
}
