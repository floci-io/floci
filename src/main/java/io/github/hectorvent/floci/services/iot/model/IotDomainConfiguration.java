package io.github.hectorvent.floci.services.iot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An AWS IoT domain configuration. Floci records the configuration; no alternate endpoint is
 * actually served for the domain and no server certificate is validated.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IotDomainConfiguration {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String DOMAIN_TYPE_AWS_MANAGED = "AWS_MANAGED";
    public static final String DOMAIN_TYPE_CUSTOMER_MANAGED = "CUSTOMER_MANAGED";

    private String name;
    private String arn;
    private String domainName;
    private List<String> serverCertificateArns = new ArrayList<>();
    private String validationCertificateArn;
    private String defaultAuthorizerName;
    private Boolean allowAuthorizerOverride;
    private String serviceType = "DATA";
    private String status = STATUS_ENABLED;
    private String domainType = DOMAIN_TYPE_AWS_MANAGED;
    private String securityPolicy;
    private boolean enableOcspCheck;
    private String ocspLambdaArn;
    private String ocspAuthorizedResponderArn;
    private String authenticationType;
    private String applicationProtocol;
    private String clientCertificateCallbackArn;
    private Instant lastStatusChangeDate;
    private Map<String, String> tags = new TreeMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public List<String> getServerCertificateArns() { return serverCertificateArns; }
    public void setServerCertificateArns(List<String> serverCertificateArns) {
        this.serverCertificateArns = serverCertificateArns;
    }

    public String getValidationCertificateArn() { return validationCertificateArn; }
    public void setValidationCertificateArn(String validationCertificateArn) {
        this.validationCertificateArn = validationCertificateArn;
    }

    public String getDefaultAuthorizerName() { return defaultAuthorizerName; }
    public void setDefaultAuthorizerName(String defaultAuthorizerName) {
        this.defaultAuthorizerName = defaultAuthorizerName;
    }

    public Boolean getAllowAuthorizerOverride() { return allowAuthorizerOverride; }
    public void setAllowAuthorizerOverride(Boolean allowAuthorizerOverride) {
        this.allowAuthorizerOverride = allowAuthorizerOverride;
    }

    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDomainType() { return domainType; }
    public void setDomainType(String domainType) { this.domainType = domainType; }

    public String getSecurityPolicy() { return securityPolicy; }
    public void setSecurityPolicy(String securityPolicy) { this.securityPolicy = securityPolicy; }

    public boolean isEnableOcspCheck() { return enableOcspCheck; }
    public void setEnableOcspCheck(boolean enableOcspCheck) { this.enableOcspCheck = enableOcspCheck; }

    public String getOcspLambdaArn() { return ocspLambdaArn; }
    public void setOcspLambdaArn(String ocspLambdaArn) { this.ocspLambdaArn = ocspLambdaArn; }

    public String getOcspAuthorizedResponderArn() { return ocspAuthorizedResponderArn; }
    public void setOcspAuthorizedResponderArn(String ocspAuthorizedResponderArn) {
        this.ocspAuthorizedResponderArn = ocspAuthorizedResponderArn;
    }

    public String getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(String authenticationType) { this.authenticationType = authenticationType; }

    public String getApplicationProtocol() { return applicationProtocol; }
    public void setApplicationProtocol(String applicationProtocol) { this.applicationProtocol = applicationProtocol; }

    public String getClientCertificateCallbackArn() { return clientCertificateCallbackArn; }
    public void setClientCertificateCallbackArn(String clientCertificateCallbackArn) {
        this.clientCertificateCallbackArn = clientCertificateCallbackArn;
    }

    public Instant getLastStatusChangeDate() { return lastStatusChangeDate; }
    public void setLastStatusChangeDate(Instant lastStatusChangeDate) {
        this.lastStatusChangeDate = lastStatusChangeDate;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
