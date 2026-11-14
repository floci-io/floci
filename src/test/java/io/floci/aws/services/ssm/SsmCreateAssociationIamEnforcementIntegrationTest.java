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

/** Creating an association is checked against the document it runs. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmCreateAssociationIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CONTENT = "{\"schemaVersion\":\"2.2\",\"description\":\"d\",\"mainSteps\":[{"
            + "\"action\":\"aws:runShellScript\",\"name\":\"s\",\"inputs\":{\"runCommand\":[\"echo hi\"]}}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anAssociationIsCreatedOnlyForADocumentThePolicyNames() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String allowedDocument = "iam-assoc-allowed-" + suffix;
        String deniedDocument = "iam-assoc-denied-" + suffix;
        try (SsmClient root = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                createDocument(root, allowedDocument);
                createDocument(root, deniedDocument);
                String userName = "ssm-assoc-" + suffix;
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(ACCOUNT, "iam"), userName);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "p", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ssm:CreateAssociation",
                          "Resource":["arn:aws:ssm:*:*:association/*","arn:aws:ssm:*:*:document/%s"]}]}"""
                        .formatted(allowedDocument));
                try (SsmClient user = AwsTestClients.create(SsmClient.builder(), credentials.accessKeyId(),
                        Region.US_EAST_1)) {
                    assertDoesNotThrow(() -> associate(user, allowedDocument));
                    SsmException denied = assertThrows(SsmException.class, () -> associate(user, deniedDocument));
                    assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                }
            } finally {
                root.deleteDocument(r -> r.name(allowedDocument).force(true));
                root.deleteDocument(r -> r.name(deniedDocument).force(true));
            }
        }
    }

    private static void createDocument(SsmClient root, String name) {
        root.createDocument(r -> r.name(name).documentType(DocumentType.COMMAND).content(CONTENT));
    }

    private static void associate(SsmClient ssm, String document) {
        ssm.createAssociation(r -> r.name(document).targets(t -> t.key("InstanceIds").values("i-0123456789abcdef0")));
    }
}
