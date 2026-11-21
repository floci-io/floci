package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.StatusType;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The seeded deployer's access key ID is configurable, so a deployment that provisions with it can
 * then retire it the way any IAM access key is retired. DeleteAccessKey and UpdateAccessKey take an
 * {@code AccessKeyId} of "Minimum length of 16. Maximum length of 128." matching {@code [\w]+}
 * (IAM API Reference), so the default {@code floci} is refused by the SDKs and by Floci before the
 * key is looked up, and DeleteUser then refuses the user while the key exists.
 */
@QuarkusTest
@TestProfile(IamSeededDeployerAccessKeyIntegrationTest.SeededDeployer.class)
class IamSeededDeployerAccessKeyIntegrationTest {

    private static final String DEPLOYER = "floci-deployer";
    private static final String DEPLOYER_ACCESS_KEY_ID = "AKIAFLOCIDEPLOYER001";
    private static final String DEPLOYER_SECRET_ACCESS_KEY = "floci";
    private static final String ACCOUNT = "000000000000";
    private static final String ADMINISTRATOR_ACCESS = "arn:aws:iam::aws:policy/AdministratorAccess";

    /** Signed requests under IAM enforcement, with the deployer seeded under a configured key ID. */
    public static class SeededDeployer implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.auth.validate-signatures", "true",
                    "floci.services.iam.enforcement-enabled", "true",
                    "floci.services.iam.seed-deployer-principal", "true",
                    "floci.services.iam.seed-deployer-access-key-id", DEPLOYER_ACCESS_KEY_ID);
        }
    }

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void theSeededKeyCanBeDeactivatedAndDeletedAndItsUserDeleted() {
        String adminName = "deployer-successor-" + UUID.randomUUID().toString().substring(0, 8);
        try (IamClient deployer = AwsTestClients.create(IamClient.builder(),
                     DEPLOYER_ACCESS_KEY_ID, DEPLOYER_SECRET_ACCESS_KEY, Region.US_EAST_1, endpoint);
             StsClient deployerSts = AwsTestClients.create(StsClient.builder(),
                     DEPLOYER_ACCESS_KEY_ID, DEPLOYER_SECRET_ACCESS_KEY, Region.US_EAST_1, endpoint);
             IamClient root = AwsTestClients.create(IamClient.builder(), "test", "test", Region.US_EAST_1, endpoint)) {
            assertThat(deployerSts.getCallerIdentity().arn(),
                    equalTo("arn:aws:iam::" + ACCOUNT + ":user/" + DEPLOYER));

            deployer.createUser(b -> b.userName(adminName));
            try {
                deployer.attachUserPolicy(b -> b.userName(adminName).policyArn(ADMINISTRATOR_ACCESS));
                AccessKey adminKey = deployer.createAccessKey(b -> b.userName(adminName)).accessKey();
                try (IamClient admin = AwsTestClients.create(IamClient.builder(),
                        adminKey.accessKeyId(), adminKey.secretAccessKey(), Region.US_EAST_1, endpoint)) {
                    List<String> deployerKeys = admin.listAccessKeys(b -> b.userName(DEPLOYER))
                            .accessKeyMetadata().stream().map(AccessKeyMetadata::accessKeyId).toList();
                    assertThat(deployerKeys, contains(DEPLOYER_ACCESS_KEY_ID));

                    admin.updateAccessKey(b -> b.userName(DEPLOYER).accessKeyId(DEPLOYER_ACCESS_KEY_ID)
                            .status(StatusType.INACTIVE));
                    IamException refused = assertThrows(IamException.class, deployer::listUsers);
                    assertThat(refused.statusCode(), equalTo(403));

                    admin.deleteAccessKey(b -> b.userName(DEPLOYER).accessKeyId(DEPLOYER_ACCESS_KEY_ID));
                    admin.detachUserPolicy(b -> b.userName(DEPLOYER).policyArn(ADMINISTRATOR_ACCESS));
                    admin.deleteUser(b -> b.userName(DEPLOYER));

                    assertThrows(NoSuchEntityException.class, () -> admin.getUser(b -> b.userName(DEPLOYER)));
                }
            } finally {
                deleteUser(root, adminName);
            }
        }
    }

    /** Removes what this class attached to a user it created, then the user. */
    private static void deleteUser(IamClient root, String userName) {
        for (AccessKeyMetadata key : root.listAccessKeys(b -> b.userName(userName)).accessKeyMetadata()) {
            root.deleteAccessKey(b -> b.userName(userName).accessKeyId(key.accessKeyId()));
        }
        for (AttachedPolicy policy : root.listAttachedUserPolicies(b -> b.userName(userName)).attachedPolicies()) {
            root.detachUserPolicy(b -> b.userName(userName).policyArn(policy.policyArn()));
        }
        root.deleteUser(b -> b.userName(userName));
    }
}
