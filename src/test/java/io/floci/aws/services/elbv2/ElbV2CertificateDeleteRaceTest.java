package io.floci.aws.services.elbv2;

import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener and the certificate it names cannot end up disagreeing about whether the certificate
 * exists.
 *
 * <p>The same hazard the Classic suite drives: {@code DeleteServerCertificate} checks the
 * reference providers under IAM's own lock, while a listener write checks the certificate and then
 * records it. Both writes that record one are driven here, over the wire, because the certificate
 * arrives as a request parameter and the check lives behind the handler.
 */
@QuarkusTest
class ElbV2CertificateDeleteRaceTest {

    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String ACM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/acm/aws4_request";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();
    private static final int TRIALS = 30;

    private static String createDefaultCertificate() {
        return given().contentType("application/x-amz-json-1.1")
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
                .header("Authorization", ACM_AUTH)
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .body("{\"DomainName\":\"elbv2.race.test.local\",\"ValidationMethod\":\"DNS\"}")
                .when().post("/").then().statusCode(200).extract().path("CertificateArn");
    }

    private static void deleteDefaultCertificate(String defaultCertificateArn) {
        if (defaultCertificateArn != null) {
            given().contentType("application/x-amz-json-1.1")
                    .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                            .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
                    .header("Authorization", ACM_AUTH)
                    .header("X-Amz-Target", "CertificateManager.DeleteCertificate")
                    .body("{\"CertificateArn\":\"" + defaultCertificateArn + "\"}")
                    .when().post("/").then().statusCode(200);
        }
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String uploadCertificate(String name) {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "elbv2.race.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        return given().header("Authorization", IAM_AUTH)
                .formParam("Action", "UploadServerCertificate")
                .formParam("ServerCertificateName", name)
                .formParam("CertificateBody", pair.certificatePem())
                .formParam("PrivateKey", pair.privateKeyPem())
            .when().post("/").then().statusCode(200)
                .extract().path("UploadServerCertificateResponse.UploadServerCertificateResult"
                        + ".ServerCertificateMetadata.Arn");
    }

    private static String loadBalancer(String name) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
            .when().post("/").then().statusCode(200)
                .extract().path("CreateLoadBalancerResponse.CreateLoadBalancerResult"
                        + ".LoadBalancers.member.LoadBalancerArn");
    }

    /** The stable ACM default is distinct from the IAM certificate raced by AddListenerCertificates. */
    private static String listener(String lbArn, String defaultCertificateArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Certificates.member.1.CertificateArn", defaultCertificateArn)
                .formParam("Port", "443")
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
            .when().post("/").then().statusCode(200)
                .extract().path("CreateListenerResponse.CreateListenerResult.Listeners.member"
                        + ".ListenerArn");
    }

    private static int createListenerWith(String lbArn, String certificateArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", "443")
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("Certificates.member.1.CertificateArn", certificateArn)
            .when().post("/").then().extract().statusCode();
    }

    private static int addListenerCertificate(String listenerArn, String certificateArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "AddListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", certificateArn)
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

    private static void deleteLoadBalancer(String lbArn) {
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", lbArn)
            .when().post("/").then().extract().statusCode();
    }

    @Test
    void addingACertificateNeverLeavesAListenerHoldingADeletedOne() throws Exception {
        String defaultCertificateArn = createDefaultCertificate();
        try {
            race("the listener it was added to", lbArn -> listener(lbArn, defaultCertificateArn),
                    ElbV2CertificateDeleteRaceTest::addListenerCertificate);
        } finally {
            deleteDefaultCertificate(defaultCertificateArn);
        }
    }

    @Test
    void creatingAListenerNeverPublishesOneHoldingADeletedCertificate() throws Exception {
        // The certificate is checked before the listener is built, and the listener is not in the
        // map until it is published, so without the guard the delete sees nothing referring to
        // the certificate and takes it while the listener is on its way in.
        race("the listener it was created with", lbArn -> lbArn,
                ElbV2CertificateDeleteRaceTest::createListenerWith);
    }

    /**
     * Races one listener write against {@code DeleteServerCertificate} {@value #TRIALS} times.
     *
     * @param what how to describe the listener in a failure, since the write decides whether one
     *     already exists.
     * @param prepare whatever the write needs in place first, built from the trial's load balancer
     *     and outside the timed section: the listener for a write that adds to one, the load
     *     balancer itself for the write that creates it.
     * @param write the listener write under test, given what {@code prepare} returned and the
     *     certificate ARN, answering with its status code.
     */
    private void race(String what, Function<String, String> prepare,
                      BiFunction<String, String, Integer> write) throws Exception {
        List<String> dangling = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        for (int trial = 0; trial < TRIALS; trial++) {
            String name = "elbv2-race-cert-" + trial + "-" + suffix();
            String arn = uploadCertificate(name);
            String lbArn = loadBalancer("elbv2-race-" + trial + "-" + suffix());
            String target = prepare.apply(lbArn);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger writeStatus = new AtomicInteger();
            AtomicInteger deleteStatus = new AtomicInteger();
            try {
                List<Future<?>> racers = List.of(pool.submit(() -> {
                    start.await();
                    writeStatus.set(write.apply(target, arn));
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

                int wrote = writeStatus.get();
                int delete = deleteStatus.get();
                if (wrote == 200 && delete == 200 && !certificateExists(name)) {
                    dangling.add("trial " + trial + ": " + what
                            + " holds deleted certificate " + name);
                }
                // Exactly one order wins and the loser says why: the certificate is recorded and
                // the delete answers DeleteConflict, or the certificate goes and the write is
                // refused with CertificateNotFound. Two failures would leave the race untested
                // while the trial still looked clean, so they are collected rather than ignored.
                if (!(wrote == 200 && delete == 409) && !(wrote == 400 && delete == 200)) {
                    unresolved.add("trial " + trial + ": write=" + wrote + " delete=" + delete);
                }
            } finally {
                pool.shutdownNow();
                deleteLoadBalancer(lbArn);
                if (certificateExists(name)) {
                    deleteCertificate(name);
                }
            }
        }

        assertTrue(dangling.isEmpty(), "a listener was left holding a deleted certificate in "
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
}
