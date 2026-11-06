package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.Tag;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamRoleTagConditionIntegrationTest {
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String INLINE = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""";

    @TestHTTPResource("/")
    URI baseUri;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceTag/", "iam:ResourceTag/"})
    void storedRoleTagsGatePolicyUpdatesAndExplicitDenies(String prefix) {
        try (IamClient admin = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1)) {
            String allowed = role(admin, List.of(tag("Owner", "team-a")));
            String other = role(admin, List.of(tag("Owner", "team-b")));
            String untagged = role(admin, List.of());
            String blocked = role(admin, List.of(tag("Owner", "team-a"), tag("Blocked", "yes")));
            String policy = """
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"iam:PutRolePolicy","Resource":"*",
                     "Condition":{"StringEquals":{"%sOwner":"team-a"}}},
                    {"Effect":"Deny","Action":"iam:PutRolePolicy","Resource":"*",
                     "Condition":{"StringEquals":{"%sBlocked":"yes"}}}]}""".formatted(prefix, prefix);
            try (IamClient caller = caller(policy)) {
                caller.putRolePolicy(r -> r.roleName(allowed).policyName("inline").policyDocument(INLINE));
                for (String name : List.of(other, untagged, blocked)) {
                    denied(() -> caller.putRolePolicy(r -> r.roleName(name).policyName("inline").policyDocument(INLINE)));
                    assertEquals(List.of(), admin.listRolePolicies(r -> r.roleName(name)).policyNames());
                }
                admin.untagRole(r -> r.roleName(allowed).tagKeys("Owner"));
                denied(() -> caller.putRolePolicy(r -> r.roleName(allowed).policyName("second").policyDocument(INLINE)));
                assertEquals(List.of("inline"), admin.listRolePolicies(r -> r.roleName(allowed)).policyNames());
            }
        }
    }

    @Test
    void tagRoleDistinguishesOldTagsFromTheRequestedUpdate() {
        try (IamClient admin = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1)) {
            String owned = role(admin, List.of(tag("Owner", "team-a")));
            String other = role(admin, List.of(tag("Owner", "team-b")));
            String policy = """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iam:TagRole","Resource":"*",
                    "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"team-a","aws:RequestTag/Owner":"team-b"},
                    "ForAllValues:StringEquals":{"aws:TagKeys":["Owner"]},"Null":{"aws:TagKeys":"false"}}}]}""";
            try (IamClient caller = caller(policy)) {
                denied(() -> caller.tagRole(r -> r.roleName(other).tags(tag("Owner", "team-b"))));
                denied(() -> caller.tagRole(r -> r.roleName(owned).tags(tag("Owner", "team-a"))));
                denied(() -> caller.tagRole(r -> r.roleName(owned).tags(tag("Owner", "team-b"), tag("Extra", "x"))));
                assertEquals(List.of(tag("Owner", "team-a")), admin.listRoleTags(r -> r.roleName(owned)).tags());
                caller.tagRole(r -> r.roleName(owned).tags(tag("Owner", "team-b")));
                assertEquals(List.of(tag("Owner", "team-b")), admin.listRoleTags(r -> r.roleName(owned)).tags());
                denied(() -> caller.tagRole(r -> r.roleName(owned).tags(tag("Owner", "team-b"))));
            }
        }
    }

    @Test
    void tagUserUsesRequestedValuesAndUntagUserChecksEveryRequestedKey() {
        try (IamClient admin = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1)) {
            String user = "tag-target-" + suffix();
            admin.createUser(r -> r.userName(user));
            cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth("test", "iam"), user));
            String policy = """
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"iam:TagUser","Resource":"*",
                     "Condition":{"StringEquals":{"aws:RequestTag/Env":"dev"},
                      "ForAllValues:StringEquals":{"aws:TagKeys":["Env"]}}},
                    {"Effect":"Allow","Action":"iam:UntagUser","Resource":"*",
                     "Condition":{"ForAllValues:StringEquals":{"aws:TagKeys":["Env"]},
                      "Null":{"aws:TagKeys":"false"}}}]}""";
            try (IamClient caller = caller(policy)) {
                denied(() -> caller.tagUser(r -> r.userName(user).tags(tag("Env", "prod"))));
                denied(() -> caller.tagUser(r -> r.userName(user).tags(tag("Env", "dev"), tag("Owner", "other"))));
                caller.tagUser(r -> r.userName(user).tags(tag("Env", "dev")));
                denied(() -> caller.untagUser(r -> r.userName(user).tagKeys("Env", "Owner")));
                assertEquals(List.of(tag("Env", "dev")), admin.listUserTags(r -> r.userName(user)).tags());
                caller.untagUser(r -> r.userName(user).tagKeys("Env"));
                assertEquals(List.of(), admin.listUserTags(r -> r.userName(user)).tags());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceTag/", "iam:ResourceTag/"})
    void storedUserTagsGatePolicyUpdatesAndExplicitDenies(String prefix) {
        try (IamClient admin = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1)) {
            String owned = user(admin, List.of(tag("Owner", "team-a")));
            String other = user(admin, List.of(tag("Owner", "team-b")));
            String untagged = user(admin, List.of());
            String blocked = user(admin, List.of(tag("Owner", "team-a"), tag("Blocked", "yes")));
            String policy = """
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"iam:PutUserPolicy","Resource":"*",
                     "Condition":{"StringEquals":{"%sOwner":"team-a"}}},
                    {"Effect":"Deny","Action":"iam:PutUserPolicy","Resource":"*",
                     "Condition":{"StringEquals":{"%sBlocked":"yes"}}}]}""".formatted(prefix, prefix);
            try (IamClient caller = caller(policy)) {
                caller.putUserPolicy(r -> r.userName(owned).policyName("inline").policyDocument(INLINE));
                for (String name : List.of(other, untagged, blocked)) {
                    denied(() -> caller.putUserPolicy(r -> r.userName(name).policyName("inline").policyDocument(INLINE)));
                    assertEquals(List.of(), admin.listUserPolicies(r -> r.userName(name)).policyNames());
                }
                admin.untagUser(r -> r.userName(owned).tagKeys("Owner"));
                denied(() -> caller.putUserPolicy(r -> r.userName(owned).policyName("second").policyDocument(INLINE)));
                assertEquals(List.of("inline"), admin.listUserPolicies(r -> r.userName(owned)).policyNames());
            }
        }
    }

    @Test
    void policyTaggingAndUntaggingEnforceGeneratedRequestTagConditions() {
        try (IamClient admin = AwsTestClients.create(IamClient.builder(), "test", Region.US_EAST_1)) {
            String arn = admin.createPolicy(r -> r.policyName("tag-policy-" + suffix())
                    .policyDocument(INLINE)).policy().arn();
            String policy = """
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"iam:TagPolicy","Resource":"*",
                     "Condition":{"StringEquals":{"aws:RequestTag/Env":"dev"},
                      "ForAllValues:StringEquals":{"aws:TagKeys":["Env"]}}},
                    {"Effect":"Allow","Action":"iam:UntagPolicy","Resource":"*",
                     "Condition":{"ForAllValues:StringEquals":{"aws:TagKeys":["Env"]},
                      "Null":{"aws:TagKeys":"false"}}}]}""";
            try (IamClient caller = caller(policy)) {
                denied(() -> caller.tagPolicy(r -> r.policyArn(arn).tags(tag("Env", "prod"))));
                denied(() -> caller.tagPolicy(r -> r.policyArn(arn).tags(tag("Env", "dev"), tag("Owner", "other"))));
                assertEquals(List.of(), admin.listPolicyTags(r -> r.policyArn(arn)).tags());
                caller.tagPolicy(r -> r.policyArn(arn).tags(tag("Env", "dev")));
                denied(() -> caller.untagPolicy(r -> r.policyArn(arn).tagKeys("Env", "Owner")));
                assertEquals(List.of(tag("Env", "dev")), admin.listPolicyTags(r -> r.policyArn(arn)).tags());
                caller.untagPolicy(r -> r.policyArn(arn).tagKeys("Env"));
                assertEquals(List.of(), admin.listPolicyTags(r -> r.policyArn(arn)).tags());
            } finally {
                admin.deletePolicy(r -> r.policyArn(arn));
            }
        }
    }

    private String user(IamClient admin, List<Tag> tags) {
        String name = "tag-user-" + suffix();
        admin.createUser(r -> r.userName(name).tags(tags));
        cleanup.register(() -> EnforcementFixtures.removeUser(baseUri.getPort(), auth("test", "iam"), name));
        return name;
    }

    private String role(IamClient admin, List<Tag> tags) {
        String name = "tag-role-" + suffix();
        admin.createRole(r -> r.roleName(name).path("/tag-context/").assumeRolePolicyDocument(TRUST).tags(tags));
        cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth("test", "iam"), name));
        return name;
    }

    private IamClient caller(String policy) {
        String name = "tag-caller-" + suffix();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth("test", "iam"), name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth("test", "iam"), name, "tags", policy);
        return AwsTestClients.create(IamClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(),
                Region.US_EAST_1);
    }

    private static void denied(Runnable operation) {
        IamException error = assertThrows(IamException.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
