# AWS Network Firewall

**Protocol:** AWS JSON 1.0  
**Signing name:** `network-firewall`

Floci persists Network Firewall rule groups, firewall policies, firewalls, and
logging configurations. Firewall status is immediately `READY`; endpoint IDs are
stable control-plane identifiers derived from the firewall ARN and its configured
subnets. Floci does not run a packet-inspection data plane.

## Supported operations

| Operation | Notes |
|---|---|
| `CreateRuleGroup`, `DescribeRuleGroup`, `UpdateRuleGroup`, `DeleteRuleGroup`, `ListRuleGroups` | Stateful and stateless rule-group lifecycle |
| `CreateFirewallPolicy`, `DescribeFirewallPolicy`, `UpdateFirewallPolicy`, `DeleteFirewallPolicy`, `ListFirewallPolicies` | Firewall-policy lifecycle |
| `CreateFirewall`, `DescribeFirewall`, `DeleteFirewall`, `ListFirewalls` | Persistent firewall lifecycle and ready endpoint attachments |
| Firewall protection, description, subnet, Availability Zone, and analysis update operations | Update the stored firewall configuration |
| `UpdateLoggingConfiguration`, `DescribeLoggingConfiguration` | Persistent logging configuration |
