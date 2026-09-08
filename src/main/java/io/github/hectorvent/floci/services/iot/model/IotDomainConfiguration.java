package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A domain configuration as AWS IoT Core reports it through DescribeDomainConfiguration. */
@RegisterForReflection
public class IotDomainConfiguration {
    private String domainConfigurationName;
    private String domainConfigurationArn;
    private String domainName;
    private String serviceType;
    private String domainConfigurationStatus;
    private String domainType;
    private List<ServerCertificateSummary> serverCertificates = new ArrayList<>();
    private String validationCertificateArn;
    private AuthorizerConfig authorizerConfig;
    private TlsConfig tlsConfig;
    private ServerCertificateConfig serverCertificateConfig;
    private String authenticationType;
    private String applicationProtocol;
    private ClientCertificateConfig clientCertificateConfig;
    private Instant lastStatusChangeDate;
    private Map<String, String> tags = new TreeMap<>();

    public String getDomainConfigurationName() { return domainConfigurationName; }
    public void setDomainConfigurationName(String domainConfigurationName) { this.domainConfigurationName = domainConfigurationName; }
    public String getDomainConfigurationArn() { return domainConfigurationArn; }
    public void setDomainConfigurationArn(String domainConfigurationArn) { this.domainConfigurationArn = domainConfigurationArn; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public String getDomainConfigurationStatus() { return domainConfigurationStatus; }
    public void setDomainConfigurationStatus(String domainConfigurationStatus) { this.domainConfigurationStatus = domainConfigurationStatus; }
    public String getDomainType() { return domainType; }
    public void setDomainType(String domainType) { this.domainType = domainType; }
    public List<ServerCertificateSummary> getServerCertificates() { return serverCertificates == null ? List.of() : serverCertificates; }
    public void setServerCertificates(List<ServerCertificateSummary> serverCertificates) {
        this.serverCertificates = serverCertificates == null ? new ArrayList<>() : new ArrayList<>(serverCertificates);
    }
    public String getValidationCertificateArn() { return validationCertificateArn; }
    public void setValidationCertificateArn(String validationCertificateArn) { this.validationCertificateArn = validationCertificateArn; }
    public AuthorizerConfig getAuthorizerConfig() { return authorizerConfig; }
    public void setAuthorizerConfig(AuthorizerConfig authorizerConfig) { this.authorizerConfig = authorizerConfig; }
    public TlsConfig getTlsConfig() { return tlsConfig; }
    public void setTlsConfig(TlsConfig tlsConfig) { this.tlsConfig = tlsConfig; }
    public ServerCertificateConfig getServerCertificateConfig() { return serverCertificateConfig; }
    public void setServerCertificateConfig(ServerCertificateConfig serverCertificateConfig) { this.serverCertificateConfig = serverCertificateConfig; }
    public String getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(String authenticationType) { this.authenticationType = authenticationType; }
    public String getApplicationProtocol() { return applicationProtocol; }
    public void setApplicationProtocol(String applicationProtocol) { this.applicationProtocol = applicationProtocol; }
    public ClientCertificateConfig getClientCertificateConfig() { return clientCertificateConfig; }
    public void setClientCertificateConfig(ClientCertificateConfig clientCertificateConfig) { this.clientCertificateConfig = clientCertificateConfig; }
    public Instant getLastStatusChangeDate() { return lastStatusChangeDate; }
    public void setLastStatusChangeDate(Instant lastStatusChangeDate) { this.lastStatusChangeDate = lastStatusChangeDate; }
    public Map<String, String> getTags() { return tags == null ? Map.of() : tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new TreeMap<>() : new TreeMap<>(tags); }

    @RegisterForReflection
    public record ServerCertificateSummary(String serverCertificateArn, String serverCertificateStatus,
                                           String serverCertificateStatusDetail) {
    }

    @RegisterForReflection
    public record AuthorizerConfig(String defaultAuthorizerName, Boolean allowAuthorizerOverride) {
    }

    @RegisterForReflection
    public record TlsConfig(String securityPolicy) {
    }

    @RegisterForReflection
    public record ServerCertificateConfig(Boolean enableOCSPCheck, String ocspLambdaArn, String ocspAuthorizedResponderArn) {
    }

    @RegisterForReflection
    public record ClientCertificateConfig(String clientCertificateCallbackArn) {
    }
}
