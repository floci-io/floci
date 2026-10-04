package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ServerCertificateReferenceProvider;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A distribution and the certificate it names cannot end up disagreeing about whether the
 * certificate exists.
 *
 * <p>{@code DeleteServerCertificate} checks the reference providers under its own lock, and
 * CloudFront validates the certificate and then saves the distribution. With the two critical
 * sections unrelated, either order was reachable and so was a third state that neither order
 * should produce: a saved distribution naming a certificate the delete had already removed. That
 * reproduced in well over half of the trials below, so this is a tight assertion rather than a
 * hopeful one.
 */
@QuarkusTest
class CloudFrontCertificateDeleteRaceTest {

    private static final CertificateGenerator GENERATOR = new CertificateGenerator();
    /** The window was wide, so a few dozen trials is already decisive. */
    private static final int TRIALS = 120;

    private final CloudFrontService cloudFrontService;
    private final IamService iamService;

    CloudFrontCertificateDeleteRaceTest(CloudFrontService cloudFrontService, IamService iamService) {
        this.cloudFrontService = cloudFrontService;
        this.iamService = iamService;
    }

    private ServerCertificate uploadCertificate(String name) {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "race.certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        return iamService.uploadServerCertificate(name, "/race/", pair.certificatePem(),
                pair.privateKeyPem(), null, Map.of());
    }

    private Distribution distributionWithCertificate(String certificateId) {
        Origin origin = new Origin();
        origin.setId("race-origin");
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("race-origin");
        behavior.setViewerProtocolPolicy("allow-all");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(false);
        config.setComment("certificate-delete-race");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Map<String, String> viewerCertificate = new LinkedHashMap<>();
        viewerCertificate.put("IAMCertificateId", certificateId);
        viewerCertificate.put("SSLSupportMethod", "sni-only");
        config.setViewerCertificate(viewerCertificate);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    @Test
    void aDistributionIsNeverLeftHoldingADeletedCertificate() throws Exception {
        List<String> dangling = new ArrayList<>();
        List<ServerCertificateReferenceProvider> providers = List.of(cloudFrontService);

        for (int trial = 0; trial < TRIALS; trial++) {
            String name = "race-cert-" + trial + "-" + Long.toString(System.nanoTime(), 36);
            ServerCertificate certificate = uploadCertificate(name);
            String certificateId = certificate.getServerCertificateId();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Distribution> created = new AtomicReference<>();
            AtomicBoolean deleted = new AtomicBoolean(false);
            try {
                List<Future<?>> racers = List.of(pool.submit(() -> {
                    start.await();
                    try {
                        created.set(cloudFrontService.createDistribution(
                                distributionWithCertificate(certificateId), Map.of()));
                    } catch (AwsException e) {
                        // InvalidViewerCertificate when the delete got there first. Any other
                        // code is a real failure, and swallowing it here would read as a lost
                        // race and leave the trial looking clean.
                        if (!"InvalidViewerCertificate".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                    return null;
                }), pool.submit(() -> {
                    start.await();
                    try {
                        iamService.deleteServerCertificate(name, providers);
                        deleted.set(true);
                    } catch (AwsException e) {
                        // DeleteConflict when the distribution was already saved. Any other code
                        // is a real failure and has to reach the future.
                        if (!"DeleteConflict".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                    return null;
                }));
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
                reportAnythingUnexpected(racers);

                if (created.get() != null && deleted.get()
                        && iamService.findServerCertificateById(certificateId).isEmpty()) {
                    dangling.add("trial " + trial + ": distribution " + created.get().getId()
                            + " holds deleted certificate " + certificateId);
                }
            } finally {
                pool.shutdownNow();
                cleanUpTrial(created.get(), name, certificateId);
            }
        }

        assertTrue(dangling.isEmpty(),
                "a distribution was left holding a deleted certificate in "
                        + dangling.size() + " of " + TRIALS + " trials: " + dangling);
    }

    /** Whichever order wins, the pair stays consistent: both present, or neither. */
    @Test
    void oneOfTheTwoOrdersAlwaysWins() throws Exception {
        List<ServerCertificateReferenceProvider> providers = List.of(cloudFrontService);
        int createWon = 0;
        int deleteWon = 0;

        for (int trial = 0; trial < 40; trial++) {
            String name = "race-order-" + trial + "-" + Long.toString(System.nanoTime(), 36);
            String certificateId = uploadCertificate(name).getServerCertificateId();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Distribution> created = new AtomicReference<>();
            AtomicBoolean deleted = new AtomicBoolean(false);
            try {
                List<Future<?>> racers = List.of(pool.submit(() -> {
                    start.await();
                    try {
                        created.set(cloudFrontService.createDistribution(
                                distributionWithCertificate(certificateId), Map.of()));
                    } catch (AwsException e) {
                        // the delete won; anything else is a failure, not an order
                        if (!"InvalidViewerCertificate".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                    return null;
                }), pool.submit(() -> {
                    start.await();
                    try {
                        iamService.deleteServerCertificate(name, providers);
                        deleted.set(true);
                    } catch (AwsException e) {
                        // the create won; anything else is a failure, not an order
                        if (!"DeleteConflict".equals(e.getErrorCode())) {
                            throw e;
                        }
                    }
                    return null;
                }));
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "trial did not finish");
                reportAnythingUnexpected(racers);

                boolean certificateGone =
                        iamService.findServerCertificateById(certificateId).isEmpty();
                if (created.get() != null) {
                    createWon++;
                    assertTrue(!certificateGone,
                            "trial " + trial + ": the distribution exists so the certificate "
                                    + "must too");
                } else {
                    deleteWon++;
                    assertTrue(certificateGone || !deleted.get(),
                            "trial " + trial + ": no distribution, so a successful delete must "
                                    + "have removed the certificate");
                }
            } finally {
                pool.shutdownNow();
                cleanUpTrial(created.get(), name, certificateId);
            }
        }

        assertTrue(createWon + deleteWon == 40, "every trial must resolve one way or the other");
    }

    /**
     * Surfaces anything a racing task threw that it was not written to expect. The throwable
     * otherwise stays in the task's {@link Future}, the trial reads as the other order having
     * won, and the test passes without the race it exists to measure ever having run.
     */
    private static void reportAnythingUnexpected(List<Future<?>> racers) throws Exception {
        for (Future<?> racer : racers) {
            racer.get();
        }
    }

    /**
     * Takes the trial's own fixtures back out of the application, whatever happened above. Other
     * test classes share it, and the account's certificate quota is 20, so a trial that failed
     * part way through must not leave either resource behind. Nothing is asserted and either
     * resource may already be gone, since which one survives is exactly what the race decides.
     */
    private void cleanUpTrial(Distribution created, String name, String certificateId) {
        if (created != null) {
            try {
                cloudFrontService.removeDistribution(created.getId());
            } catch (AwsException ignored) {
                // Already removed, which is one of the outcomes this test measures.
            }
        }
        if (iamService.findServerCertificateById(certificateId).isPresent()) {
            try {
                iamService.deleteServerCertificate(name, List.of());
            } catch (AwsException ignored) {
                // Deleted between that check and here by the racing task.
            }
        }
    }
}
