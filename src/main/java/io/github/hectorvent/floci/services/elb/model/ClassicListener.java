package io.github.hectorvent.floci.services.elb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Classic (2012-06-01) load balancer listener.
 *
 * <p>Mirrors the {@code Listener} shape of the {@code elasticloadbalancing} 2012-06-01 model:
 * {@code Protocol}, {@code LoadBalancerPort} and {@code InstancePort} are required members,
 * {@code InstanceProtocol} and {@code SSLCertificateId} are optional.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClassicListener {

    private String protocol;
    private Integer loadBalancerPort;
    private String instanceProtocol;
    private Integer instancePort;
    private String sslCertificateId;

    public ClassicListener() {}

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public Integer getLoadBalancerPort() { return loadBalancerPort; }
    public void setLoadBalancerPort(Integer loadBalancerPort) { this.loadBalancerPort = loadBalancerPort; }

    public String getInstanceProtocol() { return instanceProtocol; }
    public void setInstanceProtocol(String instanceProtocol) { this.instanceProtocol = instanceProtocol; }

    public Integer getInstancePort() { return instancePort; }
    public void setInstancePort(Integer instancePort) { this.instancePort = instancePort; }

    public String getSslCertificateId() { return sslCertificateId; }
    public void setSslCertificateId(String sslCertificateId) { this.sslCertificateId = sslCertificateId; }
}
