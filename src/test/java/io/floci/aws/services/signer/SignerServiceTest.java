package io.floci.aws.services.signer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.signer.model.SigningJob;
import io.floci.aws.services.signer.model.SigningProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignerServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CERT_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/abc";
    private static final Integer MAX_PAGE = Integer.valueOf(25);
    private static final String ACCOUNT = "000000000000";
    private static final String PROFILE_ARN =
            "arn:aws:signer:us-east-1:" + ACCOUNT + ":/signing-profiles/MyProfile";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AccountAwareStorageBackend<SigningProfile> profiles;
    private AccountAwareStorageBackend<SigningJob> jobs;
    private SignerService service;
    private SignerService mockSigning;

    @BeforeEach
    void setUp() {
        profiles = AccountAwareStorageBackend.inMemory(ACCOUNT);
        jobs = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new SignerService(profiles, jobs);
        mockSigning = new SignerService(profiles, jobs, true);
    }

    @Test
    void putSigningProfileKeepsPlatformOverridesAndTags() throws Exception {
        SigningProfile created = service.putSigningProfile("MyProfile", profileRequest(), REGION);

        assertEquals(PROFILE_ARN, created.getArn());
        assertTrue(created.getProfileVersion().matches("[0-9a-zA-Z]{10}"));
        assertEquals(PROFILE_ARN + "/" + created.getProfileVersion(), created.getProfileVersionArn());
        assertEquals("AWSLambda-SHA384-ECDSA", created.getPlatformId());
        assertEquals("AWS Lambda", created.getPlatformDisplayName());
        assertEquals("Active", created.getStatus());
        assertEquals("DAYS", created.getSignatureValidityPeriodType());
        assertEquals(135, created.getSignatureValidityPeriodValue().intValue());
        assertEquals("SHA-384", created.getOverrides().path("signingConfiguration")
                .path("hashAlgorithm").asText());
        assertEquals("env", created.getTags().keySet().iterator().next());
    }

    @Test
    void putSigningProfileOnAnExistingNameMintsAVersionAndKeepsTagsItWasNotGiven() throws Exception {
        String firstVersion = service.putSigningProfile("MyProfile", profileRequest(), REGION)
                .getProfileVersion();
        SigningProfile second = service.putSigningProfile("MyProfile",
                objectMapper.readTree("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}"), REGION);

        assertFalse(firstVersion.equals(second.getProfileVersion()));
        assertEquals(1, second.getTags().size());
        assertEquals("test", second.getTags().get("env"));
        assertEquals("DAYS", second.getSignatureValidityPeriodType());
        assertEquals(135, second.getSignatureValidityPeriodValue().intValue());
    }

    @Test
    void putSigningProfileRejectsAnUnknownPlatformAndAnOutOfRangeValidity() throws Exception {
        AwsException platform = assertThrows(AwsException.class, () -> service.putSigningProfile(
                "MyProfile", objectMapper.readTree("{\"platformId\":\"Not-A-Platform\"}"), REGION));
        assertEquals("ValidationException", platform.getErrorCode());

        JsonNode tooLong = objectMapper.readTree("""
                {"platformId":"AWSLambda-SHA384-ECDSA",
                 "signatureValidityPeriod":{"type":"DAYS","value":9999}}
                """);
        AwsException validity = assertThrows(AwsException.class,
                () -> service.putSigningProfile("MyProfile", tooLong, REGION));
        assertEquals("ValidationException", validity.getErrorCode());

        AwsException name = assertThrows(AwsException.class,
                () -> service.putSigningProfile("bad name?", profileRequest(), REGION));
        assertEquals("ValidationException", name.getErrorCode());
    }

    @Test
    void revokeSigningProfileRecordsTheReasonAndRefusesAStaleVersion() throws Exception {
        SigningProfile profile = service.putSigningProfile("MyProfile", profileRequest(), REGION);
        String revoke = "{\"profileVersion\":\"" + profile.getProfileVersion()
                + "\",\"reason\":\"key compromise\",\"effectiveTime\":\"2026-01-01T00:00:00Z\"}";

        SigningProfile revoked = service.revokeSigningProfile("MyProfile",
                objectMapper.readTree(revoke), REGION);

        assertEquals("Revoked", revoked.getStatus());
        assertEquals("key compromise", revoked.getStatusReason());
        assertEquals(1767225600L, revoked.getRevocationEffectiveFrom().longValue());
        assertNotNull(revoked.getRevokedAt());

        JsonNode stale = objectMapper.readTree(
                "{\"profileVersion\":\"aaaaaaaaaa\",\"reason\":\"x\",\"effectiveTime\":\"2026-01-01T00:00:00Z\"}");
        AwsException refused = assertThrows(AwsException.class,
                () -> service.revokeSigningProfile("MyProfile", stale, REGION));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void profilePermissionsMoveTheRevisionAndRequireTheCurrentOneToRemove() throws Exception {
        service.putSigningProfile("MyProfile", profileRequest(), REGION);
        String revisionId = service.addProfilePermission("MyProfile",
                objectMapper.readTree("{\"action\":\"signer:StartSigningJob\","
                        + "\"principal\":\"123456789012\",\"statementId\":\"cross-account\"}"), REGION);

        List<SigningProfile.ProfilePermission> permissions =
                service.listProfilePermissions("MyProfile", REGION);
        assertEquals(1, permissions.size());
        assertEquals("signer:StartSigningJob", permissions.get(0).getAction());
        assertNotNull(revisionId);

        AwsException stale = assertThrows(AwsException.class,
                () -> service.removeProfilePermission("MyProfile", "cross-account", "rev-1", REGION));
        assertEquals("ConflictException", stale.getErrorCode());

        String nextRevision = service.removeProfilePermission(
                "MyProfile", "cross-account", revisionId, REGION);
        assertFalse(revisionId.equals(nextRevision));
        assertTrue(service.listProfilePermissions("MyProfile", REGION).isEmpty());
    }

    @Test
    void aPermissionToSignAPayloadIsGrantedLikeTheOtherActions() throws Exception {
        service.putSigningProfile("MyProfile", profileRequest(), REGION);

        String revisionId = service.addProfilePermission("MyProfile", objectMapper.readTree(
                "{\"action\":\"signer:SignPayload\",\"principal\":\"123456789012\","
                        + "\"statementId\":\"sign-payload\"}"), REGION);

        List<SigningProfile.ProfilePermission> permissions =
                service.listProfilePermissions("MyProfile", REGION);
        assertEquals(1, permissions.size());
        assertEquals("signer:SignPayload", permissions.get(0).getAction());
        assertNotNull(revisionId);

        AwsException refused = assertThrows(AwsException.class,
                () -> service.addProfilePermission("MyProfile", objectMapper.readTree(
                        "{\"action\":\"signer:DeleteSigningProfile\","
                                + "\"principal\":\"123456789012\",\"statementId\":\"bogus\"}"),
                        REGION));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aJobFailsWhenNothingWritesTheSignedObject() throws Exception {
        service.putSigningProfile("MyProfile", profileRequest(), REGION);

        SigningJob job = service.startSigningJob(objectMapper.readTree(jobRequest("token-1")), REGION);

        assertEquals("Failed", job.getStatus());
        assertNull(job.getSignedObjectBucketName());
        assertNull(job.getSignedObjectKey());
        assertNull(job.getSignatureExpiresAt());
        assertTrue(job.getStatusReason().contains("does not sign artifacts"));
        assertEquals(job.getJobId(),
                service.describeSigningJob(job.getJobId(), REGION).getJobId());

        AwsException refused = assertThrows(AwsException.class, () -> service.revokeSignature(
                job.getJobId(), objectMapper.readTree("{\"reason\":\"x\"}"), REGION));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    @Test
    void aConfiguredMockJobSucceedsAndCanBeRevoked() throws Exception {
        SigningProfile profile = mockSigning.putSigningProfile("MyProfile", profileRequest(), REGION);

        SigningJob job = mockSigning.startSigningJob(
                objectMapper.readTree(jobRequest("token-mock")), REGION);

        assertEquals("Succeeded", job.getStatus());
        assertEquals(profile.getProfileVersion(), job.getProfileVersion());
        assertEquals("artifacts", job.getSourceBucketName());
        assertEquals("out/app.zip", job.getSignedObjectKey());
        assertEquals("signed", job.getSignedObjectBucketName());
        assertNotNull(job.getSignatureExpiresAt());

        SigningJob revoked = mockSigning.revokeSignature(job.getJobId(),
                objectMapper.readTree("{\"reason\":\"artifact withdrawn\"}"), REGION);
        assertTrue(revoked.isRevoked());
        assertEquals("artifact withdrawn", revoked.getRevocationReason());
    }

    @Test
    void startSigningJobRefusesARevokedProfileAndAMissingSource() throws Exception {
        SigningProfile profile = service.putSigningProfile("MyProfile", profileRequest(), REGION);
        service.revokeSigningProfile("MyProfile", objectMapper.readTree(
                "{\"profileVersion\":\"" + profile.getProfileVersion()
                        + "\",\"reason\":\"retired\",\"effectiveTime\":\"2026-01-01T00:00:00Z\"}"),
                REGION);

        AwsException revoked = assertThrows(AwsException.class, () -> service.startSigningJob(
                objectMapper.readTree("{\"profileName\":\"MyProfile\",\"source\":{\"s3\":"
                        + "{\"bucketName\":\"a\",\"key\":\"k\",\"version\":\"1\"}},"
                        + "\"destination\":{\"s3\":{\"bucketName\":\"b\"}},"
                        + "\"clientRequestToken\":\"token-revoked\"}"), REGION));
        assertEquals("ValidationException", revoked.getErrorCode());

        service.putSigningProfile("Other", profileRequest(), REGION);
        AwsException noVersion = assertThrows(AwsException.class, () -> service.startSigningJob(
                objectMapper.readTree("{\"profileName\":\"Other\",\"source\":{\"s3\":"
                        + "{\"bucketName\":\"a\",\"key\":\"k\"}},"
                        + "\"destination\":{\"s3\":{\"bucketName\":\"b\"}},"
                        + "\"clientRequestToken\":\"token-no-version\"}"), REGION));
        assertEquals("ValidationException", noVersion.getErrorCode());
    }

    @Test
    void listsPageAndFilterByPlatformAndRegion() throws Exception {
        service.putSigningProfile("Alpha", profileRequest(), REGION);
        service.putSigningProfile("Beta", profileRequest(), REGION);
        service.putSigningProfile("Gamma", objectMapper.readTree(
                "{\"platformId\":\"Notation-OCI-SHA384-ECDSA\"}"), REGION);

        SignerService.Page<SigningProfile> firstPage =
                service.listSigningProfiles(REGION, null, null, false, 2, null);
        assertEquals(2, firstPage.items().size());
        assertEquals("Alpha", firstPage.items().get(0).getProfileName());
        assertNotNull(firstPage.nextToken());

        SignerService.Page<SigningProfile> secondPage =
                service.listSigningProfiles(REGION, null, null, false, 2, firstPage.nextToken());
        assertEquals(1, secondPage.items().size());
        assertEquals("Gamma", secondPage.items().get(0).getProfileName());
        assertNull(secondPage.nextToken());

        SignerService.Page<SigningProfile> byPlatform = service.listSigningProfiles(
                REGION, "Notation-OCI-SHA384-ECDSA", null, false, MAX_PAGE, null);
        assertEquals(1, byPlatform.items().size());

        assertTrue(service.listSigningProfiles("eu-west-1", null, null, false, MAX_PAGE, null)
                .items().isEmpty());
    }

    @Test
    void tagsAreManagedThroughTheSharedTagHandler() throws Exception {
        service.putSigningProfile("MyProfile", profileRequest(), REGION);

        service.tagResource(REGION, PROFILE_ARN, Map.of("team", "platform"));
        Map<String, String> tags = service.listTags(REGION, PROFILE_ARN);
        assertEquals("platform", tags.get("team"));
        assertEquals("test", tags.get("env"));

        service.untagResource(REGION, PROFILE_ARN, List.of("env"));
        assertEquals(Map.of("team", "platform"), service.listTags(REGION, PROFILE_ARN));

        assertEquals("signer", service.serviceKey());
    }

    @Test
    void cancelKeepsTheProfileAsCanceledAndStopsNewJobs() throws Exception {
        SigningProfile active = service.putSigningProfile("MyProfile", profileRequest(), REGION);

        service.cancelSigningProfile("MyProfile", REGION);

        SigningProfile canceled = service.getSigningProfile("MyProfile", REGION);
        assertEquals("Canceled", canceled.getStatus());
        assertEquals(active.getArn(), canceled.getArn());
        assertEquals(active.getProfileVersion(), canceled.getProfileVersion());
        assertTrue(service.listSigningProfiles(REGION, null, null, null, MAX_PAGE, null).items().isEmpty());
        assertEquals(1, service.listSigningProfiles(REGION, null, null, true, MAX_PAGE, null)
                .items().size());
        assertEquals(1, service.listSigningProfiles(REGION, null, List.of("Canceled"), null,
                MAX_PAGE, null).items().size());

        AwsException noJob = assertThrows(AwsException.class,
                () -> service.startSigningJob(objectMapper.readTree(jobRequest("token-canceled")), REGION));
        assertEquals("ValidationException", noJob.getErrorCode());

        // Cancelling again is a no-op, and a put brings the name back as a new active version.
        service.cancelSigningProfile("MyProfile", REGION);
        assertEquals("Canceled", service.getSigningProfile("MyProfile", REGION).getStatus());
        assertEquals("Active", service.putSigningProfile("MyProfile", profileRequest(), REGION)
                .getStatus());
    }

    @Test
    void aRevokedProfileCannotBeCanceledAndForeignArnsAreRefused() throws Exception {
        SigningProfile profile = service.putSigningProfile("MyProfile", profileRequest(), REGION);
        service.revokeSigningProfile("MyProfile", objectMapper.readTree(
                "{\"profileVersion\":\"" + profile.getProfileVersion()
                        + "\",\"reason\":\"retired\",\"effectiveTime\":\"2026-01-01T00:00:00Z\"}"),
                REGION);

        AwsException revoked = assertThrows(AwsException.class,
                () -> service.cancelSigningProfile("MyProfile", REGION));
        assertEquals("ConflictException", revoked.getErrorCode());

        AwsException foreign = assertThrows(AwsException.class, () -> service.listTags(REGION,
                "arn:aws:signer:us-east-1:" + ACCOUNT + ":/signing-jobs/not-a-profile"));
        assertEquals("ValidationException", foreign.getErrorCode());
    }

    private JsonNode profileRequest() throws Exception {
        return objectMapper.readTree("""
                {"platformId":"AWSLambda-SHA384-ECDSA",
                 "signingMaterial":{"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc"},
                 "signatureValidityPeriod":{"type":"DAYS","value":135},
                 "overrides":{"signingConfiguration":{"hashAlgorithm":"SHA-384"},
                              "signingImageFormat":"JSON"},
                 "signingParameters":{"SignatureFormat":"SHA-384"},
                 "tags":{"env":"test"}}
                """);
    }

    @Test
    void rePuttingAProfileKeepsTheMembersTheRequestOmits() throws Exception {
        service.putSigningProfile("MyProfile", profileRequest(), REGION);

        service.putSigningProfile("MyProfile", objectMapper.readTree(
                "{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}"), REGION);

        SigningProfile reread = service.getSigningProfile("MyProfile", REGION);
        assertEquals(CERT_ARN, reread.getSigningMaterialCertificateArn());
        assertEquals("SHA-384", reread.getOverrides().path("signingConfiguration")
                .path("hashAlgorithm").asText());
        assertEquals("SHA-384", reread.getSigningParameters().get("SignatureFormat"));
    }

    @Test
    void tagKeysFollowTheAwsPattern() throws Exception {
        SigningProfile profile = service.putSigningProfile("MyProfile", objectMapper.readTree("""
                {"platformId":"AWSLambda-SHA384-ECDSA",
                 "tags":{"Env1":"a","k8s-app":"b","owner/team:x":"c"}}
                """), REGION);

        assertEquals("a", profile.getTags().get("Env1"));
        assertEquals("b", profile.getTags().get("k8s-app"));

        for (String rejected : new String[] {"Cost Center", "owner@team"}) {
            AwsException e = assertThrows(AwsException.class, () -> service.putSigningProfile(
                    "MyProfile", objectMapper.readTree("{\"platformId\":\"AWSLambda-SHA384-ECDSA\","
                            + "\"tags\":{\"" + rejected + "\":\"x\"}}"), REGION));
            assertEquals("ValidationException", e.getErrorCode(), rejected);
        }
    }

    @Test
    void theClientRequestTokenMakesStartSigningJobIdempotent() throws Exception {
        service.putSigningProfile("MyProfile", profileRequest(), REGION);
        JsonNode request = objectMapper.readTree(jobRequest("token-same"));

        SigningJob first = service.startSigningJob(request, REGION);
        SigningJob retried = service.startSigningJob(request, REGION);

        assertEquals(first.getJobId(), retried.getJobId());
        assertEquals(1, service.listSigningJobs(REGION, null, null, null, MAX_PAGE, null).items().size());

        AwsException missingToken = assertThrows(AwsException.class,
                () -> service.startSigningJob(objectMapper.readTree("""
                        {"profileName":"MyProfile",
                         "source":{"s3":{"bucketName":"artifacts","key":"app.zip","version":"1"}},
                         "destination":{"s3":{"bucketName":"signed"}}}
                        """), REGION));
        assertEquals("ValidationException", missingToken.getErrorCode());
    }

    @Test
    void anArnFromAnotherAccountIsRefused() throws Exception {
        service.putSigningProfile("MyProfile", profileRequest(), REGION);

        AwsException foreign = assertThrows(AwsException.class, () -> service.listTags(REGION,
                "arn:aws:signer:us-east-1:999999999999:/signing-profiles/MyProfile"));

        assertEquals("ResourceNotFoundException", foreign.getErrorCode());
        assertEquals("test", service.listTags(REGION, PROFILE_ARN).get("env"));
    }

    @Test
    void aJobWithoutAValidityPeriodUsesTheDefaultSignerApplies() throws Exception {
        service.putSigningProfile("MyProfile", objectMapper.readTree(
                "{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}"), REGION);

        SigningJob job = mockSigning.startSigningJob(
                objectMapper.readTree(jobRequest("token-default")), REGION);

        assertNotNull(job.getSignatureExpiresAt());
        long days = (job.getSignatureExpiresAt().longValue() - job.getCreatedAt().longValue()) / 86_400L;
        assertTrue(days > 3_000L, "expected the 135 month default, got " + days + " days");
    }

    @Test
    void aJobOnAPlatformWithoutRevocationCannotBeRevoked() throws Exception {
        mockSigning.putSigningProfile("IotProfile", objectMapper.readTree(
                "{\"platformId\":\"AWSIoTDeviceManagement-SHA256-ECDSA\"}"), REGION);
        SigningJob job = mockSigning.startSigningJob(
                objectMapper.readTree(jobRequest("token-iot", "IotProfile")), REGION);

        assertNull(job.getSignatureExpiresAt());
        AwsException refused = assertThrows(AwsException.class, () -> mockSigning.revokeSignature(
                job.getJobId(), objectMapper.readTree("{\"reason\":\"x\"}"), REGION));
        assertEquals("ValidationException", refused.getErrorCode());
    }

    private static String jobRequest(String token) {
        return jobRequest(token, "MyProfile");
    }

    private static String jobRequest(String token, String profileName) {
        return "{\"profileName\":\"" + profileName + "\","
                + "\"source\":{\"s3\":{\"bucketName\":\"artifacts\",\"key\":\"app.zip\",\"version\":\"1\"}},"
                + "\"destination\":{\"s3\":{\"bucketName\":\"signed\",\"prefix\":\"out/\"}},"
                + "\"clientRequestToken\":\"" + token + "\"}";
    }
}
