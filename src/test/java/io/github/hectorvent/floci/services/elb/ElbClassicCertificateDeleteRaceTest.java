package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Classic load balancer and the certificate its listener names cannot end up disagreeing about
 * whether the certificate exists.
 *
 * <p>Same hazard as the CloudFront one: {@code DeleteServerCertificate} checks the reference
 * providers under its own lock, while {@code CreateLoadBalancer} checks the certificate and then
 * commits the load balancer. Driven over the wire here, because the listener's certificate arrives
 * as a request parameter and the check lives behind the handler.
 */
@QuarkusTest
class ElbClassicCertificateDeleteRaceTest {

    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String CLASSIC_VERSION = "2012-06-01";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();
    private static final int TRIALS = 60;

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String uploadCertificate(String name) {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "elb.race.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        return given().header("Authorization", IAM_AUTH)
                .formParam("Action", "UploadServerCertificate")
                .formParam("ServerCertificateName", name)
                .formParam("CertificateBody", pair.certificatePem())
                .formParam("PrivateKey", pair.privateKeyPem())
            .when().post("/").then().statusCode(200)
                .extract().path("UploadServerCertificateResponse.UploadServerCertificateResult"
                        + ".ServerCertificateMetadata.Arn");
    }

    private static int createLoadBalancer(String lbName, String certificateArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", CLASSIC_VERSION)
                .formParam("LoadBalancerName", lbName)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Listeners.member.1.Protocol", "HTTPS")
                .formParam("Listeners.member.1.LoadBalancerPort", "443")
                .formParam("Listeners.member.1.InstanceProtocol", "HTTP")
                .formParam("Listeners.member.1.InstancePort", "8080")
                .formParam("Listeners.member.1.SSLCertificateId", certificateArn)
            .when().post("/").then().extract().statusCode();
    }

    private static int deleteCertificate(String name) {
        return given().header("Authorization", IAM_AUTH)
                .formParam("Action", "DeleteServerCertificate")
                .formParam("ServerCertificateName", name)
            .when().post("/").then().extract().statusCode();
    }

    private static boolean certificateExists(String name) {
        return given().header("Authorization", IAM_AUTH)
                .formParam("Action", "GetServerCertificate")
                .formParam("ServerCertificateName", name)
            .when().post("/").then().extract().statusCode() == 200;
    }

    private static int deleteLoadBalancer(String lbName) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("Version", CLASSIC_VERSION)
                .formParam("LoadBalancerName", lbName)
            .when().post("/").then().extract().statusCode();
    }

    @Test
    void aLoadBalancerIsNeverLeftHoldingADeletedCertificate() throws Exception {
        List<String> dangling = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (int trial = 0; trial < TRIALS; trial++) {
            String name = "elb-race-cert-" + trial + "-" + suffix();
            String arn = uploadCertificate(name);
            String lbName = "elb-race-" + trial + "-" + suffix();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger createStatus = new AtomicInteger();
            AtomicInteger deleteStatus = new AtomicInteger();
            try {
                List<Future<?>> racers = List.of(pool.submit(() -> {
                    start.await();
                    createStatus.set(createLoadBalancer(lbName, arn));
                    return null;
                }), pool.submit(() -> {
                    start.await();
                    deleteStatus.set(deleteCertificate(name));
                    return null;
                }));
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "trial did not finish");
                reportAnythingUnexpected(racers);

                int create = createStatus.get();
                int delete = deleteStatus.get();
                if (create == 200 && delete == 200 && !certificateExists(name)) {
                    dangling.add("trial " + trial + ": load balancer " + lbName
                            + " holds deleted certificate " + name);
                }
                // Exactly one order wins, and the loser says why: the load balancer commits and
                // the delete answers DeleteConflict, or the certificate goes and the listener is
                // refused with CertificateNotFound. Two failures would leave the race untested
                // while the trial still looked clean, so they are collected rather than ignored.
                if (!(create == 200 && delete == 409) && !(create == 400 && delete == 200)) {
                    unresolved.add("trial " + trial + ": create=" + create + " delete=" + delete);
                }
            } finally {
                pool.shutdownNow();
                cleanUpTrial(createStatus.get() == 200, lbName, name);
            }
        }

        assertTrue(dangling.isEmpty(),
                "a load balancer was left holding a deleted certificate in "
                        + dangling.size() + " of " + TRIALS + " trials: " + dangling);
        assertTrue(unresolved.isEmpty(),
                "every trial must resolve as one order winning and the other being told why, "
                        + "these did not: " + unresolved);
    }

    /**
     * Surfaces anything a racing task threw. The throwable otherwise stays in the task's
     * {@link Future}, the status stays at its initial zero, and the trial reads as the other
     * order having won without the race ever having run.
     */
    private static void reportAnythingUnexpected(List<Future<?>> racers) throws Exception {
        for (Future<?> racer : racers) {
            racer.get();
        }
    }

    /**
     * Takes the trial's own fixtures back out of the application, whatever happened above. Other
     * test classes share it, and the account's certificate quota is 20, so a trial that failed
     * part way through must not leave either resource behind. The status codes are ignored: which
     * of the two is still there is exactly what the race decides.
     */
    private static void cleanUpTrial(boolean created, String lbName, String name) {
        if (created) {
            deleteLoadBalancer(lbName);
        }
        if (certificateExists(name)) {
            deleteCertificate(name);
        }
    }
}
