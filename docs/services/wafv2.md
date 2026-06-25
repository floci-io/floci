# WAF v2

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`
**Target prefix:** `X-Amz-Target: AWSWAF_20190729.*`

Floci emulates the AWS WAF v2 management API. Web ACLs, IP sets, regex pattern sets, rule groups, logging configurations, permission policies, tags, and resource associations are persisted in Floci's storage backend. Floci does not inspect or filter live traffic — this surface lets you create, read, update, and delete WAF resources and validate IaC (CloudFormation/CDK/Terraform) locally.

## Supported Actions

| Action | Description |
|---|---|
| `CreateWebACL` | Creates a web ACL |
| `GetWebACL` | Returns a web ACL by name/id/scope |
| `UpdateWebACL` | Updates a web ACL (requires the current `LockToken`) |
| `DeleteWebACL` | Deletes a web ACL |
| `ListWebACLs` | Lists web ACLs for a scope |
| `GetWebACLForResource` | Returns the web ACL associated with a resource |
| `AssociateWebACL` | Associates a web ACL with a resource |
| `DisassociateWebACL` | Removes a web ACL association from a resource |
| `ListResourcesForWebACL` | Lists resources associated with a web ACL |
| `CreateIPSet` | Creates an IP set |
| `GetIPSet` | Returns an IP set |
| `UpdateIPSet` | Updates an IP set |
| `DeleteIPSet` | Deletes an IP set |
| `ListIPSets` | Lists IP sets for a scope |
| `CreateRegexPatternSet` | Creates a regex pattern set |
| `GetRegexPatternSet` | Returns a regex pattern set |
| `UpdateRegexPatternSet` | Updates a regex pattern set |
| `DeleteRegexPatternSet` | Deletes a regex pattern set |
| `ListRegexPatternSets` | Lists regex pattern sets for a scope |
| `CreateRuleGroup` | Creates a rule group |
| `GetRuleGroup` | Returns a rule group |
| `UpdateRuleGroup` | Updates a rule group |
| `DeleteRuleGroup` | Deletes a rule group |
| `ListRuleGroups` | Lists rule groups for a scope |
| `CheckCapacity` | Returns the WCU capacity required for a set of rules |
| `PutLoggingConfiguration` | Sets the logging configuration for a web ACL |
| `GetLoggingConfiguration` | Returns the logging configuration for a web ACL |
| `DeleteLoggingConfiguration` | Removes a logging configuration |
| `ListLoggingConfigurations` | Lists logging configurations for a scope |
| `PutPermissionPolicy` | Attaches an IAM-style policy to a rule group |
| `GetPermissionPolicy` | Returns the policy attached to a rule group |
| `DeletePermissionPolicy` | Removes the policy from a rule group |
| `TagResource` | Adds tags to a WAF resource |
| `UntagResource` | Removes tags from a WAF resource |
| `ListTagsForResource` | Lists tags for a WAF resource |

## Scope

WAF v2 resources are partitioned by `Scope`: `REGIONAL` (ALB, API Gateway, AppSync) or `CLOUDFRONT`. Pass `--scope` on every call; `CLOUDFRONT`-scoped requests must target `us-east-1` as on real AWS.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create an IP set
aws wafv2 create-ip-set \
  --name blocklist --scope REGIONAL \
  --ip-address-version IPV4 \
  --addresses 192.0.2.0/24

# Create a web ACL that allows by default
aws wafv2 create-web-acl \
  --name demo-acl --scope REGIONAL \
  --default-action Allow={} \
  --visibility-config SampledRequestsEnabled=true,CloudWatchMetricsEnabled=true,MetricName=demo

aws wafv2 list-web-acls --scope REGIONAL
```
