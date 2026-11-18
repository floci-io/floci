package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A permission the account that owns a network interface has granted another account over it
 * ({@code NetworkInterfacePermission}): {@code INSTANCE-ATTACH} lets that account attach the
 * interface to one of its own instances. CreateNetworkInterfacePermission grants one; it is kept on
 * the interface it is over.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkInterfacePermission {

    private String networkInterfacePermissionId;
    private String networkInterfaceId;
    private String awsAccountId;
    private String permission;
    private String state;

    public NetworkInterfacePermission() {}

    public NetworkInterfacePermission(String networkInterfacePermissionId, String networkInterfaceId,
                                      String awsAccountId, String permission, String state) {
        this.networkInterfacePermissionId = networkInterfacePermissionId;
        this.networkInterfaceId = networkInterfaceId;
        this.awsAccountId = awsAccountId;
        this.permission = permission;
        this.state = state;
    }

    public String getNetworkInterfacePermissionId() { return networkInterfacePermissionId; }
    public void setNetworkInterfacePermissionId(String networkInterfacePermissionId) {
        this.networkInterfacePermissionId = networkInterfacePermissionId;
    }

    public String getNetworkInterfaceId() { return networkInterfaceId; }
    public void setNetworkInterfaceId(String networkInterfaceId) { this.networkInterfaceId = networkInterfaceId; }

    public String getAwsAccountId() { return awsAccountId; }
    public void setAwsAccountId(String awsAccountId) { this.awsAccountId = awsAccountId; }

    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }

    /** {@code PermissionState.State}: {@code granted} once it applies. */
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
