package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.Tag;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("STS session tag conditions with IAM enforcement")
class StsSessionTagAuthorizationTest {

    private static final String TAG_TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":\"*\"},\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}";

    private IamClient iam;
    private DynamoDbClient admin;
    private DynamoDbClient userClient;
    private StsClient userSts;
    private String userName;
    private String table;
    private String roleName;
    private String roleArn;
    private AccessKey accessKey;
    private boolean userPolicyCreated;
    private boolean rolePolicyCreated;

    @BeforeEach
    void setUp() {
        iam = TestFixtures.iamClient();
        admin = TestFixtures.dynamoDbClient();
        String name = TestFixtures.uniqueName("session-tag-auth");
        userName = name;
        iam.createUser(r -> r.userName(userName));
        accessKey = iam.createAccessKey(r -> r.userName(userName)).accessKey();
        AwsBasicCredentials userCredentials = AwsBasicCredentials.create(
                accessKey.accessKeyId(), accessKey.secretAccessKey());
        userClient = DynamoDbClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(userCredentials))
                .build();
        userSts = StsClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(userCredentials))
                .build();

        boolean enforced = false;
        try {
            userClient.listTables();
        } catch (DynamoDbException error) {
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDeniedException");
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");

        table = name + "-table";
        admin.createTable(r -> r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(k -> k.attributeName("PK").keyType(KeyType.HASH))
                .attributeDefinitions(a -> a.attributeName("PK").attributeType(ScalarAttributeType.S)));
        admin.putItem(r -> r.tableName(table).item(item()));

        roleName = name + "-role";
        roleArn = iam.createRole(r -> r.roleName(roleName)
                .assumeRolePolicyDocument(TAG_TRUST_POLICY)).role().arn();
        iam.putUserPolicy(r -> r.userName(userName).policyName("tags").policyDocument(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"],\"Resource\":\"*\"}]}"));
        userPolicyCreated = true;
        iam.putRolePolicy(r -> r.roleName(roleName).policyName("tags").policyDocument(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"dynamodb:GetItem\",\"Resource\":\"*\","
                        + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/team\":\"blue\"}}}]}"));
        rolePolicyCreated = true;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (roleName != null) {
                if (rolePolicyCreated) {
                    iam.deleteRolePolicy(r -> r.roleName(roleName).policyName("tags"));
                }
                iam.deleteRole(r -> r.roleName(roleName));
            }
            if (table != null) {
                admin.deleteTable(r -> r.tableName(table));
            }
        } finally {
            try {
                if (userPolicyCreated) {
                    iam.deleteUserPolicy(r -> r.userName(userName).policyName("tags"));
                }
            } finally {
                try {
                    if (accessKey != null) {
                        iam.deleteAccessKey(r -> r.userName(userName).accessKeyId(accessKey.accessKeyId()));
                    }
                } finally {
                    try {
                        if (userName != null) {
                            iam.deleteUser(r -> r.userName(userName));
                        }
                    } finally {
                        closeQuietly(userClient, userSts, admin, iam);
                    }
                }
            }
        }
    }

    @Test
    void aSessionTagReachesTheAssumedRoleAndAnAbsentTagDeniesIt() {
        DynamoDbClient taggedSession = sessionClient(assumeRole(true));
        assertThat(taggedSession.getItem(r -> r.tableName(table).key(key())).item()).isNotEmpty();

        DynamoDbClient untaggedSession = sessionClient(assumeRole(false));
        assertThatThrownBy(() -> untaggedSession.getItem(r -> r.tableName(table).key(key())))
                .isInstanceOfSatisfying(DynamoDbException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode())
                                .isEqualTo("AccessDeniedException"));

        taggedSession.close();
        untaggedSession.close();
    }

    private Credentials assumeRole(boolean withTags) {
        return userSts.assumeRole(r -> {
                    r.roleArn(roleArn).roleSessionName(TestFixtures.uniqueName("tag-session"));
                    if (withTags) {
                        r.tags(Tag.builder().key("team").value("blue").build());
                    }
                }).credentials();
    }

    private static DynamoDbClient sessionClient(Credentials credentials) {
        return DynamoDbClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(TestFixtures.region())
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken())))
                .build();
    }

    private static Map<String, AttributeValue> key() {
        return Map.of("PK", AttributeValue.fromS("item"));
    }

    private static Map<String, AttributeValue> item() {
        return Map.of("PK", AttributeValue.fromS("item"), "value", AttributeValue.fromS("original"));
    }

    private static void closeQuietly(AutoCloseable... clients) {
        for (AutoCloseable client : clients) {
            if (client == null) {
                continue;
            }
            try {
                client.close();
            } catch (Exception ignored) {
                // A client that is already closed must not hide the test's own failure.
            }
        }
    }
}
