package io.floci.aws.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Listener {

    private String listenerArn;
    private String loadBalancerArn;
    private Integer port;
    private String protocol;
    private String defaultCertificate;
    private List<String> certificateList = new ArrayList<>();
    private String sslPolicy;
    private List<Action> defaultActions = new ArrayList<>();
    private List<String> alpnPolicy = new ArrayList<>();
    private Map<String, String> attributes = new LinkedHashMap<>();

    public Listener() {}

    public String getListenerArn() { return listenerArn; }
    public void setListenerArn(String listenerArn) { this.listenerArn = listenerArn; }

    public String getLoadBalancerArn() { return loadBalancerArn; }
    public void setLoadBalancerArn(String loadBalancerArn) { this.loadBalancerArn = loadBalancerArn; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getDefaultCertificate() { return defaultCertificate; }
    public void setDefaultCertificate(String defaultCertificate) { this.defaultCertificate = defaultCertificate; }

    public List<String> getCertificateList() { return certificateList; }
    public void setCertificateList(List<String> certificateList) { this.certificateList = certificateList; }

    /**
     * The certificates the listener serves, its default first and then its certificate list, a
     * certificate both of them name appearing once. The two are kept apart because the reference
     * keeps them apart: the default is what CreateListener and ModifyListener set, one certificate
     * at a time, the list is what AddListenerCertificates adds to, and DescribeListenerCertificates
     * names a default the list holds as well twice, telling the two apart by IsDefault.
     */
    public List<String> servedCertificates() {
        List<String> served = new ArrayList<>();
        if (defaultCertificate != null) {
            served.add(defaultCertificate);
        }
        for (String certificate : certificateList) {
            if (!served.contains(certificate)) {
                served.add(certificate);
            }
        }
        return served;
    }

    /**
     * Reads a listener saved before the default certificate and the certificate list were kept
     * apart, when both sat in one list with the default at its head. Nothing writes this member.
     */
    @JsonProperty("certificates")
    void setSavedCertificates(List<String> certificates) {
        if (certificates == null || certificates.isEmpty()) {
            return;
        }
        this.defaultCertificate = certificates.getFirst();
        this.certificateList = new ArrayList<>(certificates.subList(1, certificates.size()));
    }

    public String getSslPolicy() { return sslPolicy; }
    public void setSslPolicy(String sslPolicy) { this.sslPolicy = sslPolicy; }

    public List<Action> getDefaultActions() { return defaultActions; }
    public void setDefaultActions(List<Action> defaultActions) { this.defaultActions = defaultActions; }

    public List<String> getAlpnPolicy() { return alpnPolicy; }
    public void setAlpnPolicy(List<String> alpnPolicy) { this.alpnPolicy = alpnPolicy; }

    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
}
