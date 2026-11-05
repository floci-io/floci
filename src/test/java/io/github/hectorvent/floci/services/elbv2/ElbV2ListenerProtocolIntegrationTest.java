package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Certificate;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;

import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.QueryRequests.request;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ElbV2ListenerProtocolIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final Region REGION = Region.US_EAST_1;
    private static final String SSL_POLICY = "ELBSecurityPolicy-TLS13-1-2-2021-06";
    private ElasticLoadBalancingV2Client elb;
    private AcmClient acm;
    private String loadBalancer;
    private String targetGroup;
    private String certificate;

    @BeforeEach
    void createClients() {
        elb = AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), ACCOUNT, REGION);
        acm = AwsTestClients.create(AcmClient.builder(), ACCOUNT, REGION);
        certificate = acm.requestCertificate(request -> request.domainName("listener-protocol.example.test")
                .validationMethod("DNS")).certificateArn();
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
    void tlsCreationDefaultsSslPolicyAndRetainsExplicitReplacement() {
        createLoadBalancer("network");
        Listener created = createListener("TLS", null, certificate);
        Listener described = describe(created.listenerArn());
        Listener modified = elb.modifyListener(request -> request.listenerArn(created.listenerArn()).port(8443))
                .listeners().getFirst();
        assertAll("TLS creation uses the documented API default",
                () -> assertEquals("ELBSecurityPolicy-2016-08", created.sslPolicy()),
                () -> assertEquals("ELBSecurityPolicy-2016-08", described.sslPolicy()),
                () -> assertEquals("ELBSecurityPolicy-2016-08", modified.sslPolicy()),
                () -> assertTrue(describeXml(created.listenerArn())
                        .contains("<SslPolicy>ELBSecurityPolicy-2016-08</SslPolicy>")));

        Listener replaced = elb.modifyListener(request -> request.listenerArn(created.listenerArn())
                .sslPolicy(SSL_POLICY)).listeners().getFirst();
        Listener retained = elb.modifyListener(request -> request.listenerArn(created.listenerArn()).port(9443))
                .listeners().getFirst();
        assertEquals(SSL_POLICY, replaced.sslPolicy());
        assertEquals(SSL_POLICY, retained.sslPolicy());
        assertEquals(List.of(certificate), retained.certificates().stream().map(Certificate::certificateArn).toList());
    }

    @ParameterizedTest
    @CsvSource({"application, HTTPS, HTTP", "network, TLS, TCP"})
    void changingToPlainProtocolDropsSecuritySettingsAndCertificateOwnership(
            String type, String encrypted, String plain) {
        createLoadBalancer(type);
        Listener created = createListener(encrypted, SSL_POLICY, certificate);
        Listener modified = elb.modifyListener(request -> request.listenerArn(created.listenerArn()).protocol(plain))
                .listeners().getFirst();
        Listener described = describe(created.listenerArn());
        String xml = describeXml(created.listenerArn());

        assertAll("The plain listener has no TLS configuration",
                () -> assertEquals(plain, modified.protocolAsString()),
                () -> assertNull(modified.sslPolicy()),
                () -> assertFalse(modified.hasCertificates()),
                () -> assertNull(described.sslPolicy()),
                () -> assertFalse(described.hasCertificates()),
                () -> assertFalse(xml.contains("<SslPolicy"), xml),
                () -> assertFalse(xml.contains("<Certificates"), xml),
                () -> assertFalse(elb.describeListenerCertificates(request -> request.listenerArn(created.listenerArn()))
                        .hasCertificates()),
                () -> assertTrue(acm.describeCertificate(request -> request.certificateArn(certificate))
                        .certificate().inUseBy().isEmpty()));
        acm.deleteCertificate(request -> request.certificateArn(certificate));
        certificate = null;
    }

    @ParameterizedTest
    @CsvSource({"application, HTTP, HTTPS", "network, TCP, TLS"})
    void changingToEncryptedProtocolRequiresBothSettingsBeforeMutation(
            String type, String plain, String encrypted) {
        createLoadBalancer(type);
        Listener created = createListener(plain, null, null);
        String arn = created.listenerArn();
        ElasticLoadBalancingV2Exception missingCertificate = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> elb.modifyListener(request -> request.listenerArn(arn).protocol(encrypted).sslPolicy(SSL_POLICY)));
        assertEquals("InvalidConfigurationRequest", missingCertificate.awsErrorDetails().errorCode());
        assertTrue(missingCertificate.getMessage().contains("certificate"));
        ElasticLoadBalancingV2Exception missingPolicy = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> elb.modifyListener(request -> request.listenerArn(arn).protocol(encrypted)
                        .certificates(Certificate.builder().certificateArn(certificate).build())));
        assertEquals("InvalidConfigurationRequest", missingPolicy.awsErrorDetails().errorCode());
        assertTrue(missingPolicy.getMessage().contains("policy"));

        Listener unchanged = describe(arn);
        assertAll("Refused changes preserve the plain listener",
                () -> assertEquals(plain, unchanged.protocolAsString()),
                () -> assertNull(unchanged.sslPolicy()),
                () -> assertFalse(unchanged.hasCertificates()),
                () -> assertTrue(acm.describeCertificate(request -> request.certificateArn(certificate))
                        .certificate().inUseBy().isEmpty()));
        Listener upgraded = elb.modifyListener(request -> request.listenerArn(arn).protocol(encrypted)
                .sslPolicy(SSL_POLICY).certificates(Certificate.builder().certificateArn(certificate).build()))
                .listeners().getFirst();
        Listener retained = elb.modifyListener(request -> request.listenerArn(arn).port(8443))
                .listeners().getFirst();
        assertEquals(encrypted, upgraded.protocolAsString());
        assertEquals(SSL_POLICY, upgraded.sslPolicy());
        assertEquals(SSL_POLICY, retained.sslPolicy());
        assertEquals(List.of(certificate), retained.certificates().stream().map(Certificate::certificateArn).toList());

        elb.modifyListener(request -> request.listenerArn(arn).protocol(plain));
        ElasticLoadBalancingV2Exception missingSettings = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> elb.modifyListener(request -> request.listenerArn(arn).protocol(encrypted)));
        assertEquals("InvalidConfigurationRequest", missingSettings.awsErrorDetails().errorCode());
        assertEquals(plain, describe(arn).protocolAsString());
    }

    private void createLoadBalancer(String type) {
        loadBalancer = elb.createLoadBalancer(request -> request.name(uniqueName("protocol-lb")).type(type)
                .subnets(Ec2Service.defaultSubnetId(REGION.id(), "a"),
                        Ec2Service.defaultSubnetId(REGION.id(), "b")))
                .loadBalancers().getFirst().loadBalancerArn();
        targetGroup = elb.createTargetGroup(request -> request.name(uniqueName("protocol-tg"))
                .targetType("ip").protocol(type.equals("network") ? "TCP" : "HTTP").port(80)
                .vpcId(Ec2Service.defaultVpcId(REGION.id()))).targetGroups().getFirst().targetGroupArn();
    }

    private Listener createListener(String protocol, String sslPolicy, String certificateArn) {
        return elb.createListener(request -> {
            request.loadBalancerArn(loadBalancer).protocol(protocol).port(443).sslPolicy(sslPolicy)
                    .defaultActions(action -> action.type("forward").targetGroupArn(targetGroup));
            if (certificateArn != null) {
                request.certificates(Certificate.builder().certificateArn(certificateArn).build());
            }
        }).listeners().getFirst();
    }

    private Listener describe(String arn) {
        return elb.describeListeners(request -> request.listenerArns(arn)).listeners().getFirst();
    }

    private static String describeXml(String arn) {
        return request(ACCOUNT, "elasticloadbalancing", "DescribeListeners")
                .formParam("Version", "2015-12-01").formParam("ListenerArns.member.1", arn)
                .post("/").then().statusCode(200).extract().asString();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
