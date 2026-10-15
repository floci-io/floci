package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener's certificates are resolved against IAM and ACM on the way in.
 * {@code CertificateNotFound} is modeled on {@code CreateListener}, {@code ModifyListener} and
 * {@code AddListenerCertificates} alone, and the reference gives it one sentence and a 400.
 */
@QuarkusTest
class ElbV2ListenerCertificateIntegrationTest {

    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String ACM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/acm/aws4_request";
    private static final String MISSING_IAM_CERTIFICATE =
            "arn:aws:iam::000000000000:server-certificate/never-uploaded";
    private static final String MISSING_ACM_CERTIFICATE = "arn:aws:acm:us-east-1:000000000000"
            + ":certificate/00000000-0000-0000-0000-000000000000";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    /**
     * Every certificate and load balancer this class makes is taken back out again. The account's
     * server certificate quota is 20 and the Quarkus instance is shared, so a certificate left
     * behind here fails an unrelated class's upload rather than anything in this one.
     */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private String uploadCertificate() {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "elbv2.certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
        String name = "elbv2-cert-" + suffix();
        // Registered before the load balancer's undo so it runs after it: a certificate a
        // listener still holds answers DeleteConflict.
        cleanup.register(() -> given().header("Authorization", IAM_AUTH)
                .formParam("Action", "DeleteServerCertificate")
                .formParam("ServerCertificateName", name)
            .when().post("/"));
        return given().header("Authorization", IAM_AUTH)
                .formParam("Action", "UploadServerCertificate")
                .formParam("ServerCertificateName", name)
                .formParam("CertificateBody", pair.certificatePem())
                .formParam("PrivateKey", pair.privateKeyPem())
            .when().post("/").then().statusCode(200)
                .extract().path("UploadServerCertificateResponse.UploadServerCertificateResult"
                        + ".ServerCertificateMetadata.Arn");
    }

    private static String requestAcmCertificate() {
        return given().header("Authorization", ACM_AUTH)
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType("application/x-amz-json-1.1")
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
                .body("{\"DomainName\": \"elbv2-" + suffix()
                        + ".example.com\", \"ValidationMethod\": \"DNS\"}")
            .when().post("/").then().statusCode(200)
                .extract().path("CertificateArn");
    }

    private String loadBalancer() {
        String arn = given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "elbv2-cert-" + suffix())
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
            .when().post("/").then().statusCode(200)
                .extract().path("CreateLoadBalancerResponse.CreateLoadBalancerResult"
                        + ".LoadBalancers.member.LoadBalancerArn");
        // Deleting the load balancer cascades to its listeners, which is what frees the
        // certificate for the undo registered above.
        cleanup.register(() -> given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", arn)
            .when().post("/"));
        return arn;
    }

    private ValidatableResponse createListener(String certificateArn) {
        return createListener("HTTPS", certificateArn);
    }

    private ValidatableResponse createListener(String protocol, String certificateArn) {
        RequestSpecification request = given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancer())
                .formParam("Protocol", protocol)
                .formParam("Port", "443")
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200");
        if (certificateArn != null) {
            request = request.formParam("Certificates.member.1.CertificateArn", certificateArn);
        }
        return request.when().post("/").then();
    }

    private String listenerServing(String certificateArn) {
        return listenerServing("HTTPS", certificateArn);
    }

    private String listenerServing(String protocol, String certificateArn) {
        return createListener(protocol, certificateArn).statusCode(200)
                .extract().path("CreateListenerResponse.CreateListenerResult.Listeners.member"
                        + ".ListenerArn");
    }

    private static ValidatableResponse describeListener(String listenerArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DescribeListeners")
                .formParam("ListenerArns.member.1", listenerArn)
            .when().post("/").then().statusCode(200);
    }

    private static String listenerField(String listenerArn, String field) {
        return describeListener(listenerArn).extract()
                .path("DescribeListenersResponse.DescribeListenersResult.Listeners.member."
                        + field).toString();
    }

    /** Read through {@code getList} so one certificate and several arrive the same shape. */
    private static List<String> listenerCertificates(String listenerArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DescribeListenerCertificates")
                .formParam("ListenerArn", listenerArn)
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getList("DescribeListenerCertificatesResponse"
                        + ".DescribeListenerCertificatesResult.Certificates.member.CertificateArn");
    }

    @Test
    void createListenerRejectsAnIamCertificateThatDoesNotExist() {
        createListener(MISSING_IAM_CERTIFICATE)
            .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"))
                .body("ErrorResponse.Error.Message",
                        equalTo("The specified certificate does not exist."));
    }

    @Test
    void createListenerRejectsAnAcmCertificateThatDoesNotExist() {
        createListener(MISSING_ACM_CERTIFICATE)
            .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void createListenerRejectsSomethingThatNamesNoCertificateAtAll() {
        createListener("not-an-arn")
            .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
        // An ARN of a service that serves no certificates names one no better.
        createListener("arn:aws:s3:::some-bucket")
            .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void createListenerAcceptsAnUploadedIamCertificate() {
        String certificateArn = uploadCertificate();

        String listenerArn = listenerServing(certificateArn);

        assertEquals(List.of(certificateArn), listenerCertificates(listenerArn));
    }

    @Test
    void createListenerAcceptsAnAcmCertificate() {
        createListener(requestAcmCertificate()).statusCode(200);
    }

    @Test
    void createListenerWithNoCertificateIsUntouched() {
        // Nothing to resolve, so the check has nothing to say: a listener may carry none.
        createListener(null).statusCode(200);
    }

    @Test
    void aTlsListenerIsHeldToTheSameCheckAsAnHttpsOne() {
        // The reference brackets the member for HTTPS and TLS, so both, not HTTPS alone.
        createListener("TLS", MISSING_IAM_CERTIFICATE)
            .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void aListenerOfAProtocolWithNoCertificatesIsLeftAlone() {
        // The member is bracketed "[HTTPS and TLS listeners]", so a certificate on an HTTP
        // listener is a configuration the reference does not describe. What AWS answers there is
        // not CertificateNotFound on any evidence to hand, so Floci does not invent one: this
        // pins the scope of the check rather than endorsing the request.
        createListener("HTTP", MISSING_IAM_CERTIFICATE).statusCode(200);
    }

    @Test
    void modifyListenerRejectsAMissingCertificateAndKeepsTheOneItHad() {
        String certificateArn = uploadCertificate();
        String listenerArn = listenerServing(certificateArn);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", MISSING_IAM_CERTIFICATE)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));

        // The refusal has to land before the write, or the listener is left serving a
        // certificate the account does not have.
        assertEquals(List.of(certificateArn), listenerCertificates(listenerArn));
    }

    @Test
    void addListenerCertificatesRejectsAMissingCertificateAndAddsNothing() {
        String certificateArn = uploadCertificate();
        String listenerArn = listenerServing(certificateArn);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "AddListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", MISSING_IAM_CERTIFICATE)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));

        assertEquals(List.of(certificateArn), listenerCertificates(listenerArn));
    }

    @Test
    void createListenerRejectsABlankCertificateArn() {
        // An empty value in the list names a certificate no better than "not-an-arn" does, and
        // letting it through would leave the listener holding something it cannot serve.
        createListener("").statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void addListenerCertificatesRejectsABlankCertificateArnAndAddsNothing() {
        String certificateArn = uploadCertificate();
        String listenerArn = listenerServing(certificateArn);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "AddListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", "")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));

        assertEquals(List.of(certificateArn), listenerCertificates(listenerArn));
    }

    @Test
    void aRefusedModifyListenerLeavesThePortAndProtocolAsTheyWere() {
        String certificateArn = uploadCertificate();
        String listenerArn = listenerServing(certificateArn);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("Port", "8443")
                .formParam("Protocol", "TLS")
                .formParam("Certificates.member.1.CertificateArn", MISSING_IAM_CERTIFICATE)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));

        // A refused request must change nothing. The listener being modified is the stored one,
        // so a check that ran after the first setter would answer 400 over a listener already
        // moved to the new port, which no later read would put back.
        assertEquals("443", listenerField(listenerArn, "Port"));
        assertEquals("HTTPS", listenerField(listenerArn, "Protocol"));
        assertEquals(List.of(certificateArn), listenerCertificates(listenerArn));
    }

    @Test
    void turningAnHttpListenerIntoAnHttpsOneChecksTheCertificateItAlreadyHeld() {
        // An HTTP listener may carry a certificate nothing resolves, because the check does not
        // cover its protocol. Serving it is what the check covers, and the request that starts
        // serving it names no certificate of its own, so the one on file is the one to resolve.
        String listenerArn = listenerServing("HTTP", MISSING_IAM_CERTIFICATE);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("Protocol", "HTTPS")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));

        assertEquals("HTTP", listenerField(listenerArn, "Protocol"));
    }

    @Test
    void turningAnHttpListenerIntoAnHttpsOneIsAllowedWhenTheCertificateExists() {
        String listenerArn = listenerServing("HTTP", uploadCertificate());

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("Protocol", "HTTPS")
            .when().post("/").then().statusCode(200);

        assertEquals("HTTPS", listenerField(listenerArn, "Protocol"));
    }

    @Test
    void addListenerCertificatesAcceptsASecondUploadedCertificate() {
        String first = uploadCertificate();
        String second = uploadCertificate();
        String listenerArn = listenerServing(first);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "AddListenerCertificates")
                .formParam("ListenerArn", listenerArn)
                .formParam("Certificates.member.1.CertificateArn", second)
            .when().post("/").then().statusCode(200);

        assertTrue(listenerCertificates(listenerArn).containsAll(List.of(first, second)),
                "both certificates should be on the listener");
    }
    @Test
    void removingTheOnlyDefaultCertificateReturnsOperationNotPermitted() {
        String certificate = uploadCertificate();
        String listener = listenerServing(certificate);
        assertEquals(List.of(certificate), listenerCertificates(listener));
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "RemoveListenerCertificates").formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", certificate)
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("OperationNotPermitted"));
        assertEquals(List.of(certificate), listenerCertificates(listener));
    }

    @Test
    void defaultRemovalKeepsAdditionalCertificatesAndModifyCanReplaceTheDefault() {
        String first = uploadCertificate();
        String additional = uploadCertificate();
        String listener = listenerServing(first);
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "AddListenerCertificates").formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", additional)
                .when().post("/").then().statusCode(200);
        assertEquals(List.of(first, additional), listenerCertificates(listener));
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "RemoveListenerCertificates").formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", first)
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("OperationNotPermitted"));
        assertEquals(List.of(first, additional), listenerCertificates(listener));
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "RemoveListenerCertificates").formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", additional)
                .when().post("/").then().statusCode(200);
        assertEquals(List.of(first), listenerCertificates(listener));
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener").formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", additional)
                .when().post("/").then().statusCode(200);
        assertEquals(List.of(additional), listenerCertificates(listener));
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "RemoveListenerCertificates").formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", additional)
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("OperationNotPermitted"));
        assertEquals(List.of(additional), listenerCertificates(listener));
    }

}
