package io.floci.aws.services.cloudformation;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Certificate;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.floci.aws.testing.CfnStackWaits.output;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::ElasticLoadBalancingV2::ListenerCertificate}, read back through the AWS SDK's
 * DescribeListenerCertificates: the stack adds the declared certificates to the listener's certificate list beside
 * its default one, an update adds the ones the template gains and removes the ones it drops under the same physical
 * id, a failed update puts them back, a resource moved to another listener leaves the first one once the update
 * commits, and a resource the template drops takes the certificates it added off the listener.
 */
@QuarkusTest
class CloudFormationElbV2ListenerCertificateIntegrationTest {

    private static final String ACM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/acm/aws4_request";

    /** Declared before the stack, so its deletes run after the stack's: a listener still using one refuses it. */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("cfn-listener-certs");

    /**
     * A certificate the listener's list already held when the template declared it was not added by the
     * resource, so it stays on the listener when the resource lets go of it.
     */
    @Test
    void theCertificatesFollowTheTemplateThroughChangeRollbackAndRemoval() {
        String main = certificate();
        String first = certificate();
        String second = certificate();
        String outside = certificate();

        Stack created = stack.create(template(main, "Https", false, first, second));
        String listener = output(created, "Https");
        String extra = output(created, "Extra");
        assertTrue(extra.startsWith(listener + "|"), extra);
        assertEquals(Set.of(main), defaults(listener));
        assertEquals(Set.of(first, second), sni(listener));
        elb().addListenerCertificates(request -> request.listenerArn(listener)
                .certificates(Certificate.builder().certificateArn(outside).build()));

        Stack changed = stack.update(template(main, "Https", false, second, outside), "UPDATE_COMPLETE");
        assertEquals(extra, output(changed, "Extra"));
        assertEquals(Set.of(second, outside), sni(listener));

        stack.update(template(main, "Https", true, first), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(Set.of(second, outside), sni(listener));

        stack.update(template(main, null, false), "UPDATE_COMPLETE");
        assertEquals(Set.of(outside), sni(listener));
        assertEquals(Set.of(main), defaults(listener));
    }

    @Test
    void aResourceMovedToAnotherListenerLeavesTheFirstOnceTheUpdateCommits() {
        String main = certificate();
        String extraCertificate = certificate();

        Stack created = stack.create(template(main, "Https", false, extraCertificate));
        String https = output(created, "Https");
        String other = output(created, "Other");
        assertEquals(Set.of(extraCertificate), sni(https));

        Stack moved = stack.update(template(main, "Other", false, extraCertificate), "UPDATE_COMPLETE");
        assertTrue(output(moved, "Extra").startsWith(other + "|"), output(moved, "Extra"));
        assertEquals(Set.of(), sni(https));
        assertEquals(Set.of(extraCertificate), sni(other));

        stack.update(template(main, "Https", true, extraCertificate), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(Set.of(), sni(https));
        assertEquals(Set.of(extraCertificate), sni(other));
    }

    private ElasticLoadBalancingV2Client elb() {
        return stack.client(ElasticLoadBalancingV2Client.builder());
    }

    private List<Certificate> certificates(String listenerArn) {
        return elb().describeListenerCertificates(request -> request.listenerArn(listenerArn)).certificates();
    }

    /** The listener's certificate list, the certificates it serves by SNI. */
    private Set<String> sni(String listenerArn) {
        return certificates(listenerArn).stream().filter(certificate -> !Boolean.TRUE.equals(certificate.isDefault()))
                .map(Certificate::certificateArn).collect(Collectors.toSet());
    }

    private Set<String> defaults(String listenerArn) {
        return certificates(listenerArn).stream().filter(certificate -> Boolean.TRUE.equals(certificate.isDefault()))
                .map(Certificate::certificateArn).collect(Collectors.toSet());
    }

    /** An ACM certificate, deleted after the test. */
    private String certificate() {
        String arn = acm("RequestCertificate", "{\"DomainName\": \"cfn-certs-" + Long.toString(System.nanoTime(), 36)
                + ".example.com\", \"ValidationMethod\": \"DNS\"}").then().statusCode(200).extract().path("CertificateArn");
        cleanup.register(() -> acm("DeleteCertificate", "{\"CertificateArn\": \"" + arn + "\"}").then().statusCode(200));
        return arn;
    }

    private static Response acm(String action, String body) {
        return given().header("Authorization", ACM_AUTH)
                .header("X-Amz-Target", "CertificateManager." + action)
                .contentType("application/x-amz-json-1.1")
                .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
                .body(body)
            .when().post("/");
    }

    /**
     * A load balancer with two HTTPS listeners whose default certificate is {@code main}, and an {@code Extra}
     * listener certificate resource on the listener {@code on} names, declaring {@code certificates}, or none when
     * {@code on} is null; with a resource that fails after it when {@code laterFails}.
     */
    private static String template(String main, String on, boolean laterFails, String... certificates) {
        String extra = on == null ? "" : """
                ,
                  "Extra": {"Type": "AWS::ElasticLoadBalancingV2::ListenerCertificate", "Properties": {
                    "ListenerArn": {"Ref": "%s"}, "Certificates": [%s]}}%s""".formatted(on,
                Arrays.stream(certificates).map(arn -> "{\"CertificateArn\": \"" + arn + "\"}")
                        .collect(Collectors.joining(", ")),
                laterFails ? CfnStackWaits.failingResourceAfter("Extra") : "");
        String extraOutput = on == null ? "" : ", \"Extra\": {\"Value\": {\"Ref\": \"Extra\"}}";
        return """
                {"Resources": {
                  "Alb": {"Type": "AWS::ElasticLoadBalancingV2::LoadBalancer", "Properties": {
                    "Subnets": ["%s", "%s"]}},
                  "Https": %s,
                  "Other": %s%s
                }, "Outputs": {"Https": {"Value": {"Ref": "Https"}}, "Other": {"Value": {"Ref": "Other"}}%s}}
                """.formatted(Ec2Service.defaultSubnetId("us-east-1", "a"), Ec2Service.defaultSubnetId("us-east-1", "b"),
                listener(443, main), listener(8443, main), extra, extraOutput);
    }

    private static String listener(int port, String certificate) {
        return """
                {"Type": "AWS::ElasticLoadBalancingV2::Listener", "Properties": {
                    "LoadBalancerArn": {"Ref": "Alb"}, "Protocol": "HTTPS", "Port": %d,
                    "Certificates": [{"CertificateArn": "%s"}],
                    "DefaultActions": [{"Type": "fixed-response", "FixedResponseConfig": {"StatusCode": "200"}}]}}"""
                .formatted(port, certificate);
    }
}
