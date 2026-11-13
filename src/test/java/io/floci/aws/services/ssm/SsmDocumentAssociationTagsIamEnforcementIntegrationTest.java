package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DocumentType;
import software.amazon.awssdk.services.ssm.model.InvalidDocumentException;
import software.amazon.awssdk.services.ssm.model.ResourceTypeForTagging;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tagging a document or an association is checked against that document's or association's ARN. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmDocumentAssociationTagsIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CONTENT = "{\"schemaVersion\":\"2.2\",\"description\":\"d\",\"mainSteps\":[{"
            + "\"action\":\"aws:runShellScript\",\"name\":\"s\",\"inputs\":{\"runCommand\":[\"echo hi\"]}}]}";
    private static final String TAG_ACTIONS =
            "[\"ssm:AddTagsToResource\",\"ssm:ListTagsForResource\",\"ssm:RemoveTagsFromResource\"]";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aGrantOnParametersDoesNotCoverDocumentsOrAssociations() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String allowedDocument = "iam-tags-allowed-" + suffix;
        String deniedDocument = "iam-tags-denied-" + suffix;
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                createDocument(root, allowedDocument);
                createDocument(root, deniedDocument);
                String allowedAssociation = createAssociation(root, allowedDocument);
                String deniedAssociation = createAssociation(root, deniedDocument);
                String userName = "ssm-tags-" + suffix;
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":%s,"Resource":[
                            "arn:aws:ssm:*:*:parameter/*",
                            "arn:aws:ssm:*:*:document/%s",
                            "arn:aws:ssm:*:*:association/%s"]}]}"""
                        .formatted(TAG_ACTIONS, allowedDocument, allowedAssociation));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertDoesNotThrow(() -> tag(user, ResourceTypeForTagging.DOCUMENT, allowedDocument));
                    assertDoesNotThrow(() -> tag(user, ResourceTypeForTagging.ASSOCIATION, allowedAssociation));
                    assertDoesNotThrow(() -> user.removeTagsFromResource(r -> r
                            .resourceType(ResourceTypeForTagging.DOCUMENT).resourceId(allowedDocument).tagKeys("team")));

                    assertDenied(() -> tag(user, ResourceTypeForTagging.DOCUMENT, deniedDocument));
                    assertDenied(() -> tag(user, ResourceTypeForTagging.ASSOCIATION, deniedAssociation));
                    assertDenied(() -> user.listTagsForResource(r -> r
                            .resourceType(ResourceTypeForTagging.DOCUMENT).resourceId(deniedDocument)));
                }
            } finally {
                root.deleteDocument(r -> r.name(allowedDocument).force(true));
                root.deleteDocument(r -> r.name(deniedDocument).force(true));
            }
        }
    }

    @Test
    void aParameterIsTaggedOnItsOwnArn() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String allowedParameter = "/iam-tags-" + suffix + "/allowed";
        String deniedParameter = "/iam-tags-" + suffix + "-other/denied";
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                root.putParameter(r -> r.name(allowedParameter).value("v").type("String"));
                root.putParameter(r -> r.name(deniedParameter).value("v").type("String"));
                String userName = "ssm-param-tags-" + suffix;
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":%s,
                          "Resource":"arn:aws:ssm:*:*:parameter/iam-tags-%s/*"}]}"""
                        .formatted(TAG_ACTIONS, suffix));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertDoesNotThrow(() -> tag(user, ResourceTypeForTagging.PARAMETER, allowedParameter));
                    assertDenied(() -> tag(user, ResourceTypeForTagging.PARAMETER, deniedParameter));
                }
            } finally {
                root.deleteParameters(r -> r.names(allowedParameter, deniedParameter));
            }
        }
    }

    @Test
    void aDocumentIsCreatedWithTagsOnItsOwnArn() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String allowedDocument = "iam-create-allowed-" + suffix;
        String deniedDocument = "iam-create-denied-" + suffix;
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                String userName = "ssm-create-" + suffix;
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":["ssm:CreateDocument","ssm:AddTagsToResource"],
                          "Resource":"arn:aws:ssm:*:*:document/%s"}]}"""
                        .formatted(allowedDocument));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertDoesNotThrow(() -> user.createDocument(r -> r.name(allowedDocument)
                            .documentType(DocumentType.COMMAND).content(CONTENT)
                            .tags(t -> t.key("team").value("red"))));
                    assertDenied(() -> user.createDocument(r -> r.name(deniedDocument)
                            .documentType(DocumentType.COMMAND).content(CONTENT)
                            .tags(t -> t.key("team").value("red"))));
                }
            } finally {
                deleteIfPresent(root, allowedDocument);
                deleteIfPresent(root, deniedDocument);
            }
        }
    }

    private static void deleteIfPresent(SsmClient root, String document) {
        try {
            root.deleteDocument(r -> r.name(document).force(true));
        } catch (InvalidDocumentException expected) {
            // The document was never created, which is what a denied create leaves.
        }
    }

    private static void tag(SsmClient ssm, ResourceTypeForTagging type, String id) {
        ssm.addTagsToResource(r -> r.resourceType(type).resourceId(id).tags(t -> t.key("team").value("red")));
    }

    private static void assertDenied(Runnable call) {
        SsmException error = assertThrows(SsmException.class, call::run);
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private static void createDocument(SsmClient root, String name) {
        root.createDocument(r -> r.name(name).documentType(DocumentType.COMMAND).content(CONTENT));
    }

    private static String createAssociation(SsmClient root, String document) {
        return root.createAssociation(r -> r.name(document)
                        .targets(t -> t.key("InstanceIds").values("i-0123456789abcdef0")))
                .associationDescription().associationId();
    }
}
