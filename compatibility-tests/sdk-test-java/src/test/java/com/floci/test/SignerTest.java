package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.signer.SignerClient;
import software.amazon.awssdk.services.signer.model.DescribeSigningJobResponse;
import software.amazon.awssdk.services.signer.model.Destination;
import software.amazon.awssdk.services.signer.model.GetSigningPlatformResponse;
import software.amazon.awssdk.services.signer.model.GetSigningProfileResponse;
import software.amazon.awssdk.services.signer.model.ListSigningPlatformsResponse;
import software.amazon.awssdk.services.signer.model.ListSigningProfilesResponse;
import software.amazon.awssdk.services.signer.model.PutSigningProfileResponse;
import software.amazon.awssdk.services.signer.model.ResourceNotFoundException;
import software.amazon.awssdk.services.signer.model.S3Destination;
import software.amazon.awssdk.services.signer.model.S3Source;
import software.amazon.awssdk.services.signer.model.SignatureValidityPeriod;
import software.amazon.awssdk.services.signer.model.SigningJob;
import software.amazon.awssdk.services.signer.model.SigningPlatform;
import software.amazon.awssdk.services.signer.model.SigningProfile;
import software.amazon.awssdk.services.signer.model.SigningProfileStatus;
import software.amazon.awssdk.services.signer.model.SigningStatus;
import software.amazon.awssdk.services.signer.model.Source;
import software.amazon.awssdk.services.signer.model.ValidationException;
import software.amazon.awssdk.services.signer.model.ValidityType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AWS Signer")
class SignerTest {

    private static final String PROFILE = "SdkTestProfile";
    private static final String OTHER_PROFILE = "SdkTestNotation";
    private static final String PLATFORM_LAMBDA = "AWSLambda-SHA384-ECDSA";
    private static final String PLATFORM_NOTATION = "Notation-OCI-SHA384-ECDSA";

    private static SignerClient signer;
    private static String jobId;

    @BeforeAll
    static void setup() {
        signer = TestFixtures.signerClient();
    }

    @AfterAll
    static void cleanup() {
        if (signer == null) {
            return;
        }
        for (String profileName : new String[] {PROFILE, OTHER_PROFILE}) {
            try {
                signer.cancelSigningProfile(r -> r.profileName(profileName));
            } catch (RuntimeException e) {
                System.out.println("Signer cleanup skipped " + profileName + ": " + e);
            }
        }
        signer.close();
    }

    @Test
    @DisplayName("PutSigningProfile creates a profile that GetSigningProfile reports")
    void putAndGetSigningProfile() {
        PutSigningProfileResponse created = signer.putSigningProfile(r -> r
                .profileName(PROFILE)
                .platformId(PLATFORM_LAMBDA)
                .signatureValidityPeriod(SignatureValidityPeriod.builder()
                        .type(ValidityType.DAYS)
                        .value(135)
                        .build())
                .tags(Map.of("env", "sdk")));

        assertThat(created.arn()).startsWith("arn:aws:signer:")
                .endsWith(":/signing-profiles/" + PROFILE);
        assertThat(created.profileVersion()).hasSize(10);
        assertThat(created.profileVersionArn()).isEqualTo(
                created.arn() + "/" + created.profileVersion());

        GetSigningProfileResponse profile = signer.getSigningProfile(r -> r.profileName(PROFILE));

        assertThat(profile.profileName()).isEqualTo(PROFILE);
        assertThat(profile.platformId()).isEqualTo(PLATFORM_LAMBDA);
        assertThat(profile.platformDisplayName()).isEqualTo("AWS Lambda");
        assertThat(profile.status()).isEqualTo(SigningProfileStatus.ACTIVE);
        assertThat(profile.signatureValidityPeriod().type()).isEqualTo(ValidityType.DAYS);
        assertThat(profile.signatureValidityPeriod().value()).isEqualTo(135);
        assertThat(profile.tags()).containsEntry("env", "sdk");
    }

    @Test
    @DisplayName("PutSigningProfile on an existing name mints a version and keeps omitted tags")
    void putMintsANewVersion() {
        String firstVersion = signer.putSigningProfile(r -> r
                .profileName(PROFILE)
                .platformId(PLATFORM_LAMBDA)
                .tags(Map.of("env", "sdk")))
                .profileVersion();

        String secondVersion = signer.putSigningProfile(r -> r
                .profileName(PROFILE)
                .platformId(PLATFORM_LAMBDA))
                .profileVersion();

        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(signer.getSigningProfile(r -> r.profileName(PROFILE)).tags())
                .containsEntry("env", "sdk");
    }

    @Test
    @DisplayName("ListSigningProfiles filters by platform and pages with a token")
    void listSigningProfiles() {
        signer.putSigningProfile(r -> r.profileName(PROFILE).platformId(PLATFORM_LAMBDA));
        signer.putSigningProfile(r -> r.profileName(OTHER_PROFILE).platformId(PLATFORM_NOTATION));

        List<SigningProfile> lambdaProfiles = signer.listSigningProfiles(
                r -> r.platformId(PLATFORM_LAMBDA)).profiles();
        assertThat(lambdaProfiles).extracting(SigningProfile::profileName).contains(PROFILE);
        assertThat(lambdaProfiles).allMatch(profile -> PLATFORM_LAMBDA.equals(profile.platformId()));

        ListSigningProfilesResponse firstPage = signer.listSigningProfiles(r -> r.maxResults(1));
        assertThat(firstPage.profiles()).hasSize(1);
        assertThat(firstPage.nextToken()).isNotNull();

        ListSigningProfilesResponse secondPage = signer.listSigningProfiles(
                r -> r.maxResults(1).nextToken(firstPage.nextToken()));
        assertThat(secondPage.profiles()).hasSize(1);
        assertThat(secondPage.profiles().get(0).profileName())
                .isNotEqualTo(firstPage.profiles().get(0).profileName());
    }

    @Test
    @DisplayName("Profile permissions move the revision id the removal requires")
    void profilePermissions() {
        signer.putSigningProfile(r -> r.profileName(PROFILE).platformId(PLATFORM_LAMBDA));

        String revisionId = signer.addProfilePermission(r -> r
                .profileName(PROFILE)
                .action("signer:SignPayload")
                .principal("123456789012")
                .statementId("cross-account"))
                .revisionId();

        assertThat(signer.listProfilePermissions(r -> r.profileName(PROFILE)).permissions())
                .singleElement()
                .satisfies(permission -> {
                    assertThat(permission.action()).isEqualTo("signer:SignPayload");
                    assertThat(permission.principal()).isEqualTo("123456789012");
                    assertThat(permission.statementId()).isEqualTo("cross-account");
                });

        String nextRevisionId = signer.removeProfilePermission(r -> r
                .profileName(PROFILE)
                .statementId("cross-account")
                .revisionId(revisionId)).revisionId();

        assertThat(nextRevisionId).isNotEqualTo(revisionId);
        assertThat(signer.listProfilePermissions(r -> r.profileName(PROFILE)).permissions()).isEmpty();
    }

    @Test
    @DisplayName("A signing job is recorded, described, and can be revoked")
    void signingJobLifecycle() {
        signer.putSigningProfile(r -> r.profileName(PROFILE).platformId(PLATFORM_LAMBDA));

        jobId = signer.startSigningJob(r -> r
                .profileName(PROFILE)
                .source(Source.builder()
                        .s3(S3Source.builder()
                                .bucketName("artifacts")
                                .key("app.zip")
                                .version("1")
                                .build())
                        .build())
                .destination(Destination.builder()
                        .s3(S3Destination.builder()
                                .bucketName("signed")
                                .prefix("out/")
                                .build())
                        .build())
                .clientRequestToken("sdk-token-1"))
                .jobId();

        DescribeSigningJobResponse job = signer.describeSigningJob(r -> r.jobId(jobId));

        assertThat(job.status()).isEqualTo(SigningStatus.FAILED);
        assertThat(job.statusReason()).contains("does not sign artifacts");
        assertThat(job.profileName()).isEqualTo(PROFILE);
        assertThat(job.platformId()).isEqualTo(PLATFORM_LAMBDA);
        assertThat(job.source().s3().bucketName()).isEqualTo("artifacts");
        assertThat(job.source().s3().key()).isEqualTo("app.zip");
        assertThat(job.signedObject()).isNull();
        assertThat(job.createdAt()).isNotNull();
        assertThat(job.signatureExpiresAt()).isNull();
        assertThat(job.revocationRecord()).isNull();

        assertThat(signer.listSigningJobs(r -> r.status(SigningStatus.FAILED)).jobs())
                .extracting(SigningJob::jobId)
                .contains(jobId);

        // A job with no signature has nothing to revoke.
        assertThatThrownBy(() -> signer.revokeSignature(
                r -> r.jobId(jobId).reason("artifact withdrawn")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Revoking a profile stops it accepting new jobs")
    void revokeSigningProfile() {
        String profileVersion = signer.putSigningProfile(r -> r
                .profileName(PROFILE)
                .platformId(PLATFORM_LAMBDA))
                .profileVersion();

        signer.revokeSigningProfile(r -> r
                .profileName(PROFILE)
                .profileVersion(profileVersion)
                .reason("key compromised")
                .effectiveTime(Instant.parse("2026-01-01T00:00:00Z")));

        GetSigningProfileResponse revoked = signer.getSigningProfile(r -> r.profileName(PROFILE));
        assertThat(revoked.status()).isEqualTo(SigningProfileStatus.REVOKED);
        assertThat(revoked.revocationRecord().revocationEffectiveFrom()).isNotNull();

        assertThatThrownBy(() -> signer.startSigningJob(r -> r
                .profileName(PROFILE)
                .source(Source.builder().s3(S3Source.builder()
                        .bucketName("artifacts").key("app.zip").version("1").build()).build())
                .destination(Destination.builder().s3(S3Destination.builder()
                        .bucketName("signed").build()).build())
                .clientRequestToken("sdk-token-revoked")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("ListSigningPlatforms and GetSigningPlatform describe the AWS platforms")
    void signingPlatforms() {
        ListSigningPlatformsResponse platforms = signer.listSigningPlatforms();
        assertThat(platforms.platforms())
                .extracting(SigningPlatform::platformId)
                .contains(PLATFORM_LAMBDA, PLATFORM_NOTATION);

        GetSigningPlatformResponse platform = signer.getSigningPlatform(r -> r.platformId(PLATFORM_LAMBDA));
        assertThat(platform.displayName()).isEqualTo("AWS Lambda");
        assertThat(platform.categoryAsString()).isEqualTo("AWSLambda");
        assertThat(platform.signingConfiguration().hashAlgorithmOptions().allowedValuesAsStrings())
                .contains("SHA-384");
        assertThat(platform.signingImageFormat().defaultFormatAsString()).isEqualTo("JSON");
        assertThat(platform.maxSizeInMB()).isEqualTo(50);
    }

    @Test
    @DisplayName("The profile ARN carries tags for the shared tagging actions")
    void tagsThroughTheResourceArn() {
        String arn = signer.putSigningProfile(r -> r
                .profileName(PROFILE)
                .platformId(PLATFORM_LAMBDA))
                .arn();

        signer.tagResource(r -> r.resourceArn(arn).tags(Map.of("team", "platform")));

        assertThat(signer.listTagsForResource(r -> r.resourceArn(arn)).tags())
                .containsEntry("team", "platform");

        signer.untagResource(r -> r.resourceArn(arn).tagKeys("team"));

        assertThat(signer.listTagsForResource(r -> r.resourceArn(arn)).tags())
                .doesNotContainKey("team");
    }

    @Test
    @DisplayName("A missing profile answers ResourceNotFoundException")
    void missingProfile() {
        assertThatThrownBy(() -> signer.getSigningProfile(r -> r.profileName("SdkTestMissing")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
