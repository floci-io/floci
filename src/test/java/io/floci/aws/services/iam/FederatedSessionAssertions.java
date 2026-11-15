package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithSamlResponse;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.AssumedRoleUser;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class FederatedSessionAssertions {

    private FederatedSessionAssertions() {
    }

    static void assertIdentity(URI endpoint, IamService iam, Role role, String sessionName,
                               AssumeRoleWithWebIdentityResponse response) {
        assertIdentity(endpoint, iam, role, sessionName, response.credentials(), response.assumedRoleUser());
    }

    static void assertIdentity(URI endpoint, IamService iam, Role role, String sessionName,
                               AssumeRoleWithSamlResponse response) {
        assertIdentity(endpoint, iam, role, sessionName, response.credentials(), response.assumedRoleUser());
    }

    private static void assertIdentity(URI endpoint, IamService iam, Role role, String sessionName,
                                       Credentials credentials, AssumedRoleUser assumedRoleUser) {
        GetCallerIdentityResponse identity;
        try (StsClient caller = AwsTestClients.create(StsClient.builder(), AwsSessionCredentials.create(
                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()),
                Region.US_EAST_1, endpoint)) {
            identity = caller.getCallerIdentity();
        }
        assertAll(
                () -> assertEquals(role.roleId() + ":" + sessionName, assumedRoleUser.assumedRoleId()),
                () -> assertEquals(assumedRoleUser.arn(), identity.arn()),
                () -> assertEquals(role.roleId() + ":" + sessionName, identity.userId()),
                () -> assertEquals(Map.of("team", "federated"),
                        iam.resolveCallerArns(credentials.accessKeyId()).orElseThrow().principalTags()));
    }
}
