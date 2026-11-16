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
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** An account associates a document another account shared with it by giving the document's ARN. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmSharedDocumentAssociationIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String OWNER = "100000008364";
    private static final String CONTENT = "{\"schemaVersion\":\"2.2\",\"description\":\"d\",\"mainSteps\":[{"
            + "\"action\":\"aws:runShellScript\",\"name\":\"s\",\"inputs\":{\"runCommand\":[\"echo hi\"]}}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aSharedDocumentIsAssociatedByItsArnAndAnUnsharedOneIsNot() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String shared = "shared-" + suffix;
        String unshared = "unshared-" + suffix;
        try (SsmClient owner = AwsTestClients.create(SsmClient.builder(), OWNER, Region.US_EAST_1)) {
            try {
                createDocument(owner, shared);
                createDocument(owner, unshared);
                owner.modifyDocumentPermission(r -> r.name(shared).permissionType("Share").accountIdsToAdd(ACCOUNT));
                String userName = "ssm-shared-" + suffix;
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:CreateAssociation",
                          "Resource":["arn:aws:ssm:*:*:association/*","%s","%s"]}]}"""
                        .formatted(arn(shared), arn(unshared)));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertDoesNotThrow(() -> associate(user, arn(shared)));
                    SsmException missing = assertThrows(SsmException.class, () -> associate(user, arn(unshared)));
                    assertEquals("InvalidDocument", missing.awsErrorDetails().errorCode());
                }
            } finally {
                owner.deleteDocument(r -> r.name(shared).force(true));
                owner.deleteDocument(r -> r.name(unshared).force(true));
            }
        }
    }

    @Test
    void aSharedDocumentIsNotAssociatedWithoutAnIdentityPolicyNamingIt() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String shared = "shared-ungranted-" + suffix;
        try (SsmClient owner = AwsTestClients.create(SsmClient.builder(), OWNER, Region.US_EAST_1)) {
            try {
                createDocument(owner, shared);
                owner.modifyDocumentPermission(r -> r.name(shared).permissionType("Share").accountIdsToAdd(ACCOUNT));
                String userName = "ssm-ungranted-" + suffix;
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:CreateAssociation",
                          "Resource":"arn:aws:ssm:*:*:association/*"}]}""");
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    SsmException denied = assertThrows(SsmException.class, () -> associate(user, arn(shared)));
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                }
            } finally {
                owner.deleteDocument(r -> r.name(shared).force(true));
            }
        }
    }

    private static String arn(String document) {
        return "arn:aws:ssm:us-east-1:" + OWNER + ":document/" + document;
    }

    private static void createDocument(SsmClient owner, String name) {
        owner.createDocument(r -> r.name(name).documentType(DocumentType.COMMAND).content(CONTENT));
    }

    private static void associate(SsmClient ssm, String document) {
        ssm.createAssociation(r -> r.name(document).targets(t -> t.key("InstanceIds").values("i-0123456789abcdef0")));
    }
}
