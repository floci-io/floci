package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class StsAssumeRoleDurationIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Inject
    IamService service;

    @Test
    void roleLimitUpdatesApplyToNewSessionsWithoutChangingIssuedCredentials() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        String name = "duration-" + UUID.randomUUID();
        List<String> sessions = new ArrayList<>();
        try (IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
                StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build()) {
            String arn = iam.createRole(request -> request.roleName(name).maxSessionDuration(7200)
                    .assumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[]}"))
                    .role().arn();
            try {
                assertRejected(sts, arn, 7201);
                Instant before = Instant.now();
                AssumeRoleResponse allowed = sts.assumeRole(request -> request.roleArn(arn)
                        .roleSessionName("boundary").durationSeconds(7200));
                sessions.add(allowed.credentials().accessKeyId());
                Instant after = Instant.now();
                assertFalse(allowed.credentials().expiration().isBefore(before.plusSeconds(7200).minusMillis(1)));
                assertFalse(allowed.credentials().expiration().isAfter(after.plusSeconds(7200).plusMillis(1)));
                Instant storedExpiration = service.findSessionExpiration(
                        allowed.credentials().accessKeyId()).orElseThrow();
                iam.updateRole(request -> request.roleName(name).maxSessionDuration(3600));
                assertRejected(sts, arn, 3601);
                assertEquals(storedExpiration, service.findSessionExpiration(
                        allowed.credentials().accessKeyId()).orElseThrow());
                AssumeRoleResponse defaulted = sts.assumeRole(request -> request.roleArn(arn)
                        .roleSessionName("defaulted"));
                sessions.add(defaulted.credentials().accessKeyId());
                assertEquals(3600, iam.getRole(request -> request.roleName(name)).role().maxSessionDuration());
            } finally {
                sessions.forEach(key -> service.unregisterSession("000000000000", key));
                iam.deleteRole(request -> request.roleName(name));
            }
        }
    }

    private static void assertRejected(StsClient sts, String arn, int duration) {
        StsException error = assertThrows(StsException.class, () -> sts.assumeRole(request -> request
                .roleArn(arn).roleSessionName("rejected").durationSeconds(duration)));
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertEquals("The requested DurationSeconds exceeds the MaxSessionDuration set for this role.",
                error.awsErrorDetails().errorMessage());
    }
}
