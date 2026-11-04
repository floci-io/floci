package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Certificate;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ElbV2OptionalResponseFieldsIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final Region REGION = Region.US_EAST_1;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261104/us-east-1/elasticloadbalancing/aws4_request";

    private ElasticLoadBalancingV2Client elb;
    private AcmClient acm;
    private String loadBalancer;
    private String targetGroup;
    private String certificate;

    @BeforeEach
    void createResources() {
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), ACCOUNT, REGION);
        acm = AwsTestClients.create(AcmClient.builder(), ACCOUNT, REGION);
        certificate = acm.requestCertificate(request -> request.domainName("optional-fields.example.test")
                .validationMethod("DNS")).certificateArn();
        loadBalancer = elb.createLoadBalancer(request -> request.name(uniqueName("optional-lb"))
                .type("application").subnets(
                        Ec2Service.defaultSubnetId(REGION.id(), "a"),
                        Ec2Service.defaultSubnetId(REGION.id(), "b")))
                .loadBalancers().getFirst().loadBalancerArn();
        targetGroup = elb.createTargetGroup(request -> request.name(uniqueName("optional-tg"))
                .targetType("lambda")).targetGroups().getFirst().targetGroupArn();
    }

    @AfterEach
    void removeResources() {
        try (ElasticLoadBalancingV2Client ownedElb = elb; AcmClient ownedAcm = acm) {
            assertAll("Remove listeners before their certificate",
                    () -> {
                        if (loadBalancer != null) {
                            ownedElb.deleteLoadBalancer(request -> request.loadBalancerArn(loadBalancer));
                        }
                    },
                    () -> {
                        if (targetGroup != null) {
                            ownedElb.deleteTargetGroup(request -> request.targetGroupArn(targetGroup));
                        }
                    },
                    () -> {
                        if (certificate != null) {
                            ownedAcm.deleteCertificate(request -> request.certificateArn(certificate));
                        }
                    });
        }
    }

    @Test
    void listenerResponsesOmitAbsentCertificatesAndRetainConfiguredCertificates() {
        Listener http = createListener("HTTP", 80);
        assertFalse(http.hasCertificates());
        Listener described = elb.describeListeners(request -> request.listenerArns(http.listenerArn()))
                .listeners().getFirst();
        assertFalse(described.hasCertificates());
        assertFalse(rawDescribe("DescribeListeners", Map.of("ListenerArns.member.1", http.listenerArn()))
                .contains("<Certificates"));
        Listener modified = elb.modifyListener(request -> request.listenerArn(http.listenerArn()).port(81))
                .listeners().getFirst();
        assertFalse(modified.hasCertificates());

        Listener https = createListener("HTTPS", 443);
        assertEquals(List.of(certificate), https.certificates().stream().map(Certificate::certificateArn).toList());
        Listener secure = elb.describeListeners(request -> request.listenerArns(https.listenerArn()))
                .listeners().getFirst();
        assertTrue(secure.hasCertificates());
        assertEquals(List.of(certificate), secure.certificates().stream().map(Certificate::certificateArn).toList());
        String xml = rawDescribe("DescribeListeners", Map.of("ListenerArns.member.1", https.listenerArn()));
        assertTrue(xml.contains("<Certificates>"));
        assertTrue(xml.contains(certificate));
    }

    @Test
    void certificateListsOmitAbsentCertificatesAndRetainConfiguredCertificates() {
        Listener http = createListener("HTTP", 80);
        assertAll("HTTP listener has no certificate list",
                () -> assertFalse(elb.describeListenerCertificates(request -> request.listenerArn(http.listenerArn()))
                        .hasCertificates()),
                () -> assertFalse(rawDescribe("DescribeListenerCertificates", Map.of("ListenerArn", http.listenerArn()))
                        .contains("<Certificates")));

        Listener https = createListener("HTTPS", 443);
        assertEquals(List.of(certificate), elb.describeListenerCertificates(request -> request.listenerArn(https.listenerArn()))
                .certificates().stream().map(Certificate::certificateArn).toList());
        String xml = rawDescribe("DescribeListenerCertificates", Map.of("ListenerArn", https.listenerArn()));
        assertTrue(xml.contains("<Certificates>"));
        assertTrue(xml.contains(certificate));
    }

    @Test
    void everyDescribeResponseOmitsTerminalMarker() {
        assertTrue(elb.describeListeners(request -> request.loadBalancerArn(loadBalancer)).listeners().isEmpty());
        assertNull(elb.describeListeners(request -> request.loadBalancerArn(loadBalancer)).nextMarker());
        assertTerminalXml("DescribeListeners", Map.of("LoadBalancerArn", loadBalancer));
        Listener https = createListener("HTTPS", 443);
        String listener = https.listenerArn();

        assertAll("SDK responses end pagination with an absent marker",
                () -> assertNull(elb.describeLoadBalancers(request -> request.loadBalancerArns(loadBalancer)).nextMarker()),
                () -> assertNull(elb.describeTargetGroups(request -> request.targetGroupArns(targetGroup)).nextMarker()),
                () -> assertNull(elb.describeListeners(request -> request.loadBalancerArn(loadBalancer)).nextMarker()),
                () -> assertNull(elb.describeRules(request -> request.listenerArn(listener)).nextMarker()),
                () -> assertNull(elb.describeAccountLimits().nextMarker()),
                () -> assertNull(elb.describeListenerCertificates(request -> request.listenerArn(listener)).nextMarker()));
        assertAll("Wire responses omit the marker element",
                () -> assertTerminalXml("DescribeLoadBalancers", Map.of("LoadBalancerArns.member.1", loadBalancer)),
                () -> assertTerminalXml("DescribeTargetGroups", Map.of("TargetGroupArns.member.1", targetGroup)),
                () -> assertTerminalXml("DescribeListeners", Map.of("LoadBalancerArn", loadBalancer)),
                () -> assertTerminalXml("DescribeRules", Map.of("ListenerArn", listener)),
                () -> assertTerminalXml("DescribeAccountLimits", Map.of()),
                () -> assertTerminalXml("DescribeListenerCertificates", Map.of("ListenerArn", listener)));
    }

    private Listener createListener(String protocol, int port) {
        return elb.createListener(request -> {
            request.loadBalancerArn(loadBalancer).protocol(protocol).port(port)
                    .defaultActions(action -> action.type("fixed-response").fixedResponseConfig(response ->
                            response.statusCode("200").contentType("text/plain").messageBody("ok")));
            if (protocol.equals("HTTPS")) {
                request.certificates(Certificate.builder().certificateArn(certificate).build());
            }
        }).listeners().getFirst();
    }

    private static void assertTerminalXml(String action, Map<String, String> parameters) {
        String xml = rawDescribe(action, parameters);
        assertFalse(xml.contains("<NextMarker"), xml);
    }

    private static String rawDescribe(String action, Map<String, String> parameters) {
        return given().header("Authorization", AUTH).formParam("Action", action)
                .formParam("Version", "2015-12-01").formParams(parameters)
                .post("/").then().statusCode(200).extract().asString();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
