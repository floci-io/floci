package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.CreateKeyGroupResponse;
import software.amazon.awssdk.services.cloudfront.model.CreatePublicKeyResponse;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.S3OriginConfig;
import software.amazon.awssdk.services.cloudfront.model.ViewerProtocolPolicy;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CloudFrontTrustedKeyGroupsTest {

    @Test
    void roundTripsTrustedKeyGroupsWithTheAwsSdk() throws Exception {
        assumeFalse(
                TestFixtures.isRealAws(),
                "The immediate distribution lifecycle in this test is emulator-specific");

        String suffix = TestFixtures.uniqueName("cloudfront-trust");
        String publicKeyId = null;
        String publicKeyEtag = null;
        String keyGroupId = null;
        String keyGroupEtag = null;
        String distributionId = null;
        String encodedPublicKey = rsaPublicKeyPem();

        try (CloudFrontClient cloudFront = TestFixtures.cloudFrontClient()) {
            CreatePublicKeyResponse publicKey = cloudFront.createPublicKey(
                    request -> request.publicKeyConfig(config -> config
                            .callerReference("key-reference-" + suffix)
                            .name("key-" + suffix)
                            .encodedKey(encodedPublicKey)));
            String createdPublicKeyId = publicKey.publicKey().id();
            publicKeyId = createdPublicKeyId;
            publicKeyEtag = publicKey.eTag();

            CreateKeyGroupResponse keyGroup = cloudFront.createKeyGroup(
                    request -> request.keyGroupConfig(config -> config
                            .name("group-" + suffix)
                            .items(createdPublicKeyId)));
            String createdKeyGroupId = keyGroup.keyGroup().id();
            keyGroupId = createdKeyGroupId;
            keyGroupEtag = keyGroup.eTag();
            assertEquals(
                    List.of(createdPublicKeyId),
                    keyGroup.keyGroup().keyGroupConfig().items());
            assertEquals(
                    List.of(createdPublicKeyId),
                    cloudFront.getKeyGroupConfig(
                                    request -> request.id(createdKeyGroupId))
                            .keyGroupConfig()
                            .items());
            var listedKeyGroup =
                    cloudFront.listKeyGroups(request -> request.maxItems("100"))
                            .keyGroupList()
                            .items()
                            .stream()
                            .map(summary -> summary.keyGroup())
                            .filter(group -> createdKeyGroupId.equals(group.id()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    List.of(createdPublicKeyId),
                    listedKeyGroup.keyGroupConfig().items());

            DistributionConfig configuration = DistributionConfig.builder()
                    .callerReference("distribution-reference-" + suffix)
                    .enabled(true)
                    .origins(origins -> origins
                            .quantity(1)
                            .items(origin -> origin
                                    .id("origin")
                                    .domainName(
                                            "signed-content.s3.amazonaws.com")
                                    .s3OriginConfig(
                                            S3OriginConfig.builder()
                                                    .originAccessIdentity("")
                                                    .build())))
                    .defaultCacheBehavior(behavior -> behavior
                            .targetOriginId("origin")
                            .viewerProtocolPolicy(
                                    ViewerProtocolPolicy.ALLOW_ALL)
                            .trustedKeyGroups(groups -> groups
                                    .enabled(true)
                                    .quantity(1)
                                    .items(createdKeyGroupId)))
                    .build();

            CreateDistributionResponse created =
                    cloudFront.createDistribution(
                            request -> request.distributionConfig(configuration));
            String createdDistributionId = created.distribution().id();
            distributionId = createdDistributionId;

            GetDistributionResponse fetched =
                    cloudFront.getDistribution(
                            request -> request.id(createdDistributionId));
            assertTrue(fetched.distribution()
                    .activeTrustedKeyGroups().enabled());
            assertEquals(
                    keyGroupId,
                    fetched.distribution()
                            .activeTrustedKeyGroups()
                            .items()
                            .get(0)
                            .keyGroupId());
            assertEquals(
                    publicKeyId,
                    fetched.distribution()
                            .activeTrustedKeyGroups()
                            .items()
                            .get(0)
                            .keyPairIds()
                            .items()
                            .get(0));
            assertFalse(fetched.distribution()
                    .activeTrustedSigners().enabled());

            GetDistributionConfigResponse fetchedConfig =
                    cloudFront.getDistributionConfig(
                            request -> request.id(createdDistributionId));
            assertEquals(
                    keyGroupId,
                    fetchedConfig.distributionConfig()
                            .defaultCacheBehavior()
                            .trustedKeyGroups()
                            .items()
                            .get(0));
        } finally {
            try (CloudFrontClient cloudFront =
                         TestFixtures.cloudFrontClient()) {
                if (distributionId != null) {
                    String id = distributionId;
                    GetDistributionConfigResponse current =
                            cloudFront.getDistributionConfig(
                                    request -> request.id(id));
                    var disabled = current.distributionConfig()
                            .toBuilder()
                            .enabled(false)
                            .build();
                    var updated = cloudFront.updateDistribution(
                            request -> request
                                    .id(id)
                                    .ifMatch(current.eTag())
                                    .distributionConfig(disabled));
                    cloudFront.deleteDistribution(
                            request -> request
                                    .id(id)
                                    .ifMatch(updated.eTag()));
                }
                if (keyGroupId != null) {
                    String id = keyGroupId;
                    String etag = keyGroupEtag;
                    cloudFront.deleteKeyGroup(
                            request -> request
                                    .id(id)
                                    .ifMatch(etag));
                }
                if (publicKeyId != null) {
                    String id = publicKeyId;
                    String etag = publicKeyEtag;
                    cloudFront.deletePublicKey(
                            request -> request
                                    .id(id)
                                    .ifMatch(etag));
                }
            }
        }
    }

    private static String rsaPublicKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair()
                .getPublic()
                .getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----";
    }
}
