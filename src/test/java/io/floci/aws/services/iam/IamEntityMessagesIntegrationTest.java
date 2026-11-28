package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.floci.aws.services.iam.IamWordingRequests.DOCUMENT;
import static io.floci.aws.services.iam.IamWordingRequests.expectError;
import static io.floci.aws.services.iam.IamWordingRequests.expectNotFound;
import static io.floci.aws.services.iam.IamWordingRequests.iam;
import static io.floci.aws.services.iam.IamWordingRequests.unique;

/**
 * Pins the exact error codes and messages IAM returns for duplicate policies, inline policies and instance
 * profiles; the not-found wording of managed policies, their versions and permissions boundaries is pinned in
 * {@link IamPolicyNotFoundWordingIntegrationTest}.
 */
@QuarkusTest
class IamEntityMessagesIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final IamWordingRequests requests = new IamWordingRequests(cleanup);

    @Test
    void aDuplicatePolicyNameIsRefused() {
        String name = unique("duplicate-policy");
        requests.createPolicy(name);
        expectError(iam("CreatePolicy").formParam("PolicyName", name).formParam("PolicyDocument", DOCUMENT),
                409, "EntityAlreadyExists",
                "A policy called " + name + " already exists. Duplicate names are not allowed.");
    }

    @Test
    void inlinePolicyMessages() {
        String user = requests.createUser();
        String group = requests.createGroup();
        String role = requests.createRole();
        for (String action : new String[] {"GetUserPolicy", "DeleteUserPolicy"}) {
            expectNotFound(iam(action).formParam("UserName", user).formParam("PolicyName", "nope"),
                    "The user policy with name nope cannot be found.");
        }
        for (String action : new String[] {"GetGroupPolicy", "DeleteGroupPolicy"}) {
            expectNotFound(iam(action).formParam("GroupName", group).formParam("PolicyName", "nope"),
                    "The group policy with name nope cannot be found.");
        }
        for (String action : new String[] {"GetRolePolicy", "DeleteRolePolicy"}) {
            expectNotFound(iam(action).formParam("RoleName", role).formParam("PolicyName", "nope"),
                    "The role policy with name nope cannot be found.");
        }
    }

    @Test
    void instanceProfileMessages() {
        String name = requests.createInstanceProfile();
        expectError(iam("CreateInstanceProfile").formParam("InstanceProfileName", name),
                409, "EntityAlreadyExists", "Instance Profile " + name + " already exists.");
        String missing = unique("missing-profile");
        String message = "Instance Profile " + missing + " cannot be found.";
        expectNotFound(iam("GetInstanceProfile").formParam("InstanceProfileName", missing), message);
        expectNotFound(iam("DeleteInstanceProfile").formParam("InstanceProfileName", missing), message);
        expectNotFound(iam("TagInstanceProfile").formParam("InstanceProfileName", missing)
                .formParam("Tags.member.1.Key", "k").formParam("Tags.member.1.Value", "v"), message);
        expectNotFound(iam("UntagInstanceProfile").formParam("InstanceProfileName", missing)
                .formParam("TagKeys.member.1", "k"), message);
    }
}
