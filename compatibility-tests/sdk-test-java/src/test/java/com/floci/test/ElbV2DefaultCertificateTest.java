package com.floci.test;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Certificate;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.OperationNotPermittedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ELBv2 default listener certificates")
class ElbV2DefaultCertificateTest {

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
                        .type("application")).loadBalancers().get(0).loadBalancerArn();
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
        return client.describeListenerCertificates(b -> b.listenerArn(listener)).certificates().stream()
                .map(Certificate::certificateArn).toList();
    }
}
