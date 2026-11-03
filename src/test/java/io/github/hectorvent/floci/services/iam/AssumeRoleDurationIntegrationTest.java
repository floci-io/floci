package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AssumeRoleDurationIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    @ParameterizedTest
    @CsvSource({"user,3600,7200,false", "user,7200,7200,true", "role,7200,3600,true",
            "role,7200,3601,false", "user,3600,0,true", "token,7200,7200,true",
            "user,43200,43201,false", "user,3600,900,true", "role,7200,0,true"})
    void signedDurationLimits(String callerKind, int maximum, int requested, boolean allowed) throws Exception {
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "duration-", "admin", policy);
             IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                             user.accessKeyId(), user.secretAccessKey()))).build();
             StsClient issuer = client(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            Role role = admin.createRole(b -> b.roleName("duration-" + UUID.randomUUID()).path("/teams/duration/")
                    .maxSessionDuration(maximum).assumeRolePolicyDocument("{\"Statement\":{\"Effect\":\"Allow\","
                            + "\"Principal\":{\"AWS\":\"" + ACCOUNT + "\"},\"Action\":\"sts:AssumeRole\"}}")).role();
            List<Credentials> sessions = new ArrayList<>();
            boolean rolePolicyCreated = false;
            try {
                admin.putRolePolicy(b -> b.roleName(role.roleName()).policyName("assume").policyDocument(policy));
                rolePolicyCreated = true;
                AwsCredentials caller = AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey());
                if (!"user".equals(callerKind)) {
                    Credentials source = "role".equals(callerKind)
                            ? issuer.assumeRole(b -> b.roleArn(role.arn()).roleSessionName("first")
                                    .durationSeconds(3600)).credentials()
                            : issuer.getSessionToken(b -> b.durationSeconds(3600)).credentials();
                    sessions.add(source);
                    caller = AwsSessionCredentials.create(source.accessKeyId(), source.secretAccessKey(),
                            source.sessionToken());
                }
                try (StsClient sts = client(caller)) {
                    AssumeRoleRequest.Builder request = AssumeRoleRequest.builder().roleArn(role.arn())
                            .roleSessionName("duration-check");
                    if (requested != 0) {
                        request.durationSeconds(requested);
                    }
                    Instant before = Instant.now();
                    if (allowed) {
                        Credentials issued = sts.assumeRole(request.build()).credentials();
                        sessions.add(issued);
                        int seconds = requested == 0 ? 3600 : requested;
                        assertFalse(issued.expiration().isBefore(before.plusSeconds(seconds - 1)));
                        assertFalse(issued.expiration().isAfter(Instant.now().plusSeconds(seconds)));
                    } else {
                        StsException error = assertThrows(StsException.class, () -> {
                            sessions.add(sts.assumeRole(request.build()).credentials());
                        });
                        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                        assertEquals(400, error.statusCode());
                    }
                }
            } finally {
                try {
                    for (Credentials session : sessions.reversed()) {
                        iam.unregisterSession(ACCOUNT, session.accessKeyId());
                        assertTrue(iam.resolveCallerArns(session.accessKeyId()).isEmpty());
                    }
                } finally {
                    try {
                        if (rolePolicyCreated) {
                            admin.deleteRolePolicy(b -> b.roleName(role.roleName()).policyName("assume"));
                        }
                    } finally {
                        admin.deleteRole(b -> b.roleName(role.roleName()));
                        assertTrue(iam.findRole(ACCOUNT, role.roleName()).isEmpty());
                    }
                }
            }
        }
    }

    private StsClient client(AwsCredentials credentials) {
        return StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }
}
