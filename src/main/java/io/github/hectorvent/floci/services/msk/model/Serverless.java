package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * A serverless cluster's configuration - the {@code serverless} member of CreateClusterV2 and
 * the {@code Serverless} member of the v2 Cluster response, which carry the same shape.
 *
 * <p>Only SASL/IAM is a valid client authentication for serverless clusters; the shared
 * {@link ClientAuthentication} model is reused because it is a superset and omits its unset
 * members, so a serverless response never grows a {@code tls} or {@code unauthenticated} key.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Serverless {

    @JsonProperty("vpcConfigs")
    private List<VpcConfig> vpcConfigs;

    @JsonProperty("clientAuthentication")
    private ClientAuthentication clientAuthentication;

    @JsonProperty("connectivityInfo")
    private ConnectivityInfo connectivityInfo;

    public Serverless() {}

    public List<VpcConfig> getVpcConfigs() { return vpcConfigs; }
    public void setVpcConfigs(List<VpcConfig> vpcConfigs) { this.vpcConfigs = vpcConfigs; }

    public ClientAuthentication getClientAuthentication() { return clientAuthentication; }
    public void setClientAuthentication(ClientAuthentication clientAuthentication) { this.clientAuthentication = clientAuthentication; }

    public ConnectivityInfo getConnectivityInfo() { return connectivityInfo; }
    public void setConnectivityInfo(ConnectivityInfo connectivityInfo) { this.connectivityInfo = connectivityInfo; }
}
