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
  `StartExecution` for a state machine, whose `name` is the execution's), and no member holds its ARN,
  or, when no member does either, a string member whose botocore shape is named after the type (Parameter Store's
  `GetParametersByPath` names a hierarchy by a `Path` holding a `PSParameterName`, as `GetParameterHistory` names a
  parameter by its `Name`).
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

Every operation in `NAMESPACES` is read, whether Floci serves it or not. In a namespace in `HAND_BUILT`, whose
other operations keep their existing resource builders, so are the operations `READ_IN_HAND_BUILT` lists, which
follow the rule unchanged: a family of a hand-built namespace whose requests name their resources the
way the rule reads them. `HAND_BUILT` are the namespaces `ResourceArnBuilder` builds resources for itself, and
`NOT_COVERED` the ones Floci serves that are not read yet, each with its reason;
`RequestResourceNamespacesIntegrationTest` fails for a served namespace in none of the three.
`ID_TARGETS` stages id-list namespaces separately: a resource ARN's variable names its list member,
with the configured heterogeneous resource list as a fallback. Botocore supplies its serialized
EC2 Query name (including queryName and capitalization), and documented id prefixes distinguish
the supported resource types. `resourceTagKeys` comes from those types' Service Reference condition keys. Each generated `targets` entry preserves the
request's order and duplicates, including a wildcard for each unsupported id; only resolved ARNs
use the corresponding target's condition context. Other requests in those namespaces stay on `*`.
Beyond the members' names, botocore's shapes and documentation say what a member names, and the rule reads them:
- a member whose documentation names the type, word for word ("The name of the pipeline to start"), fills its
  variable when it is the kind the variable is (a name, an id), and is found by the type's service otherwise
  (`find`: "The name of the stream processor" for its `${StreamprocessorId}`), the id open when it finds none;
  one whose documentation's head noun is an ARN ("The Amazon Resource Name (ARN) to which you want to add the tag")
  holds an ARN, and so does one whose name ends in `ArnList`;
- a field of a top-level structure is a member too (`Budget.BudgetName`, `pipeline.name`, a bare field taking the
  structure's name), and so is a field of each element of a list or value of a map of structures named after a type
  (`PutEvents`' `Entries[].EventBusName`); a nested field named as an optional top-level member filling the same
  variable fills it only when that member is absent or null (Cloud Map's `DnsConfig.NamespaceId`);
- the id a create answers beside another type's ARN is that type's (`RunJobFlow`: `JobFlowId`, `ClusterArn`), an
  account id member fills a type's `AccountId`, a member qualified by the type's name fills it first
  (`SnapshotClusterIdentifier` for a snapshot), and a variable the reference names `ResourceId` is the type's own
  id; a type whose one variable is `ResourceId`, in a REST request, is found by its path's labels and the member
  named after it (a QuickSight user);
- an ARN member whose pattern takes another service's ARNs names none of the action's resources;
- the type a non-create is about, which its action does not act on, reaches the acted-on types through their
  service (`through`: a CodeBuild build id, its project), and so does a `ResourceId` naming whatever the action acts
  on (an EMR cluster or Studio its tag calls name);
- a member whose enum values the word that sets two types' formats apart abbreviates picks the type, its
  documented default picking one for a request leaving it out (Elastic Load Balancing v2's `Type`);
- two types whose formats differ by one variable an optional member fills are marked so the one without it
  applies when the member is absent or names its service's default (`whenDefault`, `unlessDefault`: a rule on the
  default event bus has no bus in its ARN), and a resource filling a variable from an optional member no other
  reads, beside another, is named only when the request gives it (`GetClusterCredentials`' `DbName`);
- where two clients' requests of one operation name different resources, each is read apart and its own apply
  only to requests of its `endpoint` prefix (EventBridge's `events` and EventBridge V2's `eventsv2`), or to every
  request where they share one; a client takes the type its ARN patterns name of two it names by the same members,
  a versioned twin (`event-busv2`) for one they do not;
- a variable no member fills is `fixed` to the one value of an enum named after it that the operation's name gives
  (CodePipeline's `CreateCustomActionType` makes an action type whose `ActionOwner` is `Custom`);
- a type named only by an optional member documented as holding what the operation is about ("the cluster to which
  the instance group belongs") is named, when the request leaves that member out, through each member naming what
  the operation is about (`ModifyInstanceGroups`' `InstanceGroups[].InstanceGroupId`).
What a request names, this file alone says. A service only looks up what it holds where an entry asks it to
(`StoredResourceArns`): the resource the members a `find` gives name, the ones a `through` member reaches, and the
resource its handler uses for a member left out (Athena's `primary` workgroup, EventBridge's `default` bus). No
service says what an operation acts on.

`MEASURED` holds what AWS was measured to check where neither botocore nor the Service Reference says it, and
nothing else. It is the only part of the file stated by hand, and an entry is admitted only with evidence that AWS
itself answered (a policy probe against real AWS published in a Floci pull request, or AWS's AccessDenied message
recorded verbatim), for resource types the reference does not list for the action, giving what the rules do not
already give, and linking to its evidence; the generator refuses an entry that breaks any of the last three, and
review the first. Documentation, analogy, the request's shape and what a Floci handler does are not evidence: what
they give belongs in a rule. DynamoDB's `ListTables` (`table/*`) and Step Functions' `SendTask*` (the token's
activity or state machine) are its entries.
`CLIENTS_NOT_READ` are SDK clients the reference lists for a
namespace's operations whose requests Floci does not serve, each with its reason.
`UNQUALIFIED` are the resource types whose ARN a request may give with a qualifier, one more `:<name>`
past its format, and whose service checks it without one: Step Functions checks a version or an alias
on its state machine's ARN, and Parameter Store a version or a label on its parameter's. A value filling a variable
named `...WithoutLeadingSlash` is read as a Parameter Store name, without the whitespace around it and one leading
`/`: a parameter's ARN is `parameter/<name>`, whether or not its name starts with a slash. `MINTED_IDS` are the
resource types whose service follows the name their format ends in with an id it mints, `/<id>`, which the
format leaves out and a request naming one by its name does not give: EventBridge's connections and API destinations, whose stored ARN the reader
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
  operation's resources must be exactly what the rules give from botocore for the resource types and
  ARN formats the file records, so a hand-edited entry fails.
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
              "rekognition", "resource-explorer-2", "route53", "servicediscovery", "servicequotas", "ssm", "sso",
              "states", "textract", "timestream-influxdb", "translate", "signer", "xray", "securityhub", "guardduty")

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
              "resource-groups", "s3", "scheduler", "secretsmanager", "sns", "sqs", "sts")

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

# ---------------------------------------------------------------------------------------------------------------------
# MEASURED: what AWS was measured to check where neither botocore nor the Service Reference says it.
#
# This is the only place in the generator that states an operation's resources by hand, and it is closed to
# everything but measured AWS behaviour. An entry is admitted only when ALL of these hold:
#
#   1. Measured. Its evidence is AWS itself answering: a policy probe run against real AWS with real credentials,
#      published with its cases and results in a Floci pull request, or an AWS response recorded verbatim (an
#      AccessDenied message naming the resource AWS checked). Documentation prose, an analogy with another
#      operation, a reading of the request's shape and what a Floci handler does are NOT evidence: a fact one of
#      those gives belongs in a rule, which reads it from botocore or the Service Reference for every operation.
#   2. Absent from the metadata. The Service Reference lists none of the entry's resource types for the
#      operation's action, so no rule can read them; an entry for a type the reference lists is refused.
#   3. Needed. The rules do not already give the entry's resources; an entry they give is refused, so one a later
#      rule, a botocore bump or a reference update makes redundant is taken out rather than kept.
#   4. Cited. Its evidence names where the measurement is, with at least one https:// link to it.
#
# The generator enforces 2 to 4 (`measured_resources`); review enforces 1. Never add an entry to make a check pass,
# to keep Floci's existing behaviour, or because a handler acts on a resource: measure AWS first, or leave the
# operation to the rules. Floci services never state what an operation acts on either (`StoredResourceArns` only
# looks up what a service holds), so this table is the whole of the hand-stated part of the file.
# ---------------------------------------------------------------------------------------------------------------------
_SEND_TASK_EVIDENCE = (
    "real AWS probes in https://github.com/floci-io/floci/pull/7277 (boto3 against us-east-1 on 2026-11-02, a fresh "
    "assumed role per case holding only the policy under test) authorize an activity's task token on its activity and "
    "a .waitForTaskToken integration's on its unqualified state machine, a grant on a version ARN failing and a deny "
    "on one not applying; AWS's recorded denial of SendTaskSuccess for such a token names 'on resource: "
    "<stateMachineArn>' (https://github.com/aws/aws-emulator/blob/main/issues/things-that-do-not-work/"
    "20261109T171600Z-stepfunctions-step-functions-the-accessdeniedexception-for-states-sendtasksuccess-names.md); "
    "the Service Reference lists no resource type for the action, and the token is resolved by the service (find)")
MEASURED: dict[str, tuple[list[dict], str]] = {
    "dynamodb:ListTables": (
        [{"type": "table", "fill": {}}],
        "real AWS probes in https://github.com/floci-io/floci/pull/7336 (27 policy cases with real AWS STS role "
        "sessions) accept an allow and enforce a deny on arn:<partition>:dynamodb:<region>:<account>:table/*, the "
        "literal table/* (table/? matches it, table/ does not), whatever TableName or ExclusiveStartTableName the "
        "request gives; AWS's recorded denial names 'on resource: arn:aws:dynamodb:<region>:<acct>:table/*' "
        "(https://github.com/aws/aws-emulator/blob/main/issues/things-that-do-not-work/"
        "20261024T015148Z-dynamodb-does-not-enforce-iam-listtables-succeeds-for.md); the Service Reference lists no "
        "resource type for the action"),
    **{f"states:{operation}": ([{"type": kind, "fill": {}, "find": ["taskToken"]} for kind in ("activity", "statemachine")],
                               _SEND_TASK_EVIDENCE)
       for operation in ("SendTaskSuccess", "SendTaskFailure", "SendTaskHeartbeat")},
}

_LATER ="not read yet: a later batch, with the tests that show its ARNs are the ones its handlers mint"
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
                "rules do not fill a name from, so nearly every operation's resource would be left open",
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


# Resource types whose ARN a request may give with a qualifier, one more `:<name>` past its format, which their
# service drops to check it, and why.
UNQUALIFIED: dict[str, str] = {
    "states:statemachine": "Step Functions authorizes a version or an alias on its state machine's ARN, not on the "
                           "version's or the alias's ('To authorize a version or an alias to invoke an API action, "
                           "Step Functions uses the state machine's ARN instead of using the version ARN or alias ARN')",
    "ssm:parameter": "Parameter Store reads a version or a label after a parameter's name ('To query by parameter "
                     "label, use \"Name\": \"name:label\". To query by parameter version, use \"Name\": "
                     "\"name:version\"', GetParameter's Name) and checks the read on the parameter, whose name "
                     "holds no colon",
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
# a wildcard entry. Lists named by their resource ARN variable and heterogeneous Resources lists
# share this reader and its generated condition contexts; requests without those lists stay unchanged.
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


def resource_tag_keys(documents: list[dict]) -> dict[str, dict[str, list[str]]]:
    """Stored-tag key prefixes for the resource types the id-target reader supports."""
    result = {}
    for document in documents:
        namespace = document["Name"]
        supported = ID_TARGETS.get(namespace, {}).get("prefixes", {})
        types = {resource["Name"]: sorted(key.removesuffix("${TagKey}")
                 for key in resource.get("ConditionKeys", []) if key.endswith(":ResourceTag/${TagKey}"))
                 for resource in document.get("Resources", []) if resource["Name"] in supported}
        if types:
            result[namespace] = dict(sorted(types.items()))
    return dict(sorted(result.items()))


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
    """The fields of each value of a top-level map of structures, or of each element of a list of them, in the
    operation's request body that hold a string and end as an id, a name or an identifier, by their path,
    `Name{}.Field` or `Name[].Field`, each with the field's name: `PutEvents`' `Entries[].EventBusName`."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or _reading(model) != "all":
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            if member.get("location") not in BODY_LOCATIONS or shape["type"] not in ("map", "list"):
                continue
            value = model["shapes"][shape["value" if shape["type"] == "map" else "member"]["shape"]]
            marker = "{}" if shape["type"] == "map" else "[]"
            for field, nested in value.get("members", {}).items() if value["type"] == "structure" else ():
                if kind_of(field) and model["shapes"][nested["shape"]]["type"] == "string":
                    found.setdefault(f"{name}{marker}.{field}", field)
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


# --------------------------------------------------------------------------- #
# What botocore's documentation and shapes say a member names
# --------------------------------------------------------------------------- #
# Words that end a type's name in a member's documentation: "The name of the pipeline to start".
DOC_STOP = {"to", "that", "you", "for", "which", "created", "from", "in", "of", "about", "whose", "with", "on",
            "belonging", "the", "a", "an", "is", "are", "and", "or", "by", "used", "will", "should", "can", ""}
# "The name of the pipeline", "Specifies the name or the CloudTrail ARN of the trail", "The ID of the delivery".
DOC_NAMES_RE = re.compile(r"^(?:specifies )?(?:the |a |an )?(?:unique )?(?:name|id|identifier)"
                          r"(?: or (?:the )?(?:[a-z]+ )?arn)? (?:of|for) (?:the |a |an )?(?P<phrase>.+)$")
# "A list of service-generated unique DB Instance Ids belonging to the DB Cluster".
DOC_LIST_RE = re.compile(r"^(?:a |the )?(?:list of )?(?:service-generated |unique )*(?P<phrase>[a-z][a-z0-9 -]*?) "
                         r"(?:ids|names|identifiers)\b")
# The service an ARN pattern names: `arn:aws(-[\w]+)*:iam::[0-9]{12}:role/.*` is IAM's.
# A member's documentation saying it holds another thing: "the cluster to which the instance group belongs".
SUBJECT_HOLDER_RE = re.compile(r"\bto which the (?P<subject>[a-z][a-z ]*?) belongs\b")
PATTERN_SERVICE_RE = re.compile(r"^\^?\(?arn:[^:]*:\(?([a-z0-9-]+)\)?:")


def _lead(documentation: str | None) -> str:
    """A member's documentation, lowercased, up to its first sentence's end."""
    text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", documentation or "")).strip().lower()
    return re.split(r"(?<=[a-z0-9)])\.(?:\s|$)", text, maxsplit=1)[0]


def doc_head(lead: str) -> tuple[str, str | None]:
    """The documentation's head noun phrase and what it is of: ("the arn", "the trail, event data store, ...") for
    "Specifies the ARN of the trail, event data store, dashboard, or channel"; a list's head is what it lists ("a list
    of trail, event data store, dashboard, or channel arns" before "whose tags will be listed")."""
    rest = re.sub(r" (?:of|for)$", "", re.split(r" (?:to|from|whose|that|which|in which|with|associated) ", lead,
                                               maxsplit=1)[0])
    match = re.match(r"^(.*?)(?: (?:of|for) (?:the |a |an )?(.*))?$", rest)
    head, of = match.group(1).strip(), (match.group(2) or None)
    if re.search(r"(?:^|\s)list$", head) and of:
        return f"{head} of {of}", None
    return head, of


def doc_kind(lead: str) -> str | None:
    """Whether the documentation says the member is a name or an id; None when it says neither."""
    head, _ = doc_head(lead)
    if re.search(r"\bname(?: or (?:the )?(?:\w+ )?arn)?$", head):
        return "name"
    if re.search(r"\b(?:id|identifier|ids|identifiers)(?: or (?:the )?(?:\w+ )?arn)?$", head):
        return "id"
    return None


def doc_names(lead: str, kind: str, formats: dict[str, str]) -> bool:
    """Whether a member's documentation says it names a resource of the type, by the type's name or its format's
    leading resource word, word for word and followed by a word that ends it: "The name of the stream processor you
    want to delete" names a `streamprocessor`, "The ID of the pipeline execution" no `pipeline`."""
    word = RESOURCE_PART_RE.match(formats.get(kind, ""))
    targets = {re.sub(r"[^a-z0-9]", "", kind.lower())} | ({re.sub(r"[^a-z0-9]", "", word.group(1).lower())} if word else set())
    for regex in (DOC_NAMES_RE, DOC_LIST_RE):
        match = regex.match(lead)
        if not match:
            continue
        words = re.split(r"[\s,;()]+", match.group("phrase"))
        joined = ""
        for index, piece in enumerate(words[:4]):
            joined += re.sub(r"[^a-z0-9]", "", piece)
            if joined in targets:
                return regex is DOC_LIST_RE or re.sub(r"[^a-z0-9]", "", words[index + 1] if index + 1 < len(words) else "") in DOC_STOP
            if not any(target.startswith(joined) for target in targets):
                break
    return False


def documented_members(models: dict[str, dict], operation: str) -> dict[str, dict]:
    """The request's members holding a string or a list of strings, top-level and fields of a top-level structure,
    each with its documentation's lead, its pattern and whether it is nested, by name or `Name.Field`."""
    found: dict[str, dict] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or _reading(model) != "all":
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
                continue
            shape = model["shapes"][member["shape"]]
            entries = [(name, member, shape)]
            if shape["type"] == "structure" and member.get("location") in BODY_LOCATIONS:
                entries += [(f"{name}.{field}", nested, model["shapes"][nested["shape"]])
                            for field, nested in shape.get("members", {}).items()]
            for path, item, item_shape in entries:
                strings = _strings(model, item_shape)
                if strings is None:
                    continue
                element = model["shapes"][item_shape["member"]["shape"]] if item_shape["type"] == "list" else item_shape
                found.setdefault(path, {"path": path + strings, "nested": "." in path,
                                        "lead": _lead(item.get("documentation") or item_shape.get("documentation")),
                                        "pattern": element.get("pattern") or ""})
    return found


def uri_labels(models: dict[str, dict], operation: str) -> list[str]:
    """The members a REST request's path carries, in the order its path gives them."""
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or "http" not in spec:
            continue
        by_label = {member.get("locationName", name): name
                    for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items()
                    if member.get("location") == "uri"}
        return [by_label[label] for label in re.findall(r"\{(\w+)\+?}", spec["http"]["requestUri"]) if label in by_label]
    return []


def created_aliases(models: dict[str, dict]) -> dict[str, str]:
    """The id a create answers beside the ARN of another type, by their stems: `RunJobFlow` answers `JobFlowId` and
    `ClusterArn`, so a job flow's id is a cluster's."""
    aliases: dict[str, str] = {}
    for model in models.values():
        for name, spec in model["operations"].items():
            if not CREATING_RE.match(name) or "output" not in spec:
                continue
            subject = stem(CREATING_RE.sub("", name))
            answered = model["shapes"][spec["output"]["shape"]].get("members", {})
            ids = [m for m in answered if re.search(r"id$", m, re.IGNORECASE) and stem(m) and related(stem(m), subject)]
            arns = [m for m in answered if re.search(r"arn$", m, re.IGNORECASE) and stem(m) and not related(stem(m), subject)]
            if len(ids) == 1 and len(arns) == 1:
                aliases[stem(ids[0])] = stem(arns[0])
    return aliases


def with_documented(operation: str, types: list[str], formats: dict[str, str], models: dict[str, dict],
                    resources: list[dict]) -> list[dict]:
    """The resources, completed from what botocore's shapes and documentation say each member names.

    - A member whose documentation's head noun is an ARN of a type the action acts on (or of whatever it acts on)
      holds one: Redshift's `ResourceName`, 'The Amazon Resource Name (ARN) to which you want to add the tag'.
    - An ARN member whose pattern takes another service's ARNs (a role's, a bucket's, a key's, CUR's billing view),
      or whose documentation gives it as the ARN of another type of the service (a `CreateExport`'s
      `Export.ExportArn`, "for this export"), names none of the action's resources, unless named after one of them.
    - A type with one variable left to fill takes the one member that names it: a field of a top-level structure
      named for it (`Budget.BudgetName`, `pipeline.name`), a member whose documentation names the type and says it
      is a name or an id as the variable is ("The name of the pipeline to start"), an id a create answers beside the
      type's ARN (`JobFlowId`, beside `RunJobFlow`'s `ClusterArn`), or an account id member for an `AccountId`. One
      whose documentation names the type but is the other kind (a stream processor's name for its id variable) is
      found by the type's service by that member (`find`), the id open when it finds none; on a create, no
      documentation fills a variable of another or no kind.
    - A field of a structure named as a top-level member filling the same variable fills it only when that member
      is absent or null (Cloud Map's `DnsConfig.NamespaceId`).
    - A bare id of the type a non-create operation is about, which its action does not act on, names the types the
      action acts on through their service (`through`): a CodeBuild build id, its project."""
    name = operation.split(":", 1)[1]
    made = stem(CREATING_RE.sub("", name)) if CREATING_RE.match(name) else None
    # A create makes a type of its namespace: `AddTags` makes no tag, `RegisterUser` a user.
    creating = made is not None and any(related(made, stem(kind)) or related(made, stem(word.group(1)))
                                        for kind in formats for word in [RESOURCE_PART_RE.match(formats[kind])] if word)
    facts = documented_members(models, name)
    accounts = account_members(models, name)
    aliases = created_aliases(models)
    kinds = [kind for kind in types if kind in formats]
    resources = [dict(resource) for resource in resources]
    held = {resource["arn"] for resource in resources if "arn" in resource}
    for path, fact in sorted(facts.items()):
        if fact["nested"] or fact["path"] in held or not kinds or re.search(r"arn(s|list)?$", path, re.IGNORECASE):
            continue
        head, of = doc_head(fact["lead"])
        if re.search(r"(?:arns?|amazon resource names? \(arns?\))$", head) \
                and not re.search(r"\b(?:name|id|identifier)s? or\b", head) and (
                (of is None and stem(path).startswith("resource"))
                or (of is not None and any(doc_names("the name of the " + part.strip(), kind, formats)
                                           for part in re.split(r",| or ", of) for kind in kinds))):
            resources.insert(0, {"types": kinds, "arn": fact["path"]})
    services = {formats[kind].split(":")[2] for kind in kinds}
    def foreign(path: str) -> bool:
        fact = facts.get(path.removesuffix("[]"))
        service = PATTERN_SERVICE_RE.match(fact["pattern"]) if fact else None
        return service is not None and service.group(1) not in services
    # An ARN member its documentation gives as the ARN of another type of the service, one the action does not act
    # on, holds that resource's: a `CreateExport`'s `Export.ExportArn`, "the ARN for this export", is the export's.
    # A member named after a type the action acts on keeps it, its name saying more than a shared shape's
    # documentation (OpenSearch's `applicationArn`, documented as "the ARN of the domain").
    others = [kind for kind in formats if kind not in kinds]

    def of_another_type(path: str) -> bool:
        fact = facts.get(path.removesuffix("[]"))
        member = stem(path.removesuffix("[]").split(".")[-1])
        if member and any(related(member, stem(kind)) for kind in kinds):
            return False
        head, of = doc_head(fact["lead"]) if fact else ("", None)
        if of is None or not re.search(r"(?:arns?|amazon resource names? \(arns?\))$", head):
            return False
        parts = [re.sub(r"^(?:this|that|its) ", "", part.strip()) for part in re.split(r",| or ", of)]
        return not any(doc_names("the name of the " + part, kind, formats) for part in parts for kind in kinds) \
            and any(doc_names("the name of the " + part, kind, formats) for part in parts for kind in others)
    resources = [resource for resource in resources
                 if "arn" not in resource or not (foreign(resource["arn"]) or of_another_type(resource["arn"]))]
    used = {path.removesuffix("[]") for resource in resources for path in resource.get("fill", {}).values()}
    for kind in kinds:
        variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
        # A type an ARN member of the request already holds is named there (`UpdateAccelerator`'s `AcceleratorArn`,
        # whose `Name` is the new name).
        if any("arn" in resource and "." not in resource["arn"] and kind in resource["types"]
               and (stem(resource["arn"]) in ("", "resource") or related(stem(resource["arn"]), stem(kind)))
               for resource in resources):
            continue
        current = next((resource for resource in resources if resource.get("type") == kind
                        and not {"find", "when", "unless"} & set(resource)), None)
        opened = current is not None and current.get("fill") == {} and "made" not in current
        fill = dict(current["fill"]) if current else {}
        unfilled = [v for v in variables if v not in fill]
        if len(unfilled) != 1:
            continue
        variable = unfilled[0]
        kind_stem = stem(kind)
        candidates = []
        for path, fact in sorted(facts.items()):
            if path in used or re.search(r"arns?$", path, re.IGNORECASE):
                continue
            field = path.split(".")[-1]
            effective = stem(path.split(".")[0] if fact["nested"] and field.lower() in BARE else field)
            aliased = aliases.get(effective, effective)
            if fact["nested"]:
                if aliased and (related(aliased, stem(variable)) or related(aliased, kind_stem)) \
                        and compatible(field, variable):
                    candidates.append(("fill", fact["path"]))
            elif doc_names(fact["lead"], kind, formats):
                said = doc_kind(fact["lead"]) or kind_of(field)
                if (compatible(said, variable) or (kind_of(variable) == "identifier" and not creating)) \
                        and not (creating and not kind_of(variable)) \
                        and not (creating and said != kind_of(variable) and kind_of(variable) != "identifier"):
                    candidates.append(("fill", fact["path"]))
                elif len(variables) == 1 and not fact["path"].endswith("[]"):
                    candidates.append(("find", fact["path"]))
            elif aliased != effective and related(aliased, kind_stem) and compatible(field, variable):
                candidates.append(("fill", fact["path"]))
            elif variable == "AccountId" and path in accounts:
                candidates.append(("fill", fact["path"]))
        if len(candidates) != 1:
            continue
        how, path = candidates[0]
        if how == "find":
            if current is None or opened:
                resources = [resource for resource in resources if resource is not current]
                resources.append({"type": kind, "fill": {}, "find": [path]})
            continue
        fill[variable] = path
        if current is not None:
            current["fill"] = dict(sorted(fill.items()))
        else:
            resources.append({"type": kind, "fill": dict(sorted(fill.items()))})
    # A nested field filling a variable an optional top-level member of its name fills too is read when that member
    # is absent.
    optional = optional_members(models, name)
    for path, fact in sorted(facts.items()):
        field = path.split(".")[-1]
        if not fact["nested"] or field not in facts or facts[field]["nested"] or field not in optional \
                or re.search(r"arns?$", field, re.IGNORECASE):
            continue
        for resource in list(resources):
            if "fill" not in resource or {"when", "unless", "find"} & set(resource):
                continue
            filled = [v for v, p in resource["fill"].items() if p == facts[field]["path"]]
            if len(filled) == 1 and fact["path"] not in resource["fill"].values():
                fallback = {key: value for key, value in resource.items() if key != "fill"}
                fallback["fill"] = dict(resource["fill"], **{filled[0]: fact["path"]})
                fallback["when"] = {field: [None]}
                resource["unless"] = {field: [None]}
                resources.insert(resources.index(resource) + 1, fallback)
    # A type whose one variable is the reference's `ResourceId` placeholder, in a REST request, is found by its
    # service by the labels of its path up to the member named after it, and that member: a QuickSight user by its
    # account, namespace and name, a group a membership is in by its own, not the member's. A member holding its ARN
    # names it there, not by a name to find (an embed URL's `UserArn`).
    labels = uri_labels(models, name)
    for kind in kinds:
        if [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES] != ["ResourceId"] or not labels:
            continue
        if any(resource.get("type") == kind and resource.get("fill") for resource in resources):
            continue
        finding = [resource for resource in resources if resource.get("type") == kind and "find" in resource]
        named = [path for path, fact in facts.items() if not fact["nested"] and path not in labels
                 and stem(path) and related(stem(path), stem(kind)) and not path.endswith("[]")
                 and not re.search(r"arns?$", path, re.IGNORECASE)]
        member = finding[0]["find"][-1] if finding else (named[0] if len(named) == 1 else None)
        about_it = related(made, stem(kind)) if made is not None else related(stem(SUBJECT_RE.sub("", name)), stem(kind))
        if member is None or not (finding or about_it):
            continue
        resources = [resource for resource in resources if not (resource.get("type") == kind and "find" in resource)
                     and not (resource.get("type") == kind and resource.get("fill") == {})]
        # The labels before the member's own scope it; those after it name something inside it (a group's member).
        scope = labels[:labels.index(member)] if member in labels else labels
        resources.append({"type": kind, "fill": {}, "find": [label for label in scope if label != member] + [member]})
    # A `ResourceId` a non-create's request gives names whatever the action acts on, which its service finds it as
    # (an EMR cluster or Studio its tag calls name), and another member then scopes the call rather than names one.
    one = [kind for kind in kinds if len([v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]) == 1]
    if not creating and "ResourceId" in facts and len(one) >= 2 \
            and not any("arn" in resource or "find" in resource for resource in resources):
        resources = [resource for resource in resources if resource.get("type") not in one]
        resources.append({"types": one, "through": "ResourceId"})
    subject = stem(SUBJECT_RE.sub("", name))
    about = [kind for kind in formats if kind not in kinds and related(subject, stem(kind))]
    if about and kinds and not creating and not any(resource.get("type") in kinds and resource.get("fill")
                                                    for resource in resources):
        bare = [fact["path"] for path, fact in sorted(facts.items()) if not fact["nested"] and path.lower() in BARE]
        if len(bare) == 1:
            resources.append({"types": kinds, "through": bare[0]})
    return resources


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


def member_shapes(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The botocore shape each top-level member of the operation's request holds, by member name, across the request's
    clients."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec:
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            found.setdefault(name, member["shape"])
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


def picked_by_enum(operation: str, types: list[str], formats: dict[str, str], members: dict[str, str],
                   enums: dict[str, set[str]], models: dict[str, dict] | None = None, arn_members: set[str] | None = None,
                   labels: dict[str, str] | None = None) -> list[dict] | None:
    """The resources of a request a member of which picks the type: one whose enum lists two or more of the action's
    types, a value naming a type when it matches it ignoring case and hyphens (Route 53's tag calls name a
    `ResourceType` of `healthcheck` or `hostedzone`, SSM's a `Document` or a `ManagedInstance`), or when the word that
    sets those types' formats apart abbreviates it, its letters in order from the first (Elastic Load Balancing v2's
    `Type` of `application`, `network` or `gateway`, for `loadbalancer/app/`, `/net/` and `/gwy/`). A type is picked
    too by a request leaving the member out when its documentation gives that value as the default ("The default is
    application."). Each type is filled from the member naming whatever the action acts on (`ResourceId`), whatever
    kind of value the type's one variable is, or as the rule fills it; None when no member picks a type, or no member
    names the resource."""
    def abbreviates(word: str, value: str) -> bool:
        if len(word) < 3 or not value.lower().startswith(word[0]):
            return False
        rest = iter(value.lower())
        return all(letter in rest for letter in word)
    def word(kind: str) -> str | None:
        parts = _resource(formats[kind]).split("/")
        return parts[1] if len(parts) > 2 and "$" not in parts[1] else None
    for picker, values in sorted(enums.items()):
        picks: dict[str, list] = {}
        for kind in types:
            if kind not in formats:
                continue
            matched = [value for value in sorted(values) if _enum_key(value) == _enum_key(kind)]
            if not matched and word(kind):
                matched = [value for value in sorted(values) if abbreviates(word(kind), value)]
                matched = matched if len(matched) == 1 else []
            if matched:
                picks[kind] = matched
        if len(picks) < 2:
            continue
        default = _documented_default(models or {}, operation.split(":", 1)[1], picker)
        picked = []
        for kind, picked_values in picks.items():
            variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
            named = [member for member in members if member != picker and stem(member) == "resource"
                     and len(variables) == 1]
            if named:
                member = min(named, key=lambda m: (members[m].endswith("[]"), len(m), m))
                fill = {variables[0]: members[member]}
            else:
                own = [r_ for r_ in resources_for(operation, [kind], formats, members, arn_members, labels)
                       if r_.get("type") == kind and r_.get("fill")]
                if not own:
                    return None
                fill = own[0]["fill"]
            defaulted = default is not None and any(_enum_key(default) == _enum_key(v) for v in picked_values)
            when = list(picked_values) + ([None] if defaulted else [])
            picked.append({"type": kind, "fill": fill, "when": {picker: when}})
        return picked
    return None


def _enum_key(value: str) -> str:
    """An enum value or a resource type as the two are compared: lowercased, without hyphens."""
    return value.lower().replace("-", "")


def _documented_default(models: dict[str, dict], operation: str, member: str) -> str | None:
    """The value a member's documentation gives as its default: "The default is application." gives `application`."""
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec:
            continue
        found = model["shapes"][spec["input"]["shape"]].get("members", {}).get(member)
        if found:
            match = re.search(r"the default is ([a-z0-9_-]+)", _lead_text(found.get("documentation")))
            if match:
                return match.group(1)
    return None


def _lead_text(documentation: str | None) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", documentation or "")).strip().lower()


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
    of strings, as `Name.Field` or `Name[].Field`; and those of the structures of each value of a map, or of a
    structure inside them, as `Name{}.Field` or `Name{}.Structure.Field` (a QuickSight data set's
    `PhysicalTableMap{}.S3Source.DataSourceArn`)."""
    if shape["type"] == "map":
        value = model["shapes"][shape["value"]["shape"]]
        if value["type"] != "structure":
            return []
        found = []
        for field, member in value.get("members", {}).items():
            inner = model["shapes"][member["shape"]]
            if re.search(r"arns?$", field, re.IGNORECASE) and _strings(model, inner) == "":
                found.append(f"{name}{{}}.{field}")
            elif inner["type"] == "structure":
                found += [f"{name}{{}}.{field}.{sub}" for sub, nested in inner.get("members", {}).items()
                          if re.search(r"arns?$", sub, re.IGNORECASE) and _strings(model, model["shapes"][nested["shape"]]) == ""]
        return found
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
            | set(resource.get("unless", {}))
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
                  arn_members: set[str] | None = None, labels: dict[str, str] | None = None,
                  shapes: dict[str, str] | None = None) -> list[dict]:
    """The resources the rule finds the operation's request naming, as vendored entries. `labels` gives the path
    segment before each bare member a REST request carries as a path label, stemmed (`path_labels`), and `shapes` the
    botocore shape each member holds (`member_shapes`)."""
    name = operation.split(":", 1)[1]
    created = stem(CREATING_RE.sub("", name)) if CREATING_RE.match(name) else None
    subject = stem(SUBJECT_RE.sub("", name))
    arns = {member: path for member, path in members.items() if re.search(r"arn(s|list)?$", member, re.IGNORECASE)
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
        # A type is also named by its format's leading resource word: `rule-on-default-event-bus` is a `rule`.
        word = RESOURCE_PART_RE.match(formats[kind])
        word_stem = stem(word.group(1)) if word else kind_stem
        for member in sorted(arns):
            by_arn.setdefault(member, []).append(kind)
        # A top-level ARN member named for the type, or for whatever the action acts on, holds it, and a bare
        # name then names something else.
        held = [member for member in arns if "." not in member and (stem(member) in ("", "resource")
                or (stem(member) == kind_stem if member in (arn_members or set())
                    else related(stem(member), kind_stem) or kind_stem.startswith(stem(member))))]
        variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
        # A create makes its own type, so a member holding whatever the action acts on does not hold that one:
        # `CreateResourceShare`'s `resourceArns` are what the share it makes holds.
        creates_it = created is not None and (related(created, kind_stem) or related(created, word_stem))
        holding = [member for member in held if not (creates_it and stem(member) in ("", "resource"))]
        # Its bare name then names what it makes, when the type's variables say they are a name or an id: a share's
        # `${ResourcePath}`, which RAM mints, takes no name.
        naming = holding if all(kind_of(v) for v in variables) else held
        # A bare `Name` or `Id` names the type the operation is about, or the one the path segment before it
        # names: `UpdateSafetyLeverState`'s `id`, in `/safetyLevers/{id}/state`, is the lever's.
        bare = set() if naming else set(names) if related(subject, kind_stem) or related(subject, word_stem) \
            else {member for member, segment in (labels or {}).items() if related(segment, kind_stem)}
        # A member fills the resource's own variables before the account its ARN is in, which a request gives
        # through an account member alone: Account Management's `AccountId` is the member account an
        # `account/o-${OrganizationId}/${MemberAccountId}` names, not its management account.
        slot = ACCOUNT_SLOT_RE.match(formats[kind])
        in_slot = slot.group(1) if slot else None
        fill: dict[str, str] = {}
        for variable in sorted(variables, key=lambda v: (v == in_slot, kind_of(v) == "", v)):
            choice = _filling(variable, kind_stem, len(variables), {m: p for m, p in names.items() if m not in fill.values()},
                              bare=bare, qualified=namespace_of(operation) in QUALIFIED_MEMBERS, word_stem=word_stem,
                              shapes=shapes if not held else None)
            if choice is not None:
                fill[variable] = choice
                used[kind] = choice
        fill = {variable: names[member] for variable, member in fill.items()}
        if len([path for path in fill.values() if path.endswith("[]")]) > 1:
            fill = {variable: path for variable, path in fill.items() if not path.endswith("[]")}
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
             qualified: bool = False, word_stem: str | None = None,
             shapes: dict[str, str] | None = None) -> str | None:
    """The member that fills the variable: one of a compatible kind whose stem relates to the variable's,
    or, for the variable named after the type or a type's only one, a member named after the type, or a
    bare `Name` or `Id` among those `bare` gives, of a compatible kind, or else a string member whose botocore
    shape is named after the type, of a compatible kind (Parameter Store's `Path` and `GetParameterHistory`'s
    `Name` hold a `PSParameterName`); the closest of them, one qualified by the type's own name first (a
    snapshot's `SnapshotClusterIdentifier` before `ClusterIdentifier`), and a string before a list. A variable the
    reference names `ResourceId` is the type's own id (`ingestion/${ResourceId}`)."""
    # A `ResourceId` variable is filled by a member named so (CreateOpsMetadata's, `opsmetadata/${ResourceId}`) before
    # one named after the type; a bare `Id` is not, being whatever the operation is about.
    if stem(variable) == "resource" and variable in names and not names[variable].endswith("[]"):
        return variable
    variable_stem = kind_stem if stem(variable) == "resource" else stem(variable)
    candidates = [member for member in names
                  if stem(member) and related(stem(member), variable_stem) and compatible(member, variable)]
    if not candidates and (related(variable_stem, kind_stem) or variables == 1):
        candidates = [member for member in names if compatible(member, variable)
                      and ((member in bare and member.lower() in BARE)
                           or (stem(member) and (related(stem(member), kind_stem)
                                                 or (qualified and names_type(member, kind_stem)))))]
    elif not candidates and word_stem and related(variable_stem, word_stem):
        # A variable named after the format's leading word (`rule/${EventBusName}/${RuleName}`'s `RuleName`) takes a
        # bare `Name` or `Id` of the operation that is about the type, and no member named after another type.
        candidates = [member for member in names if compatible(member, variable)
                      and member in bare and member.lower() in BARE]
    if not candidates and (related(variable_stem, kind_stem) or variables == 1):
        candidates = [member for member in names if member in (shapes or {}) and not names[member].endswith("[]")
                      and "." not in member and stem(shapes[member]) and related(stem(shapes[member]), kind_stem)
                      and compatible(shapes[member], variable) and compatible(member, variable)]
    if not candidates:
        return None
    # A member naming the type before the variable (`SnapshotClusterIdentifier` for the snapshot's `ClusterName`,
    # `DistributionTenantId` for the tenant's `Id`), or qualified by the type's own name, wins over a bare one, which
    # names something else.
    return min(candidates, key=lambda member: (not (stem(member) == kind_stem + variable_stem
                                                    or (stem(member).startswith(kind_stem) and stem(member) != kind_stem)),
                                                stem(member) != variable_stem, names[member].endswith("[]"),
                                                len(member), member))


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
    """An id list, in model and EC2 Query spelling, with each supported type's discriminator."""
    configured = ID_TARGETS.get(namespace_of(operation))
    if configured is None:
        return None
    found = None
    for model in models.values():
        spec = model["operations"].get(operation.split(":", 1)[1], {})
        if "input" not in spec or "ec2" not in _protocols(model):
            continue
        members = model["shapes"][spec["input"]["shape"]].get("members", {})
        variables = {variable for kind in configured["prefixes"] if kind in types and kind in formats
                     for variable in set(VARIABLE_RE.findall(formats[kind])) - KNOWN_VARIABLES}
        candidates = [name for name, member in members.items()
                      if _strings(model, model["shapes"][member["shape"]]) == "[]"
                      and name.lower().removesuffix("s") in {variable.lower() for variable in variables}]
        if len(candidates) > 1:
            raise ValueError(f"{operation}: several id lists name the target: {candidates}")
        member_name = candidates[0] if candidates else configured["member"]
        member = members.get(member_name)
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
        location = member.get("locationName", member_name)
        query = member.get("queryName", location[:1].upper() + location[1:])
        target = {"member": member_name + "[]", "query": query, "prefixes": prefixes}
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
    resources = by_client(operation, models, lambda read: read_resources(operation, types, formats, read), formats)
    resources = with_made(resources, operation, formats)
    if operation in MEASURED:
        resources = measured_resources(operation, types, formats, resources)
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


def measured_resources(operation: str, types: list[str], formats: dict[str, str], derived: list[dict]) -> list[dict]:
    """The resources `MEASURED` gives the operation, refused unless its admission rules hold: its evidence links to
    the measurement, the Service Reference lists none of its types for the action, each has an ARN format, and the
    rules, which gave `derived`, do not already give them."""
    resources, evidence = MEASURED[operation]
    kinds = {kind for resource in resources for kind in _kinds(resource)}
    if "https://" not in evidence:
        raise ValueError(f"MEASURED gives {operation} with no link to the measurement of AWS it rests on")
    listed = sorted(kinds & set(types))
    if listed:
        raise ValueError(f"MEASURED gives {operation} {listed}, which the Service Reference lists for its action: "
                         f"the rules read those, so a measurement of them belongs in a rule")
    unknown = sorted(kinds - set(formats))
    if unknown:
        raise ValueError(f"MEASURED gives {operation} {unknown}, which have no ARN format in the Service Reference")
    if derived == resources:
        raise ValueError(f"MEASURED gives {operation} what the rules already give: take it out")
    return resources


def read_resources(operation: str, types: list[str], formats: dict[str, str], models: dict[str, dict]) -> list[dict]:
    """The resources the rules read from the request of the clients given."""
    name = operation.split(":", 1)[1]
    members = request_members(models, name)
    patterns = string_patterns(models, name)
    arn_members = {member for member, values in patterns.items()
                   if (kind_of(member) == "identifier" or stem(member) in ("", "resource"))
                   and values and all(only_arn(value) for value in values)}
    labels = path_labels(models, name)
    resources = picked_by_enum(operation, types, formats, members, enum_members(models, name), models, arn_members, labels)
    if resources is None:
        resources = with_through(with_found(with_parent_ids(resources_for(operation, types, formats, members,
                                                                          arn_members, labels,
                                                                          member_shapes(models, name)),
                                                            formats, types, members),
                                            namespace_of(operation), types, members),
                                 namespace_of(operation), types, members)
        resources = with_documented(operation, types, formats, models, resources)
    optional = optional_members(models, name)
    resources = with_absent_arn(resources, formats, optional)
    resources = with_absent_account(resources, formats, account_members(models, name), optional)
    resources = with_map_fields(resources, types, formats, map_fields(models, name))
    resources = with_account(resources, formats, account_members(models, name))
    resources = with_structure_account(resources, formats, structure_account_members(models, name, types))
    resources = with_arn_of(resources, formats, name_or_arn_members(models, name))
    resources = with_default_pairs(resources, formats, optional)
    resources = with_enum_named(operation, resources, formats, models)
    resources = with_subject_through(operation, resources, members, map_fields(models, name), optional, models)
    return with_optional_qualifier(resources, optional)


def with_enum_named(operation: str, resources: list[dict], formats: dict[str, str],
                    models: dict[str, dict]) -> list[dict]:
    """The resources, a variable no member fills of a type named by its variables `fixed` to the one value of an enum
    named after the variable that the operation's name gives as a word: CodePipeline's `CreateCustomActionType` names
    an `actiontype:${Owner}/${Category}/${Provider}/${Version}` whose `ActionOwner` enum lists `AWS`, `ThirdParty`
    and `Custom`, the owner of the custom action types it makes."""
    name = operation.split(":", 1)[1]
    found = []
    for resource in resources:
        if not resource.get("fill") or "find" in resource or "default" in resource:
            found.append(resource)
            continue
        unfilled = set(VARIABLE_RE.findall(formats[resource["type"]])) - KNOWN_VARIABLES - set(resource["fill"])
        fixed = {}
        for variable in sorted(unfilled):
            values = {value for model in models.values() for shape_name, shape in model["shapes"].items()
                      if shape.get("enum") and shape_name.lower().endswith(variable.lower()) for value in shape["enum"]}
            given = sorted(value for value in values
                           if value[:1].isupper() and re.search(rf"{re.escape(value)}(?=[A-Z]|$)", name))
            if len(given) == 1:
                fixed[variable] = given[0]
        found.append(dict(resource, fixed=fixed) if fixed else resource)
    return found


def with_subject_through(operation: str, resources: list[dict], members: dict[str, str], fields: dict[str, str],
                         optional: set[str], models: dict[str, dict]) -> list[dict]:
    """The resources, and a type the request names only by an optional member documented as holding what the operation
    is about (its name past the verb), "the cluster to which the instance group belongs", also named, when the request
    leaves that member out, through each member naming what the operation is about: `ModifyInstanceGroups` names its
    cluster by its `ClusterId`, or else through each `InstanceGroups[].InstanceGroupId`, the cluster the service finds
    the group in."""
    name = operation.split(":", 1)[1]
    verb = SUBJECT_RE.match(name)
    subject = stem(name[verb.end():]) if verb else ""
    if not subject:
        return resources
    found = list(resources)
    for resource in resources:
        fill = resource.get("fill")
        if not fill or set(resource) - {"type", "fill", "arnOf"} or len(set(fill.values())) != 1:
            continue
        member = next(iter(fill.values()))
        held = SUBJECT_HOLDER_RE.search(_lead(_member_documentation(models, name, member)))
        if member not in optional or not held or stem(held.group("subject")) != subject:
            continue
        for path in sorted(set(members.values()) | set(fields)):
            if path != member and stem(path.split(".")[-1].removesuffix("[]")) == subject:
                found.append({"types": [resource["type"]], "through": path, "when": {member: [None]}})
    return found


def _member_documentation(models: dict[str, dict], operation: str, member: str) -> str | None:
    """The documentation of a top-level member of the operation's request, from the first client that has it."""
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec and "input" in spec:
            found = model["shapes"][spec["input"]["shape"]].get("members", {}).get(member)
            if found:
                return found.get("documentation")
    return None


def pattern_words(pattern: str) -> set[str]:
    """The resource type words an ARN pattern names after its account: `...:([0-9]{12}):(event-busv2|subscriber)\\/`
    gives `event-busv2` and `subscriber`."""
    words: set[str] = set()
    for match in re.finditer(r"\{12\}\)?\??:", pattern):
        rest = pattern[match.end():].lstrip("(")
        depth, text = 0, ""
        for char in rest:
            if char == "(":
                depth += 1
            elif char == ")":
                if depth == 0:
                    break
                depth -= 1
            elif char == "/" and depth == 0:
                break
            text += char
        depth, part, parts = 0, "", []
        for char in text:
            depth += char == "("
            depth -= char == ")"
            if char == "|" and depth == 0:
                parts.append(part)
                part = ""
            else:
                part += char
        parts.append(part)
        words |= {found.group(0) for found in (re.match(r"[a-z][a-z0-9-]*", item) for item in parts) if found}
    return words


def client_words(model: dict) -> set[str]:
    """The resource type words a client's ARN patterns name."""
    return set().union(*(pattern_words(shape.get("pattern") or "") for shape in model["shapes"].values()
                         if shape.get("type") == "string"))


def by_client(operation: str, models: dict[str, dict], read, formats: dict[str, str] | None = None) -> list[dict]:
    """The resources read from every client of the namespace whose model has the operation, each read apart when
    they disagree: a resource every client names stays as it is, and one only some name applies to the requests of
    their endpoint prefix (EventBridge's `events` and EventBridge V2's `eventsv2`), or to every request where the
    clients share one (Elastic Load Balancing's classic and v2 APIs). Where a client's ARN patterns name one of two
    types its request names by the same members, that client's request names that one: EventBridge V2's
    `CreateEventBus` makes an `event-busv2`."""
    name = operation.split(":", 1)[1]
    clients = sorted(client for client, model in models.items()
                     if "input" in model["operations"].get(name, {}) and _reading(model))
    merged = read(models)
    if len(clients) < 2:
        return merged
    apart = {client: read({client: models[client]}) for client in clients}
    if all(apart[client] == apart[clients[0]] for client in clients):
        return merged
    for client in clients:
        apart[client] = narrowed(apart[client], client_words(models[client]), models[client], name, formats)
    prefixes = {client: models[client]["metadata"].get("endpointPrefix") for client in clients}
    shared = len(set(prefixes.values())) < len(clients)
    resources: list[dict] = []
    for client in clients:
        for resource in apart[client]:
            everywhere = all(resource in apart[other] for other in clients)
            tagged = resource if everywhere or shared else dict(resource, endpoint=prefixes[client])
            if tagged not in resources:
                resources.append(tagged)
    return resources


def narrowed(resources: list[dict], words: set[str], model: dict, operation: str,
             formats: dict[str, str] | None = None) -> list[dict]:
    """A client's resources, each ARN member's types narrowed to those its own pattern names, and a type its patterns
    never name read as its versioned twin they do (`event-bus` as `event-busv2`, EventBridge V2's own), when that
    twin's format has the variables it fills."""
    def word(kind: str) -> str:
        match = RESOURCE_PART_RE.match((formats or {}).get(kind, ""))
        return match.group(1) if match else ""
    twins = {kind: twin for kind in (formats or {}) for twin in (formats or {})
             if word(kind) and word(kind) not in words and word(twin) in words
             and re.fullmatch(r"v\d+", word(twin)[len(word(kind)):] if word(twin).startswith(word(kind)) else "")}
    resources = [dict(resource, type=twins[resource["type"]]) if resource.get("type") in twins
                 and set(resource.get("fill", {})) <= set(VARIABLE_RE.findall(formats[twins[resource["type"]]]))
                 else resource for resource in resources]
    spec = model["operations"].get(operation, {})
    root = model["shapes"][spec["input"]["shape"]] if "input" in spec else {"members": {}}
    found = []
    for resource in resources:
        if "arn" in resource and "." not in resource["arn"]:
            member = root.get("members", {}).get(resource["arn"].removesuffix("[]"))
            shape = model["shapes"][member["shape"]] if member else {}
            element = model["shapes"][shape["member"]["shape"]] if shape.get("type") == "list" else shape
            own = pattern_words(element.get("pattern") or "")
            kinds = [kind for kind in resource["types"] if kind in own]
            found.append(dict(resource, types=kinds) if kinds and kinds != resource["types"] else resource)
        else:
            found.append(resource)
    return found


def with_default_pairs(resources: list[dict], formats: dict[str, str], optional: set[str]) -> list[dict]:
    """The resources, two types whose formats differ by one variable an optional member fills (EventBridge's
    `rule/${RuleName}` and `rule/${EventBusName}/${RuleName}`) marked so the one without it applies when the member
    is absent or names its service's default (`whenDefault`), and the other otherwise (`unlessDefault`): a rule on
    the default event bus has no bus in its ARN."""
    found = [dict(resource) for resource in resources]
    for longer in found:
        for shorter in found:
            if "fill" not in longer or "fill" not in shorter or longer is shorter or "type" not in longer \
                    or "type" not in shorter:
                continue
            extra = set(longer["fill"]) - set(shorter["fill"])
            if len(extra) != 1:
                continue
            variable = extra.pop()
            member = longer["fill"][variable]
            if member not in optional or shorter["fill"] != {k: v for k, v in longer["fill"].items() if k != variable}:
                continue
            if _resource(formats[shorter["type"]]).replace("${" + variable + "}/", "") != \
                    _resource(formats[longer["type"]]).replace("${" + variable + "}/", "") \
                    or "${" + variable + "}" in formats[shorter["type"]]:
                continue
            # The type whose default it is: the one the member may give the ARN of, or else whose format is the
            # variable alone.
            kind = longer.get("arnOf", {}).get(member) or named_by_variable(variable, formats)
            if kind is None:
                continue
            shorter["whenDefault"] = {member: kind}
            longer["unlessDefault"] = {member: kind}
            # The resource on the default is in the account and Region of an ARN the member gives, as the other is.
            if member in longer.get("arnOf", {}):
                shorter["arnOf"] = {**shorter.get("arnOf", {}), member: longer["arnOf"][member]}
    return found


def named_by_variable(variable: str, formats: dict[str, str]) -> str | None:
    """The one type whose format's resource is a word and the variable alone (`event-bus/${EventBusName}`)."""
    found = [kind for kind, arn in formats.items() if re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*/\$\{" + variable + "}", _resource(arn))]
    return found[0] if len(found) == 1 else None


def with_optional_qualifier(resources: list[dict], optional: set[str]) -> list[dict]:
    """The resources, one filling a variable from an optional member no other resource reads, beside another it
    fills from a member that is, named only when the request gives that member: Redshift's `GetClusterCredentials`
    checks the database it names only 'If the DbName parameter is specified'."""
    read = [path for resource in resources for path in resource.get("fill", {}).values()]
    found = []
    for resource in resources:
        fill = resource.get("fill", {})
        own = [path for path in fill.values() if path in optional and read.count(path) == 1]
        if len(fill) >= 2 and len(own) == 1 and len(own) < len(fill) \
                and not {"when", "unless", "find", "whenDefault", "unlessDefault"} & set(resource):
            resource = dict(resource, unless={own[0]: [None]})
        found.append(resource)
    return found


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
        "resourceTagKeys": resource_tag_keys(documents),
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
    """Read whole namespaces, and in a namespace otherwise built by hand the operations `READ_IN_HAND_BUILT` lists
    and those `MEASURED` gives."""
    namespace = namespace_of(operation)
    return namespace in NAMESPACES or namespace in HAND_BUILT and (operation in READ_IN_HAND_BUILT
                                                                     or operation in MEASURED)


def _check_overrides(covered: dict[str, dict]) -> None:
    """Every operation `READ_IN_HAND_BUILT` lists or `MEASURED` gives is one the file reads, and no namespace is in
    two places."""
    stale = [f"READ_IN_HAND_BUILT names {operation}, which the reference does not give" for operation in READ_IN_HAND_BUILT
              if operation not in covered]
    stale += [f"MEASURED gives {operation}, which the file does not read" for operation in MEASURED
              if operation not in covered]
    stale += [f"MEASURED gives {operation}, which READ_IN_HAND_BUILT reads by the rules" for operation in MEASURED
              if operation in READ_IN_HAND_BUILT]
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
    for key in ("_source", "namespaces", "clientsNotRead", "handBuilt", "notCovered", "otherNamespaces", "arnFormats", "resourceTagKeys",
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
FOUND_ONLY = {"arnOf", "fixed"}


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
    tag_keys = document.get("resourceTagKeys", {})
    if not isinstance(tag_keys, dict) or any(
            not isinstance(kinds, dict) or any(kind not in formats.get(namespace, {})
                or not isinstance(keys, list) or not all(isinstance(key, str) for key in keys) or keys != sorted(set(keys))
                or any(not isinstance(key, str) or not re.fullmatch(r"[a-z0-9-]+:ResourceTag/", key) for key in keys)
                for kind, keys in kinds.items()) for namespace, kinds in tag_keys.items()):
        problems.append("resourceTagKeys is not stored-tag prefixes for supported resource types")
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
            # Only a measured entry names a type the reference omits for the action; MEASURED's admission checks
            # (see `measured_resources`) say when it may.
            measured = set(_kinds(resource)) if isinstance(resource, dict) and operation in MEASURED else set()
            problems += _resource_problems(operation, resource, set(types) | measured, formats.get(namespace, {}))
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
    if not isinstance(resource, dict) or not ({"types", "arn"} <= set(resource) <= {"types", "arn", "made", "endpoint"}
                                              or {"types", "through"} <= set(resource) <= {"types", "through", "when",
                                                                                            "endpoint"}
                                              or {"type", "fill"} <= set(resource) <= {"type", "fill", "when", "unless", "arnOf", "account",
                                                                                       "fixed", "made", "find", "endpoint",
                                                                                       "default", "whenDefault", "unlessDefault"}):
        return [f"{operation} names {resource!r}, which is neither an ARN member nor a type's variables"]
    for key in ("whenDefault", "unlessDefault"):
        pair = resource.get(key)
        if key in resource and (not isinstance(pair, dict) or len(pair) != 1
                                or not all(isinstance(m, str) and PATH_RE.match(m) and k in formats for m, k in pair.items())
                                or (key == "unlessDefault" and next(iter(pair)) not in resource["fill"].values())
                                or ("whenDefault" in resource and "unlessDefault" in resource)):
            return [f"{operation} names {resource!r} {key} no member and type of a default, or both"]
    if "default" in resource and (resource["default"] is not True or resource["fill"] or not resource.get("when")
                                  or set(resource) - {"type", "fill", "when", "default", "endpoint"}):
        return [f"{operation} names {resource!r} as its service's default, which is not for a request naming none"]
    if "endpoint" in resource and not (isinstance(resource["endpoint"], str) and resource["endpoint"]):
        return [f"{operation} serves {resource!r} on {resource['endpoint']!r}, which is no endpoint prefix"]
    if resource.get("made", True) is not True:
        return [f"{operation} marks {resource!r} made by {resource['made']!r}, which is not true"]
    named = resource.get("types") or [resource.get("type")]
    kinds = _kinds(resource)
    if len(kinds) != len(named) or not all(kind in types and kind in formats for kind in kinds):
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
                   and (namespace_of(operation) not in vendored["handBuilt"] or operation in READ_IN_HAND_BUILT
                        or operation in MEASURED)
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
        if document.get("resourceTagKeys", {}) != resource_tag_keys(documents):
            untrue.append("resourceTagKeys differ from AWS Service Reference")
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
