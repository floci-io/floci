package io.floci.aws.services.organizations;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.Session;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.OrganizationsFixture;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A closed account's credentials under IAM enforcement: the account root key and a session the management account
 * opened in it both work until the account is closed, and are refused before any policy is read once it is.
 *
 * <p>Runs as its own management account and enables no service control policies, so the SCP enforcement this
 * profile turns on never reaches it. As on AWS, a closed account stays in its organization, so the test ends with
 * {@code /_floci/state/reset}. Test classes run one at a time, so the reset clears only what this class and the
 * ones before it created.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class OrganizationsCloseAccountIamIntegrationTest {

    private static final String MANAGEMENT_ACCOUNT = "878787878787";
    private static final String SECRET = "test";

    @TestHTTPResource("/")
    URI endpoint;

    private final OrganizationsFixture organizations = new OrganizationsFixture();
    private String member;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createOrganizationWithAMember() {
        organizations.request(MANAGEMENT_ACCOUNT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        member = organizations.createAccount(MANAGEMENT_ACCOUNT, "member@example.com", "Member");
    }

    @AfterEach
    void resetState() {
        given().post("/_floci/state/reset").then().statusCode(200);
    }

    @Test
    void closingTheAccountRefusesItsRootKeyAndItsSessions() {
        Session session = EnforcementFixtures.assumeRole(endpoint.getPort(), MANAGEMENT_ACCOUNT,
                "arn:aws:iam::" + member + ":role/OrganizationAccountAccessRole");
        AwsCredentials rootKey = AwsBasicCredentials.create(member, SECRET);
        AwsCredentials sessionKey = AwsSessionCredentials.create(session.accessKeyId(), SECRET, session.sessionToken());
        assertEquals(member, callerAccount(rootKey));
        assertEquals(member, callerAccount(sessionKey));

        organizations.request(MANAGEMENT_ACCOUNT, "CloseAccount", "{\"AccountId\":\"" + member + "\"}")
                .post("/").then().statusCode(200);

        assertRefused(rootKey);
        assertRefused(sessionKey);
        assertEquals(MANAGEMENT_ACCOUNT, callerAccount(AwsBasicCredentials.create(MANAGEMENT_ACCOUNT, SECRET)));
    }

    private void assertRefused(AwsCredentials credentials) {
        StsException refused = assertThrows(StsException.class, () -> callerAccount(credentials));
        assertEquals(403, refused.statusCode());
        assertEquals("InvalidClientTokenId", refused.awsErrorDetails().errorCode());
    }

    private String callerAccount(AwsCredentials credentials) {
        try (StsClient sts = AwsTestClients.create(StsClient.builder(), credentials, Region.US_EAST_1, endpoint)) {
            return sts.getCallerIdentity().account();
        }
    }
}
