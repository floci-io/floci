package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.IamException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IamPolicyArnConditionTest {

    private static final String DOCUMENT = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}""";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @ParameterizedTest
    @ValueSource(strings = {"Role", "User", "Group"})
    void policyArnConditionsControlAttachmentsAndPreserveDeniedState(String kind) {
        String target = TestFixtures.uniqueName("sdk-policy-target");
        String caller = TestFixtures.uniqueName("sdk-policy-caller");
        List<Runnable> cleanup = new ArrayList<>();
        try (IamClient root = TestFixtures.iamClient()) {
            try {
                String policy = root.createPolicy(b -> b.policyName(TestFixtures.uniqueName("sdk-policy"))
                        .policyDocument(DOCUMENT)).policy().arn();
                cleanup.add(() -> root.deletePolicy(b -> b.policyArn(policy)));
                String resource = createTarget(root, kind, target);
                cleanup.add(() -> {
                    for (String arn : attached(root, kind, target)) {
                        change(root, kind, target, arn, false);
                    }
                    deleteTarget(root, kind, target);
                });
                root.createUser(b -> b.userName(caller));
                cleanup.add(() -> root.deleteUser(b -> b.userName(caller)));
                AccessKey key = root.createAccessKey(b -> b.userName(caller)).accessKey();
                cleanup.add(() -> root.deleteAccessKey(b -> b.userName(caller).accessKeyId(key.accessKeyId())));
                try (IamClient client = IamClient.builder().endpointOverride(TestFixtures.endpoint())
                        .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()))).build()) {
                    boolean enforcement;
                    try {
                        client.getUser(b -> b.userName(caller));
                        enforcement = false;
                    } catch (IamException e) {
                        assertThat(e.statusCode()).isEqualTo(403);
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
                        enforcement = true;
                    }
                    Assumptions.assumeTrue(enforcement, "IAM enforcement must be enabled");
                    String allowed = statement("Allow", kind, resource, policy);
                    root.putUserPolicy(b -> b.userName(caller).policyName("condition").policyDocument(document(allowed)));
                    cleanup.add(() -> root.deleteUserPolicy(b -> b.userName(caller).policyName("condition")));
                    change(client, kind, target, policy, true);
                    assertThat(attached(root, kind, target)).containsExactly(policy);
                    change(client, kind, target, policy, false);
                    assertThat(attached(root, kind, target)).isEmpty();

                    String denied = "{\"Effect\":\"Allow\",\"Action\":\"iam:*\",\"Resource\":\"*\"},"
                            + statement("Deny", kind, resource, policy);
                    root.putUserPolicy(b -> b.userName(caller).policyName("condition").policyDocument(document(denied)));
                    assertDenied(() -> change(client, kind, target, policy, true));
                    assertThat(attached(root, kind, target)).isEmpty();
                    change(root, kind, target, policy, true);
                    assertDenied(() -> change(client, kind, target, policy, false));
                    assertThat(attached(root, kind, target)).containsExactly(policy);
                }
            } finally {
                RuntimeException cleanupFailure = null;
                for (int i = cleanup.size() - 1; i >= 0; i--) {
                    try {
                        cleanup.get(i).run();
                    } catch (RuntimeException failure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = failure;
                        } else {
                            cleanupFailure.addSuppressed(failure);
                        }
                    }
                }
                if (cleanupFailure != null) {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static String document(String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    private static String statement(String effect, String kind, String resource, String policy) {
        return """
                {"Effect":"%s","Action":["iam:Attach%sPolicy","iam:Detach%sPolicy"],"Resource":"%s",
                 "Condition":{"ArnEquals":{"iam:PolicyARN":"%s"}}}""".formatted(effect, kind, kind, resource, policy);
    }

    private static void assertDenied(Runnable request) {
        IamException error = assertThrows(IamException.class, request::run);
        assertThat(error.statusCode()).isEqualTo(403);
        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
    }

    private static String createTarget(IamClient client, String kind, String name) {
        return switch (kind) {
            case "Role" -> client.createRole(b -> b.roleName(name).path("/sdk/")
                    .assumeRolePolicyDocument(TRUST)).role().arn();
            case "User" -> client.createUser(b -> b.userName(name).path("/sdk/")).user().arn();
            case "Group" -> client.createGroup(b -> b.groupName(name).path("/sdk/")).group().arn();
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static void deleteTarget(IamClient client, String kind, String name) {
        switch (kind) {
            case "Role" -> client.deleteRole(b -> b.roleName(name));
            case "User" -> client.deleteUser(b -> b.userName(name));
            case "Group" -> client.deleteGroup(b -> b.groupName(name));
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private static List<String> attached(IamClient client, String kind, String name) {
        return switch (kind) {
            case "Role" -> client.listAttachedRolePolicies(b -> b.roleName(name)).attachedPolicies()
                    .stream().map(p -> p.policyArn()).toList();
            case "User" -> client.listAttachedUserPolicies(b -> b.userName(name)).attachedPolicies()
                    .stream().map(p -> p.policyArn()).toList();
            case "Group" -> client.listAttachedGroupPolicies(b -> b.groupName(name)).attachedPolicies()
                    .stream().map(p -> p.policyArn()).toList();
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static void change(IamClient client, String kind, String name, String policy, boolean attach) {
        switch (kind) {
            case "Role" -> {
                if (attach) {
                    client.attachRolePolicy(b -> b.roleName(name).policyArn(policy));
                } else {
                    client.detachRolePolicy(b -> b.roleName(name).policyArn(policy));
                }
            }
            case "User" -> {
                if (attach) {
                    client.attachUserPolicy(b -> b.userName(name).policyArn(policy));
                } else {
                    client.detachUserPolicy(b -> b.userName(name).policyArn(policy));
                }
            }
            case "Group" -> {
                if (attach) {
                    client.attachGroupPolicy(b -> b.groupName(name).policyArn(policy));
                } else {
                    client.detachGroupPolicy(b -> b.groupName(name).policyArn(policy));
                }
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }
}
