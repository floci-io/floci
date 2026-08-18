package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The customer side of a Site-to-Site VPN connection.
 *
 * <p>AWS reports {@code bgpAsn} and {@code bgpAsnExtended} as strings even though the
 * request takes them as numbers, so they are stored as strings and echoed verbatim.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerGateway {

    private String customerGatewayId;
    private String state = "available";
    private String type;
    private String ipAddress;
    private String bgpAsn;
    private String bgpAsnExtended;
    private String certificateArn;
    private String deviceName;
    private String ownerId;
    private String region;
    private List<Tag> tags = new ArrayList<>();

    public CustomerGateway() {}

    public String getCustomerGatewayId() { return customerGatewayId; }
    public void setCustomerGatewayId(String customerGatewayId) { this.customerGatewayId = customerGatewayId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getBgpAsn() { return bgpAsn; }
    public void setBgpAsn(String bgpAsn) { this.bgpAsn = bgpAsn; }

    public String getBgpAsnExtended() { return bgpAsnExtended; }
    public void setBgpAsnExtended(String bgpAsnExtended) { this.bgpAsnExtended = bgpAsnExtended; }

    public String getCertificateArn() { return certificateArn; }
    public void setCertificateArn(String certificateArn) { this.certificateArn = certificateArn; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
