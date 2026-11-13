#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-request-resources.json from AWS's Service Reference and botocore.

Under IAM enforcement every request is checked against the resources it acts on. For the services
`ResourceArnBuilder` builds them for by hand that is their ARN; for every other service it is `*`,
so a grant scoped to a resource is refused and a deny scoped to one never applies. This file gives,
for each operation of the namespaces it reads, the resources its request names, and `RequestResources`
builds their ARNs from the request. It comes from two sources, each answering what the other cannot:

- AWS's Service Reference, which lists the resource types each action acts on and the ARN formats
  of each type, but not which request member names a resource:

      https://servicereference.us-east-1.amazonaws.com/            (the index of services)
      https://servicereference.us-east-1.amazonaws.com/v1/<svc>/<svc>.json

- botocore's model of each SDK client the reference lists the operation for (Apache-2.0,
  https://github.com/boto/botocore, the pinned release), which gives the request's members.

An operation is checked as its own action, or as the action of another name the reference gives it
(`regen_iam_authorized_actions.py` vendors that mapping). For each resource type that action acts
on, the request names a resource when one of its top-level members holds a string or a list of
strings, or, for an ARN, a member of a structure or of each element of a list of structures does
(`SetRulePriorities`' `RulePriorities[].RuleArn`), and:

- holds the resource's ARN: its name ends in `Arn` or `Arns`, or it is an Identifier whose modeled
  pattern only accepts an ARN. Any of the action's types may be the
  one it holds (`ResourceArn` holds whatever the action acts on, `RuleArn` a `listener-rule/app`),
  and a value names a resource of a type when it matches the type's ARN format, so an ARN of
  something the action does not act on is not checked.
- fills one of the variables of the type's ARN format: its stem (the name lowercased, `Arn`, `Name`,
  `Id`, `Identifier` and a plural dropped) is the variable's, ends with it or is the end of it, four
  letters at least (`AutoScalingGroupName` fills `${GroupFriendlyName}`, `GroupId` fills
  `${SecurityGroupId}`), and it is a name for a name and an id for an id (an `Identifier` either),
  or the member is a bare `Name`, `Id` or `Identifier`, the variable is named after the type and the
  operation is about the type, its name past the verb the type's (`DescribeTargetGroups`, but not
  `StartExecution` for a state machine, whose `name` is the execution's), and no member holds its ARN.
  Of several members that could fill a variable, one naming the type before the variable wins over a
  bare one: the snapshot `RestoreFromClusterSnapshot` restores has the cluster its
  `SnapshotClusterIdentifier` names, not the `ClusterIdentifier` of the cluster it creates, and the
  tenant `GetInvalidationForDistributionTenant` reads has its `DistributionTenantId`, not the
  invalidation's `Id`.
  The variables nothing fills are left open as `*`: a resource the request creates has the id its
  service mints, so `CreateTargetGroup` names `targetgroup/<Name>/*`.
- names neither, but the type is the one the operation is named after (`CreateCapacityReservation`
  and `capacity-reservation`): the request creates one, every variable open.

A member whose botocore shape is an account id (a string of pattern `[0-9]{12}`) gives the account of
the types the request names by their variables: ECR's `registryId` selects the registry its handler
acts in, so `repository/<name>` is that account's. On a non-create, a bare string member also takes
that role when the same service types that member as an account id in another request. Redshift
Serverless's `ownerAccount` names the snapshot's owner this way, but on a create it names the account
of a resource the new one is made in. A top-level member whose name ends in `Account` gives the account
of each type whose botocore structure has a member of that same name: Redshift's `Snapshot` has an
`OwnerAccount` and its `Cluster` has none, so `RestoreFromClusterSnapshot`'s names the snapshot's
owner, while the restored cluster stays the caller's. A member whose shape's pattern takes an ARN before
the name (`(arn:...:event-bus/)?<name>`) may give the ARN of the type of that name instead, whose one
variable it is then read as, in the ARN's account and Region: EventBridge's `EventBusName`. So may a
field of each element of a list of structures, each element's resource in the account and Region of
the ARN it gives: `PutEvents`' `Entries[].EventBusName`. A format whose account the reference names
`${AccountId}` rather than `${Account}` (DataSync's) is read as every other format's account.

An operation that creates a type its action does not act on names the new resource from the ARN of the
parent it acts on, when the child's ARN format is the parent's with its resource type word replaced and
one id appended (`CreateListener` acts on a load balancer and makes `listener/app/<lb>/<id>/*`); the
tagging action a create's tags need is checked on it, as AWS checks it on the resource being tagged. A create
that names another resource beside the one it makes, the pipeline a webhook connects to or the namespace a
service is made in, marks the one it makes `made`, of a type, or with an ARN format's leading resource word,
related to its name past the verb, so its tags are checked there alone.

An optional member holding the ARN of a type whose format has no variable of its own, Security Hub's `HubArn` and
`hub/default`, also names that type when the request leaves it out: there is one such resource in the caller's
account and Region, the one the service acts on then (`DescribeHub`).

A type nothing names is not checked: an action lists the optional resources its requests may use
(`RunInstances` lists `elastic-gpu`), and checking one a request does not name would refuse a grant
that covers what it does name. An operation whose action acts on no resource type, or whose request
names none, is checked on `*`, as AWS checks it.

A REST request (`rest-json`) carries members in its path and its query as well as its body: a label of its path,
`{DomainName}`, and a parameter of its query, `?arn`, are members as its body's are, under their botocore names, and
the file records where each is (`located`), which the reader lays over the body from the path the request was routed
by and its query. A namespace read this way also takes the operations of each botocore model Floci reads REST routes
for under it (`regen_rest_routes.MODELS`) that the reference lists for none of its clients, each checked as the action
of its own name with that action's resource types when the reference has that action, and left out when it does not:
the reference lists one client's operations per namespace, and
OpenSearch Service's are the `es` client's alone, while the `opensearch` client's are named after `es` actions.

Every operation in `NAMESPACES` is read, whether Floci serves it or not. Explicit `CORRECTIONS`
in `HAND_BUILT` are also read, overriding only those operations while the remaining operations
keep their existing resource builders, and so are the operations `READ_IN_HAND_BUILT` lists, which
follow the rule unchanged: a family of a hand-built namespace whose requests name their resources the
way the rule reads them. `HAND_BUILT` are the namespaces `ResourceArnBuilder` builds resources for itself, and
`NOT_COVERED` the ones Floci serves that are not read yet, each with its reason;
`RequestResourceNamespacesIntegrationTest` fails for a served namespace in none of the three.
`ID_TARGETS` stages heterogeneous id-list namespaces separately: botocore supplies the list member
and its serialized EC2 Query name (including queryName and capitalization), and documented id
prefixes distinguish the supported resource types. Each generated `targets` entry preserves the
request's order and duplicates, including a wildcard for each unsupported id; only resolved ARNs
use the corresponding target's condition context. Other requests in those namespaces stay on `*`.
`CORRECTIONS` gives an operation's resources where the rule gets them wrong, each with its reason; a
resource there may apply only `when` a member has one of some values (`Type` picks the load balancer
v2 `CreateLoadBalancer` creates), or `unless` it has, a member given a JSON null counting as absent
(Cloud Map's handler then reads `DnsConfig.NamespaceId`), and a member read as a value when it is
`absent` (an EventBridge request naming no event bus names the default one), a member filling a variable may be
read up to a character `before` (a CodeBuild build id, `<project>:<id>`, names its project), a variable may be
filled from a field of each element of a list of structures, one resource for each element and the `absent` value
for an
element lacking it (`PutEvents` publishes each entry to its bus), or from a structure's field (`pipeline.name`),
a variable no member gives may be `fixed` to the one value its handler uses (a CodePipeline custom action
type's owner, `Custom`), and an ARN member's value may be read
only up to a character `before` which its handler drops the rest (`StartExecution`'s
`<stateMachineArn>#<test case>`). A resource there may be named `through` a member, a list's included, by each of
its values, `when` the request names it no other way (an EMR `ModifyInstanceGroups` with no `ClusterId`, through
each group's id, on the cluster it is in). A resource there may also apply only to the requests of the client whose
`endpoint` prefix it names, where two clients give an operation of one name and nothing in a request tells them
apart (EventBridge's `CreateEventBus` and EventBridge V2's); Floci reads that from the service serving the
request, never from a member a caller could add. `CLIENTS_NOT_READ` are SDK clients the reference lists for a
namespace's operations whose requests Floci does not serve, each with its reason.
`UNQUALIFIED` are the resource types whose ARN a request may give with a qualifier, one more `:<name>`
past its format, and whose service checks it without one: Step Functions checks a version or an alias
on its state machine's ARN. `MINTED_IDS` are the resource types whose service follows the name their
format ends in with an id it mints, `/<id>`, which the format leaves out and a request naming one by
its name does not give: EventBridge's connections and API destinations, whose stored ARN the reader
asks the service for, or else checks with that id open. `FOUND_BY` are the resource types whose ARN is
all an id their service mints, which a request names by members the ARN does not hold, with those
members: each operation whose request has them all names the type by them (`find`), and the reader
asks the service for the ARN they find, or else checks the ARN with its id open, as for a create; an
Application Auto Scaling scalable target, `scalable-target/<id>`, is named by its service namespace,
resource id and scalable dimension, and the Redshift Serverless workgroup a Data API request runs on,
`workgroup/<id>`, by its name or ARN. `THROUGH` are the members naming a resource the reference gives no
type, through which a request names a resource of one it does, with those types: each operation whose
request has the member, and whose action acts on one of them, names its resource through it (`through`),
and the reader asks the service for the ARN of the caller's resource at the other end, or else names
none; a Data API session, by its `SessionId`, runs on the cluster or the serverless workgroup it was
opened on. A type named by its variables takes one no member fills from the ARN of another type the action acts
on whose format is that variable alone, given by the one top-level ARN member named after that type: a permission
set an IAM Identity Center create makes has the id of the instance its `InstanceArn` names.

The Service Reference is live rather than a pinned dependency, so a fresh generation is not
byte-identical. The two gates split along the sources:

- `--check` is offline and needs the pinned botocore. The file must be well-formed, and every
  operation's resources must be exactly what the rule and the corrections give from botocore for the
  resource types and ARN formats the file records, so a hand-edited entry fails.
- `--verify` is online and stdlib-only. Every operation must still be checked as the action, on the
  resource types, the file records, and every type must still have its ARN format. An operation AWS
  has added is reported without failing: until the next sync it is checked on `*`, where it was
  before this file existed.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_request_resources.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_request_resources.py --check   # offline: resources match botocore
    python3 tools/aws/regen_iam_request_resources.py --verify  # online: actions and types match AWS
    python3 tools/aws/regen_iam_request_resources.py --source-dir <dir>  # read saved <svc>.json files
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from regen_iam_authorized_actions import authorized_actions_from, fetch_references
from regen_iam_passed_roles import (
    NAMESPACE_RE,
    OPERATION_RE,
    _shown,
    load_models,
    namespace_of,
)
from regen_partitions import resolve_botocore_data
from regen_rest_routes import MODELS as REST_MODELS

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-request-resources.json"

# The namespaces read, in batches: each moves here from NOT_COVERED with the tests that show its ARNs
# are the ones its handlers mint.
NAMESPACES = ("access-analyzer", "account", "acm", "application-autoscaling", "athena", "bcm-data-exports",
              "bcm-pricing-calculator", "budgets", "ce", "cloudfront", "cloudfront-keyvaluestore", "cloudtrail",
              "codebuild", "codepipeline", "cognito-identity", "cognito-idp", "controlcatalog", "controltower", "cur",
              "datasync", "ec2", "ecr", "elasticloadbalancing", "elasticmapreduce", "emr-serverless", "es", "events",
              "firehose", "fis", "globalaccelerator", "inspector2", "kinesisanalytics", "macie2", "oam",
              "payment-cryptography", "quicksight", "ram", "redshift", "redshift-data", "redshift-serverless",
              "rekognition", "resource-explorer-2", "route53", "servicediscovery", "servicequotas", "sso", "states", "textract",
              "timestream-influxdb", "translate", "signer", "xray", "securityhub", "guardduty")

# SDK clients the reference lists for a namespace's operations whose requests Floci does not serve, and why: their
# members are not read.
CLIENTS_NOT_READ: dict[str, str] = {
    "kinesisanalytics": "Kinesis Data Analytics v1, SQL applications on the KinesisAnalytics_20150814 target, which "
                        "Floci does not serve (it serves v2, kinesisanalyticsv2); its operations share v2's names and "
                        "name their resources by other members",
}

# The namespaces whose members may name a resource of a type with a word before the type's, as
# `IncomingKeyIdentifier` names a key: the word says which of them it is. Such a member's value may
# be an alias, and the namespace's service resolves it to the resource the alias points at, so the
# resource is the one the alias names. A namespace whose service cannot resolve an alias is not
# among these: an alias would be read as the name of a resource of the type, which names nothing.
QUALIFIED_MEMBERS = ("payment-cryptography",)

# The namespaces whose resources ResourceArnBuilder builds itself.
HAND_BUILT = ("apigateway", "bedrock", "dsql", "dynamodb", "eks", "iam", "kinesis", "kms", "lambda", "logs", "pipes",
              "resource-groups", "s3", "scheduler", "secretsmanager", "sns", "sqs", "ssm", "sts")

# Operations of a namespace in `HAND_BUILT` read from the reference all the same, each with its reason.
_LOGS_DELIVERY = ("CloudWatch Logs vended-log delivery: its requests name a delivery source, destination or delivery "
                  "by the member the rule reads, while ResourceArnBuilder keeps building the log group and stream ARNs")
READ_IN_HAND_BUILT: dict[str, str] = {
    f"logs:{operation}": _LOGS_DELIVERY
    for operation in ("PutDeliverySource", "GetDeliverySource", "DeleteDeliverySource", "DescribeDeliverySources",
                      "PutDeliveryDestination", "GetDeliveryDestination", "DeleteDeliveryDestination",
                      "DescribeDeliveryDestinations", "CreateDelivery", "GetDelivery", "UpdateDeliveryConfiguration",
                      "DeleteDelivery", "DescribeDeliveries")
}
READ_IN_HAND_BUILT["logs:PutDestination"] = (
    "CloudWatch Logs destination creation names the destination by destinationName, which ResourceArnBuilder "
    "reads from the generated request-resource table")

_SSM_TAGS = ("SSM tagging: a request names its resource by ResourceType and ResourceId, or tags the document or "
             "association it creates, which the rule reads, while ResourceArnBuilder keeps building the parameter ARNs")
READ_IN_HAND_BUILT.update({f"ssm:{operation}": _SSM_TAGS
                           for operation in ("AddTagsToResource", "ListTagsForResource", "RemoveTagsFromResource",
                                             "CreateDocument", "CreateAssociation")})

_LATER = "not read yet: a later batch, with the tests that show its ARNs are the ones its handlers mint"
_REST = ("REST: its requests name their resources in the path, which RequestResources reads from the route a request "
         "matched in the vendored REST routes (regen_rest_routes.MODELS), which do not have its model yet")

# Namespaces Floci serves whose resources are not read yet, and why.
NOT_COVERED: dict[str, str] = {
    "airflow": _REST,
    "app-integrations": _REST,
    "appconfig": _REST,
    "appsync": _REST,
    "aps": _REST,
    "autoscaling": "not read yet: AWS's group and launch configuration ARNs carry an id the service mints, "
                   "`autoScalingGroup:<id>:autoScalingGroupName/<name>`, while Floci mints `autoScalingGroup:<name>`, "
                   "which other services (ECS capacity providers) read back; the mint is fixed first",
    "aws-marketplace": _LATER,
    "backup": _REST,
    "batch": _REST,
    "bedrock-agentcore": _REST,
    "cloudformation": _LATER,
    "cloudhsm": "not read yet: its ARN formats end in ${CloudHsmClusterInstanceName} and "
                "${CloudHsmBackupInstanceName}, names, while its requests give ClusterId and BackupId, ids, which the "
                "rule does not fill a name from, so nearly every operation needs a correction",
    "cloudwatch": _LATER,
    "codeartifact": _REST,
    "codeguru-reviewer": _REST,
    "codedeploy": "not read yet: the Service Reference gives an on-premises instance's ARN as "
                  "`instance:${InstanceName}`, while the CodeDeploy permissions reference gives "
                  "arn:aws:codedeploy:region:account-id:instance/instance-ID and AWS's GetOnPremisesInstance example "
                  "answers instance/<name>_<id>, an id the service mints after the name; reading it needs that format "
                  "corrected",
    "comprehend": _LATER,
    "config": _LATER,
    "connect": _REST,
    "detective": _REST,
    "dlm": _REST,
    "dms": _LATER,
    "ec2-instance-connect": _LATER,
    "ecs": _LATER,
    "elasticache": _LATER,
    "elasticbeanstalk": "not read yet: an environment's ARN carries its application's name, "
                        "`environment/<application>/<name>`, while most of its requests name it by EnvironmentName "
                        "or EnvironmentId alone, so reading it needs the stored environment those find",
    "elasticfilesystem": _REST,
    "execute-api": _REST,
    "glue": _LATER,
    "identitystore": _LATER,
    "iot": _LATER,
    "kafka": _REST,
    "lightsail": _LATER,
    "memorydb": _LATER,
    "mq": _REST,
    "network-firewall": _LATER,
    "organizations": _LATER,
    "rds": _LATER,
    "rds-data": _REST,
    "route53resolver": _LATER,
    "rum": _REST,
    "s3tables": _REST,
    "s3vectors": _REST,
    "sagemaker": _LATER,
    "servicecatalog": "not read yet: CreatePortfolioShare, UpdatePortfolioShare and DeletePortfolioShare give in "
                      "AccountId the account a portfolio is shared with, which the rule reads as the portfolio's; a "
                      "portfolio or product imported from another account keeps its owner's account in its ARN while "
                      "requests name it by its id alone; and AppRegistry's applications and attribute groups are "
                      "named in the path",
    "ses": _LATER,
    "signin": _REST,
    "sso-oauth": _REST,
    "swf": "not read yet: the Service Reference gives a domain's ARN no Region, `swf::<account>:/domain/<name>`, "
           "while the SWF Developer Guide gives it as arn:aws:swf:Region:AccountID:/domain/DomainName, as Floci mints "
           "it; reading it needs that format corrected",
    "transcribe": _LATER,
    "transfer": _LATER,
    "verifiedpermissions": "not read yet: a policyStoreId may name its store by an alias, `policy-store-alias/<name>`, "
                           "which AWS authorizes as the action on the store the alias points to and "
                           "GetPolicyStoreAlias on the alias, and an aliasName carries the `policy-store-alias/` its "
                           "format already gives; reading it needs both",
    "wafv2": _LATER,
}

# An operation's resources where the rule gets them wrong: the resources, and why. A resource's `when` names the
# values of a member it applies for, null for the member absent. Its `endpoint` names the endpoint prefix of the one
# client whose requests it applies to, where two clients share the operation's name and nothing a request gives tells
# them apart (EventBridge's `events` and EventBridge V2's `eventsv2`): Floci applies it only to a request the service
# of that endpoint serves, never by a member a caller could add to a request of the other.
CORRECTIONS: dict[str, tuple[list[dict], str]] = {
    "quicksight:CreateDataSet": ([{"types": ["datasource"], "arn": f"PhysicalTableMap{{}}.{kind}.DataSourceArn"}
                                 for kind in ("S3Source", "RelationalTable", "CustomSql", "SaaSTable", "FileSource")],
                                "CreateDataSet acts on each data source named by its physical tables; "
                                "FolderArns and RowLevelPermissionDataSet.Arn name other resource types"),
    "quicksight:CreateDashboard": ([{"type": "dashboard", "fill": {"ResourceId": "DashboardId"}}],
                                   "DashboardId names the created dashboard; FolderArns and ThemeArn name "
                                   "other resource types and are not dashboard ARN selectors"),
    **{f"quicksight:{operation}": ([{"type": "ingestion", "fill": {"DatasetId": "DataSetId", "ResourceId": "IngestionId"}}],
                                   "the ingestion ARN contains its dataset id and ingestion id, named by "
                                   "DataSetId and IngestionId in the REST path")
       for operation in ("CreateIngestion", "DescribeIngestion")},
    **{f"quicksight:{operation}": ([{"type": "user", "fill": {}, "find": ["AwsAccountId", "Namespace", "UserName"]}],
                                   "the user ARN ends in namespace/user-name, which the reference combines as "
                                   "ResourceId; QuickSight resolves it from the account, namespace and user name")
       for operation in ("RegisterUser", "DescribeUser")},
    "logs:PutDeliverySource": ([{"type": "delivery-source", "fill": {"DeliverySourceName": "name"}}],
                               "resourceArn is the ARN of the resource that sends the logs (an event bus, a load "
                               "balancer), never the delivery source's, which the request names by name"),
    "logs:DeleteDeliverySource": ([{"type": "delivery-source", "fill": {"DeliverySourceName": "name"}}],
                                  "the reference lists only delivery-destination, which nothing in the request names, "
                                  "so the rule would authorize it on * and a deny on the source it deletes would not "
                                  "apply; the request names that source by name"),
    "logs:PutDeliveryDestination": ([{"type": "delivery-destination", "fill": {"DeliveryDestinationName": "name"}}],
                                    "deliveryDestinationConfiguration.destinationResourceArn is the log group, bucket "
                                    "or stream the logs go to and roleArn a role, neither the delivery destination, "
                                    "which the request names by name"),
    **{f"logs:{operation}": ([{"type": "delivery", "fill": {"DeliveryName": "id"}}],
                             "the delivery format ends in ${DeliveryName}, while a delivery is named by the id "
                             "CreateDelivery mints, which the request gives in id")
       for operation in ("GetDelivery", "DeleteDelivery", "UpdateDeliveryConfiguration")},
    "dynamodb:ListTables": ([{"type": "table", "fill": {}}],
                            "AWS policy probes accepted by review #7336 authorize the literal regional table/*: "
                            "table/? matches while table/ does not, despite the Service Reference listing no "
                            "resource types; request-body table names and pagination do not select its resource"),
    **{f"states:{operation}": ([{"type": kind, "fill": {}, "find": ["taskToken"]}
                                for kind in ("activity", "statemachine")],
                              "AWS callback policy probes on 2026-11-02 authorize an activity token against its "
                              "activity and an integration token against its unqualified state machine, despite "
                              "the action table listing no resource types; the token's service resolves the ARN")
       for operation in ("SendTaskSuccess", "SendTaskFailure", "SendTaskHeartbeat")},
    "elasticloadbalancing:CreateLoadBalancer": ([
        {"type": "loadbalancer", "fill": {"LoadBalancerName": "LoadBalancerName"}},
        {"type": "loadbalancer/app/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["application", None]}},
        {"type": "loadbalancer/gwy/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["gateway"]}},
        {"type": "loadbalancer/net/", "fill": {"LoadBalancerName": "Name"}, "when": {"Type": ["network"]}},
    ], "the classic API names its load balancer by LoadBalancerName; the v2 API by Name, its type by Type, "
       "an application load balancer when it gives none, which the rule cannot tell apart"),
    **{f"budgets:{operation}": ([{"type": "budget", "fill": {"BudgetName": member}}],
                                f"the request names the budget by {member} ('The name of a budget'), which the rule "
                                "does not read inside a structure; its BillingViewArn is a billing view's ARN "
                                "(arn:aws:billing::<account>:billingview/...), never a budget's")
       for operation, member in (("CreateBudget", "Budget.BudgetName"), ("UpdateBudget", "NewBudget.BudgetName"))},
    **{f"codebuild:{operation}": ([{"type": "project", "fill": {"ProjectName": member}, "before": ":"}],
                                  "a build is checked on its project (the CodeBuild User Guide's permissions reference "
                                  "gives each of these the resource project/project-name), which its id names before "
                                  "its ':' (CODEBUILD_BUILD_ID, for example codebuild-demo-project:b1e6661e-...)")
       for operation, member in (("BatchDeleteBuilds", "ids[]"), ("BatchGetBuilds", "ids[]"), ("RetryBuild", "id"),
                                 ("StopBuild", "id"))},
    **{f"cur:{operation}": ([{"type": "cur", "fill": {"ReportName": member}}],
                            f"the request names the report by {member} ('The name of the report that you want to "
                            "create') alone; its ReportDefinition's BillingViewArn, which the rule reads as a report "
                            "definition's ARN, is a billing view's (arn:aws:billing::<payer account>:billingview/...)")
       for operation, member in (("ModifyReportDefinition", "ReportName"),
                                 ("PutReportDefinition", "ReportDefinition.ReportName"))},
    **{f"codepipeline:{operation}": ([{"type": "pipeline", "fill": {"PipelineName": "pipeline.name"}}],
                                     "the request names the pipeline by its pipeline structure's name, which the "
                                     "rule does not read inside a structure; its roleArn is the role CodePipeline "
                                     "assumes, an IAM role's ARN by botocore's pattern, never a pipeline's, so it "
                                     "names no pipeline")
       for operation in ("CreatePipeline", "UpdatePipeline")},
    "codepipeline:PutWebhook": ([{"type": "webhook", "fill": {"WebhookName": "webhook.name"}},
                                 {"type": "pipeline", "fill": {"PipelineName": "webhook.targetPipeline"}}],
                                "the request names the webhook by its webhook structure's name and the pipeline it "
                                "connects to by its targetPipeline ('The name of the pipeline you want to connect to "
                                "the webhook'), which the rule does not read inside a structure"),
    **{f"codepipeline:{operation}": ([{"type": "actiontype", "fill": {"Category": "category", "Provider": "provider",
                                                                      "Version": "version"},
                                       "fixed": {"Owner": "Custom"}}],
                                     "a custom action type's owner is Custom, which the request does not give: the API "
                                     "Reference's CreateCustomActionType example answers \"owner\": \"Custom\", and "
                                     "PollForJobs is valid only for action types with Custom in the owner field")
       for operation in ("CreateCustomActionType", "DeleteCustomActionType")},
    "servicediscovery:CreateService": ([
        {"type": "namespace", "fill": {"NamespaceId": "NamespaceId"}, "unless": {"NamespaceId": [None]}},
        {"type": "namespace", "fill": {"NamespaceId": "DnsConfig.NamespaceId"}, "when": {"NamespaceId": [None]}},
        {"type": "service", "fill": {}},
    ], "the request names its namespace by NamespaceId or, when it gives none or a JSON null, by "
       "DnsConfig.NamespaceId, which botocore keeps ('Use NamespaceId in Service instead') and Cloud Map's handler "
       "reads then; the rule does not read a structure's member, and names the service the request creates with "
       "every variable open"),
    **{f"codepipeline:{operation}": ([{"type": "pipeline", "fill": {"PipelineName": "name"}}],
                                     f"the request names the pipeline by a bare name, '{doc}' in botocore's model, "
                                     "which the rule reads only for an operation named after the type")
       for operation, doc in (("GetPipelineState", "The name of the pipeline about which you want to get "
                                                   "information"),
                              ("StartPipelineExecution", "The name of the pipeline to start"))},
    **{f"events:{operation}": ([
        {"type": "rule-on-default-event-bus", "fill": {"RuleName": rule}, "when": {"EventBusName": ["default"]},
         "absent": {"EventBusName": "default"}},
        {"type": "rule-on-custom-event-bus", "fill": {"EventBusName": "EventBusName", "RuleName": rule},
         "unless": {"EventBusName": ["default"]}, "absent": {"EventBusName": "default"}},
    ], f"a rule is named by its {rule} on the event bus EventBusName names, the default one when it names none "
       "('If you omit this, the default event bus is used'), whose rules have no bus in their ARN; the rule takes "
       "the bus's name for the rule's and cannot tell the default bus's rules from a custom bus's")
       for operation, rule in (("DeleteRule", "Name"), ("DescribeRule", "Name"), ("DisableRule", "Name"),
                               ("EnableRule", "Name"), ("ListTargetsByRule", "Rule"), ("PutRule", "Name"),
                               ("PutTargets", "Rule"), ("RemoveTargets", "Rule"))},
    "events:DescribeEventBus": ([{"types": ["event-busv2"], "arn": "EventBusArn", "endpoint": "eventsv2"},
                                 {"type": "event-bus", "fill": {"EventBusName": "Name"}, "absent": {"Name": "default"},
                                  "endpoint": "events"}],
                                "a request naming no event bus describes the default one ('If you omit this, the "
                                "default event bus is displayed'); EventBridge V2's request names its bus by "
                                "EventBusArn alone"),
    "events:PutEvents": ([{"types": ["event-busv2"], "arn": "EventBusArn", "endpoint": "eventsv2"},
                          {"type": "event-bus", "fill": {"EventBusName": "Entries[].EventBusName"},
                           "absent": {"Entries[].EventBusName": "default"}, "endpoint": "events"}],
                         "each entry goes to the event bus its EventBusName names, the default one when it names "
                         "none ('If you omit this, the default event bus is used'), and publishing is authorized "
                         "against the bus for every entry in the request; the rule reads no member of a list's "
                         "structures. EventBridge V2's request names by EventBusArn the one bus every entry goes to"),
    "events:CreateEventBus": ([{"types": ["event-bus", "event-busv2"], "arn": "DeadLetterConfig.Arn",
                                "endpoint": "events"},
                               {"type": "event-bus", "fill": {"EventBusName": "Name"}, "endpoint": "events"},
                               {"type": "event-busv2", "fill": {"EventBusName": "Name"}, "endpoint": "eventsv2"}],
                              "EventBridge's request and EventBridge V2's both name the bus they create by Name, and "
                              "nothing a request gives tells them apart; the API serving it does, an event-bus "
                              "for EventBridge and an event-busv2, whose id V2 mints, for V2"),
    **{f"events:{operation}": ([*extra, {"types": [v2], "arn": arn, "endpoint": "eventsv2"},
                                {"type": v1, "fill": {variable: "Name"}, "endpoint": "events"}],
                               f"EventBridge's request names the {v1} by Name, EventBridge V2's by {arn}; the rule "
                               "fills a type's variables only when no member holds its ARN, so with both clients "
                               "read it drops EventBridge's Name")
       for operation, v1, v2, variable, arn, extra in (
           ("DeleteEventBus", "event-bus", "event-busv2", "EventBusName", "EventBusArn", []),
           ("UpdateEventBus", "event-bus", "event-busv2", "EventBusName", "EventBusArn",
            [{"types": ["event-bus", "event-busv2"], "arn": "DeadLetterConfig.Arn", "endpoint": "events"}]),
           ("DescribeEventSource", "event-source", "event-sourcev2", "EventSourceName", "EventSourceArn", []))},
    **{f"states:{operation}": ([{"types": ["statemachine"], "arn": "stateMachineArn", "before": "#"}],
                              "Floci takes Step Functions Local's <stateMachineArn>#<test case> and runs the machine "
                              "the part before the # names, so that part is the one checked")
       for operation in ("StartExecution", "StartSyncExecution")},
    **{f"athena:{operation}": ([{"type": "workgroup", "fill": {"WorkGroupName": "WorkGroup"},
                                 "absent": {"WorkGroup": "primary"}}],
                               "a request naming no workgroup acts in the primary one (ListQueryExecutions: 'If a "
                               "workgroup is not specified, returns a list of query execution IDs for the primary "
                               "workgroup', ListNamedQueries alike; the user guide has users without their own "
                               "workgroup 'continue running queries in the primary workgroup'), and Floci's "
                               "GetWorkGroup answers the primary one for a request naming none")
       for operation in ("GetWorkGroup", "ListNamedQueries", "ListQueryExecutions", "StartQueryExecution")},
    "athena:GetDataCatalog": ([{"type": "datacatalog", "fill": {"DataCatalogName": "Name"},
                                "absent": {"Name": "AwsDataCatalog"}}],
                              "Floci's GetDataCatalog answers the account's Glue Data Catalog, AwsDataCatalog, for a "
                              "request naming no catalog, so that is the one checked"),
    **{f"athena:{operation}": ([{"type": "datacatalog", "fill": {"DataCatalogName": "CatalogName"},
                                 "absent": {"CatalogName": "AwsDataCatalog"}},
                                {"type": "datacatalog", "fill": {}, "fixed": {"DataCatalogName": "AwsDataCatalog"}}],
                               "Floci's handler reads the account's Glue Data Catalog, AwsDataCatalog, whichever "
                               "catalog CatalogName names, so that one is checked as well as the one named, and a "
                               "request naming none is read as naming it")
       for operation in ("GetTableMetadata", "ListDatabases", "ListTableMetadata")},
    **{f"ce:{operation}": ([], "Floci serves no billing views: it answers the account's costs whichever view "
                               "BillingViewArn names, so the request is checked on `*`, as one naming no view, and a "
                               "grant scoped to a view does not reach them")
       for operation in ("GetCostAndUsage", "GetCostAndUsageComparisons", "GetCostAndUsageWithResources",
                         "GetCostCategories", "GetCostComparisonDrivers", "GetCostForecast", "GetDimensionValues",
                         "GetTags", "GetUsageForecast")},
    "bcm-data-exports:CreateExport": ([], "its Export.ExportArn is the ARN 'for this export', the one the request "
                                          "creates, never a billing view's or a table's, although its pattern takes "
                                          "any bcm-data-exports ARN, a table's included; the request names its table "
                                          "in its query statement and its table configurations' keys, which the rule "
                                          "does not read"),
    **{f"cloudtrail:{operation}": ([{"type": "trail", "fill": {"TrailName": "Name"}}],
                                   "the request names the trail by a bare Name, 'the name or the CloudTrail ARN of "
                                   "the trail', which the rule reads only for an operation named after the type")
       for operation in ("GetTrailStatus", "StartLogging", "StopLogging")},
    **{f"cloudtrail:{operation}": ([{"types": ["channel", "dashboard", "eventdatastore", "trail"], "arn": member}],
                                   f"the request names the resource by its ARN in {member} ('the ARN of the trail, "
                                   "event data store, dashboard, or channel'), whose name does not end in Arn")
       for operation, member in (("AddTags", "ResourceId"), ("ListTags", "ResourceIdList[]"),
                                 ("RemoveTags", "ResourceId"))},
    **{f"cloudtrail:{operation}": ([{"type": "dashboard", "fill": {"DashboardName": "DashboardId"}}],
                                   "the request names the dashboard by DashboardId, 'The name or ARN for the "
                                   "dashboard', which the rule does not take for a name")
       for operation in ("DeleteDashboard", "GetDashboard", "StartDashboardRefresh", "UpdateDashboard")},
    **{f"ce:{operation}": ([{"types": [kind], "arn": f"{member}[]"}],
                           f"the request names the {noun} by the ARNs in {member} ('A list of cost anomaly {noun} "
                           "ARNs'), whose name does not end in Arn" + extra)
       for operation, member, kind, noun, extra in (
           ("GetAnomalyMonitors", "MonitorArnList", "anomalymonitor", "monitor", ""),
           ("GetAnomalySubscriptions", "SubscriptionArnList", "anomalysubscription", "subscription",
            "; its MonitorArn narrows them to a monitor's, an anomaly monitor's ARN, never a subscription's"))},
    "timestream-influxdb:RebootDbCluster": ([
        {"type": "db-cluster", "fill": {"DbClusterId": "dbClusterId"}},
        {"type": "db-instance", "fill": {"DbInstanceIdentifier": "instanceIds[]"}},
    ], "the request names the cluster's instances to reboot by instanceIds ('A list of service-generated unique DB "
       "Instance Ids belonging to the DB Cluster to reboot'), ids the rule does not take for an identifier"),
    "globalaccelerator:ListCustomRoutingPortMappings": ([{"types": ["accelerator"], "arn": "AcceleratorArn"}],
                                                        "its EndpointGroupArn narrows the mappings to an endpoint "
                                                        "group of the accelerator, whose ARN extends the "
                                                        "accelerator's (accelerator/<id>/listener/<id>/"
                                                        "endpoint-group/<id>) and so matches the accelerator's "
                                                        "format, which the action acts on alone"),
    **{f"elasticmapreduce:{operation}": ([{"type": "cluster", "fill": {"ClusterId": member}}],
                                         f"the request names its cluster by {member}, a job flow's id, which is the "
                                         "cluster's (RunJobFlow answers it as JobFlowId beside the cluster's ARN, and "
                                         "SetVisibleToAllUsers' JobFlowIds is 'The unique identifier of the job flow "
                                         "(cluster)'), which the rule does not take for a ClusterId")
       for operation, member in (("AddInstanceGroups", "JobFlowId"), ("AddJobFlowSteps", "JobFlowId"),
                                 ("DescribeJobFlows", "JobFlowIds[]"),
                                 ("SetKeepJobFlowAliveWhenNoSteps", "JobFlowIds[]"),
                                 ("SetTerminationProtection", "JobFlowIds[]"),
                                 ("SetUnhealthyNodeReplacement", "JobFlowIds[]"),
                                 ("SetVisibleToAllUsers", "JobFlowIds[]"), ("TerminateJobFlows", "JobFlowIds[]"))},
    **{f"elasticmapreduce:{operation}": ([{"type": "cluster", "fill": {"ClusterId": "ResourceId"}}],
                                         "the request names the resource it tags by ResourceId ('a cluster identifier "
                                         "or an Amazon EMR Studio ID'), not by its ClusterId, which only 'scopes the "
                                         "tag operation' of a session-scoped resource; Floci tags clusters alone, the "
                                         "one ResourceId names")
       for operation in ("AddTags", "RemoveTags")},
    "elasticmapreduce:ModifyInstanceGroups": (
        [{"type": "cluster", "fill": {"ClusterId": "ClusterId"}, "unless": {"ClusterId": [None]}},
         {"types": ["cluster"], "through": "InstanceGroups[].InstanceGroupId", "when": {"ClusterId": [None]}}],
        "a request may leave out its ClusterId ('The ID of the cluster to which the instance group belongs', "
        "Required: No), or give it a JSON null, which the handler reads as none, and name each instance group by "
        "its InstanceGroupId alone, which is then in a cluster the service finds and modifies the group in; read "
        "through the group, the cluster is the one it is in, and a null ClusterId names no cluster of its own"),
    **{f"rekognition:{operation}": ([{"type": "streamprocessor", "fill": {"StreamprocessorId": "Name"}}],
                                    "the request names the stream processor by the Name it was created with, which "
                                    "its ARN ends in (the Developer Guide's CreateStreamProcessor example: Name "
                                    "streamProcessorForCam, StreamProcessorArn arn:aws:rekognition:us-east-1:"
                                    "nnnnnnnnnnnn:streamprocessor/streamProcessorForCam), and the rule does not take "
                                    "a name for the format's id")
       for operation in ("DeleteStreamProcessor", "DescribeStreamProcessor", "StartStreamProcessor",
                         "StopStreamProcessor", "UpdateStreamProcessor")},
    **{f"sso:{operation}": ([{"types": ["Account", "Instance", "PermissionSet"], "arn": "InstanceArn"},
                             {"types": ["Account", "Instance", "PermissionSet"], "arn": "PermissionSetArn"},
                             {"type": "Account", "fill": {"AccountId": "TargetId"}}],
                            "the request names the account it assigns or provisions the permission set to by TargetId "
                            "('TargetID is an Amazon Web Services account identifier'), which the rule does not take "
                            "for the id of the Account the action acts on")
       for operation in ("CreateAccountAssignment", "DeleteAccountAssignment")},
    "sso:ProvisionPermissionSet": (
        [{"types": ["Account", "Instance", "PermissionSet"], "arn": "InstanceArn"},
         {"types": ["Account", "Instance", "PermissionSet"], "arn": "PermissionSetArn"},
         {"type": "Account", "fill": {"AccountId": "TargetId"}, "when": {"TargetType": ["AWS_ACCOUNT"]}},
         {"types": ["Account"], "through": "PermissionSetArn", "when": {"TargetType": ["ALL_PROVISIONED_ACCOUNTS"]}}],
        "the request names the account it provisions the permission set to by TargetId ('TargetID is an Amazon Web "
        "Services account identifier'), which the rule does not take for the id of the Account the action acts on; "
        "with the TargetType ALL_PROVISIONED_ACCOUNTS it names none and provisions the permission set to every "
        "account it is provisioned to ('The ProvisionPermissionSet API operation can provision a permission set "
        "using the option ALL_PROVISIONED_ACCOUNTS to, at most, 3,500 AWS accounts', the AWS_ACCOUNT option "
        "provisioning it 'in a single AWS account', IAM Identity Center User Guide), read through the permission set; "
        "its TargetId names the account only for AWS_ACCOUNT, the handler reading a null one as none"),
    "redshift:GetClusterCredentials": ([
        {"type": "dbname", "fill": {"ClusterName": "ClusterIdentifier", "DbName": "DbName"},
         "unless": {"DbName": [None]}},
        {"type": "dbuser", "fill": {"ClusterName": "ClusterIdentifier", "DbUser": "DbUser"}},
    ], "the database is checked only when the request names one ('If the DbName parameter is specified, the IAM "
       "policy must allow access to the resource dbname for the specified database name'), and the Management "
       "Guide's policy granting the action on a dbuser alone allows it; the rule names the database by "
       "ClusterIdentifier alone when DbName is absent"),
    **{f"redshift:{operation}": ([{"types": ["cluster", "eventsubscription", "hsmclientcertificate", "hsmconfiguration",
                                             "integration", "parametergroup", "securitygroup",
                                             "securitygroupingress-cidr", "securitygroupingress-ec2securitygroup",
                                             "snapshot", "snapshotcopygrant", "snapshotschedule", "subnetgroup",
                                             "usagelimit"], "arn": "ResourceName"}],
                                 "the request names the resource by its ARN in ResourceName ('The Amazon Resource "
                                 "Name (ARN) to which you want to add the tag or tags'), whose name does not end in "
                                 "Arn")
       for operation in ("CreateTags", "DeleteTags", "DescribeTags")},
    "macie2:CreateMember": ([{"type": "Member", "fill": {"ResourceId": "account.accountId"}}],
                            "the member ARN ends in the account the request associates, which it names as "
                            "account.accountId, nested where the rule does not look for the member's id"),
}

# Resource types whose ARN a request may give with a qualifier, one more `:<name>` past its format, which their
# service drops to check it, and why.
UNQUALIFIED: dict[str, str] = {
    "states:statemachine": "Step Functions authorizes a version or an alias on its state machine's ARN, not on the "
                           "version's or the alias's ('To authorize a version or an alias to invoke an API action, "
                           "Step Functions uses the state machine's ARN instead of using the version ARN or alias ARN')",
}

# Resource types whose service follows the one variable their format ends in with an id it mints, `/<id>`,
# which the format leaves out and a request naming one by its name does not give, and why.
MINTED_IDS: dict[str, str] = {
    "events:connection": "EventBridge mints a connection's ARN as connection/<name>/<id> (botocore's ConnectionArn "
                         "pattern ends in connection/<name>/<id>) and checks a connection on it: the user "
                         "guide grants events:RetrieveConnectionCredentials on connection/<name>/*",
    "events:api-destination": "EventBridge mints an API destination's ARN as api-destination/<name>/<id> "
                              "(botocore's ApiDestinationArn pattern ends in api-destination/<name>/<id>), "
                              "as it does a connection's",
}

# Resource types whose ARN is all an id their service mints, which a request names by members the ARN does not
# hold, the members in the order the service reads them, and why: an operation whose request has them all names the
# type by them, and the reader asks the service for the ARN they find.
FOUND_BY: dict[str, tuple[list[str], str]] = {
    "application-autoscaling:ScalableTarget": (
        ["ServiceNamespace", "ResourceId", "ScalableDimension"],
        "a scalable target's ARN ends in its unique id, scalable-target/<id> ('The unique ID "
        "(1234abcd56ab78cd901ef1234567890ab123) is a value assigned by Application Auto Scaling to the scalable "
        "target', Application Auto Scaling User Guide), which the reference names ${ResourceId}, while its requests "
        "name the target by ServiceNamespace, ResourceId and ScalableDimension; ResourceId is the target's "
        "resource (service/<cluster>/<service>), not that id"),
    **{f"redshift-serverless:{kind}": ([member], f"a Redshift Serverless {kind}'s ARN ends in the id the service gives "
                                                  f"it, {path}/<id> ('The unique identifier' of its {kind}), while its "
                                                  f"requests name it by {member}")
       for kind, member, path in (("namespace", "namespaceName", "namespace"),
                                  ("workgroup", "workgroupName", "workgroup"),
                                  ("snapshot", "snapshotName", "snapshot"),
                                  ("endpointAccess", "endpointName", "managedvpcendpoint"))},
    "redshift-data:workgroup": (
        ["WorkgroupName"],
        "a serverless workgroup's ARN ends in the id Redshift Serverless gives it, workgroup/<id> (its Workgroup's "
        "workgroupId, 'The unique identifier of the workgroup'; botocore's WorkgroupNameString takes an ARN ending "
        "in workgroup/<uuid>), while a Data API request names the workgroup by WorkgroupName, 'The serverless "
        "workgroup name or Amazon Resource Name (ARN)'"),
}

# Members naming a resource of their service's own the reference gives no type, through which a request names a
# resource of one it does, the types that resource may be of, and why: an operation whose request has the member,
# and whose action acts on one of those types, names it through the member, and the reader asks the service for the
# ARN of the caller's resource at the other end.
THROUGH: dict[str, tuple[list[str], str]] = {
    "redshift-data:SessionId": (
        ["cluster", "workgroup"],
        "a Data API session runs on the cluster or the serverless workgroup it was opened on (ListSessions filters "
        "sessions 'by session status, compute target (cluster or serverless workgroup), or database'), and its "
        "SessionId 'can then be used in subsequent ExecuteStatement and BatchExecuteStatement operations' "
        "(Amazon Redshift Management Guide), which then run there"),
}

# The variables of an ARN format the account, partition and Region fill.
KNOWN_VARIABLES = {"Partition", "Region", "Account"}

# A staged read of heterogeneous resource-id lists. The model supplies the member and its wire name;
# the Service Reference supplies the action's types and their ARN formats. EC2 documents instance ids
# with the i- prefix (EC2 User Guide, resource-ids). Only instances are covered here: other ids retain
# a wildcard entry, and other EC2 requests retain their existing wildcard authorization until their
# ARN resolution and condition contexts have coverage. No operation names select this read.
ID_TARGETS = {"ec2": {"member": "Resources", "prefixes": {"instance": "i-"}}}
# An ARN format's account given as `${AccountId}`, the account `${Account}` gives in every other format.
ACCOUNT_ID_SLOT_RE = re.compile(r"^(arn:[^:]*:[^:]*:[^:]*:)\$\{AccountId}:")
VARIABLE_RE = re.compile(r"\$\{(\w+)}")
STEM_SUFFIX_RE = re.compile(r"(arns|arn|names|name|identifiers|identifier|ids|id|config|s)$")
# A bare member that names the resource of the type its operation acts on.
BARE = {"name", "names", "id", "ids", "identifier", "identifiers"}
# The patterns botocore gives a string shape that holds an account id.
ACCOUNT_ID_PATTERNS = {"[0-9]{12}", "^[0-9]{12}$", r"\d{12}", r"^\d{12}$"}
# The verbs an operation that creates the resource it is named after starts with.
CREATING_RE = re.compile(r"^(Create|Run|Allocate|Register|Put|Import|Copy|Request|Start|Add|Enable)")
# An operation's verb: what follows is what it acts on.
SUBJECT_RE = re.compile(r"^[A-Z][a-z]+")
# The protocols whose requests RequestResources reads: form parameters or a JSON or CBOR body.
READ_PROTOCOLS = {"json", "query", "ec2", "smithy-rpc-v2-cbor", "rest-json"}
# Protocols whose requests are read for the members a request carries in its path and query alone: RequestResources
# reads those the same way whatever the protocol, while a REST-XML body is not one it reads.
LOCATED_ONLY_PROTOCOLS = {"rest-xml"}
BODY_LOCATIONS = (None, "")
# Where a REST request carries a member outside its body, as the file writes it: a label of its path, `{Name}`, or a
# parameter of its query, `?name`.
LOCATED_RE = re.compile(r"^(\{[A-Za-z0-9_]+\}|\?[A-Za-z0-9_.-]+)$")


def stem(name: str) -> str:
    """The name lowercased, without what is not a letter or digit, and without a trailing `Arn`,
    `Name`, `Id`, `Identifier`, `Config` or plural, twice over (`TargetGroupArns` is `targetgroup`,
    `DistributionConfig` is `distribution`)."""
    value = re.sub(r"[^a-z0-9]", "", name.lower())
    for _ in range(2):
        value = STEM_SUFFIX_RE.sub("", value)
    return value


def related(a: str, b: str) -> bool:
    """Whether two stems name the same thing: equal, or one ends with the other, four letters at least."""
    return a == b or (min(len(a), len(b)) >= 4 and (a.endswith(b) or b.endswith(a)))


# --------------------------------------------------------------------------- #
# The Service Reference
# --------------------------------------------------------------------------- #
def checked_actions(documents: list[dict]) -> dict[str, dict]:
    """Each operation of each namespace, as `<namespace>:<Operation>`, with the action Floci checks it
    as, that action's resource types (null when the namespace has no action of that name) and the
    operation's SDK clients."""
    mapped = authorized_actions_from(documents)
    found = {}
    for document in documents:
        namespace = document["Name"]
        actions = {action["Name"].lower(): action for action in document.get("Actions", [])}
        for operation in document.get("Operations", []):
            name = operation["Name"]
            action_name = mapped.get(namespace, {}).get(name, f"{namespace}:{name}").split(":", 1)[1]
            action = actions.get(action_name.lower())
            found[f"{namespace}:{name}"] = {
                "action": f"{namespace}:{action['Name']}" if action else f"{namespace}:{action_name}",
                "types": None if action is None else [resource["Name"] for resource in action.get("Resources") or []],
                "clients": sorted({sdk["Name"] for sdk in operation.get("SDK", []) if sdk.get("Package") == "Boto3"}),
            }
    return found


def arn_formats(documents: list[dict], namespaces: set[str]) -> dict[str, dict[str, str]]:
    """Each resource type of the namespaces given, as its first ARN format, its account `${Account}` where the
    reference names it `${AccountId}` (DataSync's, IAM Identity Center's applications)."""
    formats = {}
    for document in documents:
        if document["Name"] in namespaces:
            formats[document["Name"]] = dict(sorted(
                (resource["Name"], ACCOUNT_ID_SLOT_RE.sub(r"\1${Account}:", resource["ARNFormats"][0]))
                for resource in document.get("Resources", []) if resource.get("ARNFormats")))
    return dict(sorted(formats.items()))


# --------------------------------------------------------------------------- #
# botocore
# --------------------------------------------------------------------------- #
def _protocols(model: dict) -> set[str]:
    metadata = model["metadata"]
    return set(metadata.get("protocols") or [metadata.get("protocol")])


def with_absent_arn(resources: list[dict], formats: dict[str, str], optional: set[str]) -> list[dict]:
    """Adds, for an optional top-level member holding the ARN of a type, that type for a request leaving the member
    out. A type whose format has no variable of its own (Security Hub's `hub/default`) has one resource in the caller's
    account and Region, the one its service acts on then (`DescribeHub` with no `HubArn`). Any other is the one its
    service uses for a request naming none (`default`), which the reader asks the service for, and none when it has
    none: Resource Explorer's `Search` with no `ViewArn` searches with the Region's default view."""
    named = {resource["type"] for resource in resources if "type" in resource}
    added = []
    for resource in resources:
        member = resource.get("arn")
        if "types" not in resource or member not in optional:
            continue
        for kind in resource["types"]:
            variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
            if kind in formats and kind not in named and related(stem(member), stem(kind)):
                added.append({"type": kind, "fill": {}, "when": {member: [None]}} if not variables
                             else {"type": kind, "fill": {}, "when": {member: [None]}, "default": True})
                named.add(kind)
    return resources + added


def map_fields(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The fields of each value of a top-level map of structures in the operation's request body that hold a string and
    end as an id, a name or an identifier, by their path, `Name{}.Field`, each with the field's name."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or _reading(model) != "all":
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            if member.get("location") not in BODY_LOCATIONS or shape["type"] != "map":
                continue
            value = model["shapes"][shape["value"]["shape"]]
            for field, nested in value.get("members", {}).items() if value["type"] == "structure" else ():
                if kind_of(field) and model["shapes"][nested["shape"]]["type"] == "string":
                    found.setdefault(f"{name}{{}}.{field}", field)
    return found


def with_map_fields(resources: list[dict], types: list[str], formats: dict[str, str],
                    fields: dict[str, str]) -> list[dict]:
    """The resources, and a type the action acts on whose format has one variable of its own and that no resource
    names by its variables, named by the one field of each value of a map of structures named after it, one resource
    for each value: FIS's `CreateExperimentTemplate` acts on each action its `actions` map names by `actionId`."""
    named = {resource["type"] for resource in resources if "type" in resource}
    added = []
    for kind in types:
        variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
        if kind in named or kind not in formats or len(variables) != 1:
            continue
        filling = [path for path, field in sorted(fields.items())
                   if stem(field) == stem(kind) and compatible(field, variables[0])]
        if len(filling) == 1:
            added.append({"type": kind, "fill": {variables[0]: filling[0]}})
    return resources + added


def with_absent_account(resources: list[dict], formats: dict[str, str], accounts: list[str],
                        optional: set[str]) -> list[dict]:
    """The resources, a type whose format has no variable of its own applying only to a request that leaves out its
    one account id member, when that member is optional and fills a variable of another type: a request naming an
    account acts on that resource, and one naming none on the caller's own account. Account Management's `AccountId`
    names a member account of the caller's organization (`accountInOrganization`), and a request without it acts on
    the caller's `account`."""
    if len(accounts) != 1 or accounts[0] not in optional:
        return resources
    member = accounts[0]
    if not any(member in {path.removesuffix("[]") for path in resource.get("fill", {}).values()}
               for resource in resources):
        return resources
    return [dict(resource, when={member: [None]}) if resource.get("fill") == {} and "when" not in resource
            and not [v for v in VARIABLE_RE.findall(formats.get(resource["type"], "")) if v not in KNOWN_VARIABLES]
            else resource for resource in resources]


def path_labels(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The bare members (`BARE`) the operation's REST request carries as a path label after a segment naming
    something, each with that segment stemmed, a plural in `ies` read as its `y`: FIS's `id` in
    `/safetyLevers/{id}/state` is `safetylever`, Route 53's `Id` in `/trafficpolicies/{Id}/versions` is
    `trafficpolicy`."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or "http" not in spec:
            continue
        segments = spec["http"]["requestUri"].split("?", 1)[0].strip("/").split("/")
        labelled = {match.group(1): stem(re.sub(r"ies$", "y", segments[index - 1]))
                    for index, segment in enumerate(segments) if index and "{" not in segments[index - 1]
                    and (match := re.fullmatch(r"\{(\w+)\+?}", segment))}
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            label = member.get("locationName", name)
            if member.get("location") == "uri" and name.lower() in BARE and label in labelled:
                found[name] = labelled[label]
    return found


def optional_members(models: dict[str, dict], operation: str) -> set[str]:
    """The top-level members of the operation's request that hold a string and that it may leave out."""
    found: set[str] = set()
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        root = model["shapes"][spec["input"]["shape"]]
        required = set(root.get("required", []))
        for name, member in root.get("members", {}).items():
            if name not in required and model["shapes"][member["shape"]].get("type") == "string":
                found.add(name)
    return found


def _reading(model: dict) -> str | None:
    """How a model's requests are read: "all" of their members, only the "located" ones, or None, not at all."""
    protocols = _protocols(model)
    if READ_PROTOCOLS & protocols:
        return "all"
    return "located" if LOCATED_ONLY_PROTOCOLS & protocols else None


def request_members(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The top-level members of the operation's request that hold a string (`Name`) or a list of
    strings (`Name[]`), by name, and the ARN members one level inside one (`Name.FieldArn`,
    `Name[].FieldArn`), by that path, as the vendored path reads them; empty for a request this file
    does not read."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        reading = _reading(model)
        if spec is None or "input" not in spec or reading is None:
            continue
        root = model["shapes"][spec["input"]["shape"]]
        for name, member in root.get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
                continue
            if reading == "located" and member.get("location") in BODY_LOCATIONS:
                continue
            shape = model["shapes"][member["shape"]]
            if _strings(model, shape) is not None:
                found.setdefault(name, name + _strings(model, shape))
            for path in _nested_arns(model, name, shape):
                found.setdefault(path.removesuffix("[]"), path)
    return found


def enum_members(models: dict[str, dict], operation: str) -> dict[str, set[str]]:
    """The top-level string members of the operation's request that this file reads, with the values their enum
    lists; a member with no enum is left out."""
    found: dict[str, set[str]] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        reading = _reading(model)
        if spec is None or "input" not in spec or reading is None:
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
                continue
            if reading == "located" and member.get("location") in BODY_LOCATIONS:
                continue
            values = model["shapes"][member["shape"]].get("enum")
            if values:
                found.setdefault(name, set()).update(values)
    return found


def picked_by_enum(types: list[str], formats: dict[str, str], members: dict[str, str],
                   enums: dict[str, set[str]]) -> list[dict] | None:
    """The resources of a request a member of which picks the type: one whose enum lists two or more of the action's
    types, a value naming a type when it matches it ignoring case and hyphens (Route 53's tag calls name a
    `ResourceType` of `healthcheck` or `hostedzone`, SSM's a `Document` or a `ManagedInstance`). Each type it lists is
    filled from the member naming whatever the action acts on (`ResourceId`), whatever kind of value the type's
    variable is, when the request holds that value; None when no member picks a type, or no member names the
    resource."""
    for picker, values in sorted(enums.items()):
        kinds = {kind: value for kind in types if kind in formats for value in values
                 if _enum_key(value) == _enum_key(kind)}
        if len(kinds) < 2:
            continue
        picked = []
        for kind, value in kinds.items():
            variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
            if len(variables) != 1:
                return None
            named = [member for member in members if member != picker and stem(member) == "resource"]
            if not named:
                return None
            member = min(named, key=lambda m: (members[m].endswith("[]"), len(m), m))
            picked.append({"type": kind, "fill": {variables[0]: members[member]}, "when": {picker: [value]}})
        return picked
    return None


def _enum_key(value: str) -> str:
    """An enum value or a resource type as the two are compared: lowercased, without hyphens."""
    return value.lower().replace("-", "")


def string_patterns(models: dict[str, dict], operation: str) -> dict[str, set[str | None]]:
    """Patterns of each top-level string member, including list elements, across the request's clients."""
    found: dict[str, set[str | None]] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        reading = _reading(model)
        if spec is None or "input" not in spec or reading is None:
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
                continue
            if reading == "located" and member.get("location") in BODY_LOCATIONS:
                continue
            shape = model["shapes"][member["shape"]]
            if _strings(model, shape) is None:
                continue
            element = model["shapes"][shape["member"]["shape"]] if shape["type"] == "list" else shape
            found.setdefault(name, set()).add(element.get("pattern"))
    return found


def only_arn(pattern: str | None) -> bool:
    """A required ARN prefix, with no top-level alternative that could instead name another value."""
    if not pattern or not re.match(r"^\^?arn:", pattern):
        return False
    depth = 0
    in_class = False
    escaped = False
    for char in pattern:
        if escaped:
            escaped = False
        elif char == "\\":
            escaped = True
        elif in_class:
            in_class = char != "]"
        elif char == "[":
            in_class = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "|" and depth == 0:
            return False
    return True


def located_members(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The members of a REST request that its path or its query carries, holding a string, by name, with where:
    `{Label}` for a label of its path, `?name` for a parameter of its query. The reader lays them over the request's
    body under their names, so a resource reads them as it reads a member of the body."""
    found: dict[str, str] = {}
    for client, model in sorted(models.items()):
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not ({"rest-json"} | LOCATED_ONLY_PROTOCOLS) & _protocols(model):
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            where = _located(member)
            if where is None or _strings(model, model["shapes"][member["shape"]]) != "":
                continue
            if found.setdefault(name, where) != where:
                raise ValueError(f"{operation}'s {name} is {found[name]} in one client and {where} in {client}")
    return dict(sorted(found.items()))


def _located(member: dict) -> str | None:
    """Where a REST request carries a member outside its body, as `located_members` writes it; None for its body or a
    header."""
    if member.get("location") == "uri":
        return "{" + member.get("locationName", "") + "}"
    if member.get("location") == "querystring":
        return "?" + member.get("locationName", "")
    return None


def _strings(model: dict, shape: dict) -> str | None:
    """How a path reads a shape that holds a string (``) or a list of strings (`[]`); None for another."""
    if shape["type"] == "string":
        return ""
    if shape["type"] == "list" and model["shapes"][shape["member"]["shape"]]["type"] == "string":
        return "[]"
    return None


def _nested_arns(model: dict, name: str, shape: dict) -> list[str]:
    """The members ending in `Arn` or `Arns` of a structure, or of a list's structures, holding a string or a list
    of strings, as `Name.Field` or `Name[].Field`."""
    element = model["shapes"][shape["member"]["shape"]] if shape["type"] == "list" else shape
    if element["type"] != "structure":
        return []
    prefix = name + ("[]." if shape["type"] == "list" else ".")
    return [prefix + field + _strings(model, model["shapes"][member["shape"]])
            for field, member in element.get("members", {}).items()
            if re.search(r"arns?$", field, re.IGNORECASE) and _strings(model, model["shapes"][member["shape"]]) is not None]


def account_members(models: dict[str, dict], operation: str) -> list[str]:
    """The top-level members of the operation's request whose shape is an account id, by name."""
    found: set[str] = set()
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        account_names = {name for other in model["operations"].values() if "input" in other
                         for name, member in model["shapes"][other["input"]["shape"]].get("members", {}).items()
                         if member.get("location") in BODY_LOCATIONS
                         and model["shapes"][member["shape"]].get("pattern") in ACCOUNT_ID_PATTERNS | {r"(\d{12})"}
                         and model["shapes"][member["shape"]]["type"] == "string"}
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            if member.get("location") in BODY_LOCATIONS and shape["type"] == "string" \
                    and (shape.get("pattern") in ACCOUNT_ID_PATTERNS
                         or (name in account_names and not CREATING_RE.match(operation))):
                found.add(name)
    return sorted(found)


# A pattern that takes an ARN of a type before a name: `(arn:...:event-bus\/)?<name>`, the type's word captured.
NAME_OR_ARN_RE = re.compile(r"^\(arn:[^)]*:([A-Za-z][A-Za-z0-9-]*)\\?/\)\?")


def name_or_arn_members(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The top-level members of the operation's request whose shape's pattern takes an ARN before a name, by name,
    and the fields of each element of a list of structures whose shape's does, as `Name[].Field`, each with the
    resource type word of that ARN."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS:
                continue
            shape = model["shapes"][member["shape"]]
            word = _name_or_arn(shape)
            if word:
                found.setdefault(name, word)
            element = model["shapes"][shape["member"]["shape"]] if shape["type"] == "list" else {}
            if element.get("type") == "structure":
                for field, nested in element.get("members", {}).items():
                    word = _name_or_arn(model["shapes"][nested["shape"]])
                    if word:
                        found.setdefault(f"{name}[].{field}", word)
    return found


def _name_or_arn(shape: dict) -> str | None:
    """The resource type word of the ARN a string shape's pattern takes before a name; None for another shape."""
    match = NAME_OR_ARN_RE.match(shape.get("pattern") or "") if shape["type"] == "string" else None
    return match.group(1) if match else None


def named_by(word: str, formats: dict[str, str]) -> str | None:
    """The type whose ARN format's resource is the word and one variable (`event-bus/${EventBusName}`)."""
    found = [kind for kind, arn in formats.items()
             if re.fullmatch(r"arn:\$\{Partition}:[^:]*:[^:]*:[^:]*:" + re.escape(word) + r"/\$\{\w+}", arn)]
    return found[0] if len(found) == 1 else None


def with_arn_of(resources: list[dict], formats: dict[str, str], members: dict[str, str]) -> list[dict]:
    """The resources, each a type named by its variables reading a member that may give an ARN of a type instead of
    its name, as that type's one variable."""
    found = []
    for resource in resources:
        read = {path.removesuffix("[]") for path in resource.get("fill", {}).values()} | set(resource.get("when", {})) \
            | set(resource.get("unless", {})) | set(resource.get("absent", {}))
        arn_of = {member: named_by(members[member], formats) for member in sorted(read) if member in members}
        arn_of = {member: kind for member, kind in arn_of.items() if kind is not None}
        found.append(dict(resource, arnOf={**resource.get("arnOf", {}), **arn_of})
                     if "fill" in resource and "default" not in resource and arn_of else resource)
    return found


def with_parent_ids(resources: list[dict], formats: dict[str, str], types: list[str],
                    members: dict[str, str]) -> list[dict]:
    """The resources, each a type named by its variables taking one no member fills from the ARN of another type the
    action acts on whose format is that variable alone, given by the one top-level ARN member named after that type:
    a permission set made in an instance, `permissionSet/${InstanceId}/${PermissionSetId}`, has the id of the
    instance the request's InstanceArn names, `instance/${InstanceId}`, and its own id open."""
    found = []
    for resource in resources:
        if "fill" not in resource or "find" in resource:
            found.append(resource)
            continue
        fill, arn_of = dict(resource["fill"]), dict(resource.get("arnOf", {}))
        for parent in types:
            variables = [v for v in VARIABLE_RE.findall(formats.get(parent, "")) if v not in KNOWN_VARIABLES]
            if parent == resource["type"] or len(variables) != 1 or variables[0] in fill \
                    or variables[0] not in VARIABLE_RE.findall(formats[resource["type"]]) \
                    or not ONE_VARIABLE_RE.match(_resource(formats[parent])):
                continue
            held = [member for member, path in members.items() if path == member
                    and re.search(r"arn$", member, re.IGNORECASE) and related(stem(member), stem(parent))]
            if len(held) == 1:
                fill[variables[0]] = held[0]
                arn_of[held[0]] = parent
        found.append(dict(resource, fill=dict(sorted(fill.items())), arnOf=arn_of) if fill != resource["fill"]
                     else resource)
    return found


def with_account(resources: list[dict], formats: dict[str, str], accounts: list[str]) -> list[dict]:
    """The resources, each a type named by its variables taking its account from the request's one account id
    member, when it has exactly one and the type's format has an account, unless the resource applies only when that
    member is absent."""
    if len(accounts) != 1:
        return resources
    return [dict(resource, account=accounts[0]) if "fill" in resource and "default" not in resource
            and "${Account}" in formats.get(resource["type"], "")
            and resource.get("when", {}).get(accounts[0]) != [None] else resource for resource in resources]


def structure_account_members(models: dict[str, dict], operation: str, types: list[str]) -> dict[str, str]:
    """The top-level members of the operation's request whose name ends in `Account`, by the type whose botocore
    structure (the one named after the type, `Snapshot` for `snapshot`) has a member of that same name; a type
    whose structure has two of them, or none, is left out."""
    found: dict[str, set[str]] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        accounts = [name for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items()
                    if member.get("location") in BODY_LOCATIONS and re.search(r"account$", name, re.IGNORECASE)
                    and model["shapes"][member["shape"]]["type"] == "string"]
        for kind in types:
            structures = [shape for name, shape in model["shapes"].items()
                          if shape.get("type") == "structure" and stem(name) == stem(kind)]
            for shape in structures:
                found.setdefault(kind, set()).update(name for name in accounts if name in shape.get("members", {}))
    return {kind: next(iter(names)) for kind, names in sorted(found.items()) if len(names) == 1}


def with_structure_account(resources: list[dict], formats: dict[str, str], accounts: dict[str, str]) -> list[dict]:
    """The resources, each a type named by its variables and given no account yet taking the one the request's
    member of its structure's name gives, when the type's format has an account."""
    return [dict(resource, account=accounts[resource["type"]]) if "fill" in resource and "account" not in resource
            and resource["type"] in accounts and "${Account}" in formats.get(resource["type"], "") else resource
            for resource in resources]


def _kinds(resource: dict) -> list[str]:
    """The resource types a resource names, as the strings they are: the types it acts on, or the one it names."""
    named: list = list(resource.get("types") or [resource.get("type")])
    return [kind for kind in named if isinstance(kind, str)]


def kind_of(name: str) -> str:
    """Whether a name ends as a name, an id, or an identifier (which is either), or neither."""
    lower = name.lower()
    if re.search(r"identifiers?$", lower):
        return "identifier"
    if re.search(r"names?$", lower):
        return "name"
    if re.search(r"ids?$", lower):
        return "id"
    return ""


def compatible(member: str, variable: str) -> bool:
    """Whether a member can fill a variable: a name fills a name, an id an id, an identifier either."""
    member_kind, variable_kind = kind_of(member), kind_of(variable)
    return not member_kind or not variable_kind or member_kind == variable_kind or member_kind == "identifier"


def resources_for(operation: str, types: list[str], formats: dict[str, str], members: dict[str, str],
                  arn_members: set[str] | None = None, labels: dict[str, str] | None = None) -> list[dict]:
    """The resources the rule finds the operation's request naming, as vendored entries. `labels` gives the path
    segment before each bare member a REST request carries as a path label, stemmed (`path_labels`)."""
    name = operation.split(":", 1)[1]
    created = stem(CREATING_RE.sub("", name)) if CREATING_RE.match(name) else None
    subject = stem(SUBJECT_RE.sub("", name))
    arns = {member: path for member, path in members.items() if re.search(r"arns?$", member, re.IGNORECASE)
            or member in (arn_members or set())}
    names = {member: path for member, path in members.items() if member not in arns}
    by_arn: dict[str, list[str]] = {}
    filled: dict[str, dict[str, str]] = {}
    used: dict[str, str] = {}
    complete: dict[str, bool] = {}
    for kind in types:
        if kind not in formats:
            continue
        kind_stem = stem(kind)
        for member in sorted(arns):
            by_arn.setdefault(member, []).append(kind)
        # A top-level ARN member named for the type, or for whatever the action acts on, holds it, and a bare
        # name then names something else.
        held = [member for member in arns if "." not in member and (stem(member) in ("", "resource")
                or (stem(member) == kind_stem if member in (arn_members or set())
                    else related(stem(member), kind_stem) or kind_stem.startswith(stem(member))))]
        variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
        # A bare `Name` or `Id` names the type the operation is about, or the one the path segment before it
        # names: `UpdateSafetyLeverState`'s `id`, in `/safetyLevers/{id}/state`, is the lever's.
        bare = set() if held else set(names) if related(subject, kind_stem) \
            else {member for member, segment in (labels or {}).items() if related(segment, kind_stem)}
        # A member fills the resource's own variables before the account its ARN is in, which a request gives
        # through an account member alone: Account Management's `AccountId` is the member account an
        # `account/o-${OrganizationId}/${MemberAccountId}` names, not its management account.
        slot = ACCOUNT_SLOT_RE.match(formats[kind])
        in_slot = slot.group(1) if slot else None
        fill: dict[str, str] = {}
        for variable in sorted(variables, key=lambda v: (v == in_slot, kind_of(v) == "", v)):
            choice = _filling(variable, kind_stem, len(variables), {m: p for m, p in names.items() if m not in fill.values()},
                              bare=bare, qualified=namespace_of(operation) in QUALIFIED_MEMBERS)
            if choice is not None:
                fill[variable] = choice
                used[kind] = choice
        fill = {variable: names[member] for variable, member in fill.items()}
        if len([path for path in fill.values() if path.endswith("[]")]) > 1:
            fill = {variable: path for variable, path in fill.items() if not path.endswith("[]")}
        # A create makes its own type, so a member holding whatever the action acts on does not hold that one:
        # `CreateResourceShare`'s `resourceArns` are what the share it makes holds.
        creates_it = created is not None and related(created, kind_stem)
        holding = [member for member in held if not (creates_it and stem(member) in ("", "resource"))]
        if fill or (not holding and (not variables or creates_it)):
            filled[kind] = fill
            complete[kind] = len(fill) == len(variables)
    # Types named only through the same members are alternatives the request picks one of: the one it names in
    # full, or none when that is not one type, rather than checking every one.
    by_members: dict[tuple, list[str]] = {}
    for kind, fill in filled.items():
        by_members.setdefault(tuple(sorted(set(fill.values()))), []).append(kind)
    kept = []
    for kinds in by_members.values():
        if len(kinds) == 1 or not by_members_key_is_empty(kinds, filled):
            full = [kind for kind in kinds if complete[kind]]
            kept += kinds if len(kinds) == 1 else (full if len(full) == 1 else [])
    specs = [{"types": kinds, "arn": arns[member]} for member, kinds in sorted(by_arn.items())]
    qualified = namespace_of(operation) in QUALIFIED_MEMBERS
    return specs + [{"type": kind, "fill": dict(sorted(filled[kind].items()))} for kind in types if kind in kept] + \
        also_filled(types, members, filled, kept, used, qualified)


RESOURCE_PART_RE = re.compile(r"^arn:\$\{Partition}:[^:]*:[^:]*:[^:]*:([A-Za-z][A-Za-z0-9-]*)(.*)$")
# An ARN format whose account is a variable of its own, captured: `arn:${Partition}:account::${ManagementAccountId}:`.
ACCOUNT_SLOT_RE = re.compile(r"^arn:\$\{Partition}:[^:]*:[^:]*:\$\{(\w+)}:")


def with_made(resources: list[dict], operation: str, formats: dict[str, str]) -> list[dict]:
    """The resources, those a create makes marked `made` when it names others too (the pipeline a webhook connects
    to, the namespace a service is created in), so that its tags are checked on what it makes alone: of a type, or
    with an ARN format's leading resource word, related to the operation's name past its verb. A member that may hold
    an ARN of several types is made only when every one of them is: one that may name the parent too (a subscriber's
    `EventBusArn`) names a resource the create does not make."""
    name = operation.split(":", 1)[1]
    if not CREATING_RE.match(name):
        return resources
    created = stem(CREATING_RE.sub("", name))

    def is_created(kind: str) -> bool:
        word = RESOURCE_PART_RE.match(formats.get(kind, ""))
        return related(created, stem(kind)) or (word is not None and related(created, stem(word.group(1))))

    def makes(resource: dict) -> bool:
        return all(is_created(kind) for kind in _kinds(resource))

    made = [makes(resource) for resource in resources]
    if all(made) or not any(made):
        return resources
    return [dict(resource, made=True) if is_made else resource
            for resource, is_made in zip(resources, made, strict=True)]


def child_id(child: str, parent: str) -> str | None:
    """The variable a child type's ARN format adds to its parent's: the parent's with its leading resource type word
    replaced and one id appended (`listener/app/.../${ListenerId}` for `loadbalancer/app/...`); None for any other."""
    child_match, parent_match = RESOURCE_PART_RE.match(child), RESOURCE_PART_RE.match(parent)
    if child_match is None or parent_match is None or child_match.group(1) == parent_match.group(1):
        return None
    appended = re.fullmatch(re.escape(parent_match.group(2)) + r"[/:]\$\{(\w+)}", child_match.group(2))
    return appended.group(1) if appended else None


def creates_for(operation: str, types: list[str], formats: dict[str, str], members: dict[str, str]) -> list[dict]:
    """The resources a create of a type its action does not act on makes, named from the ARN of the parent it acts
    on: `CreateListener` acts on a load balancer and makes a listener under it, its id open."""
    name = operation.split(":", 1)[1]
    if not CREATING_RE.match(name):
        return []
    created = stem(CREATING_RE.sub("", name))
    arns = sorted(member for member in members if "." not in member and re.search(r"arns?$", member, re.IGNORECASE))
    found = []
    for child in sorted(formats):
        if child in types:
            continue
        for parent in types:
            appended = child_id(formats[child], formats.get(parent, ""))
            if appended is None or not related(stem(appended), created):
                continue
            for member in arns:
                if stem(member) in ("", "resource") or related(stem(member), stem(parent)) \
                        or stem(parent).startswith(stem(member)):
                    found.append({"type": child, "parent": parent, "arn": members[member]})
    return found


def makes_for(operation: str, types: list[str], formats: dict[str, str], members: dict[str, str],
              arn_members: set[str] | None = None, labels: dict[str, str] | None = None) -> list[dict]:
    """The resource a create whose action acts on no resource type makes: of the one type of its namespace named as
    the operation is past its verb, or else related to that name, named as the rule names a type an action acts on,
    its variables nothing fills open. AWS checks the tagging action a create's tags need on the resource being
    tagged, so they are checked there (`CreateView` makes `view/<ViewName>/*`, `CreateSink` `sink/*`), and the create
    itself stays checked on `*`."""
    name = operation.split(":", 1)[1]
    if types or not CREATING_RE.match(name):
        return []
    created = stem(CREATING_RE.sub("", name))
    kinds = [kind for kind in formats if stem(kind) == created] \
        or [kind for kind in formats if related(created, stem(kind))]
    if len(kinds) != 1:
        return []
    return [resource for resource in resources_for(operation, kinds, formats, members, arn_members, labels)
            if resource.get("type") == kinds[0]][:1]


def by_members_key_is_empty(kinds: list[str], filled: dict[str, dict[str, str]]) -> bool:
    """Whether the types are named by no member at all: each a create's own type, all ids minted."""
    return all(not filled[kind] for kind in kinds)


def _filling(variable: str, kind_stem: str, variables: int, names: dict[str, str], bare: set[str],
             qualified: bool = False) -> str | None:
    """The member that fills the variable: one of a compatible kind whose stem relates to the variable's,
    or, for the variable named after the type or a type's only one, a member named after the type, or a
    bare `Name` or `Id` among those `bare` gives, of a compatible kind; the closest of them, and a string
    before a list."""
    variable_stem = stem(variable)
    candidates = [member for member in names
                  if stem(member) and related(stem(member), variable_stem) and compatible(member, variable)]
    if not candidates and (related(variable_stem, kind_stem) or variables == 1):
        candidates = [member for member in names if compatible(member, variable)
                      and ((member in bare and member.lower() in BARE)
                           or (stem(member) and (related(stem(member), kind_stem)
                                                 or (qualified and names_type(member, kind_stem)))))]
    if not candidates:
        return None
    # A member naming the type before the variable (`SnapshotClusterIdentifier` for the snapshot's `ClusterName`,
    # `DistributionTenantId` for the tenant's `Id`) wins over a bare one, which names something else.
    return min(candidates, key=lambda member: (stem(member) != kind_stem + variable_stem, stem(member) != variable_stem,
                                               names[member].endswith("[]"), len(member), member))


def also_filled(types: list[str], members: dict[str, str], filled: dict[str, dict[str, str]],
                kept: list[str], used: dict[str, str], qualified: bool) -> list[dict]:
    """The resources a request names besides the one it fills a type with, when it names several of that type and
    acts on each of them.

    <p>A re-encryption decrypts under the key its path names and encrypts under the key its body names, so both are
    acted on and both are checked. A member that names a resource of the type, with a word before it saying which of
    them it is, fills the type's variable as well."""
    found = []
    for kind in types:
        if kind not in kept or kind not in filled or not filled[kind]:
            continue
        kind_stem = stem(kind)
        variable = next(iter(filled[kind]))
        for member in sorted(members):
            if used.get(kind) == member:
                continue
            if not qualified or not names_type(member, kind_stem):
                continue
            if not compatible(member, variable):
                continue
            found.append({"type": kind, "fill": {variable: members[member]}})
    return found


def names_type(member: str, kind_stem: str) -> bool:
    """Whether the member names a resource of the type, with a word before it saying which of them it is: an
    `IncomingKeyIdentifier` names a key, and so does an `OutgoingKeyIdentifier`.

    <p>Only a member that identifies a resource does: a list of tag keys is no key, however its name ends."""
    member_stem = stem(member)
    return kind_of(member) == "identifier" and member_stem != kind_stem and member_stem.endswith(kind_stem)


# --------------------------------------------------------------------------- #
# The vendored document
# --------------------------------------------------------------------------- #
def id_targets(operation: str, types: list[str], formats: dict[str, str], models: dict[str, dict]) -> dict | None:
    """A heterogeneous id list, in model and EC2 Query spelling, with each supported type's discriminator."""
    configured = ID_TARGETS.get(namespace_of(operation))
    if configured is None:
        return None
    found = None
    for model in models.values():
        spec = model["operations"].get(operation.split(":", 1)[1], {})
        if "input" not in spec or "ec2" not in _protocols(model):
            continue
        member = model["shapes"][spec["input"]["shape"]].get("members", {}).get(configured["member"])
        if member is None or _strings(model, model["shapes"][member["shape"]]) != "[]":
            continue
        prefixes = {kind: prefix for kind, prefix in configured["prefixes"].items()
                    if kind in types and kind in formats}
        if not prefixes:
            continue
        for kind in prefixes:
            variables = set(VARIABLE_RE.findall(formats[kind])) - KNOWN_VARIABLES
            if len(variables) != 1:
                raise ValueError(f"{operation}: id target {kind} must have exactly one ARN variable")
        location = member.get("locationName", configured["member"])
        query = member.get("queryName", location[:1].upper() + location[1:])
        target = {"member": configured["member"] + "[]", "query": query, "prefixes": prefixes}
        if found is not None and found != target:
            raise ValueError(f"{operation}: clients disagree on the id target's serialization")
        found = target
    return found


def entry_for(operation: str, upstream: dict, formats: dict[str, str], models: dict[str, dict]) -> dict:
    """What the file says for one operation."""
    types = upstream["types"] or []
    members = request_members(models, operation.split(":", 1)[1])
    patterns = string_patterns(models, operation.split(":", 1)[1])
    # A member whose pattern takes only an ARN holds one when it is an identifier, or names whatever the action acts on
    # (`Resource`, as CloudFront's tagging calls do); another such member names a resource of a type of its own.
    arn_members = {member for member, values in patterns.items()
                   if (kind_of(member) == "identifier" or stem(member) in ("", "resource"))
                   and values and all(only_arn(value) for value in values)}
    if namespace_of(operation) in ID_TARGETS:
        entry = {"action": upstream["action"], "types": types, "resources": []}
        targets = id_targets(operation, types, formats, models)
        if targets is not None:
            entry["targets"] = targets
        return entry
    if operation in CORRECTIONS:
        resources = CORRECTIONS[operation][0]
        endpoints = {model["metadata"]["endpointPrefix"] for model in models.values()}
        unknown = {resource["endpoint"] for resource in resources if "endpoint" in resource} - endpoints
        if unknown:
            raise ValueError(f"CORRECTIONS serves {operation} on {sorted(unknown)}, no endpoint prefix of the clients "
                             f"it reads ({sorted(endpoints)})")
    else:
        labels = path_labels(models, operation.split(":", 1)[1])
        resources = picked_by_enum(types, formats, members, enum_members(models, operation.split(":", 1)[1]))
        if resources is None:
            resources = with_through(with_found(with_parent_ids(resources_for(operation, types, formats, members,
                                                                              arn_members, labels),
                                                                formats, types, members),
                                                namespace_of(operation), types, members),
                                     namespace_of(operation), types, members)
        optional = optional_members(models, operation.split(":", 1)[1])
        resources = with_absent_arn(resources, formats, optional)
        resources = with_absent_account(resources, formats, account_members(models, operation.split(":", 1)[1]),
                                        optional)
        resources = with_map_fields(resources, types, formats, map_fields(models, operation.split(":", 1)[1]))
    resources = with_account(resources, formats, account_members(models, operation.split(":", 1)[1]))
    resources = with_structure_account(resources, formats,
                                       structure_account_members(models, operation.split(":", 1)[1], types))
    resources = with_arn_of(resources, formats, name_or_arn_members(models, operation.split(":", 1)[1]))
    resources = with_made(resources, operation, formats)
    entry = {"action": upstream["action"], "types": types, "resources": resources}
    creates = creates_for(operation, types, formats, members)
    if creates:
        entry["creates"] = creates
    makes = makes_for(operation, types, formats, members, arn_members,
                      path_labels(models, operation.split(":", 1)[1]))
    if makes:
        entry["makes"] = makes
    named = _named(resources) | _named(creates) | _named(makes)
    located = {member: where for member, where in located_members(models, operation.split(":", 1)[1]).items()
               if member in named}
    if located:
        entry["located"] = located
    return entry


def _named(value: object) -> set[str]:
    """The member names an operation's resources read: every key and string they hold, each up to its first `[` or
    `.`, a superset that keeps `located` to the members something reads."""
    if isinstance(value, dict):
        return set().union(*(_named(key) | _named(item) for key, item in value.items())) if value else set()
    if isinstance(value, list):
        return set().union(*(_named(item) for item in value)) if value else set()
    return {re.split(r"[\[{.]", value, maxsplit=1)[0]} if isinstance(value, str) else set()


def with_found(resources: list[dict], namespace: str, types: list[str], members: dict[str, str]) -> list[dict]:
    """The resources, a `FOUND_BY` type the action acts on named by the members its service finds it by, when the
    request has them all, rather than by any it fills: its variables are an id the request does not give."""
    found = [resource for resource in resources if f"{namespace}:{resource.get('type')}" not in FOUND_BY]
    for kind in types:
        by = FOUND_BY.get(f"{namespace}:{kind}", ([], ""))[0]
        if by and all(members.get(member) == member for member in by):
            found.append({"type": kind, "fill": {}, "find": list(by)})
    return found


def with_through(resources: list[dict], namespace: str, types: list[str], members: dict[str, str]) -> list[dict]:
    """The resources, and the one of the `THROUGH` types the action acts on that each member of the request naming
    a resource of its service's own runs on."""
    through = []
    for key, (kinds, _) in sorted(THROUGH.items()):
        member = key.split(":", 1)[1]
        on = [kind for kind in types if kind in kinds]
        if key.startswith(f"{namespace}:") and on and members.get(member) == member:
            through.append({"types": on, "through": member})
    return resources + through


def rest_operations(data: Path) -> dict[str, set[str]]:
    """The operation names of each botocore model whose REST routes Floci reads under a namespace the file reads."""
    return {client: set(model["operations"])
            for client, model in load_models(data, {client for client, namespace in REST_MODELS.items()
                                                    if namespace in NAMESPACES}).items()}


def with_rest_operations(upstream: dict[str, dict], documents: list[dict],
                         given: dict[str, set[str]]) -> dict[str, dict]:
    """`upstream` with the operations of the models Floci reads REST routes for (`regen_rest_routes.MODELS`), each
    given as its operation names: an operation the reference lists gets the model among its clients, and one it lists
    for none of them is checked as the action of its own name, or the one the reference maps it to, with that
    action's resource types, when the reference has that action; one it does not have is left out, so `--verify`
    reports a vendored one as no longer listed. The reference lists the operations of one client of a namespace:
    OpenSearch Service's are the `es` client's alone, while the `opensearch` client's are named after `es` actions all
    the same."""
    mapped = authorized_actions_from(documents)
    actions = {document["Name"]: {action["Name"].lower(): action for action in document.get("Actions", [])}
               for document in documents}
    found = dict(upstream)
    for client, names in sorted(given.items()):
        namespace = REST_MODELS[client]
        for name in sorted(names):
            operation = f"{namespace}:{name}"
            if operation in found:
                if client not in found[operation]["clients"]:
                    found[operation] = {**found[operation], "clients": sorted(found[operation]["clients"] + [client])}
                continue
            action_name = mapped.get(namespace, {}).get(name, operation).split(":", 1)[1]
            action = actions.get(namespace, {}).get(action_name.lower())
            if action is None:
                continue
            found[operation] = {
                "action": f"{namespace}:{action['Name']}",
                "types": [resource["Name"] for resource in action.get("Resources") or []],
                "clients": [client],
            }
    return found


def build(documents: list[dict], provenance: str, data: Path, botocore: str) -> dict:
    upstream = with_rest_operations(checked_actions(documents), documents, rest_operations(data))
    covered = {operation: value for operation, value in upstream.items() if reads(operation)}
    _check_overrides(covered)
    read_namespaces = set(NAMESPACES) | {namespace_of(operation) for operation in covered}
    namespaces = {namespace: sorted({client for operation, value in covered.items()
                                     if namespace_of(operation) == namespace for client in value["clients"]
                                     if client not in CLIENTS_NOT_READ})
                  for namespace in sorted(read_namespaces)}
    formats = arn_formats(documents, read_namespaces)
    unqualified = unqualified_types()
    minted = minted_types()
    missing = [f"{name} names {namespace}:{kind}, no resource type with an ARN format of a namespace the file reads"
               for name, listed in (("UNQUALIFIED", unqualified), ("MINTED_IDS", minted))
               for namespace, kinds in listed.items() for kind in kinds if kind not in formats.get(namespace, {})]
    missing += [f"MINTED_IDS names {namespace}:{kind}, whose ARN format does not end in its one variable"
                for namespace, kinds in minted.items() for kind in kinds
                if kind in formats.get(namespace, {}) and not ONE_VARIABLE_RE.match(_resource(formats[namespace][kind]))]
    missing += [f"FOUND_BY names {key}, no resource type with an ARN format of a namespace the file reads"
                for key in sorted(FOUND_BY) if key.split(":", 1)[1] not in formats.get(key.split(":", 1)[0], {})]
    missing += [f"THROUGH names {key.split(':', 1)[0]}:{kind}, no resource type with an ARN format of a namespace the "
                f"file reads" for key, (kinds, _) in sorted(THROUGH.items()) for kind in kinds
                if kind not in formats.get(key.split(":", 1)[0], {})]
    if missing:
        raise ValueError("; ".join(missing))
    operations = {}
    for operation, value in sorted(covered.items()):
        models = load_models(data, set(namespaces[namespace_of(operation)]))
        operations[operation] = entry_for(operation, value, formats.get(namespace_of(operation), {}), models)
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_request_resources.py",
            "reference": provenance,
            "botocore": botocore,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-request-resources-check re-derives every operation's resources from botocore; "
                    "make iam-request-resources-verify checks every action, type and format against AWS.",
        },
        "namespaces": namespaces,
        "clientsNotRead": dict(sorted(CLIENTS_NOT_READ.items())),
        "handBuilt": list(HAND_BUILT),
        "notCovered": dict(sorted(NOT_COVERED.items())),
        "otherNamespaces": sorted(namespace for namespace in _with_resource_types(documents)
                                  if namespace not in NAMESPACES and namespace not in HAND_BUILT
                                  and namespace not in NOT_COVERED),
        "arnFormats": formats,
        "unqualified": unqualified,
        "mintedIds": minted,
        "operations": operations,
    }


def unqualified_types() -> dict[str, list[str]]:
    """`UNQUALIFIED` as the types of each namespace."""
    return _types_of(UNQUALIFIED)


def minted_types() -> dict[str, list[str]]:
    """`MINTED_IDS` as the types of each namespace."""
    return _types_of(MINTED_IDS)


def _types_of(listed: dict[str, str]) -> dict[str, list[str]]:
    """`<namespace>:<type>` keys as the types of each namespace."""
    found: dict[str, list[str]] = {}
    for key in sorted(listed):
        namespace, kind = key.split(":", 1)
        found.setdefault(namespace, []).append(kind)
    return found


def _resource(arn_format: str) -> str:
    """The resource part of an ARN format, past its fifth colon."""
    return arn_format.split(":", 5)[5]


def _with_resource_types(documents: list[dict]) -> set[str]:
    """The namespaces with an action that acts on a resource type."""
    return {document["Name"] for document in documents
            if any(action.get("Resources") for action in document.get("Actions", []))}


def reads(operation: str) -> bool:
    """Read whole namespaces, and in a namespace otherwise built by hand its explicit corrections and the
    operations `READ_IN_HAND_BUILT` lists."""
    namespace = namespace_of(operation)
    return namespace in NAMESPACES or namespace in HAND_BUILT and (operation in CORRECTIONS
                                                                     or operation in READ_IN_HAND_BUILT)


def _check_overrides(covered: dict[str, dict]) -> None:
    """Every correction names an operation the file reads, and no namespace is in two places."""
    stale = [f"CORRECTIONS names {operation}, which the file does not read" for operation in CORRECTIONS
             if operation not in covered]
    stale += [f"READ_IN_HAND_BUILT names {operation}, which the reference does not give" for operation in READ_IN_HAND_BUILT
              if operation not in covered]
    stale += [f"READ_IN_HAND_BUILT names {operation}, outside HAND_BUILT" for operation in READ_IN_HAND_BUILT
              if namespace_of(operation) not in HAND_BUILT]
    for left, right in (("NAMESPACES", "HAND_BUILT"), ("NAMESPACES", "NOT_COVERED"), ("HAND_BUILT", "NOT_COVERED")):
        stale += [f"{namespace} is in both {left} and {right}"
                  for namespace in sorted(set(globals()[left]) & set(globals()[right]))]
    if stale:
        raise ValueError("; ".join(stale))


def render(document: dict) -> str:
    """The document with one operation to a line."""
    lines = ["{"]
    for key in ("_source", "namespaces", "clientsNotRead", "handBuilt", "notCovered", "otherNamespaces", "arnFormats",
                "unqualified", "mintedIds"):
        lines.append(f'  "{key}": {json.dumps(document[key])},')
    lines.append('  "operations": {')
    operations = list(document["operations"].items())
    for index, (operation, entry) in enumerate(operations):
        tail = "," if index < len(operations) - 1 else ""
        lines.append(f"    {json.dumps(operation)}: {json.dumps(entry)}{tail}")
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- #
# Gates
# --------------------------------------------------------------------------- #
PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[])?$")
# A member filling a variable may also be a field of a structure, or of each element of a list of structures, one
# value for each.
FILL_PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[]|\[]\.[A-Za-z][A-Za-z0-9_]*|\{}\.[A-Za-z][A-Za-z0-9_]*"
                          r"|\.[A-Za-z][A-Za-z0-9_]*)?$")
# ARN paths can traverse structures, array elements ([]) and map values ({}).
ARN_PATH_RE = re.compile(r"^(?:[A-Za-z][A-Za-z0-9_]*(\[])?(\.[A-Za-z][A-Za-z0-9_]*(\[])?)?"
                         r"|[A-Za-z][A-Za-z0-9_]*\{\}(\.[A-Za-z][A-Za-z0-9_]*){1,2})$")
# A format's resource that is a literal and one variable, at its end.
ONE_VARIABLE_RE = re.compile(r"^[^$]*\$\{\w+}$")
# The keys a resource found by members does not take: it is read no other way.
FOUND_ONLY = {"absent", "arnOf", "fixed", "before"}


def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than out of date."""
    namespaces = document.get("namespaces")
    operations = document.get("operations")
    formats = document.get("arnFormats")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["namespaces is missing or empty"]
    if not isinstance(operations, dict) or not operations:
        return ["operations is missing or empty"]
    problems: list[str] = []
    if not isinstance(formats, dict) or set(formats) - set(namespaces) or not all(
            isinstance(types, dict) and all(isinstance(arn, str) and arn.startswith("arn:${Partition}:")
                                            for arn in types.values()) for types in formats.values()):
        return ["arnFormats is not an ARN format for each resource type of namespaces the file reads"]
    if list(operations) != sorted(operations):
        problems.append("operations is not sorted")
    unqualified = document.get("unqualified")
    if not isinstance(unqualified, dict) or not all(
            isinstance(kinds, list) and kinds and all(isinstance(kind, str) and kind in formats.get(namespace, {})
                                                      for kind in kinds) for namespace, kinds in unqualified.items()):
        problems.append("unqualified is not resource types with an ARN format of each namespace the file reads")
    minted = document.get("mintedIds")
    if not isinstance(minted, dict) or not all(
            isinstance(kinds, list) and kinds and all(isinstance(kind, str) and kind in formats.get(namespace, {})
                                                      and ONE_VARIABLE_RE.match(_resource(formats[namespace][kind]))
                                                      for kind in kinds) for namespace, kinds in minted.items()):
        problems.append("mintedIds is not resource types of each namespace the file reads whose ARN format ends in "
                        "its one variable")
    for key in ("handBuilt", "otherNamespaces"):
        if not isinstance(document.get(key), list) or not all(isinstance(n, str) and NAMESPACE_RE.match(n)
                                                              for n in document[key]):
            problems.append(f"{key} is not a list of namespaces")
    for key in ("notCovered", "clientsNotRead"):
        if not isinstance(document.get(key), dict) or not all(
                isinstance(reason, str) and reason for reason in document[key].values()):
            problems.append(f"{key} does not give each a reason")
    for operation, entry in operations.items():
        namespace = namespace_of(operation)
        if not OPERATION_RE.match(operation) or namespace not in namespaces:
            problems.append(f"{operation!r} is not an operation of a namespace the file reads")
            continue
        if not isinstance(entry, dict) or not {"action", "types", "resources"} <= set(entry) <= {
                "action", "types", "resources", "creates", "makes", "located", "targets"}:
            problems.append(f"{operation} is not an action, its types and its resources")
            continue
        if not isinstance(entry["action"], str) or not OPERATION_RE.match(entry["action"]) \
                or namespace_of(entry["action"]) != namespace:
            problems.append(f"{operation} is checked as no action of its namespace")
        types = entry["types"]
        if not isinstance(types, list) or not all(isinstance(kind, str) for kind in types):
            problems.append(f"{operation} has no list of resource types")
            continue
        for resource in entry["resources"] if isinstance(entry["resources"], list) else [None]:
            problems += _resource_problems(operation, resource, set(types), formats.get(namespace, {}))
        for created in entry.get("creates", []) if isinstance(entry.get("creates", []), list) else [None]:
            problems += _created_problems(operation, created, set(types), formats.get(namespace, {}))
        for made in entry.get("makes", []) if isinstance(entry.get("makes", []), list) else [None]:
            if types or not isinstance(made, dict) or set(made) != {"type", "fill"}:
                problems.append(f"{operation} makes {made!r}, which is no type's variables of a create acting on none")
            else:
                problems += _resource_problems(operation, made, {made["type"]}, formats.get(namespace, {}))
        targets = entry.get("targets")
        if targets is not None:
            if not isinstance(targets, dict) or set(targets) != {"member", "query", "prefixes"}:
                problems.append(f"{operation} has malformed id targets")
            elif not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*\[\]", str(targets["member"])) \
                    or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", str(targets["query"])) \
                    or not isinstance(targets["prefixes"], dict) or not targets["prefixes"]:
                problems.append(f"{operation} has no list member, Query name or id prefixes")
            else:
                prefixes = targets["prefixes"]
                for kind, prefix in prefixes.items():
                    arn = formats.get(namespace, {}).get(kind, "")
                    if kind not in types or not arn or len(set(VARIABLE_RE.findall(arn)) - KNOWN_VARIABLES) != 1 \
                            or not isinstance(prefix, str) or not prefix:
                        problems.append(f"{operation} has an invalid id target type or prefix: {kind}")
                values = [p for p in prefixes.values() if isinstance(p, str)]
                if any(a.startswith(b) for i, a in enumerate(values) for j, b in enumerate(values) if i != j):
                    problems.append(f"{operation} has ambiguous id prefixes")
        located = entry.get("located", {})
        if not isinstance(located, dict) or not located and "located" in entry or not all(
                isinstance(member, str) and PATH_RE.match(member) and isinstance(where, str) and LOCATED_RE.match(where)
                for member, where in located.items()):
            problems.append(f"{operation} does not give each member it locates a label of its path or a parameter of "
                            f"its query")
    return problems


def _resource_problems(operation: str, resource: object, types: set[str], formats: dict[str, str]) -> list[str]:
    if not isinstance(resource, dict) or not ({"types", "arn"} <= set(resource) <= {"types", "arn", "before", "made",
                                                                                     "endpoint"}
                                              or {"types", "through"} <= set(resource) <= {"types", "through", "when",
                                                                                            "endpoint"}
                                              or {"type", "fill"} <= set(resource) <= {"type", "fill", "when", "unless", "absent", "arnOf", "account",
                                                                                       "fixed", "made", "before", "find", "endpoint",
                                                                                       "default"}):
        return [f"{operation} names {resource!r}, which is neither an ARN member nor a type's variables"]
    if "default" in resource and (resource["default"] is not True or resource["fill"] or not resource.get("when")
                                  or set(resource) - {"type", "fill", "when", "default", "endpoint"}):
        return [f"{operation} names {resource!r} as its service's default, which is not for a request naming none"]
    if "endpoint" in resource and not (isinstance(resource["endpoint"], str) and resource["endpoint"]):
        return [f"{operation} serves {resource!r} on {resource['endpoint']!r}, which is no endpoint prefix"]
    if resource.get("made", True) is not True:
        return [f"{operation} marks {resource!r} made by {resource['made']!r}, which is not true"]
    before = resource.get("before", "#")
    if not isinstance(before, str) or len(before) != 1:
        return [f"{operation} reads its members before {before!r}, which is no one character"]
    named = resource.get("types") or [resource.get("type")]
    kinds = _kinds(resource)
    corrected = resource in CORRECTIONS.get(operation, ([], ""))[0]
    if len(kinds) != len(named) or not all((kind in types or corrected) and kind in formats for kind in kinds):
        return [f"{operation} names {named}, not resource types its action acts on with an ARN format"]
    if "arn" in resource:
        if not isinstance(resource["arn"], str) or not ARN_PATH_RE.match(resource["arn"]):
            return [f"{operation} reads an ARN at {resource['arn']!r}, which is no member"]
        return []
    if "through" in resource:
        through = resource["through"]
        if not isinstance(through, str) or not FILL_PATH_RE.match(through):
            return [f"{operation} names {kinds} through {through!r}, which is no member"]
        return _conditions_problems(operation, kinds, resource)
    fill = resource["fill"]
    find = resource.get("find", [])
    if not isinstance(find, list) or ("find" in resource and (not find or fill or set(resource) & FOUND_ONLY)) \
            or not all(isinstance(path, str) and PATH_RE.match(path) and not path.endswith("[]") for path in find):
        return [f"{operation} finds {kinds[0]} by {find!r}, which are no members of one value each, or beside a fill "
                f"or members reading it otherwise"]
    variables = set(VARIABLE_RE.findall(formats[kinds[0]])) - KNOWN_VARIABLES
    if not isinstance(fill, dict) or set(fill) - variables or not all(
            isinstance(path, str) and FILL_PATH_RE.match(path) for path in fill.values()):
        return [f"{operation} fills {fill!r}, which are no variables of {kinds[0]}'s ARN by members"]
    if sum("[]" in path or "{}" in path for path in fill.values()) > 1:
        return [f"{operation} fills {kinds[0]} from more than one list"]
    fixed = resource.get("fixed", {})
    if not isinstance(fixed, dict) or set(fixed) - variables or set(fixed) & set(fill) or not all(
            isinstance(value, str) and value for value in fixed.values()):
        return [f"{operation} fixes {fixed!r}, which are no variables of {kinds[0]}'s ARN no member fills, or no values"]
    problems = _conditions_problems(operation, kinds, resource)
    if problems:
        return problems
    absent = resource.get("absent", {})
    if not isinstance(absent, dict) or not all(_read_as_one(path, fill) and isinstance(value, str) and value
                                               for path, value in absent.items()):
        return [f"{operation} reads {absent!r} for absent members, which is no member's value"]
    arn_of = resource.get("arnOf", {})
    if not isinstance(arn_of, dict) or not all(
            _read_as_one(path, fill) and isinstance(kind, str)
            and len(set(VARIABLE_RE.findall(formats.get(kind, ""))) - KNOWN_VARIABLES) == 1
            and formats[kind].endswith("}") for path, kind in arn_of.items()):
        return [f"{operation} reads {arn_of!r} as ARNs of types, which are no members or types of one variable"]
    account = resource.get("account")
    if account is not None and (not isinstance(account, str) or not PATH_RE.match(account) or account.endswith("[]")
                                or "${Account}" not in formats[kinds[0]]):
        return [f"{operation} takes {kinds[0]}'s account from {account!r}, which is no member or no account of its ARN"]
    return []


def _conditions_problems(operation: str, kinds: list[str], resource: dict) -> list[str]:
    """What is wrong with the members' values a resource is named `when` or `unless` the request gives."""
    for key in ("when", "unless"):
        when = resource.get(key, {})
        if not isinstance(when, dict) or not all(
                isinstance(path, str) and PATH_RE.match(path) and not path.endswith("[]") and isinstance(values, list)
                and values and all(value is None or isinstance(value, str) for value in values) for path, values in when.items()):
            return [f"{operation} names {kinds[0]} {key} {when!r}, which is no member's values"]
    return []


def _read_as_one(path: object, fill: dict) -> bool:
    """Whether a member gives one value as it is read: one that is no list, top-level or a field of a structure, or a
    field of each element of a list of structures that fills a variable, one value for each element."""
    if not isinstance(path, str):
        return False
    if "[]." in path or "{}." in path:
        return bool(FILL_PATH_RE.match(path)) and path in fill.values()
    return bool(FILL_PATH_RE.match(path)) and not path.endswith("[]")


def _created_problems(operation: str, created: object, types: set[str], formats: dict[str, str]) -> list[str]:
    if not isinstance(created, dict) or set(created) != {"type", "parent", "arn"} or not isinstance(created["arn"], str) \
            or not PATH_RE.match(created["arn"]):
        return [f"{operation} creates {created!r}, which is no type made under a parent's ARN member"]
    if created["parent"] not in types or created["type"] not in formats or created["parent"] not in formats \
            or child_id(formats[created["type"]], formats[created["parent"]]) is None:
        return [f"{operation} creates a {created['type']} under a {created['parent']}, no parent of it its action acts on"]
    return []


def derive(document: dict, data: Path) -> list[str]:
    """Where the resources the file gives differ from what botocore gives for its types and formats."""
    problems: list[str] = []
    if document["clientsNotRead"] != dict(sorted(CLIENTS_NOT_READ.items())):
        problems.append(f"the file leaves out the clients {json.dumps(document['clientsNotRead'])}, CLIENTS_NOT_READ "
                        f"gives {json.dumps(dict(sorted(CLIENTS_NOT_READ.items())))}")
    if document["unqualified"] != unqualified_types():
        problems.append(f"the file checks {json.dumps(document['unqualified'])} without a qualifier, "
                        f"UNQUALIFIED gives {json.dumps(unqualified_types())}")
    if document["mintedIds"] != minted_types():
        problems.append(f"the file gives {json.dumps(document['mintedIds'])} a minted id, "
                        f"MINTED_IDS gives {json.dumps(minted_types())}")
    for operation, entry in document["operations"].items():
        namespace = namespace_of(operation)
        models = load_models(data, set(document["namespaces"][namespace]))
        expected = entry_for(operation, {"action": entry["action"], "types": entry["types"]},
                             document["arnFormats"].get(namespace, {}), models)
        if expected != entry:
            problems.append(f"{operation}: the file has {json.dumps(entry)}, botocore gives {json.dumps(expected)}")
    return problems


def verify_against(vendored: dict, upstream: dict[str, dict], formats: dict[str, dict[str, str]]) -> tuple[list[str], list[str]]:
    """What the file claims and AWS does not, and the operations AWS has added since."""
    untrue: list[str] = []
    for operation, entry in vendored["operations"].items():
        actual = upstream.get(operation)
        if actual is None:
            untrue.append(f"AWS no longer lists {operation}")
        elif actual["action"] != entry["action"]:
            untrue.append(f"AWS checks {operation} as {actual['action']}, not {entry['action']}")
        elif (actual["types"] or []) != entry["types"]:
            untrue.append(f"AWS gives {entry['action']} the resource types {actual['types']}, not {entry['types']}")
        elif not set(actual["clients"]) - set(vendored["clientsNotRead"]) <= set(vendored["namespaces"][namespace_of(operation)]):
            untrue.append(f"AWS lists {operation} for {actual['clients']}, which the file does not read")
    for namespace, types in vendored["arnFormats"].items():
        changed = sorted(kind for kind, arn in types.items() if formats.get(namespace, {}).get(kind) != arn)
        if changed:
            untrue.append(f"AWS no longer gives {namespace}'s {', '.join(changed)} the ARN format the file does")
    added = sorted(operation for operation in upstream
                   if namespace_of(operation) in vendored["namespaces"]
                   and (namespace_of(operation) not in vendored["handBuilt"] or operation in CORRECTIONS)
                   and operation not in vendored["operations"])
    return untrue, added


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: resources match the pinned botocore")
    parser.add_argument("--verify", action="store_true", help="online: actions, types and formats match AWS")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved <service>.json reference files instead of fetching them")
    parser.add_argument("--botocore-data", type=Path, default=None, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check or args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        try:
            document = json.loads(args.output.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            print(f"error: {_shown(args.output)} could not be read ({e})", file=sys.stderr)
            return 1
        problems = validate(document)
        if not problems and args.check:
            data, _ = resolve_botocore_data(args.botocore_data)
            problems = derive(document, data)
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        if problems:
            return 1
        entries = len(document["operations"])
        if args.check:
            print(f"{_shown(args.output)} matches botocore ({entries} operations over "
                  f"{len(document['namespaces'])} namespaces)")
            return 0
        try:
            documents, provenance = fetch_references(args.source_dir)
            listed = checked_actions(documents)
            upstream = with_rest_operations(listed, documents, {
                client: {operation.split(":", 1)[1] for operation in document["operations"]
                         if namespace_of(operation) == namespace and operation not in listed}
                for client, namespace in REST_MODELS.items() if client in document["namespaces"].get(namespace, [])})
            formats = arn_formats(documents, set(document["namespaces"]))
        except (OSError, ValueError) as e:
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(document, upstream, formats)
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        if untrue:
            print(f"error: {len(untrue)} vendored claim(s) do not hold upstream.", file=sys.stderr)
            return 1
        if added:
            print(f"{_shown(args.output)}: all {entries} operations verified. AWS has since added "
                  f"{len(added)}, so the file is incomplete but not wrong (run without --verify to refresh): "
                  f"{', '.join(added[:10])}{'...' if len(added) > 10 else ''}")
        else:
            print(f"{_shown(args.output)}: all {entries} operations verified against {provenance}, "
                  f"nothing added upstream.")
        return 0

    data, botocore = resolve_botocore_data(args.botocore_data)
    try:
        documents, provenance = fetch_references(args.source_dir)
        document = build(documents, provenance, data, botocore)
    except (OSError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    problems = validate(document)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    args.output.write_text(render(document), encoding="utf-8")
    print(f"wrote {_shown(args.output)} ({len(document['operations'])} operations over "
          f"{len(document['namespaces'])} namespaces, from {provenance})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
