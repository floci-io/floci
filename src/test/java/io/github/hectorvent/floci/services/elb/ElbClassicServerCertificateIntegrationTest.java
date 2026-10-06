package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A Classic listener's {@code SSLCertificateId} is resolved against IAM on the way in, and the
 * certificate it names cannot be deleted while a listener still holds it.
 */
@QuarkusTest
class ElbClassicServerCertificateIntegrationTest {

    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String ACM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/acm/aws4_request";
    private static final String CLASSIC_VERSION = "2012-06-01";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String uploadCertificate(String name) {
        CertificateGenerator.GeneratedCertificate pair = GENERATOR.generateSelfSignedCertificate(
                "elb.certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
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
                .contentType("application/x-amz-json-1.1").config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
                .body("{\"DomainName\": \"elb-" + suffix() + ".example.com\", \"ValidationMethod\": \"DNS\"}")
            .when().post("/").then().statusCode(200)
                .extract().path("CertificateArn");
    }

    private static ValidatableResponse deleteCertificate(String name) {
        return given().header("Authorization", IAM_AUTH)
                .formParam("Action", "DeleteServerCertificate")
                .formParam("ServerCertificateName", name)
            .when().post("/").then();
    }

    private static ValidatableResponse createLoadBalancer(String lbName, String certificateId) {
        return given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", CLASSIC_VERSION)
                .formParam("LoadBalancerName", lbName)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Listeners.member.1.Protocol", "HTTPS")
                .formParam("Listeners.member.1.LoadBalancerPort", "443")
                .formParam("Listeners.member.1.InstanceProtocol", "HTTP")
                .formParam("Listeners.member.1.InstancePort", "8080")
                .formParam("Listeners.member.1.SSLCertificateId", certificateId)
            .when().post("/").then();
    }

    @Test
    void createLoadBalancerRejectsAnIamCertificateThatDoesNotExist() {
        createLoadBalancer("elb-cert-missing-" + suffix(),
                "arn:aws:iam::000000000000:server-certificate/never-uploaded")
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void createLoadBalancerRejectsAnAcmCertificateThatDoesNotExist() {
        createLoadBalancer("elb-acm-missing-" + suffix(),
                "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000")
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void createLoadBalancerListenersRejectsAMissingCertificate() {
        String lb = "elb-cert-listeners-" + suffix();
        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", CLASSIC_VERSION)
                .formParam("LoadBalancerName", lb)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Listeners.member.1.Protocol", "HTTP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", "8080")
            .when().post("/").then().statusCode(200);

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "CreateLoadBalancerListeners")
                .formParam("Version", CLASSIC_VERSION)
                .formParam("LoadBalancerName", lb)
                .formParam("Listeners.member.1.Protocol", "HTTPS")
                .formParam("Listeners.member.1.LoadBalancerPort", "443")
                .formParam("Listeners.member.1.InstancePort", "8080")
                .formParam("Listeners.member.1.SSLCertificateId",
                        "arn:aws:iam::000000000000:server-certificate/never-uploaded")
            .when().post("/").then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void certificateInUseCannotBeDeletedUntilTheListenerIsGone() {
        String certName = "elb-cert-" + suffix();
        String lb = "elb-cert-in-use-" + suffix();
        String arn = uploadCertificate(certName);

        createLoadBalancer(lb, arn).statusCode(200);

        deleteCertificate(certName)
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"))
            .body("ErrorResponse.Error.Message", containsString(lb));

        given().header("Authorization", ELB_AUTH)
                .formParam("Action", "DeleteLoadBalancerListeners")
                .formParam("Version", CLASSIC_VERSION)
                .formParam("LoadBalancerName", lb)
                .formParam("LoadBalancerPorts.member.1", "443")
            .when().post("/").then().statusCode(200);

        deleteCertificate(certName).statusCode(200);
    }

    @Test
    void createLoadBalancerAcceptsAnAcmCertificateOfItsOwnRegionAndAccount() {
        createLoadBalancer("elb-acm-ok-" + suffix(), requestAcmCertificate()).statusCode(200);
    }

    @Test
    void createLoadBalancerRejectsAnAcmCertificateFromAnotherRegion() {
        String otherRegion = requestAcmCertificate().replace(":us-east-1:", ":eu-west-1:");
        createLoadBalancer("elb-acm-region-" + suffix(), otherRegion)
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void createLoadBalancerRejectsAnAcmCertificateArnOfAnotherAccountOrPartition() {
        String arn = requestAcmCertificate();
        createLoadBalancer("elb-acm-acct-" + suffix(), arn.replace(":000000000000:", ":111111111111:"))
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
        createLoadBalancer("elb-acm-part-" + suffix(), arn.replace("arn:aws:", "arn:aws-cn:"))
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("CertificateNotFound"));
    }

    @Test
    void renamedCertificateStillBlocksDeleteWhileAListenerHoldsItsFormerArn() {
        String certName = "elb-cert-old-" + suffix();
        String renamed = certName + "-new";
        String lb = "elb-cert-renamed-" + suffix();
        createLoadBalancer(lb, uploadCertificate(certName)).statusCode(200);

        given().header("Authorization", IAM_AUTH)
                .formParam("Action", "UpdateServerCertificate")
                .formParam("ServerCertificateName", certName)
                .formParam("NewServerCertificateName", renamed)
            .when().post("/").then().statusCode(200);

        deleteCertificate(renamed)
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"))
            .body("ErrorResponse.Error.Message", containsString(lb));
    }
}
