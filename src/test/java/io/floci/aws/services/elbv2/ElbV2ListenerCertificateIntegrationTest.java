package io.floci.aws.services.elbv2;

import io.floci.aws.services.acm.CertificateGenerator;
import io.floci.aws.services.acm.model.KeyAlgorithm;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener's certificates are resolved against IAM and ACM on the way in.
 * {@code CertificateNotFound} is modeled on {@code CreateListener}, {@code ModifyListener} and
 * {@code AddListenerCertificates} alone, and the reference gives it one sentence and a 400.
 *
 * <p>The default certificate and the certificate list are read apart here too: the member create
 * and modify take is one certificate, the list is what {@code AddListenerCertificates} builds,
 * {@code DescribeListenerCertificates} tells the two apart by {@code IsDefault}, and the
 * certificates in the lists of one load balancer's listeners share a quota of 25.
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
        return requestAcmCertificate("RSA_2048");
    }

    /** An elliptic-curve key where a test wants many certificates rather than one. */
    private static String requestAcmCertificate(String keyAlgorithm) {
        return given().header("Authorization", ACM_AUTH)
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType("application/x-amz-json-1.1")
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
                .body("{\"DomainName\": \"elbv2-" + suffix()
                        + ".example.com\", \"ValidationMethod\": \"DNS\", \"KeyAlgorithm\": \""
                        + keyAlgorithm + "\"}")
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
        return describeListenerCertificates(listenerArn, "CertificateArn");
    }

    /** The {@code IsDefault} flags, in the order the certificates above are named. */
    private static List<String> listenerCertificateDefaults(String listenerArn) {
        return describeListenerCertificates(listenerArn, "IsDefault");
    }

    private static List<String> describeListenerCertificates(String listenerArn, String member) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DescribeListenerCertificates")
                .formParam("ListenerArn", listenerArn)
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getList("DescribeListenerCertificatesResponse"
                        + ".DescribeListenerCertificatesResult.Certificates.member." + member);
    }

    private static ValidatableResponse addCertificates(String listenerArn, List<String> certificateArns) {
        RequestSpecification request = given().header("Authorization", ELB_AUTH)
                .formParam("Action", "AddListenerCertificates")
                .formParam("ListenerArn", listenerArn);
        int member = 1;
        for (String certificateArn : certificateArns) {
            request = request.formParam("Certificates.member." + member + ".CertificateArn", certificateArn);
            member++;
        }
        return request.when().post("/").then();
    }

    private static List<String> listenersOf(String loadBalancerArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DescribeListeners")
                .formParam("LoadBalancerArn", loadBalancerArn)
            .when().post("/").then().statusCode(200)
                .extract().xmlPath().getList("DescribeListenersResponse.DescribeListenersResult"
                        + ".Listeners.member.ListenerArn");
    }

    private static String httpsListenerOn(String loadBalancerArn, int port, String certificateArn) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", String.valueOf(port))
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("Certificates.member.1.CertificateArn", certificateArn)
            .when().post("/").then().statusCode(200)
                .extract().path("CreateListenerResponse.CreateListenerResult.Listeners.member"
                        + ".ListenerArn");
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
    void missingHttpsDefaultIsRefusedWithoutPublishingAListenerAndCanRetry() {
        String certificate = uploadCertificate();
        String lb = loadBalancer();
        Response refused = given().header("Authorization", ELB_AUTH).formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lb).formParam("Protocol", "HTTPS").formParam("Port", "443")
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .when().post("/");
        List<String> listeners = listenersOf(lb);
        assertAll(() -> assertEquals(400, refused.statusCode()),
                () -> assertEquals("InvalidConfigurationRequest", refused.xmlPath().getString("ErrorResponse.Error.Code")),
                () -> assertTrue(listeners.isEmpty(), "rejected request must leave no listener"));
        given().header("Authorization", ELB_AUTH).formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lb).formParam("Protocol", "HTTPS").formParam("Port", "443")
                .formParam("Certificates.member.1.CertificateArn", certificate)
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .when().post("/").then().statusCode(200);
    }

    @Test
    void changingHttpToHttpsWithoutADefaultPreservesTheListenerAndCanRetry() {
        String certificate = uploadCertificate();
        String listener = listenerServing("HTTP", null);
        Response refused = given().header("Authorization", ELB_AUTH).formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listener).formParam("Protocol", "HTTPS").formParam("Port", "444")
                .when().post("/");
        String protocol = listenerField(listener, "Protocol");
        String port = listenerField(listener, "Port");
        assertAll(() -> assertEquals(400, refused.statusCode()),
                () -> assertEquals("InvalidConfigurationRequest", refused.xmlPath().getString("ErrorResponse.Error.Code")),
                () -> assertEquals("HTTP", protocol), () -> assertEquals("443", port),
                () -> assertTrue(listenerCertificates(listener).isEmpty()));
        given().header("Authorization", ELB_AUTH).formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listener).formParam("Protocol", "HTTPS").formParam("Port", "444")
                .formParam("SslPolicy", "ELBSecurityPolicy-2016-08")
                .formParam("Certificates.member.1.CertificateArn", certificate)
                .when().post("/").then().statusCode(200);
        assertEquals("HTTPS", listenerField(listener, "Protocol"));
        assertEquals(List.of(certificate), listenerCertificates(listener));
    }

    @Test
    void anHttpListenerStillNeedsNoCertificate() {
        createListener("HTTP", null).statusCode(200);
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
    void turningAnHttpListenerIntoAnHttpsOneChecksTheSuppliedCertificate() {
        String listenerArn = listenerServing("HTTP", null);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("Protocol", "HTTPS")
                .formParam("SslPolicy", "ELBSecurityPolicy-2016-08")
                .formParam("Certificates.member.1.CertificateArn", MISSING_IAM_CERTIFICATE)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));

        assertEquals("HTTP", listenerField(listenerArn, "Protocol"));
    }

    @Test
    void turningAnHttpListenerIntoAnHttpsOneIsAllowedWhenTheCertificateExists() {
        String certificate = uploadCertificate();
        String listenerArn = listenerServing("HTTP", null);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listenerArn)
                .formParam("Protocol", "HTTPS")
                .formParam("SslPolicy", "ELBSecurityPolicy-2016-08")
                .formParam("Certificates.member.1.CertificateArn", certificate)
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

    @Test
    void createListenerTakesOneDefaultCertificateAndNoMore() {
        // "The default certificate for the listener. You must provide exactly one certificate."
        String first = uploadCertificate();
        String second = uploadCertificate();
        String lb = loadBalancer();

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lb)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", "443")
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("Certificates.member.1.CertificateArn", first)
                .formParam("Certificates.member.2.CertificateArn", second)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("You must provide exactly one certificate."));

        assertTrue(listenersOf(lb).isEmpty(), "a refused create must leave no listener");
    }

    @Test
    void modifyListenerTakesOneDefaultCertificateAndNoMore() {
        String first = uploadCertificate();
        String second = uploadCertificate();
        String listener = listenerServing(first);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", first)
                .formParam("Certificates.member.2.CertificateArn", second)
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        assertEquals(List.of(first), listenerCertificates(listener));
    }

    @Test
    void addListenerCertificatesTakesMoreThanOneCertificateInACall() {
        // The member reads as one certificate per call, while the Application Load Balancers
        // guide adds three in a call of its own, and the guide's example is the one to follow.
        String first = uploadCertificate();
        String second = uploadCertificate();
        String third = uploadCertificate();
        String listener = listenerServing(first);

        addCertificates(listener, List.of(second, third)).statusCode(200);
        assertEquals(List.of(first, second, third), listenerCertificates(listener));

        // "Information about the certificates in the certificate list", which the default is not
        // in and which is not the call's own member: adding a certificate the list already holds
        // answers with the whole list rather than with the one certificate named.
        List<String> answered = addCertificates(listener, List.of(third)).statusCode(200)
                .extract().xmlPath().getList("AddListenerCertificatesResponse"
                        + ".AddListenerCertificatesResult.Certificates.member.CertificateArn");

        assertEquals(List.of(second, third), answered);
    }

    @Test
    void describeListenerCertificatesTellsTheDefaultApartFromTheList() {
        String first = uploadCertificate();
        String second = uploadCertificate();
        String listener = listenerServing(first);
        addCertificates(listener, List.of(second)).statusCode(200);

        assertEquals(List.of(first, second), listenerCertificates(listener));
        assertEquals(List.of("true", "false"), listenerCertificateDefaults(listener));
    }

    @Test
    void aDefaultCertificateTheListHoldsAsWellIsNamedTwice() {
        // "If the default certificate is also in the certificate list, it appears twice in the
        // results (once with IsDefault set to true and once with IsDefault set to false)."
        String first = uploadCertificate();
        String listener = listenerServing(first);
        addCertificates(listener, List.of(first)).statusCode(200);

        assertEquals(List.of(first, first), listenerCertificates(listener));
        assertEquals(List.of("true", "false"), listenerCertificateDefaults(listener));
    }

    @Test
    void aListenerCarriesItsDefaultCertificateAloneAndNoIsDefault() {
        // The listener's own member is "the default certificate for the listener", and IsDefault
        // "is not included in the output when describing a listener".
        String first = uploadCertificate();
        String second = uploadCertificate();
        String listener = listenerServing(first);
        addCertificates(listener, List.of(second)).statusCode(200);

        describeListener(listener)
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member"
                        + ".Certificates.member.CertificateArn", equalTo(first))
                .body(not(containsString("IsDefault")));
    }

    @Test
    void replacingTheDefaultLeavesTheCertificateListStanding() {
        // "Adding the default certificate to the certificate list ensures that this certificate is
        // used with the SNI protocol even if it is replaced as the default certificate."
        String first = uploadCertificate();
        String sni = uploadCertificate();
        String replacement = uploadCertificate();
        String listener = listenerServing(first);
        addCertificates(listener, List.of(sni)).statusCode(200);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", replacement)
            .when().post("/").then().statusCode(200);

        assertEquals(List.of(replacement, sni), listenerCertificates(listener));
        assertEquals(List.of("true", "false"), listenerCertificateDefaults(listener));
    }

    @Test
    void theCertificateQuotaIsSharedByTheListenersAndLeavesTheDefaultsOut() {
        // "Certificates per Application Load Balancer (excluding default certificates): 25", so
        // the listeners of one load balancer share it and a default is not counted, its own
        // certificate list holding it or not.
        String loadBalancer = loadBalancer();
        String listener = httpsListenerOn(loadBalancer, 443, requestAcmCertificate("EC_prime256v1"));
        List<String> filling = new ArrayList<>();
        for (int i = 0; i < ElbV2Service.CERTIFICATES_PER_LOAD_BALANCER; i++) {
            filling.add(requestAcmCertificate("EC_prime256v1"));
        }
        addCertificates(listener, filling).statusCode(200);
        String theDefault = listenerCertificates(listener).getFirst();

        // The default is excluded from the count, so the list can still take it at the ceiling.
        addCertificates(listener, List.of(theDefault)).statusCode(200);
        List<String> flags = listenerCertificateDefaults(listener);
        assertEquals(ElbV2Service.CERTIFICATES_PER_LOAD_BALANCER + 2, flags.size());
        assertEquals("true", flags.getFirst());
        assertEquals("false", flags.getLast());

        String overTheQuota = requestAcmCertificate("EC_prime256v1");
        addCertificates(listener, List.of(overTheQuota)).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("TooManyCertificates"))
                .body("ErrorResponse.Error.Message", equalTo("You've reached the limit on the "
                        + "number of certificates per load balancer."));
        assertFalse(listenerCertificates(listener).contains(overTheQuota),
                "a refused add must leave the certificate list as it was");

        // The second listener shares the load balancer's ceiling rather than carrying its own.
        String second = httpsListenerOn(loadBalancer, 8443, requestAcmCertificate("EC_prime256v1"));
        addCertificates(second, List.of(overTheQuota)).statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("TooManyCertificates"));
        assertEquals(1, listenerCertificates(second).size());
    }

    @Test
    void replacingADefaultTheCertificateListHoldsIsHeldToTheCeilingToo() {
        // A default is not counted while it is the default, so replacing one the certificate list
        // holds as well starts counting it, which is why ModifyListener models TooManyCertificates
        // as the add does.
        String loadBalancer = loadBalancer();
        String theDefault = requestAcmCertificate("EC_prime256v1");
        String listener = httpsListenerOn(loadBalancer, 443, theDefault);
        List<String> filling = new ArrayList<>();
        for (int i = 0; i < ElbV2Service.CERTIFICATES_PER_LOAD_BALANCER; i++) {
            filling.add(requestAcmCertificate("EC_prime256v1"));
        }
        addCertificates(listener, filling).statusCode(200);
        addCertificates(listener, List.of(theDefault)).statusCode(200);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "ModifyListener")
                .formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", requestAcmCertificate("EC_prime256v1"))
            .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("TooManyCertificates"));
        assertEquals(theDefault, listenerCertificates(listener).getFirst(),
                "a refused modify leaves the default as it was");
    }

    @Test
    void removingADefaultTheListHoldsAsWellIsRefusedToo() {
        // "You can't remove the default certificate for a listener" names no exception for a
        // default the certificate list holds as well, so Floci refuses that too.
        String first = uploadCertificate();
        String listener = listenerServing(first);
        addCertificates(listener, List.of(first)).statusCode(200);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "RemoveListenerCertificates")
                .formParam("ListenerArn", listener)
                .formParam("Certificates.member.1.CertificateArn", first)
            .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("OperationNotPermitted"));

        assertEquals(List.of(first, first), listenerCertificates(listener));
    }

}
