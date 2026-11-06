# AWS Network Firewall

**Protocol:** AWS JSON 1.0  
**Signing name:** `network-firewall`

Floci persists Network Firewall rule groups, firewall policies, firewalls, and
logging configurations. Firewall status is immediately `READY`; endpoint IDs are
stable control-plane identifiers derived from the firewall ARN and its configured
subnets. Floci does not run a packet-inspection data plane.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRuleGroup` | Creates a stateful or stateless rule group. |
| `DescribeRuleGroup` | Returns a rule group's definition and metadata. |
| `UpdateRuleGroup` | Updates a rule group's rules and settings. |
| `DeleteRuleGroup` | Deletes the specified rule group. |
| `ListRuleGroups` | Lists the rule groups in the account. |
| `CreateFirewallPolicy` | Creates a firewall policy referencing rule groups. |
| `DescribeFirewallPolicy` | Returns a firewall policy's definition and metadata. |
| `UpdateFirewallPolicy` | Updates a firewall policy's definition. |
| `DeleteFirewallPolicy` | Deletes the specified firewall policy. |
| `ListFirewallPolicies` | Lists the firewall policies in the account. |
| `CreateFirewall` | Creates a firewall; status is immediately READY with stable endpoint IDs. |
| `DescribeFirewall` | Returns the firewall's configuration and sync state. |
| `DeleteFirewall` | Deletes the specified firewall. |
| `ListFirewalls` | Lists the firewalls in the account. |
| `UpdateFirewallDeleteProtection` | Sets the firewall's delete-protection flag. |
| `UpdateFirewallPolicyChangeProtection` | Sets the firewall's policy-change-protection flag. |
| `UpdateSubnetChangeProtection` | Sets the firewall's subnet-change-protection flag. |
| `UpdateAvailabilityZoneChangeProtection` | Sets the firewall's Availability-Zone-change-protection flag. |
| `UpdateFirewallDescription` | Updates the firewall's description. |
| `UpdateFirewallAnalysisSettings` | Updates the firewall's traffic-analysis settings. |
| `AssociateSubnets` | Attaches subnets to the firewall, creating endpoint attachments. |
| `DisassociateSubnets` | Detaches subnets from the firewall. |
| `AssociateAvailabilityZones` | Attaches Availability Zones to the firewall. |
| `DisassociateAvailabilityZones` | Detaches Availability Zones from the firewall. |
| `UpdateLoggingConfiguration` | Creates or updates the firewall's logging configuration. |
| `DescribeLoggingConfiguration` | Returns the firewall's logging configuration. |
| `AssociateFirewallPolicy` | Sets Firewall.FirewallPolicyArn on the stored firewall. |
<!-- floci:actions:end -->

## Limitations

- **`AssociateFirewallPolicy` does not validate `FirewallPolicyChangeProtection`.**
  AWS rejects the call when the firewall has policy-change protection enabled; Floci
  applies the new `FirewallPolicyArn` unconditionally.
