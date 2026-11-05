package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Certificate;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenerCertificatesResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.InvalidConfigurationRequestException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.OperationNotPermittedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ELBv2 default listener certificates")
class ElbV2DefaultCertificateTest {

    private static final String SSL_POLICY = "ELBSecurityPolicy-2016-08";

    @Test
    @DisplayName("HTTPS needs a default before listener creation or protocol replacement")
    void missingHttpsDefaultDoesNotPublishOrChangeAListener() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Local certificate and listener fixtures");
        try (AcmClient acm = TestFixtures.acmClient();
             ElasticLoadBalancingV2Client elb = TestFixtures.elbV2Client()) {
            String certificate = acm.requestCertificate(b -> b.domainName("required.example.test").validationMethod("DNS"))
                    .certificateArn();
            String loadBalancer = null;
            try {
                loadBalancer = elb.createLoadBalancer(b -> b.name(TestFixtures.uniqueName("sdk-https-default"))
                        .type("application").subnets(TestFixtures.defaultVpcSubnets()))
                        .loadBalancers().get(0).loadBalancerArn();
                String lbArn = loadBalancer;
                InvalidConfigurationRequestException creation = assertThrows(InvalidConfigurationRequestException.class,
                        () -> createListener(elb, lbArn, "HTTPS", 443, null));
                assertThat(creation.statusCode()).isEqualTo(400);
                assertThat(elb.describeListeners(b -> b.loadBalancerArn(lbArn)).listeners()).isEmpty();
                String https = createListener(elb, lbArn, "HTTPS", 443, certificate);
                elb.modifyListener(b -> b.listenerArn(https).port(444).certificates(List.of()));
                assertThat(certificates(elb, https)).containsExactly(certificate);
                String http = createListener(elb, lbArn, "HTTP", 8080, null);
                InvalidConfigurationRequestException modification = assertThrows(InvalidConfigurationRequestException.class,
                        () -> elb.modifyListener(b -> b.listenerArn(http).protocol("HTTPS").port(8443)
                                .sslPolicy(SSL_POLICY)));
                assertThat(modification.statusCode()).isEqualTo(400);
                assertThat(modification.getMessage()).contains("default certificate");
                Listener unchanged = elb.describeListeners(b -> b.listenerArns(http)).listeners().get(0);
                assertThat(unchanged.protocolAsString()).isEqualTo("HTTP");
                assertThat(unchanged.port()).isEqualTo(8080);
                assertThat(unchanged.certificates()).isEmpty();
                assertThat(unchanged.hasCertificates()).isFalse();
                elb.modifyListener(b -> b.listenerArn(http).protocol("HTTPS").port(8443)
                        .sslPolicy(SSL_POLICY)
                        .certificates(Certificate.builder().certificateArn(certificate).build()));
                assertThat(certificates(elb, http)).containsExactly(certificate);
            } finally {
                String ownedLoadBalancer = loadBalancer;
                assertAll("Delete listeners before their certificate",
                        () -> { if (ownedLoadBalancer != null) { elb.deleteLoadBalancer(b -> b.loadBalancerArn(ownedLoadBalancer)); } },
                        () -> acm.deleteCertificate(b -> b.certificateArn(certificate)));
            }
        }
    }

    private static String createListener(ElasticLoadBalancingV2Client elb, String lb, String protocol,
                                         int port, String certificate) {
        return elb.createListener(b -> {
            b.loadBalancerArn(lb).protocol(protocol).port(port).defaultActions(a -> a.type("fixed-response")
                    .fixedResponseConfig(f -> f.statusCode("200").contentType("text/plain").messageBody("ok")));
            if (certificate != null) {
                b.certificates(Certificate.builder().certificateArn(certificate).build());
            }
        }).listeners().get(0).listenerArn();
    }

    @Test
    @DisplayName("default removal is refused and ModifyListener can replace it")
    void defaultRemovalIsRefusedAndModifyCanReplaceIt() {
        Assumptions.assumeFalse(TestFixtures.isRealAws(), "Floci-only: requested ACM certificates are issued immediately");
        try (AcmClient acm = TestFixtures.acmClient();
             ElasticLoadBalancingV2Client elb = TestFixtures.elbV2Client()) {
            String first = null;
            String additional = null;
            String loadBalancer = null;
            try {
                first = acm.requestCertificate(b -> b.domainName("default.example.test").validationMethod("DNS"))
                        .certificateArn();
                additional = acm.requestCertificate(b -> b.domainName("additional.example.test").validationMethod("DNS"))
                        .certificateArn();
                String firstArn = first;
                String additionalArn = additional;
                loadBalancer = elb.createLoadBalancer(b -> b.name(TestFixtures.uniqueName("sdk-default-cert"))
                        .type("application").subnets(TestFixtures.defaultVpcSubnets()))
                        .loadBalancers().get(0).loadBalancerArn();
                String lbArn = loadBalancer;
                String listener = elb.createListener(b -> b.loadBalancerArn(lbArn).port(443).protocol("HTTPS")
                        .certificates(Certificate.builder().certificateArn(firstArn).build())
                        .defaultActions(a -> a.type("fixed-response").fixedResponseConfig(f ->
                                f.statusCode("200").contentType("text/plain").messageBody("ok"))))
                        .listeners().get(0).listenerArn();
                assertThat(certificates(elb, listener)).containsExactly(firstArn);
                assertThatThrownBy(() -> elb.removeListenerCertificates(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(firstArn).build())))
                        .isInstanceOfSatisfying(OperationNotPermittedException.class,
                                error -> assertThat(error.statusCode()).isEqualTo(400));
                assertThat(certificates(elb, listener)).containsExactly(firstArn);
                elb.addListenerCertificates(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(additionalArn).build()));
                assertThat(certificates(elb, listener)).containsExactly(firstArn, additionalArn);
                assertThatThrownBy(() -> elb.removeListenerCertificates(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(firstArn).build())))
                        .isInstanceOf(OperationNotPermittedException.class);
                assertThat(certificates(elb, listener)).containsExactly(firstArn, additionalArn);
                elb.removeListenerCertificates(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(additionalArn).build()));
                assertThat(certificates(elb, listener)).containsExactly(firstArn);
                elb.modifyListener(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(additionalArn).build()));
                assertThat(certificates(elb, listener)).containsExactly(additionalArn);
                assertThatThrownBy(() -> elb.removeListenerCertificates(b -> b.listenerArn(listener)
                        .certificates(Certificate.builder().certificateArn(additionalArn).build())))
                        .isInstanceOf(OperationNotPermittedException.class);
                assertThat(certificates(elb, listener)).containsExactly(additionalArn);
            } finally {
                try {
                    if (loadBalancer != null) {
                        String lbArn = loadBalancer;
                        elb.deleteLoadBalancer(b -> b.loadBalancerArn(lbArn));
                    }
                } finally {
                    try {
                        if (first != null) {
                            String arn = first;
                            acm.deleteCertificate(b -> b.certificateArn(arn));
                        }
                    } finally {
                        if (additional != null) {
                            String arn = additional;
                            acm.deleteCertificate(b -> b.certificateArn(arn));
                        }
                    }
                }
            }
        }
    }

    private static List<String> certificates(ElasticLoadBalancingV2Client client, String listener) {
        DescribeListenerCertificatesResponse response = client.describeListenerCertificates(b -> b.listenerArn(listener));
        assertThat(response.nextMarker()).isNull();
        return response.certificates().stream()
                .map(Certificate::certificateArn).toList();
    }
}
