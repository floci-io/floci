package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ResourceResolutionEnforcementIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @TestHTTPResource("/")
    URI baseUri;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void scopedGetRoleCanProbeAnAbsentNameAndThenReadTheCreatedRole() {
        String name = "resource-probe-" + suffix();
        String arn = "arn:aws:iam::" + ACCOUNT + ":role/" + name;
        UserCredentials keys = user("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":["iam:GetRole","iam:CreateRole"],"Resource":"%s"}]}""".formatted(arn));
        try (IamClient caller = AwsTestClients.create(IamClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                Region.US_EAST_1)) {
            NoSuchEntityException missing = assertThrows(NoSuchEntityException.class,
                    () -> caller.getRole(r -> r.roleName(name)));
            assertEquals(404, missing.statusCode());
            denied(() -> caller.getRole(r -> r.roleName(name + "-other")));
            caller.createRole(r -> r.roleName(name).assumeRolePolicyDocument(TRUST));
            cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(ACCOUNT, "iam"), name));
            assertEquals(arn, caller.getRole(r -> r.roleName(name)).role().arn());
        }
    }

    @Test
    void scopedRoleActionsWithoutAPathReachMissingEntityAndRespectExplicitDenies() {
        String name = "resource-absent-" + suffix();
        String arn = "arn:aws:iam::" + ACCOUNT + ":role/" + name;
        UserCredentials keys = user("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":["iam:DeleteRole","iam:ListRolePolicies"],"Resource":"%s"}]}""".formatted(arn));
        try (IamClient caller = AwsTestClients.create(IamClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                Region.US_EAST_1)) {
            assertThrows(NoSuchEntityException.class, () -> caller.deleteRole(r -> r.roleName(name)));
            assertThrows(NoSuchEntityException.class, () -> caller.listRolePolicies(r -> r.roleName(name)));
            denied(() -> caller.deleteRole(r -> r.roleName(name + "-other")));
        }
        UserCredentials blocked = user("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"iam:DeleteRole","Resource":"*"},
                {"Effect":"Deny","Action":"iam:DeleteRole","Resource":"%s"}]}""".formatted(arn));
        try (IamClient caller = AwsTestClients.create(IamClient.builder(), blocked.accessKeyId(), blocked.secretAccessKey(),
                Region.US_EAST_1)) {
            denied(() -> caller.deleteRole(r -> r.roleName(name)));
        }
    }

    @Test
    void storedRolePathAndAnExplicitDenyStillApply() {
        String name = "resource-path-" + suffix();
        String rootArn = "arn:aws:iam::" + ACCOUNT + ":role/" + name;
        try (IamClient admin = AwsTestClients.create(IamClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String actual = admin.createRole(r -> r.roleName(name).path("/team/")
                    .assumeRolePolicyDocument(TRUST)).role().arn();
            cleanup.register(() -> EnforcementFixtures.removeRole(baseUri.getPort(), auth(ACCOUNT, "iam"), name));
            UserCredentials keys = user("""
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"iam:GetRole","Resource":["%s","%s-absent"]}]}"""
                    .formatted(rootArn, rootArn));
            try (IamClient caller = AwsTestClients.create(IamClient.builder(), keys.accessKeyId(), keys.secretAccessKey(),
                    Region.US_EAST_1)) {
                denied(() -> caller.getRole(r -> r.roleName(name)));
            }
            UserCredentials denied = user("""
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"iam:GetRole","Resource":"*"},
                    {"Effect":"Deny","Action":"iam:GetRole","Resource":["%s","%s-absent"]}]}"""
                    .formatted(actual, rootArn));
            try (IamClient caller = AwsTestClients.create(IamClient.builder(), denied.accessKeyId(), denied.secretAccessKey(),
                    Region.US_EAST_1)) {
                denied(() -> caller.getRole(r -> r.roleName(name)));
                denied(() -> caller.getRole(r -> r.roleName(name + "-absent")));
            }
        }
    }

    @Test
    void scopedInstanceTaggingRejectsAnUnauthorizedLaterTargetBeforeChangingAnyTags() {
        try (Ec2Client admin = ec2Admin()) {
            String first = instance(admin);
            String second = instance(admin);
            UserCredentials keys = user(policy(allow(instanceArn(first), null)));
            try (Ec2Client caller = ec2Caller(keys)) {
                caller.createTags(r -> r.resources(first).tags(tag("Owner", "first")));
                denied(() -> caller.createTags(r -> r.resources(first, second).tags(tag("Owner", "changed"))));
                assertTags(admin, first, List.of(tag("Owner", "first")));
                assertTags(admin, second, List.of());
                admin.createTags(r -> r.resources(second).tags(tag("Owner", "second")));
                denied(() -> caller.deleteTags(r -> r.resources(first, second).tags(tag("Owner", null))));
                assertTags(admin, first, List.of(tag("Owner", "first")));
                assertTags(admin, second, List.of(tag("Owner", "second")));
                caller.deleteTags(r -> r.resources(first).tags(tag("Owner", null)));
                assertTags(admin, first, List.of());
            }
        }
    }

    @Test
    void explicitDenyOnTheSecondInstanceIsNotLostBehindAWildcardAllow() {
        try (Ec2Client admin = ec2Admin()) {
            String first = instance(admin);
            String second = instance(admin);
            UserCredentials keys = user(policy(allow("*", null) + "," + """
                    {"Effect":"Deny","Action":["ec2:CreateTags","ec2:DeleteTags"],"Resource":"%s"}
                    """.formatted(instanceArn(second))));
            try (Ec2Client caller = ec2Caller(keys)) {
                denied(() -> caller.createTags(r -> r.resources(first, second).tags(tag("Owner", "x"))));
                assertTags(admin, first, List.of());
                assertTags(admin, second, List.of());
                admin.createTags(r -> r.resources(first, second).tags(tag("Owner", "x")));
                denied(() -> caller.deleteTags(r -> r.resources(first, second).tags(tag("Owner", null))));
                assertTags(admin, first, List.of(tag("Owner", "x")));
                assertTags(admin, second, List.of(tag("Owner", "x")));
            }
        }
    }

    @Test
    void eachInstanceIsEvaluatedWithItsOwnStoredTags() {
        try (Ec2Client admin = ec2Admin()) {
            String first = instance(admin);
            String second = instance(admin);
            admin.createTags(r -> r.resources(first).tags(tag("Owner", "first")));
            admin.createTags(r -> r.resources(second).tags(tag("Owner", "second")));
            UserCredentials keys = user(policy(allow(instanceArn(first), "first") + ","
                    + allow(instanceArn(second), "second")));
            try (Ec2Client caller = ec2Caller(keys)) {
                caller.createTags(r -> r.resources(first, second).tags(tag("Extra", "ok")));
                caller.deleteTags(r -> r.resources(first, second).tags(tag("Extra", null)));
                assertTags(admin, first, List.of(tag("Owner", "first")));
                assertTags(admin, second, List.of(tag("Owner", "second")));
                admin.createTags(r -> r.resources(second).tags(tag("Owner", "first")));
                denied(() -> caller.createTags(r -> r.resources(first, second).tags(tag("Extra", "no"))));
                assertTags(admin, first, List.of(tag("Owner", "first")));
                assertTags(admin, second, List.of(tag("Owner", "first")));
            }
        }
    }

    @Test
    void anUnmodelledResourceAfterAnInstanceStillRequiresPermission() {
        try (Ec2Client admin = ec2Admin()) {
            String first = instance(admin);
            String vpc = admin.createVpc(r -> r.cidrBlock("10.98.0.0/16")).vpc().vpcId();
            cleanup.register(() -> {
                try (Ec2Client client = ec2Admin()) {
                    client.deleteVpc(r -> r.vpcId(vpc));
                }
            });
            UserCredentials keys = user(policy(allow(instanceArn(first), null)));
            try (Ec2Client caller = ec2Caller(keys)) {
                denied(() -> caller.createTags(r -> r.resources(first, vpc).tags(tag("Owner", "x"))));
                assertTags(admin, first, List.of());
                assertEquals(List.of(), admin.describeVpcs(r -> r.vpcIds(vpc)).vpcs().getFirst().tags());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void wildcardTagTargetsKeepEveryTargetContext(boolean explicitDeny, boolean wildcardFirst) {
        try (Ec2Client admin = ec2Admin()) {
            String instance = instance(admin);
            String vpc = admin.createVpc(r -> r.cidrBlock("10.97.0.0/16")).vpc().vpcId();
            cleanup.register(() -> {
                try (Ec2Client client = ec2Admin()) {
                    client.deleteVpc(r -> r.vpcId(vpc));
                }
            });
            admin.createTags(r -> r.resources(instance).tags(tag("Owner", "instance")));
            admin.createTags(r -> r.resources(vpc).tags(tag("Owner", "vpc")));
            admin.createTags(r -> r.resources(instance, vpc).tags(tag("Extra", "original")));
            String statements = explicitDeny ? allow("*", null) + "," + """
                    {"Effect":"Deny","Action":["ec2:CreateTags","ec2:DeleteTags"],"NotResource":"%s",
                     "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"instance"}}}
                    """.formatted(instanceArn(instance))
                    : allow(instanceArn(instance), null) + "," + allow("*", "vpc");
            List<String> targets = wildcardFirst ? List.of(vpc, instance) : List.of(instance, vpc);
            try (Ec2Client caller = ec2Caller(user(policy(statements)))) {
                denied(() -> caller.createTags(r -> r.resources(targets).tags(tag("Extra", "changed"))));
                denied(() -> caller.deleteTags(r -> r.resources(targets).tags(tag("Extra", null))));
                assertTrue(admin.describeInstances(r -> r.instanceIds(instance)).reservations().getFirst()
                        .instances().getFirst().tags().contains(tag("Extra", "original")));
                assertTrue(admin.describeVpcs(r -> r.vpcIds(vpc)).vpcs().getFirst().tags()
                        .contains(tag("Extra", "original")));
                // Once every context satisfies the wildcard policy, the same batch can proceed.
                admin.createTags(r -> r.resources(instance).tags(tag("Owner", "vpc")));
                caller.createTags(r -> r.resources(targets).tags(tag("Extra", "changed")));
                caller.deleteTags(r -> r.resources(targets).tags(tag("Extra", null)));
                assertTags(admin, instance, List.of(tag("Owner", "vpc")));
                assertEquals(List.of(tag("Owner", "vpc")), admin.describeVpcs(r -> r.vpcIds(vpc)).vpcs().getFirst().tags());
            }
        }
    }

    private UserCredentials user(String policy) {
        String name = "resource-reader-" + suffix();
        UserCredentials keys = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ACCOUNT, "iam"), name);
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), name, "resources", policy);
        return keys;
    }

    private String instance(Ec2Client admin) {
        String id = admin.runInstances(r -> r.imageId("ami-0abcdef1234567890").instanceType("t3.micro")
                .minCount(1).maxCount(1)).instances().getFirst().instanceId();
        cleanup.register(() -> {
            try (Ec2Client client = ec2Admin()) {
                client.terminateInstances(r -> r.instanceIds(id));
            }
        });
        return id;
    }

    private static String allow(String resource, String owner) {
        String condition = owner == null ? "" : ",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/Owner\":\"" + owner + "\"}}";
        return """
                {"Effect":"Allow","Action":["ec2:CreateTags","ec2:DeleteTags"],"Resource":"%s"%s}
                """.formatted(resource, condition);
    }

    private static String policy(String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    private static String instanceArn(String id) {
        return "arn:aws:ec2:us-east-1:" + ACCOUNT + ":instance/" + id;
    }

    private static Ec2Client ec2Admin() {
        return AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
    }

    private static Ec2Client ec2Caller(UserCredentials keys) {
        return AwsTestClients.create(Ec2Client.builder(), keys.accessKeyId(), keys.secretAccessKey(), Region.US_EAST_1);
    }

    private static void assertTags(Ec2Client admin, String id, List<Tag> expected) {
        assertEquals(expected, admin.describeInstances(r -> r.instanceIds(id))
                .reservations().getFirst().instances().getFirst().tags());
    }

    private static void denied(Runnable call) {
        AwsServiceException error = assertThrows(AwsServiceException.class, call::run);
        assertEquals(403, error.statusCode());
        assertEquals(error instanceof Ec2Exception ? "UnauthorizedOperation" : "AccessDenied",
                error.awsErrorDetails().errorCode());
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
