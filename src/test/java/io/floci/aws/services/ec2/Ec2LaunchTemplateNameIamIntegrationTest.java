package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.LaunchTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2LaunchTemplateNameIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    Ec2Service ec2;

    @InjectSpy
    Ec2StoredResourceArns resources;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void deletionByNameUsesTheSameScopedPermissionAsDeletionById(String regionName) throws Exception {
        Region region = Region.of(regionName);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", region, endpoint)) {
            String prefix = "name-iam-" + UUID.randomUUID();
            LaunchTemplate byId = admin.createLaunchTemplate(r -> r.launchTemplateName(prefix + "-id")
                    .launchTemplateData(d -> d.imageId("ami-12345678"))).launchTemplate();
            LaunchTemplate byName = admin.createLaunchTemplate(r -> r.launchTemplateName(prefix + "-name")
                    .launchTemplateData(d -> d.imageId("ami-12345678"))).launchTemplate();
            String policy = """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                      "Action":"ec2:DeleteLaunchTemplate","Resource":[
                        "%s", "%s"]}]}
                    """.formatted(arn(region, byId), arn(region, byName));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-name-", "delete", policy);
                    Ec2Client caller = AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(),
                            user.secretAccessKey(), region, endpoint)) {
                assertEquals(byId.launchTemplateId(), caller.deleteLaunchTemplate(r ->
                        r.launchTemplateId(byId.launchTemplateId())).launchTemplate().launchTemplateId());
                assertEquals(byName.launchTemplateId(), caller.deleteLaunchTemplate(r ->
                        r.launchTemplateName(byName.launchTemplateName())).launchTemplate().launchTemplateId());
            } finally {
                remove(admin, byId.launchTemplateId());
                remove(admin, byName.launchTemplateId());
            }
        }
    }

    @Test
    void versionMutationsByNameUseTheStoredTemplateArn() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            LaunchTemplate template = create(admin, "versions-" + UUID.randomUUID());
            String policy = """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":[
                    "ec2:CreateLaunchTemplateVersion","ec2:ModifyLaunchTemplate","ec2:DeleteLaunchTemplateVersions"],
                    "Resource":"%s"}]}
                    """.formatted(arn(Region.US_EAST_1, template));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-version-", "versions", policy);
                    Ec2Client caller = client(user)) {
                String name = template.launchTemplateName();
                assertEquals(2L, caller.createLaunchTemplateVersion(r -> r.launchTemplateName(name)
                        .launchTemplateData(d -> d.imageId("ami-87654321"))).launchTemplateVersion().versionNumber());
                assertEquals(2L, caller.modifyLaunchTemplate(r -> r.launchTemplateName(name).defaultVersion("2"))
                        .launchTemplate().defaultVersionNumber());
                assertEquals(1L, caller.deleteLaunchTemplateVersions(r -> r.launchTemplateName(name).versions("1"))
                        .successfullyDeletedLaunchTemplateVersions().getFirst().versionNumber());
            } finally {
                remove(admin, template.launchTemplateId());
            }
        }
    }

    @Test
    void unrelatedGrantAndExplicitDenyCannotDeleteByName() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            LaunchTemplate target = create(admin, "denied-" + UUID.randomUUID());
            LaunchTemplate other = create(admin, "allowed-" + UUID.randomUUID());
            try {
                assertDenied(admin, target, policy("Allow", arn(Region.US_EAST_1, other)));
                String denied = "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + statement("Allow", "*") + "," + statement("Deny", arn(Region.US_EAST_1, target)) + "]}";
                assertDenied(admin, target, denied);
            } finally {
                remove(admin, target.launchTemplateId());
                remove(admin, other.launchTemplateId());
            }
        }
    }

    @Test
    void sameNameInAnotherRegionDoesNotSupplyTheAuthorizedId() throws Exception {
        try (Ec2Client east = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint);
                Ec2Client west = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_WEST_2, endpoint)) {
            String name = "regional-" + UUID.randomUUID();
            LaunchTemplate remote = create(west, name);
            LaunchTemplate local = create(east, name);
            try {
                assertDenied(east, local, policy("Allow", arn(Region.US_WEST_2, remote)));
                try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-region-", "delete",
                        policy("Allow", arn(Region.US_EAST_1, local)));
                        Ec2Client caller = client(user)) {
                    assertEquals(local.launchTemplateId(), caller.deleteLaunchTemplate(r -> r.launchTemplateName(name))
                            .launchTemplate().launchTemplateId());
                }
                assertPresent(west, remote);
            } finally {
                remove(east, local.launchTemplateId());
                remove(west, remote.launchTemplateId());
            }
        }
    }

    @Test
    void sameNameInAnotherAccountDoesNotSupplyTheAuthorizedId() throws Exception {
        String name = "account-" + UUID.randomUUID();
        String remoteId = RequestScopes.callAs("111122223333", "us-east-1", () -> ec2.createLaunchTemplate(
                "us-east-1", name, new LaunchTemplateData(), List.of()).getLaunchTemplateId());
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            LaunchTemplate local = create(admin, name);
            try {
                assertDenied(admin, local, policy("Allow", AwsArnUtils.Arn.of("ec2", "us-east-1",
                        "111122223333", "launch-template/" + remoteId).toString()));
                try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-account-", "delete",
                        policy("Allow", arn(Region.US_EAST_1, local)));
                        Ec2Client caller = client(user)) {
                    assertEquals(local.launchTemplateId(), caller.deleteLaunchTemplate(r -> r.launchTemplateName(name))
                            .launchTemplate().launchTemplateId());
                }
                assertEquals(remoteId, RequestScopes.callAs("111122223333", "us-east-1", () ->
                        ec2.describeLaunchTemplates("us-east-1", List.of(remoteId), List.of(), Map.of())
                                .getFirst().getLaunchTemplateId()));
            } finally {
                remove(admin, local.launchTemplateId());
            }
        } finally {
            RequestScopes.runAs("111122223333", "us-east-1", () ->
                    ec2.deleteLaunchTemplate("us-east-1", remoteId, null));
        }
    }

    @Test
    void wildcardGrantPreservesMissingNameError() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-missing-", "delete", policy("Allow", "*"));
                Ec2Client caller = client(user)) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> caller.deleteLaunchTemplate(r ->
                    r.launchTemplateName("missing-" + UUID.randomUUID())));
            assertEquals(400, error.statusCode());
            assertEquals("InvalidLaunchTemplateName.NotFoundException", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void duplicateCreateDoesNotAuthorizeAgainstTheExistingTemplate() throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            LaunchTemplate template = create(admin, "duplicate-" + UUID.randomUUID());
            String policy = """
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"ec2:CreateLaunchTemplate","Resource":"*"},
                    {"Effect":"Deny","Action":"ec2:CreateLaunchTemplate","Resource":"%s"}]}
                    """.formatted(arn(Region.US_EAST_1, template));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-duplicate-", "create", policy);
                    Ec2Client caller = client(user)) {
                Ec2Exception error = assertThrows(Ec2Exception.class, () -> create(caller, template.launchTemplateName()));
                assertEquals("InvalidLaunchTemplateName.AlreadyExistsException", error.awsErrorDetails().errorCode());
                assertPresent(admin, template);
            } finally {
                remove(admin, template.launchTemplateId());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DeleteLaunchTemplate", "CreateLaunchTemplateVersion", "ModifyLaunchTemplate",
            "DeleteLaunchTemplateVersions"})
    void replacementCannotBeMutatedUsingTheOriginalTemplatesGrant(String action) throws Exception {
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1, endpoint)) {
            String name = "replaced-" + UUID.randomUUID();
            LaunchTemplate original = create(admin, name);
            CountDownLatch resolved = new CountDownLatch(1);
            CountDownLatch replaced = new CountDownLatch(1);
            doAnswer(invocation -> {
                Object found = invocation.callRealMethod();
                resolved.countDown();
                assertTrue(replaced.await(15, TimeUnit.SECONDS), "replacement must finish before authorization resumes");
                return found;
            }).when(resources).find("launch-template", List.of(name), "us-east-1");
            String policy = """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ec2:%s","Resource":"%s"}]}
                    """.formatted(action, arn(Region.US_EAST_1, original));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            LaunchTemplate replacement = null;
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-race-", "mutate", policy);
                    Ec2Client caller = client(user)) {
                Future<?> mutation = worker.submit(() -> {
                    if ("DeleteLaunchTemplateVersions".equals(action)) {
                        assertEquals("launchTemplateNameDoesNotExist", caller.deleteLaunchTemplateVersions(r ->
                                r.launchTemplateName(name).versions("2")).unsuccessfullyDeletedLaunchTemplateVersions()
                                .getFirst().responseError().codeAsString());
                    } else {
                        Ec2Exception error = assertThrows(Ec2Exception.class, () -> {
                            switch (action) {
                                case "DeleteLaunchTemplate" -> caller.deleteLaunchTemplate(r -> r.launchTemplateName(name));
                                case "CreateLaunchTemplateVersion" -> caller.createLaunchTemplateVersion(r ->
                                        r.launchTemplateName(name).launchTemplateData(d -> d.imageId("ami-87654321")));
                                case "ModifyLaunchTemplate" -> caller.modifyLaunchTemplate(r ->
                                        r.launchTemplateName(name).defaultVersion("2"));
                                default -> throw new AssertionError(action);
                            }
                        });
                        assertEquals("InvalidLaunchTemplateName.NotFoundException", error.awsErrorDetails().errorCode());
                    }
                });
                assertTrue(resolved.await(15, TimeUnit.SECONDS), "IAM must resolve the original template");
                remove(admin, original.launchTemplateId());
                replacement = create(admin, name);
                admin.createLaunchTemplateVersion(r -> r.launchTemplateName(name)
                        .launchTemplateData(d -> d.imageId("ami-22222222")));
                replaced.countDown();
                mutation.get(15, TimeUnit.SECONDS);
                assertPresent(admin, replacement);
                LaunchTemplate after = admin.describeLaunchTemplates(r -> r.launchTemplateNames(name))
                        .launchTemplates().getFirst();
                assertEquals(1L, after.defaultVersionNumber());
                assertEquals(2L, after.latestVersionNumber());
                assertEquals(2, admin.describeLaunchTemplateVersions(r -> r.launchTemplateName(name))
                        .launchTemplateVersions().size());
            } finally {
                replaced.countDown();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
                remove(admin, original.launchTemplateId());
                if (replacement != null) {
                    remove(admin, replacement.launchTemplateId());
                }
            }
        }
    }

    private void assertDenied(Ec2Client admin, LaunchTemplate template, String policy) throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "lt-deny-", "delete", policy);
                Ec2Client caller = client(user)) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> caller.deleteLaunchTemplate(r ->
                    r.launchTemplateName(template.launchTemplateName())));
            assertEquals(403, error.statusCode());
            assertEquals("UnauthorizedOperation", error.awsErrorDetails().errorCode());
            assertPresent(admin, template);
        }
    }

    private Ec2Client client(SignedIamUsers.User user) {
        return AwsTestClients.create(Ec2Client.builder(), user.accessKeyId(), user.secretAccessKey(),
                Region.US_EAST_1, endpoint);
    }

    private static LaunchTemplate create(Ec2Client admin, String name) {
        return admin.createLaunchTemplate(r -> r.launchTemplateName(name)
                .launchTemplateData(d -> d.imageId("ami-12345678"))).launchTemplate();
    }

    private static void assertPresent(Ec2Client admin, LaunchTemplate template) {
        assertEquals(template.launchTemplateId(), admin.describeLaunchTemplates(r ->
                r.launchTemplateIds(template.launchTemplateId())).launchTemplates().getFirst().launchTemplateId());
    }

    private static String arn(Region region, LaunchTemplate template) {
        return AwsArnUtils.Arn.of("ec2", region.id(), "000000000000",
                "launch-template/" + template.launchTemplateId()).toString();
    }

    private static String statement(String effect, String resource) {
        return "{\"Effect\":\"%s\",\"Action\":\"ec2:DeleteLaunchTemplate\",\"Resource\":\"%s\"}"
                .formatted(effect, resource);
    }

    private static String policy(String effect, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement(effect, resource) + "]}";
    }

    private static void remove(Ec2Client admin, String id) {
        try {
            admin.deleteLaunchTemplate(r -> r.launchTemplateId(id));
        } catch (Ec2Exception expected) {
            // A successful deletion assertion already removed this fixture.
            assertEquals("InvalidLaunchTemplateId.NotFound", expected.awsErrorDetails().errorCode());
        }
    }
}
