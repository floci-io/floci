package io.floci.aws.services.iam;

import io.floci.aws.services.eks.EksOidcService;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sts:RoleSessionName} in an {@code AssumeRoleWithWebIdentity} trust policy, which the
 * Service Authorization Reference lists for the action and which holds the {@code RoleSessionName}
 * the request passes: "Your administrator might require that you specify your user name as the
 * session name when you assume the role. For more information, see sts:RoleSessionName."
 */
@QuarkusTest
class WebIdentityRoleSessionNameIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String SUBJECT = "system:serviceaccount:default:app";

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService iamService;

    @Inject
    EksOidcService oidc;

    private final List<String> sessions = new ArrayList<>();
    private final List<String> roles = new ArrayList<>();
    private String keyName;
    private String issuer;
    private String providerArn;
    private IamClient iam;
    private StsClient sts;

    @BeforeEach
    void createIssuer() {
        keyName = "web-session-name-" + UUID.randomUUID();
        issuer = oidc.newIssuerUrl("us-east-1");
        providerArn = "arn:aws:iam::" + ACCOUNT + ":oidc-provider/" + issuer.substring("https://".length());
        iam = AwsTestClients.create(IamClient.builder(), ACCOUNT, Region.US_EAST_1, endpoint);
        sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(AnonymousCredentialsProvider.create()).build();
    }

    @AfterEach
    void cleanUp() {
        try {
            for (String session : sessions) {
                iamService.unregisterSession(ACCOUNT, session);
            }
            for (String role : roles) {
                iam.deleteRole(b -> b.roleName(role));
            }
            oidc.deleteKey(keyName);
        } finally {
            try {
                sts.close();
            } finally {
                iam.close();
            }
        }
    }

    private Role role(String condition) {
        String name = "web-session-name-" + UUID.randomUUID().toString().substring(0, 8);
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\""
                + providerArn + "\"},\"Action\":\"sts:AssumeRoleWithWebIdentity\""
                + (condition == null ? "" : ",\"Condition\":" + condition) + "}]}";
        Role role = iam.createRole(b -> b.roleName(name).assumeRolePolicyDocument(trust)
                .tags(tag -> tag.key("team").value("federated"))).role();
        roles.add(name);
        return role;
    }

    private AssumeRoleWithWebIdentityResponse assume(Role role, String sessionName) {
        String token = oidc.mintServiceAccountToken(keyName, issuer, "default", "app", "sts.amazonaws.com", 3600);
        AssumeRoleWithWebIdentityResponse response = sts.assumeRoleWithWebIdentity(b -> b.roleArn(role.arn())
                .roleSessionName(sessionName).webIdentityToken(token));
        sessions.add(response.credentials().accessKeyId());
        return response;
    }

    private String sessionNameCondition(String sessionName) {
        return "{\"StringEquals\":{\"" + issuer.substring("https://".length()) + ":sub\":\"" + SUBJECT
                + "\",\"sts:RoleSessionName\":\"" + sessionName + "\"}}";
    }

    @Test
    void trustPolicyRequiringTheSessionNameAllowsTheRequestThatPassesIt() {
        Role role = role(sessionNameCondition("deploy-42"));

        AssumeRoleWithWebIdentityResponse response = assume(role, "deploy-42");

        assertTrue(response.assumedRoleUser().arn().endsWith("/" + role.roleName() + "/deploy-42"),
                response.assumedRoleUser().arn());
        FederatedSessionAssertions.assertIdentity(endpoint, iamService, role, "deploy-42", response);
    }

    @Test
    void trustPolicyRequiringTheSessionNameRefusesAnotherName() {
        Role role = role(sessionNameCondition("deploy-42"));

        StsException error = assertThrows(StsException.class, () -> assume(role, "deploy-43"));

        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
        assertEquals(403, error.statusCode());
    }

    @Test
    void trustPolicyWithoutTheConditionAcceptsAnySessionName() {
        Role role = role(null);

        AssumeRoleWithWebIdentityResponse response = assume(role, "anything-goes");

        FederatedSessionAssertions.assertIdentity(endpoint, iamService, role, "anything-goes", response);
    }
}
