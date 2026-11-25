#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-request-resources.json from AWS's Service Reference and botocore.

Under IAM enforcement every request is checked against the resources it acts on. A request whose
resources are not read is checked on `*`, so a grant scoped to a resource is refused and a deny scoped
to one never applies. This file gives,
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
  `Id`, `Identifier`, `Config`, `Input`, `List` and a plural dropped) is the variable's, ends with it or is the end of it, four
  letters at least (`AutoScalingGroupName` fills `${GroupFriendlyName}`, `GroupId` fills
  `${SecurityGroupId}`), and it is a name for a name and an id for an id (an `Identifier` either),
  or the member is a bare `Name`, `Id` or `Identifier`, the variable is named after the type and the
  operation is about the type, its name past the verb the type's (`DescribeTargetGroups`, but not
  `StartExecution` for a state machine, whose `name` is the execution's), and no member holds its ARN,
  or, when no member does either, a string or list member whose botocore shape is named after the type (Parameter
  Store's `GetParametersByPath` names a hierarchy by a `Path` holding a `PSParameterName`, as `GetParameterHistory`
  names a parameter by its `Name`, and Glue's `BatchDeleteTable` its tables by `TablesToDelete`, a
  `BatchDeleteTableNameList`).
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
related to its name past the verb, so its tags are checked there alone. A create whose request carries tags,
whose action acts on other types alone, makes the resource of the type it is named after when the request names
every variable of it, a field of a top-level structure included (`makes`): Glue's `CreateConnection` acts on its
connection type and the catalog, and its tags go on `connection/<ConnectionInput.Name>`.

A type of no variables of its own, the account's one (Glue's `catalog`), is named by every request, so it is no
alternative to the unnamed type a create makes: `CreateDatabase` is checked on it and on the database its
`DatabaseInput.Name` names.

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
by and its query. A member the clients of an operation carry in different places is recorded under each client
(`locatedApart`: S3's CreateBucket carries its `Bucket` as `{Bucket}`, S3 Control's as `{name}`), the request's
vendored route telling its client. A REST-XML request's body is not read, so an operation naming what it acts on there
by a shape named as the type and `Key` (S3's DeleteObjects, `Delete.Objects[].Key`) is recorded `unread`, and the
reader leaves it to its service, as checked on the rest of its request it would be checked on none of what it names.
A namespace read this way also takes the operations of each botocore model Floci reads REST routes
for under it (`regen_rest_routes.MODELS`) that the reference lists for none of its clients, each checked as the action
of its own name with that action's resource types when the reference has that action, and left out when it does not:
the reference lists one client's operations per namespace, and
OpenSearch Service's are the `es` client's alone, while the `opensearch` client's are named after `es` actions.

Every operation in `NAMESPACES` is read, whether Floci serves it or not, and `NOT_COVERED` are the namespaces Floci
serves that are not read yet, each with its reason; `RequestResourceNamespacesIntegrationTest` fails for a served
namespace in neither.
`ID_TARGETS` is Floci's coverage of id-list namespaces, the types of each it reads by id (EC2's instances and
network resources): a resource ARN's variable names its list member, with the request's list of whatever resources
it acts on (`Resources`) as a fallback. A list a type's variable names is the request's targets only when the
request names nothing else the action acts on. Botocore supplies its serialized EC2 Query name (including queryName
and capitalization), and the id prefix botocore gives each type, in its documentation ("the instance ID (for
example, i-0123456789abcdef0)") or its examples (`"GroupId": "sg-903004f8"`), distinguishes them. Cited EC2 API
examples supply a missing VPC endpoint prefix. Each generated
`targets` entry preserves the request's order and duplicates, including a wildcard for each unsupported id; only
resolved ARNs use the corresponding target's condition context. Every other request of those namespaces is read by
the ordinary rules, its EC2 Query wire names (`ec2Query`) from botocore. `resourceTagKeys` gives every type of the
namespaces read its Service Reference resource-tag condition keys (`aws:ResourceTag/`, `secretsmanager:ResourceTag/`),
which the condition resolver fills with the tags the type's service holds on the resource an ARN names, and, for those
id-list namespaces, `resourceKeys` the type's other condition keys that name a member of its botocore shape (`ec2:Vpc` a network
interface's `VpcId`), which its service answers from the resource it holds, as an ARN where the reference types the
key as one.
Beyond the members' names, botocore's shapes and documentation say what a member names, and the rule reads them:
- a member whose documentation names the type, word for word ("The name of the pipeline to start"), fills its
  variable when it is the kind the variable is (a name, an id), and is found by the type's service otherwise
  (`find`: "The name of the stream processor" for its `${StreamprocessorId}`), the id open when it finds none;
  one whose documentation's head noun is an ARN ("The Amazon Resource Name (ARN) to which you want to add the tag")
  holds an ARN, as does a member documented with "This value is an Amazon Resource Name (ARN).",
  and so does one whose name ends in `ArnList`;
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
  the operation is about (`ModifyInstanceGroups`' `InstanceGroups[].InstanceGroupId`);
- a type of two variables no member fills is named by the member whose documentation spells it as its format does
  ("The family and revision (family:revision) or full ARN of the task definition to run"), which its service finds
  as its handler resolves it (`find`), each element of a list; such a member, "or full ARN" coming after something
  else, holds no ARN alone;
- a member documented as a name or an ARN of another type than the one whose variable it fills ("The short name or
  full Amazon Resource Name (ARN) of the cluster", "The Amazon Resource Name (ARN) or short name of the cluster",
  "the cluster name or ARN": an ECS service's `cluster`) may give that type's ARN (`arnOf`), as a pattern taking
  one does;
- a list element's id field beside a field of its stem holding the target's type, an enum whose one value is a
  type the action acts on, fills that type's last variable no member fills (ECS's `PutAttributes`:
  `attributes[].targetId`, beside `targetType`, on the container instance it names);
- a member whose documentation says its service uses its default for a request leaving it out ("If you do not
  specify a cluster, the default cluster is assumed") names that default then (`defaults`), when it fills the one
  variable of that type's format, rather than naming nothing.
A URI or query member can carry the same name-or-ARN alternative as a body member. An integer shape
whose name matches an otherwise unfilled ARN variable (optionally ending in `Number`) supplies that variable.
`integerVariables` records these numeric identifiers so the reader canonicalizes leading zeroes even when the
request gives the whole ARN; string identifiers retain their spelling.
What a request names, this file alone says. A service only looks up what it holds where an entry asks it to
(`StoredResourceArns`): the resource the members a `find` gives name, the ones a `through` member reaches, the
resource its handler uses for a member left out (Athena's `primary` workgroup, EventBridge's `default` bus, ECS's
`default` cluster), and the user whose key signs a request (`signer`). No service says what an operation acts on.

`MEASURED` holds what AWS checks where neither botocore nor the Service Reference says it, and nothing else. It
is the only part of the file stated by hand, and an entry is admitted only with evidence from AWS
itself (a policy probe against real AWS published in a Floci pull request, AWS's AccessDenied message recorded
verbatim, or AWS's documentation saying it in so many words, quoted), for resource types the reference does not
list for the action, giving what the rules do not already give, and linking to its evidence; the generator refuses
an entry that breaks any of the last three, and review the first. Analogy, the request's shape and what a Floci
handler does are not evidence: what they give belongs in a rule. DynamoDB's `ListTables` (`table/*`) and Step
Functions' `SendTask*` (the token's activity or state machine) are its entries.
An operation whose service asks another service, as the caller, to act on a resource one of the operation's own
holds is also checked as that request (`forwarded`): its action, on the resource botocore's record of the
operation's type holds in the member named after the action's namespace and the variable of the type the action
acts on (a Redshift snapshot's `KmsKeyId`: `kms` and `key/${KeyId}`), which the reader asks the type's service for
and then the action's service to find, with `aws:ViaAWSService` and, where the Service Reference lists the
namespace's `ViaService` key for the action, that key naming the operation's endpoint. Which operations do this,
neither botocore nor the Service Reference says, so `MEASURED` states it, its evidence cited and the action not
one the reference lists for the operation, which a rule would read instead: a Redshift or
Redshift Serverless restore asks KMS to decrypt with the snapshot's key. `forwardedActions` records what the
reference gives each such action, the type it acts on, that type's ARN format and the `ViaService` key, which
`--verify` checks against AWS.
An operation whose request carries a statement its service runs against another service as the caller (Athena's
`StartQueryExecution`, whose DDL statements Athena runs against the Glue Data Catalog with the caller's identity) is
also checked, when its service runs the statement, as the requests that statement makes (`statements`). Neither
botocore nor the Service Reference says which those are, so they come from a third source, granted for this one
table and no other: the page of AWS's documentation that gives each statement's permissions, which its own script
vendors as it stands (`STATEMENT_SOURCES`). Each action of a statement's example policy acts on each resource of the
same policy statement whose type the Service Reference gives the action, the type whose format the example ARN
matches, its variables filled from what the page says the example's names stand for (`database`, `table`) and
open where the example has a wildcard. `statementActions` records the types the reference gives each such action,
which `--verify` checks against AWS, as it checks the vendored page against the live one.
An operation AWS authorizes as the HTTP method of its request, on the resource its path names, is checked so. API
Gateway's developer guide says it of its management API ("The API-managing Action expression has the format
apigateway:action, where action is one of the following API Gateway actions: GET, POST, PUT, DELETE, PATCH", and "API
Gateway resources have the following ARN format: arn:aws:apigateway:region::resource-path-specifier") and lists each
resource's ARN in its ARN reference. Neither botocore nor the Service Reference says which resource a route acts on,
and the reference gives an HTTP API's `Stage` where a REST API's differs, so they come from another source, granted for
that namespace alone: those pages, which their own script vendors as they stand (`PATH_SOURCES`). A route checked as
its method acts on the resource whose format, of the reference's types or the guide's rows, is its own path, its labels
filling its variables, and on the guide's ARN format with that path where no published path is (`routed_resources`):
every published path a route matches is that route's own, the `/v2` of an HTTP or WebSocket API's left out.
`pathFormats` records the formats of those the reference gives no type, which `--check` derives again from the vendored
pages and botocore, and `--verify` checks the vendored pages against the live ones.
Where a KMS operation acts on several resources, or on one its request leaves out, which neither botocore nor the
Service Reference says, they come from one more source, granted for that namespace alone: the developer guide's
permissions table, the API reference pages it links to and the guide's key identifiers, which their own script vendors
as they stand (`PERMISSION_SOURCES`). Each resource a row of the table lists is checked as the action it names, of the
type it names by its name, on the member the rules read that type from or holding the botocore shape the service's
records hold the type's variable in, the one documented as used for what the row says where it says it, and else
through the record of another resource the row lists (`permission_resources`): ReEncrypt as `kms:ReEncryptFrom` on its
`SourceKeyId` and `kms:ReEncryptTo` on its `DestinationKeyId`, UpdateAlias also on the key its alias points at now. An
operation the table checks as several actions records them (`actions`, each with the types the reference gives it) and
each resource the one it is checked as (`action`); an action on no resource is checked on `*`. A parameter whose API
reference says KMS reads its resource out of another's metadata names it through that one when the request leaves it
out (`with_from_metadata`: Decrypt's `KeyId`, `CiphertextBlob`), and a type the key identifiers give a name of that is
another identifier's ARN's resource is named by that resource whole (`named_resources`, `partialArn`: `alias/<name>`).
DynamoDB's developer guide is granted the same way, its PartiQL page and its transactions page vendored by their own
script: each item of a transaction is checked as the action the transactions page gives its kind, on the table its
`TableName` names (`item_resources`: `TransactItems[].Put.TableName` as `dynamodb:PutItem`), and each PartiQL
statement as the action the PartiQL page gives its verb, on the table or index it names, which the reader asks the
service's parser for (`statement_resources`, `statement`, `verb`, `names`); an operation checked so records the
actions (`actions`) and is checked on each element of a list apart. A top-level map keyed by a shape named after a type,
held by a member named as the type's one variable elsewhere, names that type by its keys (`with_map_keys`:
BatchGetItem's `RequestItems{}`).
IAM's user guide and API reference are granted the same way, its identifiers page and the API reference page of each
operation vendored by their own script. A type the identifiers write with a name and its path
(`user/{user-name-with-path}`) is named by a create by the name its request gives after the path it gives, as the
identifiers say the API's creates take one (`with_paths`, `path`: CreateUser's `UserName` after its `Path`), and by
any other operation by its name, the reader asking the service for the ARN it holds under it, path included. An
operation whose API reference says the caller needs permission on its old and its new name also names the resource
under the name and the path its request moves it to (`with_renames`, `renames`: UpdateGroup's `NewGroupName` and
`NewPath`); one whose API reference says it acts on the user who signs it names that user when its request leaves out
the member that would name another (`with_signing_user`, `signer`: GetUser with no `UserName`, ChangePassword always),
which the reader asks the service that holds the signing key for; a member whose API reference says its value is the
resource's ARN names the resource by it (`with_same_as_arn`: TagMFADevice's `SerialNumber`); and a type the format of a
member's identifier names is named through that member (`with_formats`: GetServiceLinkedRoleDeletionStatus's
`DeletionTaskId`, the role the task deleted).
Which types an action always acts on, which neither botocore nor the Service Reference says, comes from one more
source, granted for Identity Store alone: its page of the Service Authorization Reference, whose actions table marks
each required type with an asterisk, which its own script vendors as it stands (`REQUIRED_TYPE_SOURCE`). A required
type no member names is named through the id of the resource the request names, whose botocore record holds it
(`with_record_through`: DeleteGroupMembership's group and user, through its `MembershipId`), or found by the members
of a lookup returning its id, each way the request may give them apart (`with_lookup_find`: GetUserId's user, by its
`IdentityStoreId` and its `AlternateIdentifier`'s `ExternalId` or `UniqueAttribute`), which the reader asks the
service for.
A qualified type the reference gives an action, one whose format is another type's and one more `:${...}` (Lambda's
`function alias` and `function version`), which no member fills alone, is named by that type's member and the
request's one member documented as naming the qualified types ("The version number or alias name"): Lambda's
`GetProvisionedConcurrencyConfig` acts on the function's alias or version its `Qualifier` names. A member whose botocore pattern spells a
type's resource word and takes its ARN without the leading parts, `123456789012:function:my-function`, names the type
by such a partial ARN too (`partialArn`), which the reader completes in the request's partition. An account id member
beside an ARN member of the same name (`SourceAccount` beside `SourceArn`) is that ARN's resource's account. A record
field named after the end of a type's name (a MicroVM's `imageArn`, of its `microvmImage`) reaches that type through
the record's id, which a request may give as the thing's identifier (`microvmIdentifier` for `microvmId`), and an
operation documented as acting on a thing "in a <type>" ("a callback operation in a durable execution") reaches that
type through the thing's id. Clients of one namespace serving one endpoint keep every type an ARN member of theirs
may name, each ARN read by its format, since a request cannot tell them apart.
A type's ARN given with a qualifier, one more `:<name>` past its format, is checked without one where the
metadata says the qualifier names nothing of its own (`unqualified`): the reference gives Step Functions'
`statemachinealias` and `statemachineversion` formats as the state machine's and one more `:${...}`, and no action
acts on them, and botocore documents Parameter Store's `Name` as `"name:version"` or `"name:label"`, a selector the
reference gives no type. A value filling a variable named `...WithoutLeadingSlash` is read as a Parameter Store
name, without the whitespace around it and one leading `/`: a parameter's ARN is `parameter/<name>`, whether or not
its name starts with a slash. A type whose botocore ARN shape (`<Type>Arn`) takes one more path segment past its
format, which ends in a name, has an id its service mints after the name (`mintedIds`): EventBridge's connections
and API destinations, whose stored ARN the reader asks the service for, or else checks with that id open. A type
whose ARN is all an id its service mints is named by the members its service finds it by (`find`): those botocore
documents as its key ("Scalable targets are uniquely identified by the combination of resource ID, scalable
dimension, and namespace"), or else a name of it where no request of the namespace gives the id (a Redshift
Serverless namespace by its `namespaceName`), and the reader asks the service for the ARN they find, or else checks
the ARN with its id open, as for a create. An id of a nested record also finds the containing resource and the
resource ids held beside it: a NetworkInterface's Attachment holds AttachmentId beside InstanceId. Each selected
reference type is found by that id, with the same typed caller fallback when no stored record exists. A member
naming a resource the reference gives no type, whose botocore
record holds it beside members named after types the action acts on, names those through it (`through`): a Data
API session's `SessionData` holds its `SessionId` beside the `ClusterIdentifier` or `WorkgroupName` it runs on, and
the reader asks the service for the ARN of the caller's resource at the other end, or else names none. A member
whose botocore shape takes an alias as well as the type's ARN, named for the type with a word before it
(`IncomingKeyIdentifier`), names a resource of the type, which its service resolves. A type named by its variables takes one no member fills from the ARN of another type the action acts
on whose format is that variable alone, given by the one top-level ARN member named after that type: a permission
set an IAM Identity Center create makes has the id of the instance its `InstanceArn` names.

The Service Reference is live rather than a pinned dependency, so a fresh generation is not
byte-identical. The two gates split along the sources:

- `--check` is offline and needs the pinned botocore. The file must be well-formed, and every
  operation's resources must be exactly what the rules give from botocore for the resource types and
  ARN formats the file records, so a hand-edited entry fails.
- `--verify` is online and stdlib-only. Every operation must still be checked as the action, on the
  resource types, the file records, and every type must still have its ARN format; every forwarded
  action must still act on the type, of the format and with the `ViaService` key, the file records,
  and still be missing from what the reference lists for its operations. An operation AWS
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

import fetch_apigateway_arn_formats
import fetch_athena_glue_ddl_permissions
import fetch_dynamodb_permissions
import fetch_iam_identifiers
import fetch_kms_api_permissions
import fetch_required_resource_types
from regen_iam_authorized_actions import (ROUTES as ROUTES_FILE, authorized_actions, fetch_references,
                                          load_s3_required, s3_query_parameters, s3_version_choices,
                                          vendored_sections)
from regen_iam_passed_roles import (
    NAMESPACE_RE,
    OPERATION_RE,
    _shown,
    load_models,
    namespace_of,
)
from regen_partitions import load_model as load_json_file
from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir
from regen_rest_routes import MODELS as REST_MODELS

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-request-resources.json"

# The namespaces read, in batches: each moves here from NOT_COVERED with the tests that show its ARNs
# are the ones its handlers mint.
NAMESPACES = ("access-analyzer", "account", "acm", "application-autoscaling", "aps", "athena", "bcm-data-exports",
              "bcm-pricing-calculator", "budgets", "ce", "cloudfront", "cloudfront-keyvaluestore", "cloudtrail",
              "codebuild", "codepipeline", "cognito-identity", "cognito-idp", "controlcatalog", "controltower", "cur",
              "datasync", "ec2", "ecr", "ecs", "elasticloadbalancing", "elasticmapreduce", "emr-containers",
              "emr-serverless", "es", "events",
              "firehose", "fis", "globalaccelerator", "inspector2", "kinesisanalytics", "macie2", "oam",
              "payment-cryptography", "quicksight", "ram", "rds", "redshift", "redshift-data", "redshift-serverless",
              "rekognition", "resource-explorer-2", "route53", "s3tables", "s3vectors", "servicediscovery",
              "servicequotas", "ssm", "sso", "states", "textract", "timestream-influxdb", "translate", "signer", "xray",
              "securityhub", "guardduty", "secretsmanager", "scheduler", "lambda", "cloudformation", "glue",
              "sqs", "sns", "kinesis", "pipes", "dsql", "sts", "eks", "resource-groups", "logs", "cloudwatch",
              "comprehend", "config", "dms", "elasticache", "identitystore", "memorydb", "route53resolver",
              "sagemaker", "transcribe", "ec2-instance-connect", "s3", "bedrock", "apigateway", "kms", "dynamodb",
              "iam")

# ---------------------------------------------------------------------------------------------------------------------
# MEASURED: what AWS was measured to check where neither botocore nor the Service Reference says it.
#
# This is the only place in the generator that states an operation's resources by hand, and it is closed to
# everything but measured AWS behaviour. An entry is admitted only when ALL of these hold:
#
#   1. From AWS. Its evidence is AWS itself: a policy probe run against real AWS with real credentials, published
#      with its cases and results in a Floci pull request, an AWS response recorded verbatim (an AccessDenied
#      message naming the resource AWS checked), or AWS's documentation (a user guide, an API reference) saying it
#      in so many words, the sentence quoted. An analogy with another operation, a reading of the request's shape
#      and what a Floci handler does are NOT evidence: a fact one of those gives belongs in a rule, which reads it
#      from botocore or the Service Reference for every operation.
#   2. Absent from the metadata. The Service Reference lists none of the entry's resource types for the
#      operation's action, so no rule can read them; an entry for a type the reference lists is refused.
#   3. Needed. The rules do not already give the entry's resources; an entry they give is refused, so one a later
#      rule, a botocore bump or a reference update makes redundant is taken out rather than kept.
#   4. Cited. Its evidence names where the measurement is, with at least one https:// link to it.
#
# An entry may instead name another action AWS checks the caller for, as the request the operation's service makes
# as the caller (`{"action", "of"}`), on what botocore's record of the operation's type `of` holds in the member
# named after the action's namespace and the variable of the type the action acts on; everything else is read: the
# type and ARN format the reference gives the action, that member, the operation's endpoint prefix, and the
# namespace's `ViaService` key where the reference lists it for the action. Rules 1 and 4 hold for it as for any
# entry; for 2, the reference does not list the action among the operation's authorized actions, and for 3, the
# action acts on one resource type with an ARN format, `of` is a type the operation's action acts on, and botocore's
# record of it has the member (`check_forwarded`, `forwarded_actions`, `forwarded_for`).
#
# The generator enforces 2 to 4 (`measured_resources`); review enforces 1. Never add an entry to make a check pass,
# to keep Floci's existing behaviour, or because a handler acts on a resource: find what AWS does first, or leave the
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
_SNAPSHOT_KEY_EVIDENCE = (
    "the Redshift Management Guide, 'Sharing a snapshot' "
    "(https://docs.aws.amazon.com/redshift/latest/mgmt/working-with-snapshot-share-snapshot.html): 'When you provide "
    "access to an encrypted snapshot, Redshift requires that the AWS KMS customer managed key used to create the "
    "snapshot is shared with the account or accounts performing the restore. If the key isn't shared, attempting to "
    "restore the snapshot results in an access-denied error', the key policy naming the restoring account and "
    "allowing 'the kms:Decrypt action'; and 'Authorizing use of an AWS KMS customer managed key with Amazon Redshift "
    "(Provisioned) and Amazon Redshift Serverless' "
    "(https://docs.aws.amazon.com/redshift/latest/mgmt/authorizing-kms-customer-managed-key.html): 'To create and use "
    "an Amazon Redshift provisioned cluster or an Amazon Redshift Serverless resource that is encrypted with a "
    "customer managed key, a user must have permission to call the following operations on the customer managed key', "
    "kms:Decrypt among them; the Service Reference lists no KMS action for either restore")
MEASURED: dict[str, tuple[list[dict], str]] = {
    "dsql:ListClusters": (
        [{"type": "Cluster", "fill": {}}],
        "AWS's AccessDenied message recorded verbatim in https://github.com/floci-io/floci/pull/6328 (Aurora DSQL, "
        "eu-west-1, 2026-10-23): a session policy denying dsql:CreateCluster and dsql:ListClusters refused both "
        "'... on resource: arn:aws:dsql:eu-west-1:<account>:cluster/* with an explicit deny in a session policy'; the "
        "Service Reference lists no resource type for ListClusters"),
    "eks:CreateCluster": (
        [{"type": "cluster", "fill": {"ClusterName": "name"}}],
        "the Amazon EKS User Guide, 'Amazon EKS identity-based policy examples', 'Create a Kubernetes cluster on the "
        "AWS Cloud' (https://docs.aws.amazon.com/eks/latest/userguide/security-iam-id-based-policy-examples.html): "
        "'This example policy includes the minimum permissions required to create an Amazon EKS cluster named "
        "my-cluster in the us-west-2 AWS Region', its statement allowing 'eks:CreateCluster' on "
        "'arn:aws:eks:us-west-2:111122223333:cluster/my-cluster', and 'If you see a warning that says The actions in "
        "your policy do not support resource-level permissions and require you to choose All resources in the AWS "
        "Management Console, it can be safely ignored'; the Service Reference lists no resource type for the action"),
    "dynamodb:ListTables": (
        [{"type": "table", "fill": {}}],
        "real AWS probes in https://github.com/floci-io/floci/pull/7336 (27 policy cases with real AWS STS role "
        "sessions) accept an allow and enforce a deny on arn:<partition>:dynamodb:<region>:<account>:table/*, the "
        "literal table/* (table/? matches it, table/ does not), whatever TableName or ExclusiveStartTableName the "
        "request gives; AWS's recorded denial names 'on resource: arn:aws:dynamodb:<region>:<acct>:table/*' "
        "(https://github.com/aws/aws-emulator/blob/main/issues/things-that-do-not-work/"
        "20261024T015148Z-dynamodb-does-not-enforce-iam-listtables-succeeds-for.md); the Service Reference lists no "
        "resource type for the action"),
    "es:ListDomainNames": (
        [{"type": "domain", "fill": {}}],
        "real AWS probes in https://github.com/floci-io/floci/issues/9039 (OpenSearch, us-east-1, 2026-11-19, an "
        "assumed role per case): with no permissions the call is refused '... is not authorized to perform: "
        "es:ListDomainNames on resource: arn:aws:es:us-east-1:<account>:domain/* because no identity-based policy "
        "allows the es:ListDomainNames action', and a role whose one statement allows the action on domain/* alone "
        "is allowed it; the Service Reference lists no resource type for the action"),
    "s3tables:ListTableBuckets": (
        [{"type": "TableBucket", "fill": {}}],
        "real AWS probes in https://github.com/floci-io/floci/issues/9039 (S3 Tables, us-east-1, 2026-11-19, an "
        "assumed role per case): with no permissions the call is refused '... is not authorized to perform: "
        "s3tables:ListTableBuckets on resource: arn:aws:s3tables:us-east-1:<account>:bucket/* because no "
        "identity-based policy allows the s3tables:ListTableBuckets action', and a role whose one statement allows "
        "the action on bucket/* alone is allowed it; the Service Reference lists no resource type for the action"),
    "eks:ListClusters": (
        [{"type": "cluster", "fill": {}}],
        "real AWS probes in https://github.com/floci-io/floci/issues/9039 (Amazon EKS, us-east-1, 2026-11-19, an "
        "assumed role per case): with no permissions the call is refused '... is not authorized to perform: "
        "eks:ListClusters on resource: arn:aws:eks:us-east-1:<account>:cluster/* because no identity-based policy "
        "allows the eks:ListClusters action', and a role whose one statement allows the action on cluster/* alone "
        "is allowed it; the Service Reference lists no resource type for the action"),
    **{f"states:{operation}": ([{"type": kind, "fill": {}, "find": ["taskToken"]} for kind in ("activity", "statemachine")],
                               _SEND_TASK_EVIDENCE)
       for operation in ("SendTaskSuccess", "SendTaskFailure", "SendTaskHeartbeat")},
    **{operation: ([{"action": "kms:Decrypt", "of": "snapshot"}], _SNAPSHOT_KEY_EVIDENCE)
       for operation in ("redshift:RestoreFromClusterSnapshot", "redshift-serverless:RestoreFromSnapshot")},
    "iam:UpdateDelegationRequest": (
        [{"type": "delegation-request", "fill": {"DelegationRequestId": "DelegationRequestId"}}],
        "the IAM API reference, 'UpdateDelegationRequest' "
        "(https://docs.aws.amazon.com/IAM/latest/APIReference/API_UpdateDelegationRequest.html): 'Once a delegation "
        "request has an owner, that owner gets a default permission to update the delegation request', its "
        "DelegationRequestId being 'The unique identifier of the delegation request to update'; and the IAM User "
        "Guide, 'IAM temporary delegation' "
        "(https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies-temporary-delegation.html): its permissions "
        "table lists 'iam:UpdateDelegationRequest Forward a delegation request to an administrator for approval', and "
        "'By default, IAM principals who initiate a delegation request are automatically granted permissions to manage "
        "that specific request. They can associate it with their account, view request details, reject a request, "
        "forward it to an administrator for approval, release the exchange token to the product provider after admin "
        "approval, and list delegation requests that they own'; the Service Reference lists the other delegation "
        "request actions on delegation-request and not this one"),
}

# ---------------------------------------------------------------------------------------------------------------------
# STATEMENT_SOURCES: the statements an operation's request carries that its service runs against another service as
# the caller, and the vendored page each one's permissions come from.
#
# This is one of the file's sources besides botocore and the Service Reference (`PATH_SOURCES`,
# `PERMISSION_SOURCES` and `REQUIRED_TYPE_SOURCE` are the others),
# granted for Athena's DDL statements alone: it is not a place for any other table. Its file is written by its own
# script from AWS's documentation, unchanged
# (tools/aws/fetch_athena_glue_ddl_permissions.py, with a prompt beside the file for rebuilding it when the page's
# layout changes), and the generator only reads it (`statement_requests`): nothing about a statement is stated here.
# ---------------------------------------------------------------------------------------------------------------------
STATEMENT_SOURCES: dict[str, Path] = {
    "athena:StartQueryExecution": REPO_ROOT / "tools/aws/sources/athena-glue-ddl-permissions.json",
}
# What a row's sentence says when its policy names partition actions a table without partitions does not need.
UNPARTITIONED_RE = re.compile(r"does not have partitions, do not include partition actions", re.I)

# ---------------------------------------------------------------------------------------------------------------------
# PATH_SOURCES: the namespaces whose management calls AWS authorizes as the HTTP method of the request, on the resource
# its path names, and the vendored pages that say so.
#
# Granted for API Gateway alone, whose developer guide says it ("The API-managing Action expression has the format
# apigateway:action, where action is one of the following API Gateway actions: GET, POST, PUT, DELETE, PATCH", and
# "API Gateway resources have the following ARN format: arn:aws:apigateway:region::resource-path-specifier") and lists
# each resource's ARN in its ARN reference. Its file is written by its own script from those pages, unchanged
# (tools/aws/fetch_apigateway_arn_formats.py), and the generator only reads it (`routed_resources`): nothing about a
# route is stated here.
# ---------------------------------------------------------------------------------------------------------------------
PATH_SOURCES: dict[str, Path] = {
    "apigateway": REPO_ROOT / "tools/aws/sources/apigateway-arn-formats.json",
}

# ---------------------------------------------------------------------------------------------------------------------
# PERMISSION_SOURCES: the namespaces whose documentation says which resources an operation's actions are each checked
# on where a request names several, and where the service reads a resource the request leaves out, and the vendored
# pages that say so.
#
# Granted for KMS and DynamoDB. KMS's developer guide's permissions table says it ("kms:ReEncryptFrom on the KMS key
# used to decrypt", "kms:ReEncryptTo on the KMS key used to encrypt"; UpdateAlias "on three resources: The alias, The
# currently associated KMS key, The newly associated KMS key"), its API reference says Decrypt's key may come from its
# ciphertext ("AWS KMS can get the KMS key from metadata that it adds to the symmetric ciphertext blob"), and its key
# identifiers give an alias name as the alias ARN's resource ("alias/<alias-name>");
# tools/aws/fetch_kms_api_permissions.py vendors those pages and `permission_resources`, `with_from_metadata` and
# `named_resources` read them. DynamoDB's
# developer guide gives the action each PartiQL statement's verb and each transaction item's kind is checked as ("To
# read items using PartiQL for DynamoDB, you must have dynamodb:PartiQLSelect permission on the table or index";
# "Permissions for Put, Update, Delete, and Get actions are governed by the permissions used for the underlying
# PutItem, UpdateItem, DeleteItem, and GetItem operations"); tools/aws/fetch_dynamodb_permissions.py vendors those
# pages and `item_resources` and `statement_resources` read them. And granted for IAM: its identifiers give each
# type's ARN with a name and its path ("arn:aws:iam::account:user/user-name-with-path"; "If you use the IAM API or AWS
# Command Line Interface (AWS CLI) to create IAM resources, you can add an optional path") and its API reference what
# an operation acts on where its request does not say ("If you do not specify a user name, IAM determines the user name
# implicitly based on the AWS access key ID used to sign the request to this operation"; "must have permission to
# change the role group with the old name and the new name"); tools/aws/fetch_iam_identifiers.py vendors those pages
# and `with_paths`, `with_renames`, `with_signing_user`, `with_same_as_arn` and `with_formats` read them. Each file is
# written by its own script from the pages, unchanged, and the generator only reads it: nothing about an operation is
# stated here.
# ---------------------------------------------------------------------------------------------------------------------
PERMISSION_SOURCES: dict[str, Path] = {
    "kms": REPO_ROOT / "tools/aws/sources/kms-api-permissions.json",
    "dynamodb": REPO_ROOT / "tools/aws/sources/dynamodb-permissions.json",
    "iam": REPO_ROOT / "tools/aws/sources/iam-identifiers.json",
}
# The script that writes each namespace's permission source, whose pages `verify_permission_sources` reads it against.
PERMISSION_FETCHERS = {
    "kms": fetch_kms_api_permissions,
    "dynamodb": fetch_dynamodb_permissions,
    "iam": fetch_iam_identifiers,
}

# ---------------------------------------------------------------------------------------------------------------------
# REQUIRED_TYPE_SOURCE: which resource types each action of a granted namespace always acts on, as its page of the
# Service Authorization Reference marks them ("Resource types (*required)": DeleteGroupMembership's `Group*`,
# `GroupMembership*`, `Identitystore*` and `User*`), which neither botocore nor the Service Reference says.
#
# Granted for Identity Store alone (tools/aws/fetch_required_resource_types.py's `PAGES`). Its file is written by its
# own script from those pages, unchanged, and the generator only reads it (`required_types`): a required type no
# member names is one the service finds from what it holds, through the record of the resource the request names
# (`with_record_through`) or by the members a lookup names it by (`with_lookup_find`). Nothing about an action is
# stated here.
# ---------------------------------------------------------------------------------------------------------------------
REQUIRED_TYPE_SOURCE = fetch_required_resource_types.OUTPUT
# A resource a permissions row's list names, after the action it is checked as when it names one: "kms:ReEncryptFrom
# on the KMS key used to decrypt", "The alias (in an IAM policy)"; an action checked on no resource names none ("in an
# IAM policy in the replica Region").
PERMISSION_ITEM_RE = re.compile(r"^(?:(?P<action>[a-z0-9-]+:[A-Za-z0-9]+)\s+)?(?P<rest>.*)$")
NAMED_RESOURCE_RE = re.compile(r"^(?:on\s+)?the\s+(?P<phrase>.+?)(?:\s*\([^)]*\))?\s*$", re.I)
# What a resource is used for, as the row says ("the KMS key used to decrypt") and botocore's member documents it ("the
# KMS key that KMS will use to decrypt the ciphertext").
USED_TO_RE = re.compile(r"\buse(?:d)? to (?P<verb>[a-z]+)\b")
# The member a resource left out is read from, as a parameter's sentence names it ("from metadata that it adds to the
# symmetric ciphertext blob").
FROM_METADATA_RE = re.compile(r"\bcan get the (?P<thing>[A-Za-z ]+?) from metadata that it adds to the "
                              r"(?P<phrase>[A-Za-z ]+?)\.?$")
# What the route of an operation is checked as, where it is not the operation's own name: the authorized-actions file
# `regen_iam_authorized_actions.py` writes, whose `authorizedByMethod` and `authorizedByModel` IamAuthorizedActions
# reads.
AUTHORIZED_ACTIONS = REPO_ROOT / "src/main/resources/aws/iam-authorized-actions.json"
# The ARN format the guide gives a namespace's resources, the path naming one after its empty account.
RESOURCE_PATH_FORMAT_RE = re.compile(r"arn:aws:(?P<namespace>[a-z0-9-]+):\{region}::\{resource-path-specifier}")
# A label of a route's path, `{restapi_id}`, or one taking the rest of it, `{resource_arn+}`.
ROUTE_LABEL_RE = re.compile(r"\{(?P<label>[^{}+]+)\+?}")
# A segment of an ARN format's resource path that is one variable and nothing else.
ONE_VARIABLE_SEGMENT_RE = re.compile(r"\$\{(?P<variable>\w+)}")
# The guide's variables for the partition, Region and account, which the Service Reference writes as its own.
GUIDE_VARIABLES = {"partition": "Partition", "region": "Region", "account-id": "Account"}

_LATER ="not read yet: a later batch, with the tests that show its ARNs are the ones its handlers mint"
_REST = ("REST: its requests name their resources in the path, which RequestResources reads from the route a request "
         "matched in the vendored REST routes (regen_rest_routes.MODELS), which do not have its model yet")

# Namespaces Floci serves whose resources are not read yet, and why.
NOT_COVERED: dict[str, str] = {
    "airflow": _REST,
    "app-integrations": _REST,
    "appconfig": _REST,
    "appsync": _REST,
    "autoscaling": "not read yet: AWS's group and launch configuration ARNs carry an id the service mints, "
                   "`autoScalingGroup:<id>:autoScalingGroupName/<name>`, while Floci mints `autoScalingGroup:<name>`, "
                   "which other services (ECS capacity providers) read back; the mint is fixed first",
    "aws-marketplace": _LATER,
    "backup": _REST,
    "batch": _REST,
    "bedrock-agentcore": _REST,
    "cloudhsm": "not read yet: its ARN formats end in ${CloudHsmClusterInstanceName} and "
                "${CloudHsmBackupInstanceName}, names, while its requests give ClusterId and BackupId, ids, which the "
                "rules do not fill a name from, so nearly every operation's resource would be left open",
    "codeartifact": _REST,
    "codeguru-reviewer": _REST,
    "codedeploy": "not read yet: the Service Reference gives an on-premises instance's ARN as "
                  "`instance:${InstanceName}`, while the CodeDeploy permissions reference gives "
                  "arn:aws:codedeploy:region:account-id:instance/instance-ID and AWS's GetOnPremisesInstance example "
                  "answers instance/<name>_<id>, an id the service mints after the name; reading it needs that format "
                  "corrected",
    "connect": _REST,
    "detective": _REST,
    "dlm": _REST,
    "elasticbeanstalk": "not read yet: an environment's ARN carries its application's name, "
                        "`environment/<application>/<name>`, while most of its requests name it by EnvironmentName "
                        "or EnvironmentId alone, so reading it needs the stored environment those find",
    "elasticfilesystem": _REST,
    "execute-api": _REST,
    "iot": _LATER,
    "kafka": _REST,
    "lightsail": _LATER,
    "mq": _REST,
    "network-firewall": _LATER,
    "organizations": _LATER,
    "rds-data": _REST,
    "rum": _REST,
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
    "transfer": _LATER,
    "verifiedpermissions": "not read yet: a policyStoreId may name its store by an alias, `policy-store-alias/<name>`, "
                           "which AWS authorizes as the action on the store the alias points to and "
                           "GetPolicyStoreAlias on the alias, and an aliasName carries the `policy-store-alias/` its "
                           "format already gives; reading it needs both",
    "wafv2": _LATER,
}


# The variables of an ARN format the account, partition and Region fill.
KNOWN_VARIABLES = {"Partition", "Region", "Account"}

# Floci's coverage of id-list namespaces: the types of each it reads by id, which ResourceArnBuilder and the condition
# resolver resolve with their stored tags. It states no AWS fact: the model supplies each list member and its wire
# name, the Service Reference the action's types and ARN formats, and botocore's documentation and examples the id
# prefix of each type (`id_prefixes`), supplemented by cited API examples when botocore has none. Other ids keep
# a wildcard entry until their coverage grows; every request
# without those lists is read by the ordinary rules.
ID_TARGETS = {"ec2": ("instance", "network-acl", "network-interface", "route-table", "security-group", "vpc-endpoint")}
# An ARN format's account given as `${AccountId}`, the account `${Account}` gives in every other format.
ACCOUNT_ID_SLOT_RE = re.compile(r"^(arn:[^:]*:[^:]*:[^:]*:)\$\{AccountId}:")
VARIABLE_RE = re.compile(r"\$\{(\w+)}")
# A resource-tag condition key the reference gives a type, named after the tag key it compares, which it writes as the
# variable `${TagKey}` or as `tag-key`, as the IAM User Guide writes `aws:ResourceTag/tag-key` ("There is one context
# key for each tag key-value pair"): Secrets Manager's `secretsmanager:ResourceTag/tag-key`. SSM spells the word
# `resourceTag`; condition key names are not case-sensitive.
RESOURCE_TAG_KEY_RE = re.compile(r"(?P<prefix>[a-z0-9-]+:(?i:ResourceTag)/)(?:\$\{TagKey}|tag-key)")
# A type's id with the example botocore documents it by: "the instance ID (for example, i-0123456789abcdef0)".
ID_EXAMPLE_RE = re.compile(r"\b([a-z][a-z ]*?) ID \(for example, ([a-z0-9]+-)[0-9a-f]{8,}\)")
# An id one of botocore's request or response examples gives a member: "GroupId": "sg-903004f8".
EXAMPLE_ID_RE = re.compile(r"^([a-z0-9]+-)[0-9a-f]{8,17}$")
# A sentence of botocore's documentation stating the members that key a type: "<things> are uniquely identified by the
# combination of <parts>".
UNIQUE_KEY_RE = re.compile(r"(?P<things>[A-Za-z][A-Za-z ]*?) (?:are|is) uniquely identified by (?:the )?combination of "
                           r"(?P<parts>[A-Za-z ,]+?)(?:,? which\b|\.|$)")
# One more `:${...}` past a format: what a type of the reference qualifies another's ARN by.
QUALIFIER_RE = re.compile(r":\$\{\w+}")
# A member documented as naming a version or an alias, the words a type's qualified types add to its name, and what
# follows: Lambda's `Qualifier`, "Specify a version or alias to invoke a published version of the function", "The
# version number or alias name", "The alias name", "The function version to filter executions by".
DOC_QUALIFIER = (r"^(?:specify )?(?:a |an |the )?(?:[a-z]+ )?(?P<first>{words})(?: number| name)?"
                 r"(?: or (?:a |an |the )?(?P<second>{words})(?: number| name)?)?(?P<rest>(?: .*)?)$")
# An operation documented as acting on a thing of its service's own inside a resource of a type: "Sends a successful
# completion response for a callback operation in a durable execution".
DOC_THING_IN_RE = re.compile(r"\bfor an? (?P<thing>[a-z][a-z -]*?) in an? (?P<phrase>[a-z][a-z -]*?)(?:[.,;]|$)")
# A string pattern that takes an alias, `alias/...`, as one of its alternatives.
ALIAS_PATTERN_RE = re.compile(r"(?:^|[|(])\^?alias/")
# A format's resource that is a word and one variable (`connection/${ConnectionName}`), and the two path segments of
# its own an ARN pattern takes past that word when its service mints an id after the name (`\\/[...]+\\/[...]+$`).
MINTED_WORD_RE = re.compile(r"^([A-Za-z][A-Za-z0-9-]*)/\$\{\w+}$")
MINTED_TAIL = r"\\/(?:\[[^\]]*\][+*]|\([^()]*\)[+*]?)\\/(?:\[[^\]]*\][+*]|\([^()]*\)[+*]?)\$?$"
# A create's member documented as the name its service follows with characters it mints: "Secrets Manager automatically
# adds a hyphen and six random characters after the secret name at the end of the ARN".
DOC_MINTED_SUFFIX_RE = re.compile(r"\badds an? (?P<separator>hyphen|dash|underscore|period) and (?P<count>\w+) random "
                                  r"characters after the (?P<word>[a-z][a-z -]*?) name at the end of the arn\b")
SEPARATORS = {"hyphen": "-", "dash": "-", "underscore": "_", "period": "."}
COUNTS = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10}
STEM_SUFFIX_RE = re.compile(r"(arns|arn|names|name|identifiers|identifier|ids|id|config|input|list|s)$")
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
# parameter of its query, `?name`. A label may hold a hyphen, as API Gateway v2's `/v2/tags/{resource-arn}` does.
LOCATED_RE = re.compile(r"^(\{[A-Za-z0-9_-]+\}|\?[A-Za-z0-9_.-]+)$")


def stem(name: str) -> str:
    """The name lowercased, without what is not a letter or digit, and without a trailing `Arn`,
    `Name`, `Id`, `Identifier`, `Config`, `Input`, `List` or plural, twice over (`TargetGroupArns` is `targetgroup`,
    `DistributionConfig` is `distribution`, Glue's `DatabaseInput` is `database` and `ConnectionNameList`
    `connection`)."""
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
def action_mapping(documents: list[dict]) -> dict[str, dict[str, str]]:
    """The action each operation is checked as where it is not its own name, as the authorized-actions file gives it
    (`regen_iam_authorized_actions.authorized_actions`), an operation the S3 user guide gives a version choice taking
    its action without a version, whose types are the same, and one several clients share its namesake client's
    (`namesake_by_model`, and the S3 user guide's for S3's requests)."""
    page = load_s3_required()
    models = json.loads(ROUTES_FILE.read_text(encoding="utf-8"))["models"] if ROUTES_FILE.exists() else {}
    query = s3_query_parameters() if any(document.get("Name") == "s3" for document in documents) else None
    mapped = authorized_actions(documents, page, models, query)
    sections = vendored_sections(documents, page, models, query, mapped)
    for namespace, operations in sections["versioned"].items():
        for operation, choice in operations.items():
            mapped.setdefault(namespace, {}).setdefault(operation, choice["without"])
    # One whose request names the version in its body takes the same types (DeleteObjects, s3:DeleteObject's), though
    # no query tells which action a request needs.
    if query is not None:
        types = {action["Name"]: sorted(resource["Name"] for resource in action.get("Resources") or [])
                 for document in documents if document.get("Name") == "s3" for action in document.get("Actions", [])}
        for operation, choice in s3_version_choices(page).items():
            without, with_ = (choice[side].split(":", 1)[1] for side in ("without", "with"))
            if types.get(without) != types.get(with_):
                raise ValueError(f"s3:{operation}: the S3 user guide's {choice['without']} and {choice['with']} act on "
                                 f"different types, so neither's stand for the other's")
            mapped.setdefault("s3", {}).setdefault(operation, choice["without"])
    for namespace, clients in sections["authorizedByModel"].items():
        for operation, action in clients.get(namespace, {}).items():
            mapped.setdefault(namespace, {}).setdefault(operation, action)
    return mapped


def checked_actions(documents: list[dict]) -> dict[str, dict]:
    """Each operation of each namespace, as `<namespace>:<Operation>`, with the action Floci checks it
    as, that action's resource types (null when the namespace has no action of that name) and the
    operation's SDK clients."""
    mapped = action_mapping(documents)
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


def resource_tag_keys(documents: list[dict], formats: dict[str, dict[str, str]]) -> dict[str, dict[str, list[str]]]:
    """Stored-tag key prefixes for every resource type of the namespaces read that has an ARN format (`formats`), as
    the Service Reference gives each type's resource-tag condition keys: one key per tag the resource holds, named
    after its tag key (`RESOURCE_TAG_KEY_RE`). A key naming one tag key of its own is not a prefix."""
    result = {}
    for document in documents:
        namespace = document["Name"]
        if namespace not in formats:
            continue
        types = {}
        for resource in document.get("Resources", []):
            kind = resource["Name"]
            if kind not in formats[namespace]:
                continue
            keys = resource.get("ConditionKeys", [])
            prefixes = {match.group("prefix") for match in map(RESOURCE_TAG_KEY_RE.fullmatch, keys) if match}
            # RDS spells its resource-specific keys as the namespace and resource type, e.g. rds:pg-tag/${TagKey}.
            typed_prefix = f"{namespace}:{kind}-tag/"
            if typed_prefix + "${TagKey}" in keys or typed_prefix + "tag-key" in keys:
                prefixes.add(typed_prefix)
            types[kind] = sorted(prefixes)
        types = {kind: keys for kind, keys in types.items() if keys}
        if types:
            result[namespace] = dict(sorted(types.items()))
    return dict(sorted(result.items()))


SCALAR_SHAPES = ("string", "boolean", "integer", "long")


def resource_keys(documents: list[dict], data: Path, namespaces: dict[str, list[str]]) -> dict[str, dict[str, dict]]:
    """The condition keys the Service Reference gives each resource type of the id-target namespaces that name a
    member of the type's botocore shape (`network-interface` is `NetworkInterface`): a key named as a member, or as
    the member without its `Id` (`ec2:Vpc` its `VpcId`, `ec2:NetworkInterfaceID` its `NetworkInterfaceId`), holding a
    scalar. Each is `{"member": <member>}`, with `"arn": <type>` where the reference types the key as an ARN and the
    namespace has a type named as the key (`ec2:Vpc` takes a `vpc` ARN); an ARN key with no such type is left out, as
    are keys with a variable and keys no member names. The type's service holds the member's value for the resource
    a request names, which is the key's value."""
    result = {}
    for document in documents:
        namespace = document["Name"]
        if namespace not in ID_TARGETS or namespace not in namespaces:
            continue
        key_types = {key["Name"]: key.get("Types", []) for key in document.get("ConditionKeys", [])}
        shapes = {}
        for model in load_models(data, set(namespaces[namespace])).values():
            shapes.update(model["shapes"])
        types = {resource["Name"] for resource in document.get("Resources", []) if resource.get("ARNFormats")}
        by_shape_name = {_shape_name(kind).lower(): kind for kind in types}
        found = {}
        for resource in document.get("Resources", []):
            shape = shapes.get(_shape_name(resource["Name"]))
            if not shape or shape.get("type") != "structure":
                continue
            members = {name.lower(): (name, spec["shape"]) for name, spec in shape.get("members", {}).items()}
            keys = {}
            for key in resource.get("ConditionKeys", []):
                if not key.startswith(namespace + ":") or "${" in key:
                    continue
                named = key.split(":", 1)[1].lower()
                member = members.get(named) or members.get(named + "id")
                if member is None or shapes.get(member[1], {}).get("type") not in SCALAR_SHAPES:
                    continue
                spec = {"member": member[0]}
                if "ARN" in key_types.get(key, []):
                    if named not in by_shape_name:
                        continue
                    spec["arn"] = by_shape_name[named]
                keys[key] = spec
            if keys:
                found[resource["Name"]] = dict(sorted(keys.items()))
        if found:
            result[namespace] = dict(sorted(found.items()))
    return dict(sorted(result.items()))


def _shape_name(kind: str) -> str:
    """The botocore shape a resource type of the reference names: `network-interface` is `NetworkInterface`."""
    return "".join(part[:1].upper() + part[1:] for part in kind.split("-"))


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
    none: Resource Explorer's `Search` with no `ViewArn` searches with the Region's default view. A format ending in a
    wildcard (DMS's `cert:*`) names every resource of its type, not one, so a request leaving the member out names
    none of them."""
    named = {resource["type"] for resource in resources if "type" in resource}
    added = []
    for resource in resources:
        member = resource.get("arn")
        if "types" not in resource or member not in optional:
            continue
        for kind in resource["types"]:
            variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
            if kind in formats and "*" in VARIABLE_RE.sub("", formats[kind]):
                continue
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
            "belonging", "being", "the", "a", "an", "is", "are", "and", "or", "by", "used", "will", "should", "can", ""}
# "The name of the pipeline", "Specifies the name or the CloudTrail ARN of the trail", "The ID of the delivery", "The URL of
# the queue".
DOC_NAMES_RE = re.compile(r"^(?:specifies )?(?:the |a |an )?(?:unique )?(?:name|id|identifier|url)"
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
    match = re.match(r"^(.*?)(?: (?:of|for|identifying) (?:the |a |an )?(.*))?$", rest)
    head, of = match.group(1).strip(), (match.group(2) or None)
    if re.search(r"(?:^|\s)list$", head) and of:
        return f"{head} of {of}", None
    return head, of


def doc_kind(lead: str) -> str | None:
    """Whether the documentation says the member is a name, an id or a URL; None when it says none of them."""
    head, _ = doc_head(lead)
    if re.search(r"\bname(?: or (?:the )?(?:\w+ )?arn)?$", head):
        return "name"
    if re.search(r"\b(?:id|identifier|ids|identifiers)(?: or (?:the )?(?:\w+ )?arn)?$", head):
        return "id"
    if re.search(r"\burl$", head):
        return "url"
    return None


def spelled(kind: str, arn: str) -> str | None:
    """How documentation spells a resource of two variables joined by a colon after its type's word, each variable
    without the type's own words and its kind: `(family:revision)` for a task definition,
    `task-definition/${TaskDefinitionFamilyName}:${TaskDefinitionRevisionNumber}`; None for any other format."""
    match = re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*/\$\{(\w+)}:\$\{(\w+)}", _resource(arn))
    if not match:
        return None
    own = "".join(part.capitalize() for part in kind.split("-"))
    words = [re.sub(r"(?:Name|Number|Id|Identifier)$", "", variable.removeprefix(own)).lower()
             for variable in match.groups()]
    return f"({words[0]}:{words[1]})" if all(words) else None


def doc_names(lead: str, kind: str, formats: dict[str, str]) -> bool:
    """Whether a member's documentation says it names a resource of the type, by the type's name or its format's
    leading resource word, word for word and followed by a word that ends it: "The name of the stream processor you
    want to delete" names a `streamprocessor`, "The ID of the pipeline execution" no `pipeline`. A hyphenated word
    before it qualifies it: "The URL of a dead-letter queue" names a `queue`."""
    word = RESOURCE_PART_RE.match(formats.get(kind, ""))
    targets = {re.sub(r"[^a-z0-9]", "", kind.lower())} | ({re.sub(r"[^a-z0-9]", "", word.group(1).lower())} if word else set())
    for regex in (DOC_NAMES_RE, DOC_LIST_RE):
        match = regex.match(lead)
        if not match:
            continue
        words = re.split(r"[\s,;()]+", match.group("phrase"))
        starts = [0, 1] if len(words) > 1 and "-" in words[0] else [0]
        for start in starts:
            joined = ""
            for index, piece in enumerate(words[start:start + 4], start):
                joined += re.sub(r"[^a-z0-9]", "", piece)
                if joined in targets:
                    return regex is DOC_LIST_RE or re.sub(r"[^a-z0-9]", "", words[index + 1] if index + 1 < len(words) else "") in DOC_STOP
                if not any(target.startswith(joined) for target in targets):
                    break
    return False


def _without_service_name(lead: str, model: dict) -> str:
    """A documentation lead without the model's own service name before the word it qualifies, as botocore's metadata
    spells it: "the url of the amazon sqs queue to delete" reads "the url of the queue to delete"."""
    for name in (model["metadata"].get("serviceFullName"), model["metadata"].get("serviceAbbreviation")):
        if name:
            lead = re.sub(r"\b" + re.escape(name.lower()) + r" (?=[a-z])", "", lead)
    return lead


def documented_members(models: dict[str, dict], operation: str) -> dict[str, dict]:
    """The request's members holding a string or a list of strings, top-level and fields of a top-level structure,
    each with its documentation's lead, without its service's own name (`_without_service_name`), its pattern and
    whether it is nested, by name or `Name.Field`."""
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
                # A list inside a structure (`InstanceSpecification.ExcludeDataVolumeIds`) is no path the vendored
                # file or its reader takes, so it names nothing.
                if strings is None or "." in path and strings:
                    continue
                element = model["shapes"][item_shape["member"]["shape"]] if item_shape["type"] == "list" else item_shape
                found.setdefault(path, {"path": path + strings, "nested": "." in path,
                                        "lead": _without_service_name(_lead(item.get("documentation")
                                                                            or item_shape.get("documentation")), model),
                                        "arnValue": bool(re.search(
                                            r"(?:^|\. )this value is an amazon resource name \(arn\)\.",
                                            _lead_text(item.get("documentation") or item_shape.get("documentation")))),
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


def query_members(models: dict[str, dict], operation: str) -> list[str]:
    """The members a REST request's query string carries, in the order its model gives them."""
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or "http" not in spec:
            continue
        return [name for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items()
                if member.get("location") == "querystring"]
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
      holds one: Redshift's `ResourceName`, 'The Amazon Resource Name (ARN) to which you want to add the tag'; not
      one documented as a name, an id or a spelled form or that ARN ('The family and revision (family:revision) or
      full ARN of the task definition').
    - A type of two variables no member fills is found by its service (`find`) by the one member whose
      documentation spells it as its format does, `(family:revision)` for a task definition, each element of a list.
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
    documented_arns = set()
    for path, fact in sorted(facts.items()):
        if fact["nested"] or fact["path"] in held or not kinds or re.search(r"arn(s|list)?$", path, re.IGNORECASE):
            continue
        if fact["arnValue"]:
            resources.insert(0, {"types": kinds, "arn": fact["path"]})
            documented_arns.add(fact["path"])
            continue
        head, of = doc_head(fact["lead"])
        if re.search(r"(?:arns?|amazon resource names? \(arns?\))$", head) \
                and not re.search(r"(?:\b(?:name|id|identifier)s?|\)) or\b", head) and (
                (of is None and stem(path).startswith("resource"))
                or (of is not None and any(doc_names("the name of the " + part.strip(), kind, formats)
                                           for part in re.split(r",| or ", of) for kind in kinds))):
            resources.insert(0, {"types": kinds, "arn": fact["path"]})
            if " identifying " in fact["lead"]:
                documented_arns.add(fact["path"])
    # A member documented as an ARN cannot also fill a name variable of that same target.
    resources = [resource for resource in resources if not resource.get("fill")
                 or not set(resource["fill"].values()) <= documented_arns]
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
        # An optional id and name documented as mutually exclusive selectors name the same stored resource.
        # Keep the id path, and resolve the name only when that id is absent (EC2 launch templates).
        if not creating and len(variables) == 1 and not unfilled and kind_of(variables[0]) == "id":
            selector = fill[variables[0]]
            optional = optional_members(models, name)
            names = [path for path, fact in facts.items()
                     if path in optional and not fact["nested"] and not fact["path"].endswith("[]")
                     and doc_kind(fact["lead"]) == "name" and doc_names(fact["lead"], kind, formats)
                     and "either" in (_member_documentation(models, name, path) or "").lower()
                     and "but not both" in (_member_documentation(models, name, path) or "").lower()]
            if selector in optional and len(names) == 1:
                resources.append({"type": kind, "fill": {}, "find": names, "when": {selector: [None]}})
        if len(unfilled) != 1:
            continue
        variable = unfilled[0]
        kind_stem = stem(kind)
        candidates = []
        for path, fact in sorted(facts.items()):
            if path in used or re.search(r"arns?$", path, re.IGNORECASE) \
                    or any(resource.get("through") == fact["path"] for resource in resources):
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
                # "Identifier" can mean a name; an explicitly named member cannot fill a generated id.
                if kind_of(field) == "name" and re.search(r"\bidentifiers?$", doc_head(fact["lead"])[0]):
                    said = "name"
                if (compatible(said, variable) or (kind_of(variable) == "identifier" and not creating)) \
                        and not (creating and not kind_of(variable)) \
                        and not (creating and said != kind_of(variable) and kind_of(variable) != "identifier"):
                    candidates.append(("fill", fact["path"]))
                elif len(variables) == 1 and not fact["path"].endswith("[]"):
                    # A create asking for "A name for" its new resource does not select an existing id.
                    if creating and made == stem(kind) and fact["lead"].startswith("a name for "):
                        continue
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
    # A type of two variables no member fills is named by the one member whose documentation spells it as its format
    # does, which its service finds as its handler resolves it, each element of a list: ECS's `taskDefinition`, "The
    # family and revision (family:revision) or full ARN of the task definition to run".
    for kind in kinds:
        form = spelled(kind, formats[kind])
        if form is None or any(resource.get("type") == kind and (resource.get("fill") or "find" in resource)
                               for resource in resources):
            continue
        spelling = [fact["path"] for path, fact in sorted(facts.items()) if not fact["nested"] and form in fact["lead"]]
        if len(spelling) == 1:
            resources = [resource for resource in resources if resource.get("type") != kind]
            resources.append({"type": kind, "fill": {}, "find": spelling})
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
    # A type whose format ends in an id its service mints, which no member fills, in a REST request whose path labels
    # (or, with none, query string members) end in a member naming the type by its name, documented so or holding a
    # botocore shape named so (`TableName`, documented as "The table name."), is found by its service by those
    # members: an S3 Tables table by its table bucket's ARN, its namespace and its name, in
    # `/tables/{tableBucketARN}/{namespace}/{name}`. A member among them holding an ARN holds another type's, which
    # scopes this one; an optional member holding this type's own ARN names it instead when the request gives it
    # (`GetTable`'s `tableArn`).
    located = labels or query_members(models, name)
    held_shapes = member_shapes(models, name)
    for kind in kinds:
        variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
        if creating or len(variables) < 2 or kind_of(variables[-1]) != "id" or not located:
            continue
        if any(resource.get("type") == kind and "find" in resource for resource in resources):
            continue
        named = [member for member in located if member in facts and not facts[member]["nested"] and (
            (doc_kind(facts[member]["lead"]) == "name" and doc_names(facts[member]["lead"], kind, formats))
            or (kind_of(held_shapes.get(member, "")) == "name" and stem(held_shapes[member]) == stem(kind)))]
        if len(named) != 1:
            continue
        scope = located[:located.index(named[0]) + 1]
        own = [resource["arn"] for resource in resources if "arn" in resource and resource["arn"] not in scope
               and kind in resource["types"] and related(stem(resource["arn"]), stem(kind))]
        optional = optional_members(models, name)
        if own and not all(member in optional for member in own):
            continue
        resources = [resource for resource in resources if resource.get("type") != kind
                     and not ("arn" in resource and resource["arn"] in scope)]
        found = {"type": kind, "fill": {}, "find": scope}
        if own:
            found["when"] = {member: [None] for member in own}
        resources.append(found)
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


def alias_members(models: dict[str, dict], operation: str) -> set[str]:
    """The top-level members of the operation's request whose botocore shape takes an alias of a resource as well as
    its ARN (Payment Cryptography's `KeyArnOrKeyAliasType`, `...:(key/...|alias/...)$|^alias/...`): its service
    resolves such a value to the resource the alias points at, so a member named for a type with a word before it
    (`IncomingKeyIdentifier`) names a resource of that type."""
    found: set[str] = set()
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec:
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            if shape.get("type") == "string" and ALIAS_PATTERN_RE.search(shape.get("pattern") or ""):
                found.add(name)
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


def located_members(models: dict[str, dict], operation: str) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    """The members of a REST request that its path or its query carries, holding a string, by name, with where:
    `{Label}` for a label of its path, `?name` for a parameter of its query. The reader lays them over the request's
    body under their names, so a resource reads them as it reads a member of the body.

    A member every client of the operation locates alike is in the first map; one its clients locate apart, or some
    locate and another's read body carries, is in the second, under each client locating it, whose vendored route tells
    a request's client (`locatedApart`: S3's CreateBucket carries its `Bucket` as `{Bucket}`, S3 Control's as
    `{name}`); a request of another client is read from its body. One located apart by a client whose routes are not
    vendored is refused, as no request would say where it is."""
    per_client: dict[str, dict[str, str]] = {}
    in_body: dict[str, set[str]] = {}
    for client, model in sorted(models.items()):
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec:
            continue
        locating = bool(({"rest-json"} | LOCATED_ONLY_PROTOCOLS) & _protocols(model))
        found: dict[str, str] = {}
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            where = _located(member)
            if where is None and _reading(model) == "all" and member.get("location") in BODY_LOCATIONS:
                in_body.setdefault(name, set()).add(client)
            shape = model["shapes"][member["shape"]]
            if not locating or where is None or (_strings(model, shape) != "" and shape["type"] not in ("integer", "long")):
                continue
            found[name] = where
        if locating:
            per_client[client] = found
    shared: dict[str, str] = {}
    apart: dict[str, dict[str, str]] = {}
    for name in sorted({name for found in per_client.values() for name in found}):
        places = {client: found[name] for client, found in per_client.items() if name in found}
        if len(set(places.values())) == 1 and not in_body.get(name):
            shared[name] = next(iter(places.values()))
            continue
        unrouted = sorted(client for client in places if client not in REST_MODELS)
        if unrouted:
            raise ValueError(f"{operation}'s {name} is located apart by {', '.join(sorted(places))}, and "
                             f"{', '.join(unrouted)} has no vendored routes to tell its requests apart")
        for client, where in places.items():
            apart.setdefault(client, {})[name] = where
    return shared, {client: dict(sorted(found.items())) for client, found in sorted(apart.items())}


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
    """The top-level members of the operation's request whose shape is an account id, by name; not one naming the
    account of another record of the service, by that record's shape name (CloudFormation's
    `StackInstanceAccount`, the account of a `StackInstance`, not of the stack set it belongs to)."""
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
        request = model["shapes"][spec["input"]["shape"]].get("members", {})
        for name, member in request.items():
            shape = model["shapes"][member["shape"]]
            # An account id beside an ARN of the same name, Lambda's `SourceAccount` beside its `SourceArn`, is the
            # account of the resource that ARN names, not of one the request acts on.
            if name.endswith("Account") and name.removesuffix("Account") + "Arn" in request:
                continue
            qualifier = re.match(r"^(\w+?)Account(?:Id)?$", name[:1].upper() + name[1:])
            of_another = qualifier is not None \
                and model["shapes"].get(qualifier.group(1), {}).get("type") == "structure"
            if member.get("location") in BODY_LOCATIONS and shape["type"] == "string" and not of_another \
                    and (shape.get("pattern") in ACCOUNT_ID_PATTERNS
                         or (name in account_names and not CREATING_RE.match(operation))):
                found.add(name)
    return sorted(found)


# A pattern that takes an ARN of a type before a name: `(arn:...:event-bus\/)?<name>`, the type's word captured.
NAME_OR_ARN_RE = re.compile(r"^\(arn:[^)]*:([A-Za-z][A-Za-z0-9-]*)\\?/\)\?")
# Documentation saying a member is a name or an ARN of a type: "The short name or full Amazon Resource Name (ARN) of
# the cluster", the type's phrase captured.
DOC_NAME_OR_ARN_RE = re.compile(r"^(?:the |a |an )?(?:short )?name or (?:the )?(?:full )?"
                                r"(?:arn|amazon resource name \(arn\)) ?(?:of |for )?(?:the |a |an )?(?P<phrase>.+)$")
# The same in the other order: "The Amazon Resource Name (ARN) or short name of the cluster".
DOC_ARN_OR_NAME_RE = re.compile(r"^(?:the |a |an )?(?:full )?(?:arn|amazon resource name \(arn\)) or (?:the )?(?:short )?"
                                r"name (?:of|for) (?:the |a |an )?(?P<phrase>.+)$")
# Or naming the type before them, anywhere in the documentation: "This can either be the cluster name or ARN".
DOC_TYPE_NAME_OR_ARN_RE = re.compile(r"\bthe (?P<phrase>[a-z][a-z -]*?) name or (?:the )?(?:full )?"
                                     r"(?:arn|amazon resource name \(arn\))\b")
# Or listing the two, the thing the member names leading its documentation: "The web function to retrieve. You can
# specify either of the following: The function name, for example my-function. The function ARN."
DOC_EITHER_NAME_OR_ARN_RE = re.compile(r"\byou can specify either of the following: (?:the )?[a-z][a-z -]*? name\b"
                                       r".*\bthe [a-z][a-z -]*? arn\b")
DOC_LEAD_THING_RE = re.compile(r"^(?:the |a |an )?(?P<phrase>[a-z][a-z -]*)")


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
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
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


def documented_name_or_arn_members(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The top-level string members of the operation's request whose documentation says it is a name or an ARN of a
    type ("The short name or full Amazon Resource Name (ARN) of the cluster"), by name, each with the resource type
    word of that ARN."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            body = member.get("location") in BODY_LOCATIONS
            word = _documented_name_or_arn(member.get("documentation"), located=not body) \
                if (body or _located(member) is not None) and model["shapes"][member["shape"]]["type"] == "string" \
                else None
            if word:
                found.setdefault(name, word)
    return found


def _documented_name_or_arn(documentation: str | None, located: bool = False) -> str | None:
    """The resource type word of the ARN a member's documentation says it holds instead of a name: `cluster` for "The
    short name or full Amazon Resource Name (ARN) of the cluster that hosts the service", or with its `of` left out
    ("(ARN)the cluster"), the two the other way round ("The Amazon Resource Name (ARN) or short name of the
    cluster"), or the type before them ("This can either be the cluster name or ARN"); for a member a REST request
    carries in its path or query, those, or the two listed after the thing the documentation leads with ("The web
    function to retrieve. You can specify either of the following: The function name ... The function ARN" names a
    `web-function`); None for any other."""
    lead = _lead(documentation)
    if located and DOC_EITHER_NAME_OR_ARN_RE.search(_lead_text(documentation)):
        match = DOC_LEAD_THING_RE.match(lead)
    else:
        match = DOC_NAME_OR_ARN_RE.match(lead) or DOC_ARN_OR_NAME_RE.match(lead) \
            or DOC_TYPE_NAME_OR_ARN_RE.search(_lead_text(documentation))
    if not match:
        return None
    words = []
    for word in match.group("phrase").split():
        if word in DOC_STOP:
            break
        words.append(word)
    return "-".join(words) or None


def _name_or_arn(shape: dict) -> str | None:
    """The resource type word of the ARN a string shape's pattern takes before a name; None for another shape."""
    if shape["type"] != "string":
        return None
    pattern = shape.get("pattern") or ""
    match = NAME_OR_ARN_RE.match(pattern)
    if match:
        return match.group(1)
    alternative = re.fullmatch(r"\(arn:.*:([A-Za-z][A-Za-z0-9-]*)[:/](.+)\)\|(.+)", pattern)
    if alternative and alternative.group(2) == alternative.group(3):
        return alternative.group(1)
    return None


def named_by(word: str, formats: dict[str, str]) -> str | None:
    """The type whose ARN format's resource is the word and one variable (`event-bus/${EventBusName}`)."""
    found = [kind for kind, arn in formats.items()
             if re.fullmatch(r"arn:\$\{Partition}:[^:]*:[^:]*:[^:]*:" + re.escape(word) + r"[:/]\$\{\w+}", arn)]
    return found[0] if len(found) == 1 else None


def with_arn_of(resources: list[dict], formats: dict[str, str], members: dict[str, str],
                documented: dict[str, str] | None = None) -> list[dict]:
    """The resources, each a type named by its variables reading a member that may give an ARN of a type instead of
    its name, as that type's one variable: one whose pattern takes it, or one whose documentation says so of another
    type than the resource's (an ECS service's `cluster`), an ARN of its own type naming it already."""
    found = []
    for resource in resources:
        read = {path.removesuffix("[]") for path in resource.get("fill", {}).values()} | set(resource.get("when", {})) \
            | set(resource.get("unless", {}))
        arn_of = {member: named_by(members[member], formats) for member in sorted(read) if member in members}
        arn_of |= {member: kind for member in sorted(read) if member in (documented or {}) and member not in arn_of
                   for kind in [named_by(documented[member], formats)] if kind != resource.get("type")}
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
            held = [member for member, path in members.items() if path == member and not re.search(r"[.\[]", member)
                    and re.search(r"arn$", member, re.IGNORECASE) and related(stem(member), stem(parent))]
            if len(held) == 1:
                fill[variables[0]] = held[0]
                arn_of[held[0]] = parent
        found.append(dict(resource, fill=dict(sorted(fill.items())), arnOf=arn_of) if fill != resource["fill"]
                     else resource)
    return found


def region_members(models: dict[str, dict], operation: str) -> list[str]:
    """The top-level string members of the operation's request named as a Region (`AuthorizedAwsRegion`), by name."""
    found: set[str] = set()
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        found.update(name for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items()
                     if member.get("location") in BODY_LOCATIONS and re.search(r"region$", name, re.IGNORECASE)
                     and model["shapes"][member["shape"]]["type"] == "string")
    return sorted(found)


def with_account_variables(resources: list[dict], formats: dict[str, str], accounts: list[str],
                           regions: list[str]) -> list[dict]:
    """The resources, each a type named by its variables whose format names an account or a Region of its own beside
    the ARN's (Config's `aggregation-authorization/${AggregatorAccount}/${AggregatorRegion}`), taking that account
    from the request's one account id member and that Region from its one Region member; the ARN's own account then
    stays the caller's (`with_account`)."""
    found = []
    for resource in resources:
        if "fill" not in resource or "default" in resource:
            found.append(resource)
            continue
        fill = dict(resource["fill"])
        for variable in VARIABLE_RE.findall(formats.get(resource["type"], "")):
            if variable in fill:
                continue
            if variable != "Account" and variable.endswith("Account") and len(accounts) == 1:
                fill[variable] = accounts[0]
            elif variable != "Region" and variable.endswith("Region") and len(regions) == 1:
                fill[variable] = regions[0]
        found.append(dict(resource, fill=dict(sorted(fill.items()))) if fill != resource["fill"] else resource)
    return found


def with_account(resources: list[dict], formats: dict[str, str], accounts: list[str]) -> list[dict]:
    """The resources, each a type named by its variables taking its account from the request's one account id
    member, when it has exactly one and the type's format has an account, unless the resource applies only when that
    member is absent or the member fills one of the type's own variables, the account of something its ARN names."""
    if len(accounts) != 1:
        return resources
    return [dict(resource, account=accounts[0]) if "fill" in resource and "default" not in resource
            and "${Account}" in formats.get(resource["type"], "")
            and accounts[0] not in resource["fill"].values()
            and resource.get("when", {}).get(accounts[0]) != [None] else resource for resource in resources]


CHANGE_LIST_RE = re.compile(r"^(?P<base>.+?)(?:ToAdd|ToRemove)$")


def with_change_lists(resources: list[dict], types: list[str], formats: dict[str, str],
                      members: dict[str, str]) -> list[dict]:
    """The resources and, for each type the action acts on that no resource names yet and whose format has one
    variable of its own, one read from each list of the request named after the type with `ToAdd` or `ToRemove`
    (ElastiCache's ModifyUserGroup `UserIdsToAdd` and `UserIdsToRemove`), the resources the request adds or removes."""
    named = {kind for resource in resources for kind in _kinds(resource)}
    added = []
    for kind in types:
        if kind in named or kind not in formats:
            continue
        variables = [variable for variable in VARIABLE_RE.findall(formats[kind]) if variable not in KNOWN_VARIABLES]
        if len(variables) != 1:
            continue
        for member, path in sorted(members.items()):
            change = CHANGE_LIST_RE.match(member)
            if change and path.endswith("[]") and related(stem(change.group("base")), stem(kind)):
                added.append({"type": kind, "fill": {variables[0]: path}})
    return resources + added


def record_reached(models: dict[str, dict], operation: str, types: list[str]) -> dict[str, dict[str, list[str]]]:
    """Each top-level member of the request holding the id of one type the action acts on, named after the type, that
    the type's botocore record (a structure named after it) holds, with that type and the other types the action acts
    on the record names beside it, by an id field named after the type, or one of a structure it holds: Identity
    Store's `GroupMembership` holds its `MembershipId` beside the `GroupId` and the `MemberId.UserId` of the group and
    the user it links."""
    request = _request_shapes(models, operation)
    found: dict[str, dict[str, set[str]]] = {}
    for model in models.values():
        shapes = model["shapes"]
        for shape_name, shape in shapes.items():
            owners = [kind for kind in types if stem(shape_name) == stem(kind)]
            if shape.get("type") != "structure" or len(owners) != 1:
                continue
            fields = shape.get("members", {})
            for member in request:
                if member not in fields or kind_of(member) != "id" or not related(stem(member), stem(owners[0])) \
                        or shapes[fields[member]["shape"]].get("type") != "string":
                    continue
                reached: set[str] = set()
                for field, spec in fields.items():
                    held = shapes[spec["shape"]]
                    names = [field] if held.get("type") == "string" else [
                        inner for inner, inner_spec in held.get("members", {}).items()
                        if shapes[inner_spec["shape"]].get("type") == "string"] if held.get("type") == "structure" \
                        else []
                    reached |= {kind for name in names if name != member and kind_of(name) == "id"
                                for kind in types if kind != owners[0] and stem(name) == stem(kind)}
                if reached:
                    found.setdefault(member, {}).setdefault(owners[0], set()).update(reached)
    return {member: {owner: sorted(kinds) for owner, kinds in sorted(by_owner.items())}
            for member, by_owner in sorted(found.items())}


def with_record_through(resources: list[dict], formats: dict[str, str], reached: dict[str, dict[str, list[str]]],
                        required: set[str]) -> list[dict]:
    """The resources and, for each member filling the one variable of a type whose record names other types the action
    acts on (`record_reached`), those of them the action requires (`required_types`) that no resource names yet, named
    through that member: the reader asks the service for the ARNs of the resources the caller's record of that id
    names (the group and the user of the membership `DeleteGroupMembership`'s `MembershipId` names)."""
    named = {kind for resource in resources for kind in _kinds(resource)}
    added = []
    for member, by_owner in reached.items():
        for owner, kinds in by_owner.items():
            variables = [v for v in VARIABLE_RE.findall(formats.get(owner, "")) if v not in KNOWN_VARIABLES]
            filled = any(resource.get("type") == owner and resource.get("fill") == {variables[0]: member}
                         for resource in resources) if len(variables) == 1 else False
            left = [kind for kind in kinds if kind in required and kind not in named and kind in formats]
            if filled and left:
                added.append({"types": left, "through": member})
                named.update(left)
    return resources + added


def _key_paths(shapes: dict, shape_name: str, prefix: str, depth: int = 0) -> list[list[str]] | None:
    """The string paths of a request member a lookup reads, one list for each way the request may give it: a string's
    own path; each member of a union's apart, as the request gives one (Identity Store's `AlternateIdentifier`, an
    `ExternalId` or a `UniqueAttribute`); every member of another structure together; a document as itself. None for a
    list, a map or anything else."""
    shape = shapes[shape_name]
    if shape.get("type") == "string" or shape.get("document"):
        return [[prefix]]
    if shape.get("type") != "structure" or depth > 2:
        return None
    parts = []
    for name, spec in shape.get("members", {}).items():
        paths = _key_paths(shapes, spec["shape"], f"{prefix}.{name}", depth + 1)
        if paths is None:
            return None
        parts.append(paths)
    if shape.get("union"):
        return [path for paths in parts for path in paths]
    combined = [[]]
    for paths in parts:
        combined = [done + path for done in combined for path in paths]
    return combined


def lookup_keys(models: dict[str, dict], operation: str) -> dict[str, list[list[str]]]:
    """Each top-level id member of the operation's response that its request does not hold, which the operation looks
    up, with the paths of the request it is looked up by (`_key_paths`), one list for each way the request may give
    them: Identity Store's `GetGroupId` returns the `GroupId` of the group its `IdentityStoreId` and
    `AlternateIdentifier` name. None for a request holding a list or a map."""
    found: dict[str, list[list[str]]] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or "output" not in spec or _reading(model) != "all":
            continue
        shapes = model["shapes"]
        request = shapes[spec["input"]["shape"]].get("members", {})
        combined = [[]]
        for name, member in request.items():
            paths = _key_paths(shapes, member["shape"], name)
            if paths is None:
                return {}
            combined = [done + path for done in combined for path in paths]
        for name, member in shapes[spec["output"]["shape"]].get("members", {}).items():
            if name not in request and kind_of(name) == "id" and shapes[member["shape"]].get("type") == "string" \
                    and combined != [[]]:
                found.setdefault(name, combined)
    return found


def with_lookup_find(resources: list[dict], types: list[str], formats: dict[str, str],
                     keys: dict[str, list[list[str]]], required: set[str]) -> list[dict]:
    """The resources and, for each type the action requires (`required_types`) that no resource names yet whose format
    has one variable of its own the operation looks up (`lookup_keys`), that type found by the members the request
    looks it up by, once for each way it may give them: `GetGroupMembershipId` finds the membership of its `GroupId`
    and `MemberId.UserId` in its `IdentityStoreId`."""
    named = {kind for resource in resources for kind in _kinds(resource)}
    added = []
    for kind in types:
        variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
        if kind not in required or kind in named or len(variables) != 1 or variables[0] not in keys:
            continue
        added.extend({"type": kind, "fill": {}, "find": paths} for paths in keys[variables[0]])
    return resources + added


def with_type_named_members(resources: list[dict], types: list[str], formats: dict[str, str],
                            members: dict[str, str], subject: str | None = None) -> list[dict]:
    """The resources, a type of one variable of its own read from the one top-level member of the request that names
    that variable: named after the type and ending in the variable's name (MemoryDB's `MultiRegionClusterName`, of its
    `multiregioncluster`, `multiregioncluster/${ClusterName}`), or, for the type the operation is about (`subject`,
    its name past the verb), a name or id with a word of its own that the variable's name ends in (SageMaker's
    `JobName`, of `DescribeInferenceRecommendationsJob`'s `${InferenceRecommendationsJobName}`). It is read where no
    resource names the type yet (DeleteCluster's `ClusterName`, of its `cluster`), where a resource leaves its id open
    (CreateInferenceRecommendationsJob's job), and instead of a member named after something else where one fills it
    (CreateCluster's `ClusterName`, the new regional cluster, which the multi-Region cluster's `${ClusterName}` would
    otherwise take). Where another type the action acts on has a variable of that name, the member names this type only
    when that one has a member named after it too: SSM's `InstanceId` may name an `instance` or a `managed-instance`,
    and names neither alone."""
    named_kinds = {kind for resource in resources for kind in _kinds(resource)}
    found = [dict(resource) for resource in resources]

    def own_members(kind: str, variable: str) -> list[str]:
        return [member for member, path in members.items() if path == member
                and ((stem(member) == stem(kind) and member.endswith(variable))
                     or (subject == stem(kind) and stem(member) and kind_of(member) in ("name", "id")
                         and member != variable and variable.endswith(member)))]

    for kind in types:
        variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
        if len(variables) != 1:
            continue
        variable = variables[0]
        own = own_members(kind, variable)
        sharing = [other for other in types if other != kind
                   and variable in VARIABLE_RE.findall(formats.get(other, ""))]
        if len(own) != 1 or any(len(own_members(other, variable)) != 1 for other in sharing):
            continue
        if kind not in named_kinds:
            found.append({"type": kind, "fill": {variable: own[0]}})
            continue
        for resource in found:
            fill = resource.get("fill")
            if resource.get("type") != kind or fill is None or {"find", "when", "unless", "default"} & set(resource):
                continue
            if fill == {} or (list(fill) == [variable] and fill[variable] != own[0] and "." not in fill[variable]
                              and stem(fill[variable].removesuffix("[]")) != stem(kind)):
                resource["fill"] = {variable: own[0]}
    return found


def structure_name_fields(models: dict[str, dict], operation: str, types: list[str]) -> dict[str, str]:
    """By type, the `name` field of the operation's request's top-level structure member named after the type
    (PutConfigurationRecorder's `ConfigurationRecorder.name`), the name the structure gives the resource; a type with
    no such structure, or two, is left out."""
    found: dict[str, set[str]] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            if member.get("location") not in BODY_LOCATIONS or shape["type"] != "structure":
                continue
            fields = [field for field, inner in shape.get("members", {}).items()
                      if field.lower() == "name" and model["shapes"][inner["shape"]]["type"] == "string"]
            for kind in types:
                if stem(name) == stem(kind) and len(fields) == 1:
                    found.setdefault(kind, set()).add(f"{name}.{fields[0]}")
    return {kind: next(iter(paths)) for kind, paths in sorted(found.items()) if len(paths) == 1}


def with_structure_names(resources: list[dict], formats: dict[str, str], names: dict[str, str]) -> list[dict]:
    """The resources of a type the request names but fills no variable of, whose format has one variable that is a
    name (`configuration-recorder/${RecorderName}/${RecorderId}`), taking it from the request's structure named after
    the type."""
    found = []
    for resource in resources:
        kind = resource.get("type")
        variables = [variable for variable in VARIABLE_RE.findall(formats.get(kind, "")) if variable.endswith("Name")
                     and variable not in KNOWN_VARIABLES] if isinstance(kind, str) else []
        if "fill" in resource and not resource["fill"] and kind in names and len(variables) == 1:
            found.append(dict(resource, fill={variables[0]: names[kind]}))
        else:
            found.append(resource)
    return found


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
        if not accounts:
            continue
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
    """Whether a name ends as a name, an id, or an identifier (which is either), a URL, or none of them."""
    lower = name.lower()
    if re.search(r"identifiers?$", lower):
        return "identifier"
    if re.search(r"names?$", lower):
        return "name"
    if re.search(r"ids?$", lower):
        return "id"
    if re.search(r"urls?$", lower):
        return "url"
    return ""


def compatible(member: str, variable: str) -> bool:
    """Whether a member can fill a variable: a name fills a name, an id an id, an identifier either."""
    member_kind, variable_kind = kind_of(member), kind_of(variable)
    return not member_kind or not variable_kind or member_kind == variable_kind or member_kind == "identifier"


def resources_for(operation: str, types: list[str], formats: dict[str, str], members: dict[str, str],
                  arn_members: set[str] | None = None, labels: dict[str, str] | None = None,
                  shapes: dict[str, str] | None = None, aliased: set[str] | None = None,
                  foreign: set[str] | None = None) -> list[dict]:
    """The resources the rule finds the operation's request naming, as vendored entries. `labels` gives the path
    segment before each bare member a REST request carries as a path label, stemmed (`path_labels`), `shapes` the
    botocore shape each member holds (`member_shapes`), and `foreign` the members holding another service's ARN
    (`foreign_arn_members`), which name nothing of the namespace."""
    members = {member: path for member, path in members.items() if member not in (foreign or set())}
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
    # A type named exactly as what the operation makes is the one it makes, so another only ending with that name is
    # not: PutObject makes an `object`, not an `accesspointobject`.
    exactly_created = created is not None and any(
        created in (stem(kind), stem(word.group(1)) if (word := RESOURCE_PART_RE.match(formats[kind])) else stem(kind))
        for kind in types if kind in formats)
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
        creates_it = created is not None and (created in (kind_stem, word_stem) if exactly_created
                                              else related(created, kind_stem) or related(created, word_stem))
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
            # Reserve a member naming a more qualified variable for that type. A cluster parameter group
            # cannot also fill the instance parameter group's shorter ParameterGroupName variable.
            qualified = {v for other in types if other != kind for v in VARIABLE_RE.findall(formats.get(other, ""))
                         if stem(variable) and v not in KNOWN_VARIABLES and stem(v) != stem(variable)
                         and stem(v).endswith(stem(variable))}
            eligible = {m: p for m, p in names.items() if m not in fill.values()
                        and not ("." not in p and not p.endswith("[]")
                                 and any(stem(m).endswith(stem(v)) and compatible(m, v) for v in qualified))}
            choice = _filling(variable, kind_stem, len(variables), eligible,
                              bare=bare, aliased=aliased, word_stem=word_stem,
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
        else:
            # A type of no variables of its own is the account's one, which every request names, never an
            # alternative to a create's own type: Glue's `catalog` beside the database `CreateDatabase` makes.
            kept += [kind for kind in kinds if not [v for v in VARIABLE_RE.findall(formats[kind])
                                                     if v not in KNOWN_VARIABLES]]
    specs = [{"types": kinds, "arn": arns[member]} for member, kinds in sorted(by_arn.items())]
    return specs + [{"type": kind, "fill": dict(sorted(filled[kind].items()))} for kind in types if kind in kept] + \
        also_filled(types, members, filled, kept, used, aliased or set(), shapes)


RESOURCE_PART_RE = re.compile(r"^arn:\$\{Partition}:[^:]*:[^:]*:[^:]*:([A-Za-z][A-Za-z0-9-]*)(.*)$")
# An ARN format whose account is a variable of its own, captured: `arn:${Partition}:account::${ManagementAccountId}:`.
ACCOUNT_SLOT_RE = re.compile(r"^arn:\$\{Partition}:[^:]*:[^:]*:\$\{(\w+)}:")


def with_made(resources: list[dict], operation: str, formats: dict[str, str]) -> list[dict]:
    """The resources, those a create makes marked `made` when it names others too (the pipeline a webhook connects
    to, the namespace a service is created in), so that its tags are checked on what it makes alone: of a type, or
    with an ARN format's leading resource word, related to the operation's name past its verb, or with its sole
    resource variable named after that operation's subject (`db:${DbInstanceName}`). A member that may hold
    an ARN of several types is made only when every one of them is: one that may name the parent too (a subscriber's
    `EventBusArn`) names a resource the create does not make. Where the namespace has a type named as the operation is
    past its verb, that type, and any type whose format's leading resource word is that name, is what it makes:
    MemoryDB's CreateCluster makes a `cluster`, not the `multiregioncluster` it may join, whose name merely ends in the
    same word."""
    name = operation.split(":", 1)[1]
    if not CREATING_RE.match(name):
        return resources
    created = stem(CREATING_RE.sub("", name))
    # A type's whole name says what it is before its stem does, which drops a trailing word: SageMaker's CreateEndpoint
    # makes an `endpoint`, not the `endpoint-config` whose stem is `endpoint` too.
    named = re.sub(r"[^a-z0-9]", "", CREATING_RE.sub("", name).lower())
    whole = {kind for kind in formats if re.sub(r"[^a-z0-9]", "", kind.lower()) == named}
    exact = whole or {kind for kind in formats if stem(kind) == created}

    def is_created(kind: str) -> bool:
        word = RESOURCE_PART_RE.match(formats.get(kind, ""))
        if whole:
            return kind in whole or (word is not None and re.sub(r"[^a-z0-9]", "", word.group(1).lower()) == named)
        if exact:
            return kind in exact or (word is not None and stem(word.group(1)) == created)
        variables = [variable for variable in VARIABLE_RE.findall(formats.get(kind, ""))
                     if variable not in KNOWN_VARIABLES]
        return (related(created, stem(kind)) or (word is not None and related(created, stem(word.group(1))))
                or len(variables) == 1 and stem(variables[0]) == created)

    def makes(resource: dict) -> bool:
        return all(is_created(kind) for kind in _kinds(resource))

    made = [makes(resource) for resource in resources]
    if all(made) or not any(made):
        return resources
    return [dict(resource, made=True) if is_made else resource
            for resource, is_made in zip(resources, made, strict=True)]


def with_documented_creation(resources: list[dict], operation: str, types: list[str],
                             formats: dict[str, str], models: dict[str, dict]) -> list[dict]:
    """A create whose name differs from its type can name it in the operation's opening sentence:
    AllocateAddress 'Allocates an Elastic IP address'. Require that direct object and the type's sole
    ARN id as a string in the response, absent from the request. Pool ids echoed in the response and
    types mentioned later in the documentation do not identify a newly created resource."""
    name = operation.split(":", 1)[1]
    verb = CREATING_RE.match(name)
    if not verb:
        return resources
    named = {kind for resource in resources for kind in _kinds(resource)}
    added = []
    for kind in types:
        variables = set(VARIABLE_RE.findall(formats.get(kind, ""))) - KNOWN_VARIABLES
        if kind in named or len(variables) != 1:
            continue
        variable = next(iter(variables))
        if kind_of(variable) != "id":
            continue
        phrase = r"[ -]+".join(re.escape(word) for word in re.split(r"[ -]+", kind.lower()))
        opening = rf"^{verb.group().lower()}s? (?:a |an |the )?{phrase}\b"
        for model in models.values():
            spec = model["operations"].get(name, {})
            if "input" not in spec or "output" not in spec or not re.match(opening, _lead(spec.get("documentation"))):
                continue
            inputs = model["shapes"][spec["input"]["shape"]].get("members", {})
            outputs = model["shapes"][spec["output"]["shape"]].get("members", {})
            if variable not in inputs and variable in outputs \
                    and model["shapes"][outputs[variable]["shape"]]["type"] == "string":
                added.append({"type": kind, "fill": {}, "made": True})
                break
    return resources + added


def child_id(child: str, parent: str) -> str | None:
    """The variable a child type's ARN format adds to its parent's: the parent's with its leading resource type word
    replaced and one id appended (`listener/app/.../${ListenerId}` for `loadbalancer/app/...`), or the parent's whole
    with a word of its own and one id appended (`bucket/${TableBucketName}/table/${TableID}` for
    `bucket/${TableBucketName}`); None for any other."""
    child_match, parent_match = RESOURCE_PART_RE.match(child), RESOURCE_PART_RE.match(parent)
    if child_match is None or parent_match is None:
        return None
    nested = r"[/:][A-Za-z][A-Za-z0-9-]*" if child_match.group(1) == parent_match.group(1) else ""
    appended = re.fullmatch(re.escape(parent_match.group(2)) + nested + r"[/:]\$\{(\w+)}", child_match.group(2))
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
              arn_members: set[str] | None = None, labels: dict[str, str] | None = None,
              aliased: set[str] | None = None, models: dict[str, dict] | None = None) -> list[dict]:
    """The resource a create whose action acts on no resource type makes: of the one type of its namespace named as
    the operation is past its verb, or else related to that name, named as the rule names a type an action acts on,
    its variables nothing fills open. AWS checks the tagging action a create's tags need on the resource being
    tagged, so they are checked there (`CreateView` makes `view/<ViewName>/*`, `CreateSink` `sink/*`), and the create
    itself stays checked on `*`. So is the resource a create whose request carries tags makes when its action acts
    on other types alone and the request names it in full, a field of a top-level structure included (`ConnectionInput.Name`
    for the connection Glue's `CreateConnection` makes, whose action acts on its connection type and catalog), the
    create itself staying checked on those."""
    name = operation.split(":", 1)[1]
    if not CREATING_RE.match(name):
        return []
    created = stem(CREATING_RE.sub("", name))
    kinds = [kind for kind in formats if stem(kind) == created] \
        or [kind for kind in formats if related(created, stem(kind))]
    if len(kinds) != 1:
        return []
    if types:
        return made_by_a_create_of_other_types(operation, types, formats, kinds[0], members, arn_members, labels,
                                               aliased, models)
    return [resource for resource in resources_for(operation, kinds, formats, members, arn_members, labels,
                                                   aliased=aliased)
            if resource.get("type") == kinds[0]][:1]


def made_by_a_create_of_other_types(operation: str, types: list[str], formats: dict[str, str], kind: str,
                                    members: dict[str, str], arn_members: set[str] | None,
                                    labels: dict[str, str] | None, aliased: set[str] | None,
                                    models: dict[str, dict] | None) -> list[dict]:
    """The resource of the type a create whose action acts on other types alone makes, when its request carries tags
    (a top-level member named `Tags`, `TagList` or the like) and names every variable of it, as the rules name a type
    an action acts on and from the fields of a top-level structure its documentation reads; none otherwise."""
    name = operation.split(":", 1)[1]
    created = stem(CREATING_RE.sub("", name))
    # A type related to the create's name is the one it makes, unless the request names it by a member of its own,
    # as a resource the create joins: MemoryDB's CreateCluster names the multi-Region cluster it may join by its
    # `MultiRegionClusterName` and makes a `cluster`; RAM's CreatePermission names no customer managed permission and
    # makes that one.
    joined = {other for other in types if stem(kind) == created and stem(other) != created
              and any(path == member and stem(member) == stem(other) for member, path in members.items())}
    if kind in types or any(related(created, stem(other)) for other in types if other not in joined) or not models:
        return []
    shapes = member_shapes(models, name)
    if not any(stem(member) == "tag" for member in shapes):
        return []
    found = with_documented(operation, [kind], formats, models,
                            resources_for(operation, [kind], formats, members, arn_members, labels, shapes, aliased))
    variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
    return [resource for resource in found if resource.get("type") == kind and variables
            and set(resource.get("fill", {})) == set(variables) and set(resource) == {"type", "fill"}][:1]


def by_members_key_is_empty(kinds: list[str], filled: dict[str, dict[str, str]]) -> bool:
    """Whether the types are named by no member at all: each a create's own type, all ids minted."""
    return all(not filled[kind] for kind in kinds)


def _filling(variable: str, kind_stem: str, variables: int, names: dict[str, str], bare: set[str],
             aliased: set[str] | None = None, word_stem: str | None = None,
             shapes: dict[str, str] | None = None) -> str | None:
    """The member that fills the variable: one of a compatible kind whose stem relates to the variable's,
    or, for the variable named after the type or a type's only one, a member named after the type, or a
    bare `Name` or `Id` among those `bare` gives, of a compatible kind, or else a string or list member whose
    botocore shape is named after the type, of a compatible kind (Parameter Store's `Path` and
    `GetParameterHistory`'s `Name` hold a `PSParameterName`, Glue's `TablesToDelete` a `BatchDeleteTableNameList`, S3's
    `Key` an `ObjectKey`, a shape named as the type and `Key`);
    the closest of them, one qualified by the type's own name first (a snapshot's `SnapshotClusterIdentifier` before `ClusterIdentifier`), and a string before a list. A variable the
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
                                                 or (member in (aliased or set())
                                                     and names_type(member, kind_stem)))))]
    elif not candidates and word_stem and related(variable_stem, word_stem):
        # A variable named after the format's leading word (`rule/${EventBusName}/${RuleName}`'s `RuleName`) takes a
        # bare `Name` or `Id` of the operation that is about the type, and no member named after another type.
        candidates = [member for member in names if compatible(member, variable)
                      and member in bare and member.lower() in BARE]
    if not candidates and (related(variable_stem, kind_stem) or variables == 1):
        # A list names the type when its own list shape is named for the type's names (`BatchDeleteTableNameList`),
        # not when it holds a name's shape.
        candidates = [member for member in names if member in (shapes or {})
                      and (not names[member].endswith("[]") or shapes[member].endswith("List"))
                      and "." not in member and stem(shapes[member])
                      and (related(stem(shapes[member]), kind_stem) or stem(shapes[member]) == kind_stem + "key")
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
                kept: list[str], used: dict[str, str], aliased: set[str],
                shapes: dict[str, str] | None = None) -> list[dict]:
    """The resources a request names besides the one it fills a type with, when it names several of that type and
    acts on each of them.

    <p>A re-encryption decrypts under the key its path names and encrypts under the key its body names, so both are
    acted on and both are checked. A member that names a resource of the type, with a word before it saying which of
    them it is, fills the type's variable as well, when botocore's shape for it takes an alias as well as the type's
    ARN, which the service resolves (Payment Cryptography's `KeyArnOrKeyAliasType`). Paired Add/Remove ID lists
    of the same modeled shape also name separate resources, including the resources being removed."""
    found = []
    for kind in types:
        if kind not in kept or kind not in filled or not filled[kind]:
            continue
        kind_stem = stem(kind)
        variable = next(iter(filled[kind]))
        for member in sorted(members):
            if used.get(kind) == member:
                continue
            # Two qualified ids of the same modeled id shape name separate resources: IPv4 and IPv6 IPAM pools.
            selected = used.get(kind, "")
            sibling_id = (len(filled[kind]) == 1 and kind_of(variable) == "id"
                          and kind_of(member) == "id" and kind_of(selected) == "id"
                          and stem(member) != stem(variable) and stem(selected) != stem(variable)
                          and stem(member).endswith(stem(variable)) and stem(selected).endswith(stem(variable))
                          and (shapes or {}).get(member) == (shapes or {}).get(selected)
                          and stem((shapes or {}).get(member, "")) == stem(variable))
            paired_lists = (len(filled[kind]) == 1 and members[member].endswith("[]")
                            and members.get(selected, "").endswith("[]")
                            and {member.removesuffix("s"), selected.removesuffix("s")}
                            == {"Add" + variable, "Remove" + variable}
                            and (shapes or {}).get(member) is not None
                            and shapes[member] == shapes.get(selected))
            if not sibling_id and not paired_lists and (member not in aliased or not names_type(member, kind_stem)):
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
def load_examples(data: Path, clients: set[str]) -> dict[str, dict]:
    """Each client's botocore `examples-1.json`, the requests and responses its API reference shows, for the clients
    that have one."""
    examples = {}
    for client in sorted(clients):
        if not (data / client).is_dir():
            continue
        directory = latest_model_dir(data, client)
        for name in ("examples-1.json", "examples-1.json.gz"):
            if (directory / name).is_file():
                examples[client] = load_json_file(directory / name)
                break
    return examples


def _example_ids(model: dict, shape_name: str, value: object, found: list[tuple[str, str]]) -> None:
    """The ids an example's value gives, each with the string shape botocore declares for its member: the
    `SecurityGroupId` a `GroupId` holds, or each of a `GroupIds` list."""
    shape = model["shapes"].get(shape_name, {})
    if isinstance(value, dict) and shape.get("type") == "structure":
        members = shape.get("members", {})
        for name, item in value.items():
            if name in members:
                _example_ids(model, members[name]["shape"], item, found)
    elif isinstance(value, list) and shape.get("type") == "list":
        for item in value:
            _example_ids(model, shape["member"]["shape"], item, found)
    elif isinstance(value, str) and shape.get("type") == "string":
        match = EXAMPLE_ID_RE.match(value)
        if match:
            found.append((shape_name, match.group(1)))


def id_prefixes(models: dict[str, dict], kinds: tuple[str, ...],
                examples: dict[str, dict] | None = None) -> dict[str, str]:
    """The prefix botocore gives each type's ids, for the types given: in its documentation, "the instance ID (for
    example, i-0123456789abcdef0)", and in its examples, the ids of the members whose shape names the type
    (`"GroupId": "sg-903004f8"`, a `SecurityGroupId`). The prefix given most wins; a type with none has none."""
    counts: dict[str, dict[str, int]] = {}

    def count(named: str, prefix: str) -> None:
        kind = next((kind for kind in kinds if stem(kind) == stem(named)), None)
        if kind:
            counts.setdefault(kind, {}).setdefault(prefix, 0)
            counts[kind][prefix] += 1

    for model in models.values():
        for shape in model["shapes"].values():
            for member in shape.get("members", {}).values():
                documentation = member.get("documentation") or ""
                if "for example, " not in documentation:
                    continue
                text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", documentation))
                for named, prefix in ID_EXAMPLE_RE.findall(text):
                    count(named.split()[-1], prefix)
    for client, document in sorted((examples or {}).items()):
        model = models.get(client)
        if model is None:
            continue
        for operation, shown in sorted(document.get("examples", {}).items()):
            spec = model["operations"].get(operation, {})
            for example in shown:
                found: list[tuple[str, str]] = []
                for part in ("input", "output"):
                    if part in spec and isinstance(example.get(part), dict):
                        _example_ids(model, spec[part]["shape"], example[part], found)
                for shape_name, prefix in found:
                    count(shape_name, prefix)
    return {kind: max(found, key=found.get) for kind, found in sorted(counts.items())}


def namespace_id_prefixes(namespace: str, models: dict[str, dict],
                          examples: dict[str, dict] | None = None) -> dict[str, str] | None:
    """`id_prefixes` of the types `ID_TARGETS` reads by id in the namespace; None for a namespace it reads none of."""
    if namespace not in ID_TARGETS:
        return None
    return id_prefixes(models, ID_TARGETS[namespace], examples)


def id_targets(operation: str, types: list[str], formats: dict[str, str], models: dict[str, dict],
               known: dict[str, str] | None = None) -> dict | None:
    """An id list, in model and EC2 Query spelling, with each type `ID_TARGETS` reads by id's discriminator, the prefix
    botocore gives its ids (`id_prefixes`): the list a type's ARN variable names, when the request names nothing else
    the action acts on, or else the request's list of whatever resources it acts on (`Resources`, of the tagging
    calls). None when the request has neither, and the ordinary rules read it."""
    configured = ID_TARGETS.get(namespace_of(operation))
    if configured is None:
        return None
    known = known if known is not None else namespace_id_prefixes(namespace_of(operation), models)
    found = None
    for model in models.values():
        spec = model["operations"].get(operation.split(":", 1)[1], {})
        if "input" not in spec or "ec2" not in _protocols(model):
            continue
        members = model["shapes"][spec["input"]["shape"]].get("members", {})
        variables = {variable for kind in known if kind in types and kind in formats
                     for variable in set(VARIABLE_RE.findall(formats[kind])) - KNOWN_VARIABLES}
        candidates = [name for name, member in members.items()
                      if _strings(model, model["shapes"][member["shape"]]) == "[]"
                      and name.lower().removesuffix("s") in {variable.lower() for variable in variables}]
        if len(candidates) > 1:
            # Lists of several types (a VPC endpoint's `RouteTableIds` and `SecurityGroupIds`) name more than one
            # thing the action acts on, which the ordinary rules read.
            continue
        listed = [name for name, member in members.items() if stem(name) == "resource"
                  and _strings(model, model["shapes"][member["shape"]]) == "[]"]
        member_name = candidates[0] if candidates else listed[0] if len(listed) == 1 else None
        member = members.get(member_name) if member_name else None
        if member is None or _strings(model, model["shapes"][member["shape"]]) != "[]":
            continue
        prefixes = {kind: prefix for kind, prefix in known.items() if kind in types and kind in formats}
        if candidates:
            # A list a type's ARN variable names holds that type's ids alone, and it is all the request acts on only
            # when the request names nothing else the action acts on: a launch's `SecurityGroupIds` sit beside its
            # image, subnet and key pair, so they are not its targets.
            listed_as = member_name.lower().removesuffix("s")
            prefixes = {kind: prefix for kind, prefix in prefixes.items()
                        if listed_as in {variable.lower() for variable in VARIABLE_RE.findall(formats[kind])}}
            others = {kind for resource in by_client(operation, models,
                                                      lambda read: read_resources(operation, types, formats, read),
                                                      formats)
                      for kind in _kinds(resource)} - set(prefixes)
            if others:
                continue
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


def ec2_query_members(operation: str, models: dict[str, dict], named: set[str]) -> dict[str, str]:
    """Wire names for the top-level EC2 members the rules read. EC2 lists use Name.N,
    unlike Query's Name.member.N; locationName/queryName can also rename a member."""
    result = {}
    for model in models.values():
        spec = model["operations"].get(operation.split(":", 1)[1], {})
        if "ec2" not in _protocols(model) or "input" not in spec:
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            suffix = _strings(model, shape)
            if name not in named or suffix is None:
                continue
            location = member.get("locationName", name)
            wire = member.get("queryName", location[:1].upper() + location[1:])
            if suffix or wire != name:
                path = name + suffix
                if path in result and result[path] != wire:
                    raise ValueError(f"{operation}: clients disagree on {name}'s EC2 Query name")
                result[path] = wire
    return result


def entry_for(operation: str, upstream: dict, formats: dict[str, str], models: dict[str, dict],
              known: dict[str, str] | None = None, forwarded: dict[str, dict] | None = None,
              routed: list[dict] | None = None) -> dict:
    """What the file says for one operation, `forwarded` being what the file records of each action a `MEASURED` entry
    names, and `routed` the resources its routes name where AWS authorizes them as their method (`routed_resources`),
    which are then its resources. `upstream` gives, for a namespace with vendored permissions, the resource types of
    the actions they may name (`actions`)."""
    types = upstream["types"] or []
    members = request_members(models, operation.split(":", 1)[1])
    patterns = string_patterns(models, operation.split(":", 1)[1])
    # A member whose pattern takes only an ARN holds one when it is an identifier, or names whatever the action acts on
    # (`Resource`, as CloudFront's tagging calls do); another such member names a resource of a type of its own.
    arn_members = {member for member, values in patterns.items()
                   if (kind_of(member) == "identifier" or stem(member) in ("", "resource"))
                   and values and all(only_arn(value) for value in values)}
    targets = id_targets(operation, types, formats, models, known) if namespace_of(operation) in ID_TARGETS else None
    if targets is not None:
        return {"action": upstream["action"], "types": types, "resources": [], "targets": targets}
    actions = None
    if routed is not None:
        resources = routed
    else:
        resources = by_client(operation, models, lambda read: read_resources(operation, types, formats, read), formats)
        resources = with_documented_creation(resources, operation, types, formats, models)
        resources = with_made(resources, operation, formats)
        required = required_types(upstream["action"], types)
        if required:
            name = operation.split(":", 1)[1]
            resources = with_record_through(resources, formats, record_reached(models, name, types), required)
            resources = with_lookup_find(resources, types, formats, lookup_keys(models, name), required)
        source = load_permission_source(namespace_of(operation))
        known = {upstream["action"]: types, **(upstream.get("actions") or {})}
        if source is not None and "permissions" in source:
            name = operation.split(":", 1)[1]
            row = next((found for found in source["permissions"] if found["operation"] == name), None)
            resources, actions = permission_resources(operation, upstream["action"], resources, row, known, formats,
                                                      models)
            resources = with_from_metadata(resources, operation,
                                           [found for found in source["fromMetadata"] if found["operation"] == name],
                                           models)
            named = named_resources(source["identifiers"], formats)
            resources = [dict(resource, partialArn=True) if resource.get("type") in named
                         and len(resource.get("fill") or {}) == 1 else resource for resource in resources]
        if source is not None and "partiql" in source:
            each = item_resources(operation, item_actions(source, namespace_of(operation)), known, formats, models) \
                or statement_resources(operation, source, known, formats, models)
            if each:
                resources = each
                actions = {action: known[action] for action in dict.fromkeys(row["action"] for row in each)}
        if source is not None and "arns" in source:
            name = operation.split(":", 1)[1]

            def of_operation(key: str) -> list[dict]:
                return [found for found in source[key] if found["operation"] == name]

            resources = with_paths(resources, operation, source["arns"], formats, models)
            resources = with_renames(resources, operation, of_operation("bothNames"), source["arns"], formats, models)
            resources = with_signing_user(resources, operation, types, of_operation("signingUser"), models)
            resources = with_same_as_arn(resources, operation, types, of_operation("sameAsArn"), formats, models)
            resources = with_formats(resources, operation, types, of_operation("formats"), models)
    uses = MEASURED.get(operation, (None, ""))[0]
    # An entry naming only another action AWS checks gives the operation no resources of its own.
    if uses is not None and not (uses and all("action" in use for use in uses)):
        resources = measured_resources(operation, types, formats, resources)
    entry = {"action": upstream["action"], "types": types, **({"actions": actions} if actions else {}),
             "resources": resources}
    unread = body_only_identifiers(types, formats, resources, models, operation.split(":", 1)[1])
    if unread:
        entry["unread"] = unread
    creates = creates_for(operation, types, formats, members)
    if creates:
        entry["creates"] = creates
    makes = makes_for(operation, types, formats, members, arn_members,
                      path_labels(models, operation.split(":", 1)[1]), alias_members(models, operation.split(":", 1)[1]),
                      models)
    if makes:
        entry["makes"] = makes
    named = _named(resources) | _named(creates) | _named(makes)
    query_members = ec2_query_members(operation, models, named)
    if query_members:
        entry["ec2Query"] = query_members
    shared, apart = located_members(models, operation.split(":", 1)[1])
    located = {member: where for member, where in shared.items() if member in named}
    if located:
        entry["located"] = located
    located_apart = {client: kept for client, found in apart.items()
                     if (kept := {member: where for member, where in found.items() if member in named})}
    if located_apart:
        entry["locatedApart"] = located_apart
    if operation in measured_actions():
        entry["forwarded"] = forwarded_for(operation, types, models, forwarded or {})
    return entry


def body_only_identifiers(types: list[str], formats: dict[str, str], resources: list[dict], models: dict[str, dict],
                          name: str) -> list[str]:
    """The members of a request whose body the reader does not read (`LOCATED_ONLY_PROTOCOLS`) that name what a type
    of the action acts on, by a shape named as the type and `Key`, where the members it does read leave the type's own
    variable open: S3's DeleteObjects names each object it deletes in `Delete.Objects[].Key`, an `ObjectKey`. Read on
    the rest of its request, such an operation would be checked on what it names in none of its body (`unread`)."""
    found: list[str] = []
    for model in (model for _, model in sorted(models.items())):
        spec = model["operations"].get(name)
        if spec is None or "input" not in spec or _reading(model) != "located":
            continue
        for kind in types:
            variables = [variable for variable in VARIABLE_RE.findall(formats.get(kind, ""))
                         if variable not in KNOWN_VARIABLES and stem(variable) == stem(kind)]
            if not variables or any(set(variables) <= set(resource.get("fill", {})) for resource in resources
                                    if kind in _kinds(resource)):
                continue
            for path, shape in _body_members(model, spec["input"]["shape"]):
                if stem(shape) == stem(kind) + "key" and path not in found:
                    found.append(path)
    return found


def _body_members(model: dict, shape_name: str, prefix: str = "", depth: int = 0) -> list[tuple[str, str]]:
    """The members of a request's body, at any depth up to five, as `Outer.List[].Member` paths, with the shape each
    holds."""
    shape = model["shapes"][shape_name]
    found: list[tuple[str, str]] = []
    if depth > 4:
        return found
    if shape["type"] == "structure":
        for member, ref in shape.get("members", {}).items():
            if ref.get("location") in ("uri", "querystring", "header", "headers"):
                continue
            found.append((prefix + member, ref["shape"]))
            found += _body_members(model, ref["shape"], prefix + member + ".", depth + 1)
    elif shape["type"] == "list":
        found += _body_members(model, shape["member"]["shape"], prefix.removesuffix(".") + "[].", depth + 1)
    return found


def measured_resources(operation: str, types: list[str], formats: dict[str, str], derived: list[dict]) -> list[dict]:
    """The resources `MEASURED` gives the operation, refused unless its admission rules hold: its evidence links to
    the measurement, the Service Reference lists none of its types for the action, each has an ARN format, and the
    rules, which gave `derived`, do not already give them."""
    resources = [resource for resource in MEASURED[operation][0] if "action" not in resource]
    evidence = MEASURED[operation][1]
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


def measured_actions() -> dict[str, tuple[list[dict], str]]:
    """The `MEASURED` entries naming another action AWS checks the caller for, by operation, with their evidence."""
    return {operation: ([use for use in uses if "action" in use], evidence)
            for operation, (uses, evidence) in MEASURED.items() if any("action" in use for use in uses)}


def forwarded_actions(documents: list[dict]) -> dict[str, dict]:
    """For each action a `MEASURED` entry names, what the Service Reference gives it: the one resource type it acts
    on, that type's ARN format, and its namespace's `ViaService` condition key where the reference lists that for the
    action."""
    by_name = {document["Name"]: document for document in documents}
    found: dict[str, dict] = {}
    for action in sorted({use["action"] for uses, _ in measured_actions().values() for use in uses}):
        namespace, name = action.split(":", 1)
        document = by_name.get(namespace, {})
        listed = next((entry for entry in document.get("Actions", []) if entry["Name"] == name), None)
        if listed is None:
            raise ValueError(f"MEASURED names {action}, which the Service Reference does not list")
        kinds = [resource["Name"] for resource in listed.get("Resources") or []]
        formats = arn_formats([document], {namespace}).get(namespace, {})
        if len(kinds) != 1 or kinds[0] not in formats \
                or len(set(VARIABLE_RE.findall(formats[kinds[0]])) - KNOWN_VARIABLES) != 1:
            raise ValueError(f"MEASURED names {action}, which acts on {kinds}, not one resource type whose ARN format "
                             f"has one variable of its own")
        entry = {"type": kinds[0], "format": formats[kinds[0]]}
        if f"{namespace}:ViaService" in (listed.get("ActionConditionKeys") or []):
            entry["viaService"] = f"{namespace}:ViaService"
        found[action] = entry
    return found


def authorized_by(documents: list[dict]) -> dict[str, set[str]]:
    """The actions the Service Reference lists each operation as authorized by, as `<namespace>:<Operation>`."""
    return {f"{document['Name']}:{operation['Name']}": {f"{action['Service']}:{action['Name']}"
                                                         for action in operation.get("AuthorizedActions") or []}
            for document in documents for operation in document.get("Operations", [])}


def check_forwarded(authorized: dict[str, set[str]]) -> None:
    """The rules for a `MEASURED` entry naming another action that do not need botocore: it links to its evidence,
    and names no action the
    Service Reference lists for its operation, which a rule would read instead."""
    for operation, (uses, evidence) in sorted(measured_actions().items()):
        if "https://" not in evidence:
            raise ValueError(f"MEASURED gives {operation} with no link to the evidence of AWS it rests on")
        listed = sorted({use["action"] for use in uses} & authorized.get(operation, set()))
        if listed:
            raise ValueError(f"MEASURED gives {operation} {listed}, which the Service Reference lists for it: "
                             f"a rule reads those, so take it out")


def record_member(models: dict[str, dict], kind: str, word: str) -> str | None:
    """The member named `word`, whatever its case, of botocore's record of the type: the structure named after it
    (Redshift's and Redshift Serverless' `Snapshot`, `KmsKeyId` and `kmsKeyId`)."""
    record = re.sub(r"[^a-z0-9]", "", kind.lower())
    for model in models.values():
        for name, shape in model["shapes"].items():
            if shape.get("type") == "structure" and name.lower() == record:
                member = next((member for member in shape.get("members", {}) if member.lower() == word.lower()), None)
                if member is not None:
                    return member
    return None


def forwarded_for(operation: str, types: list[str], models: dict[str, dict], actions: dict[str, dict]) -> list[dict]:
    """What the operation's service asks another to do as the caller (`MEASURED`): each action, on the resource
    botocore's record of `of` holds in the member named after the action's namespace and the variable of the format
    of the type it acts on (`kms` and `${KeyId}`, a snapshot's `KmsKeyId`), sent from the operation's endpoint prefix."""
    uses, _ = measured_actions()[operation]
    prefixes = {model.get("metadata", {}).get("endpointPrefix") for model in models.values()}
    if len(prefixes) != 1 or None in prefixes:
        raise ValueError(f"MEASURED gives {operation}, whose clients have no one endpoint prefix")
    endpoint = next(iter(prefixes))
    found = []
    for use in uses:
        action = actions.get(use["action"])
        if action is None:
            raise ValueError(f"MEASURED gives {operation} {use['action']}, for which the file records no type")
        if use["of"] not in types:
            raise ValueError(f"MEASURED gives {operation} {use['action']} on what a {use['of']} holds, no type its "
                             f"action acts on")
        variable = next(iter(set(VARIABLE_RE.findall(action["format"])) - KNOWN_VARIABLES))
        member = record_member(models, use["of"], namespace_of(use["action"]) + variable)
        if member is None:
            raise ValueError(f"MEASURED gives {operation} {use['action']} on what a {use['of']} holds, which "
                             f"botocore's record of it has no {namespace_of(use['action'])}{variable} member for")
        found.append({"action": use["action"], "of": use["of"], "member": member, "endpoint": endpoint})
    return found


def load_statement_sources() -> dict[str, dict]:
    """Each operation's vendored statement source (`STATEMENT_SOURCES`)."""
    return {operation: json.loads(path.read_text(encoding="utf-8")) for operation, path in STATEMENT_SOURCES.items()}


def source_actions(sources: dict[str, dict]) -> list[str]:
    """Every action the statement sources' policies name."""
    return sorted({action for source in sources.values() for row in source["statements"].values()
                   for statement in row["policy"] for action in statement["Action"]})


def statement_actions(documents: list[dict], sources: dict[str, dict]) -> dict[str, list[str]]:
    """For each action the statement sources name, the resource types the Service Reference gives it."""
    by_name = {document["Name"]: document for document in documents}
    found: dict[str, list[str]] = {}
    for action in source_actions(sources):
        namespace, name = action.split(":", 1)
        listed = next((entry for entry in by_name.get(namespace, {}).get("Actions", []) if entry["Name"] == name),
                      None)
        if listed is None:
            raise ValueError(f"a statement source names {action}, which the Service Reference does not list")
        found[action] = sorted(resource["Name"] for resource in listed.get("Resources") or [])
    return found


def example_type(arn: str, formats: dict[str, str], statement: str) -> tuple[str, dict[str, str]]:
    """The one resource type whose ARN format an example ARN matches, and the value it gives each variable of its own."""
    matches = []
    for kind, arn_format in formats.items():
        variables = VARIABLE_RE.findall(arn_format)
        pieces = VARIABLE_RE.split(arn_format)[::2]
        match = re.fullmatch("([^:/]+)".join(re.escape(piece) for piece in pieces), arn)
        if match:
            matches.append((kind, {variable: match.group(index + 1) for index, variable in enumerate(variables)
                                   if variable not in KNOWN_VARIABLES}))
    if len(matches) != 1:
        raise ValueError(f"the policy the page gives {statement} names {arn}, which is "
                         f"{'no' if not matches else 'more than one'} resource type's ARN format")
    return matches[0]


def statement_requests(source: dict, actions: dict[str, list[str]],
                       formats: dict[str, dict[str, str]]) -> dict[str, list[dict]]:
    """The requests each statement of a vendored source makes: each action of its example policy, on each resource of
    the same policy statement of a type the Service Reference gives the action (`actions`), as the type whose format
    the example ARN matches, its variables filled from what the page says the example's names stand for and open
    where the example has a wildcard. An action whose name says it is a partition action is marked `partitioned` in a
    statement whose sentence says a table without partitions does not need those."""
    examples = source["examples"]
    found: dict[str, list[dict]] = {}
    for statement, row in source["statements"].items():
        unpartitioned = UNPARTITIONED_RE.search(row["text"]) is not None
        requests: dict[str, list[dict]] = {}
        for policy_statement in row["policy"]:
            for action in policy_statement["Action"]:
                namespace = namespace_of(action)
                resources = requests.setdefault(action, [])
                for arn in policy_statement["Resource"]:
                    kind, values = example_type(arn, formats.get(namespace, {}), statement)
                    if kind not in actions[action]:
                        continue
                    fill = {}
                    for variable, value in values.items():
                        if value == "*":
                            continue
                        if value not in examples:
                            raise ValueError(f"the policy the page gives {statement} names {arn}, whose {value} the "
                                             f"page does not say stands for anything")
                        fill[variable] = examples[value]
                    resource = {"type": kind, "fill": dict(sorted(fill.items()))}
                    if resource not in resources:
                        resources.append(resource)
        entries = []
        for action, resources in requests.items():
            if not resources:
                raise ValueError(f"the policy the page gives {statement} grants {action} on no resource of a type "
                                 f"the Service Reference gives it")
            entry = {"action": action, "resources": resources}
            if unpartitioned and "Partition" in action.split(":", 1)[1]:
                entry["partitioned"] = True
            entries.append(entry)
        found[statement] = entries
    return found


def statements_for(sources: dict[str, dict], actions: dict[str, list[str]],
                   formats: dict[str, dict[str, str]]) -> dict[str, dict[str, list[dict]]]:
    """Each operation's statements and the requests each makes (`statements`)."""
    return {operation: statement_requests(source, actions, formats) for operation, source in sorted(sources.items())}


def load_path_sources() -> dict[str, dict]:
    """Each namespace's vendored path source (`PATH_SOURCES`)."""
    return {namespace: json.loads(path.read_text(encoding="utf-8")) for namespace, path in PATH_SOURCES.items()}


def load_route_actions(source: Path = AUTHORIZED_ACTIONS) -> dict[str, dict]:
    """What the authorized-actions file checks a route of an operation as, beside the operation's own action: each
    namespace's operations authorized by method (`byMethod`), and its actions by namespace and client (`byModel`)."""
    document = json.loads(source.read_text(encoding="utf-8"))
    return {"byMethod": {namespace: set(names) for namespace, names in document.get("authorizedByMethod", {}).items()},
            "byModel": document.get("authorizedByModel", {})}


def guide_formats(namespace: str, source: dict) -> dict[str, str]:
    """Each row of the vendored ARN reference naming a resource of the namespace, as `<resource> (<heading>)`, its ARN
    spelled as the Service Reference spells a format: the reference gives a REST API's `Stage` and an HTTP API's under
    two headings, and execute-api's ARNs beside API Gateway's."""
    formats: dict[str, str] = {}
    for table in source["tables"]:
        for row in table["rows"]:
            parts = row["arn"].split(":", 5)
            if len(parts) == 6 and parts[2] == namespace:
                formats[f"{row['resource']} ({table['heading']})"] = re.sub(
                    r"\{([^{}]+)}",
                    lambda match: "${" + GUIDE_VARIABLES.get(match.group(1), _words(match.group(1))) + "}", row["arn"])
    return dict(sorted(formats.items()))


def _words(name: str) -> str:
    """A label's or a variable's words run together, each capitalized: `stage-name` and `stage_name` are `StageName`."""
    return "".join(word[:1].upper() + word[1:] for word in re.split(r"[-_]", name) if word)


def _route_label(segment: str) -> str | None:
    match = ROUTE_LABEL_RE.fullmatch(segment)
    return match.group("label") if match else None


def _format_path(arn: str) -> list[str] | None:
    """The segments of an ARN format's resource when it is a path; None for another."""
    parts = arn.split(":", 5)
    return [segment for segment in parts[5].split("/") if segment] if len(parts) == 6 and parts[5].startswith("/") \
        else None


def _dropped(segments: list[str], arn: str) -> int | None:
    """How many leading segments of a route's path a format's resource path leaves out, the rest being that path: a
    literal for each literal and one variable for each label. None when it is not, or a segment it leaves out is a
    label."""
    template = _format_path(arn)
    if not template:
        return None
    dropped = len(segments) - len(template)
    if dropped < 0 or any(_route_label(segment) for segment in segments[:dropped]):
        return None
    for segment, part in zip(segments[dropped:], template):
        if _route_label(segment) is not None:
            if not ONE_VARIABLE_SEGMENT_RE.fullmatch(part):
                return None
        elif segment != part:
            return None
    return dropped


def routed_resources(namespace: str, operations: dict[str, dict], formats: dict[str, str], source: dict,
                     models: dict[str, dict], actions: dict[str, dict]) -> tuple[dict[str, list[dict]], dict[str, str]]:
    """The resource each route of the namespace's operations names where AWS authorizes it as its HTTP method
    (`PATH_SOURCES`), by operation, and the ARN formats of those the Service Reference gives no type, by name.

    A route is authorized as its method when it is checked as `<namespace>:<METHOD>`: as its client's action
    (`authorizedByModel`), as an operation authorized by method (`authorizedByMethod`), or as the action the reference
    gives the operation. It acts on the resource whose format, of the reference's types or the guide's rows, is its own
    path: a literal for each literal, one variable for each label, each label's member filling its variable, the
    leading segments every published path of its client leaves out (API Gateway v2's `/v2`) left out. A reference type
    comes before a guide row of the same path, whose name is `<resource> (<heading>)`. A route no published path
    matches acts on the guide's ARN format with its own path as the resource path (named `<path>
    (resource-path-specifier)`), unless a label's member holds the ARN of what it acts on (`...Arn`), which the rules
    read then, as for an operation whose routes are not authorized by method."""
    verbs = set(source["action"]["actions"])
    guide = guide_formats(namespace, source)
    published = sorted(formats.items()) + sorted(guide.items())
    routes = []
    for operation, upstream in sorted(operations.items()):
        name = operation.split(":", 1)[1]
        by_method = set()
        for client, model in sorted(models.items()):
            spec = model["operations"].get(name)
            if spec is None or "http" not in spec:
                continue
            method = spec["http"]["method"].upper()
            action = actions["byModel"].get(namespace, {}).get(client, {}).get(name) or (
                f"{namespace}:{method}" if name in actions["byMethod"].get(namespace, set()) else upstream["action"])
            routed = method in verbs and action == f"{namespace}:{method}"
            by_method.add(routed)
            if not routed:
                continue
            members = {member.get("locationName", member_name): member_name for member_name, member in
                       (model["shapes"][spec["input"]["shape"]].get("members", {}) if "input" in spec else {}).items()
                       if member.get("location") == "uri"}
            path = [segment for segment in spec["http"]["requestUri"].split("?", 1)[0].split("/") if segment]
            routes.append((operation, client, path, members))
        if len(by_method) > 1:
            raise ValueError(f"{operation} is authorized as its method on one client's route and not another's")
    matched: dict[int, tuple[int, str, str]] = {}
    prefixes: dict[str, set[tuple[str, ...]]] = {}
    for index, (operation, client, path, _) in enumerate(routes):
        found = [(dropped, kind, arn) for kind, arn in published if (dropped := _dropped(path, arn)) is not None]
        if not found:
            continue
        least = min(dropped for dropped, _, _ in found)
        best = [(kind, arn) for dropped, kind, arn in found if dropped == least]
        if len({"${Account}" in arn for _, arn in best}) > 1:
            raise ValueError(f"{operation}'s route {'/'.join(path)} is a path the reference and the guide give with an "
                             f"account and without one: {sorted(kind for kind, _ in best)}")
        chosen = [entry for entry in best if entry[0] in formats] or best
        if len({arn for _, arn in chosen}) > 1:
            raise ValueError(f"{operation}'s route {'/'.join(path)} is the path of several formats: "
                             f"{sorted(kind for kind, _ in chosen)}")
        matched[index] = (least, *chosen[0])
        prefixes.setdefault(client, set()).add(tuple(path[:least]))
    for client, left in sorted(prefixes.items()):
        if len(left) > 1:
            raise ValueError(f"{namespace}'s published paths leave out different leading segments of {client}'s "
                             f"routes: {sorted(left)}")
    resources: dict[str, list[dict]] = {}
    arns: dict[str, str] = {}
    unrouted: set[str] = set()
    for index, (operation, client, path, members) in enumerate(routes):
        if index in matched:
            least, kind, arn = matched[index]
            template = _format_path(arn)
        else:
            left = next(iter(prefixes.get(client, {()})))
            least = len(left) if tuple(path[:len(left)]) == left else 0
            labels = [label for segment in path[least:] if (label := _route_label(segment))]
            if any(re.search(r"Arns?$", members.get(label, "")) for label in labels):
                unrouted.add(operation)
                continue
            match = RESOURCE_PATH_FORMAT_RE.fullmatch(source["resource"]["format"])
            if match is None or match.group("namespace") != namespace:
                raise ValueError(f"the guide gives {namespace}'s resources no ARN format of a resource path: "
                                 f"{source['resource']['format']}")
            template = [("${" + _words(label) + "}") if (label := _route_label(segment)) else segment
                        for segment in path[least:]]
            kind = "/" + "/".join(path[least:]) + " (resource-path-specifier)"
            arn = f"arn:${{Partition}}:{namespace}:${{Region}}::/" + "/".join(template)
            if len(set(template)) != len(template):
                raise ValueError(f"{operation}'s route {'/'.join(path)} gives two labels one variable")
        fill: dict[str, str] = {}
        for segment, part in zip(path[least:], template):
            label = _route_label(segment)
            if label is None:
                continue
            if label not in members:
                raise ValueError(f"{operation}'s route {'/'.join(path)} has a label {label} no member is carried in")
            fill[ONE_VARIABLE_SEGMENT_RE.fullmatch(part).group("variable")] = members[label]
        arns[kind] = arn
        resource = {"type": kind, "fill": fill}
        if resource not in resources.setdefault(operation, []):
            resources[operation].append(resource)
    for operation in unrouted:
        resources.pop(operation, None)
    used = {resource["type"] for named in resources.values() for resource in named}
    needed = {kind: arns[kind] for kind in sorted(used) if kind not in formats}
    return dict(sorted(resources.items())), needed


def namespace_routed(operations: dict[str, dict], formats: dict[str, dict[str, str]], namespaces: dict[str, list[str]],
                     data: Path) -> tuple[dict[str, list[dict]], dict[str, dict[str, str]]]:
    """`routed_resources` for each namespace with a path source the file reads: the resources by operation, and the
    formats they need beside the reference's, by namespace."""
    resources: dict[str, list[dict]] = {}
    needed: dict[str, dict[str, str]] = {}
    sources = load_path_sources()
    actions = load_route_actions() if sources else {}
    for namespace, source in sorted(sources.items()):
        if namespace not in namespaces:
            continue
        found, kinds = routed_resources(namespace, {operation: value for operation, value in operations.items()
                                                    if namespace_of(operation) == namespace},
                                        formats.get(namespace, {}), source,
                                        load_models(data, set(namespaces[namespace])), actions)
        resources.update(found)
        if kinds:
            needed[namespace] = kinds
    return resources, needed


_REQUIRED_TYPES: dict | None = None


def required_types(action: str, types: list[str]) -> set[str]:
    """The resource types the vendored Service Authorization Reference (`REQUIRED_TYPE_SOURCE`) marks the action as
    requiring, each one of `types`, the Service Reference's; empty for an action of a namespace it is not granted."""
    global _REQUIRED_TYPES
    if _REQUIRED_TYPES is None:
        _REQUIRED_TYPES = json.loads(REQUIRED_TYPE_SOURCE.read_text(encoding="utf-8"))["namespaces"]
    namespace, name = action.split(":", 1)
    cells = _REQUIRED_TYPES.get(namespace, {}).get("actions", {}).get(name, [])
    required = {cell.removesuffix("*") for cell in cells if cell.endswith("*")}
    if required - set(types):
        raise ValueError(f"{action}: the Service Authorization Reference requires {sorted(required - set(types))}, "
                         f"which the Service Reference does not give it")
    return required


def load_permission_source(namespace: str) -> dict | None:
    """The namespace's vendored permission source (`PERMISSION_SOURCES`), or None for a namespace it is not granted."""
    path = PERMISSION_SOURCES.get(namespace)
    return None if path is None else json.loads(path.read_text(encoding="utf-8"))


def _named_kind(operation: str, phrase: str, kinds: list[str]) -> str:
    """The one of `kinds` a vendored phrase names by its name, a word of it ("KMS key used to decrypt" names `key`)."""
    words = {word.lower() for word in re.findall(r"[A-Za-z]+", phrase)}
    named = [kind for kind in kinds if kind.lower() in words]
    if len(named) != 1:
        raise ValueError(f"{operation}: the vendored permissions name '{phrase}', which names {named or 'none'} of the "
                         f"types {kinds} the Service Reference gives its action")
    return named[0]


def _request_shapes(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The botocore shape each top-level member of the operation's request holds, in the request's order."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec and "input" in spec:
            for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
                found.setdefault(name, member["shape"])
    return found


def _held_shapes(models: dict[str, dict], member: str) -> set[str]:
    """The botocore shapes the service's structures hold a member of that name in (KMS's `KeyId`, a `KeyIdType`)."""
    return {held["shape"] for model in models.values() for shape in model["shapes"].values()
            if shape.get("type") == "structure" for name, held in shape.get("members", {}).items() if name == member}


def permission_resources(operation: str, checked_as: str, resources: list[dict], row: dict | None,
                         known: dict[str, list[str]], formats: dict[str, str],
                         models: dict[str, dict]) -> tuple[list[dict], dict | None]:
    """The resources of an operation whose namespace's vendored permissions (`PERMISSION_SOURCES`) list those its
    actions are each checked on, and those actions when the list names others than `checked_as`, the one the operation
    is checked as otherwise, each with the
    resource types the Service Reference gives it (`known`); the resources as they are and none when its row lists
    none. Each item of the row's list is a resource of the type it names by its name, checked as the action it names
    or else the row's one action, or no resource when it names none ("kms:CreateKey in an IAM policy in the replica
    Region": that action on `*`). The members that may name a type are those the rules read it from and those holding
    the botocore shape the service's records hold the type's variable in (`TargetKeyId`, `SourceKeyId` and
    `DestinationKeyId` hold the `KeyIdType` of a key's `KeyId`): an item saying what its resource is used for takes the
    member botocore documents as used for that, or for its re- form ("the KMS key used to decrypt", ReEncrypt's
    `SourceKeyId`, "the KMS key that KMS will use to decrypt the ciphertext"; "used to encrypt", its
    `DestinationKeyId`, "the KMS key that is used to reencrypt the data"), any other the next of them in the request's
    order, and one no member is left for is reached through the member of another of the row's resources whose botocore
    record holds a member of such a shape (UpdateAlias's currently associated key, through its `AliasName`:
    `AliasListEntry` holds `AliasName` and `TargetKeyId`), which the reader asks the service for. They replace what the
    rules read of the types the list names; an operation checked as several actions is checked on its list's resources
    alone, each as its action (`action`)."""
    if row is None or not row["items"]:
        return resources, None
    name = operation.split(":", 1)[1]
    items = []
    for item in row["items"]:
        match = PERMISSION_ITEM_RE.match(item)
        action = match.group("action") or (row["actions"][0] if len(row["actions"]) == 1 else None)
        if action not in known:
            raise ValueError(f"{operation}: the vendored permissions check '{item}' as {action}, which the Service "
                             f"Reference does not have")
        named = NAMED_RESOURCE_RE.match(match.group("rest"))
        phrase = named.group("phrase") if named else None
        items.append((action, None if phrase is None else _named_kind(operation, phrase, known[action]), phrase))
    actions = list(dict.fromkeys([*row["actions"], *(action for action, _, _ in items)]))
    several = set(actions) != {checked_as}
    shapes = _request_shapes(models, name)
    docs = {member: _lead(_member_documentation(models, name, member)) for member in shapes}
    named_kinds = {kind for _, kind, _ in items if kind}
    taken: list[str] = []
    rows: list[dict] = []
    for action, kind, phrase in items:
        if kind is None:
            continue
        variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
        held = _held_shapes(models, variables[0]) if len(variables) == 1 else set()
        candidates = list(dict.fromkeys(
            [path for resource in resources if resource.get("type") == kind and len(resource.get("fill") or {}) == 1
             for path in resource["fill"].values()]
            + [member for member, shape in shapes.items() if shape in held]))
        free = [member for member in candidates if member not in taken]
        used = USED_TO_RE.search(phrase.lower())
        if used:
            verb = used.group("verb")
            free = [member for member in free if (found := USED_TO_RE.search(docs[member]))
                    and found.group("verb") in (verb, "re" + verb)]
            if len(free) != 1:
                raise ValueError(f"{operation}: the vendored permissions name '{phrase}', which {len(free)} members "
                                 f"of the request are documented as used for")
        resource: dict | None = None
        if free:
            taken.append(free[0])
            resource = {"type": kind, "fill": {variables[0]: free[0]}}
        else:
            # Reached through another resource of the row whose record holds the type: an alias's key.
            inputs = {spec["input"]["shape"] for model in models.values() for spec in model["operations"].values()
                      if "input" in spec}
            for other in rows:
                member = next(iter(other.get("fill", {}).values()), None)
                if other.get("type") != kind and member and any(
                        record_name not in inputs and record.get("type") == "structure"
                        and member in record.get("members", {})
                        and any(held_member["shape"] in held for held_member in record["members"].values())
                        for model in models.values() for record_name, record in model["shapes"].items()):
                    resource = {"types": [kind], "through": member}
                    break
        if resource is None:
            raise ValueError(f"{operation}: the vendored permissions name '{phrase}', which no member of the request "
                             f"names or reaches")
        rows.append(dict(resource, action=action) if several else resource)
    if not several:
        return [resource for resource in resources if not set(_kinds(resource)) & named_kinds] + rows, None
    return rows, {action: known[action] for action in actions}


def with_from_metadata(resources: list[dict], operation: str, sentences: list[dict],
                       models: dict[str, dict]) -> list[dict]:
    """The resources, and the type a member names that the vendored permissions (`PERMISSION_SOURCES`) say its service
    gets from metadata another member holds when the request leaves it out ("If you used a symmetric encryption KMS
    key, AWS KMS can get the KMS key from metadata that it adds to the symmetric ciphertext blob", of Decrypt's
    `KeyId` and ReEncrypt's `SourceKeyId`), named then through that other member, the member the sentence's last words
    name (`CiphertextBlob`), which the reader asks the service for. A resource checked as an action of its own keeps
    it."""
    name = operation.split(":", 1)[1]
    given = list(_request_shapes(models, name))
    found = list(resources)
    for sentence in sentences:
        match = FROM_METADATA_RE.search(sentence["sentence"])
        words = match.group("phrase").split() if match else []
        holder = next((joined for start in range(len(words))
                       if (joined := "".join(word[:1].upper() + word[1:] for word in words[start:])) in given), None)
        if holder is None or sentence["parameter"] not in given:
            raise ValueError(f"{operation}: the vendored sentence of its {sentence['parameter']} names no member of "
                             f"the request a resource is read from: '{sentence['sentence']}'")
        for resource in resources:
            fill = resource.get("fill") or {}
            if list(fill.values()) != [sentence["parameter"]] or "type" not in resource:
                continue
            _named_kind(operation, match.group("thing"), [resource["type"]])
            through = {"types": [resource["type"]], "through": holder, "when": {sentence["parameter"]: [None]}}
            found.append(dict(through, action=resource["action"]) if "action" in resource else through)
    return found


def named_resources(identifiers: list[dict], formats: dict[str, str]) -> set[str]:
    """The types the vendored permissions (`PERMISSION_SOURCES`) give an identifier of that is another's ARN's resource
    ("alias/{alias-name}", the resource of "arn:{partition}:kms:{region}:{account-id}:alias/{alias-name}"): a type
    whose format's resource is that identifier's literal and one variable (`alias/${Alias}`). A member naming one gives
    that resource whole, `alias/ExampleAlias`, which the reader completes as the ARN (`partialArn`), not the
    variable's value alone."""
    arns = [identifier["format"] for identifier in identifiers if identifier["format"].startswith("arn:")]
    found: set[str] = set()
    for identifier in identifiers:
        name = identifier["format"]
        literal = name.split("{", 1)[0]
        if not literal or name.startswith("arn:") or not any(arn.split(":", 5)[5:] == [name] for arn in arns):
            continue
        found |= {kind for kind, arn in formats.items() if ONE_VARIABLE_RE.match(_resource(arn))
                  and _resource(arn).split("${", 1)[0] == literal}
    return found


def with_map_keys(resources: list[dict], types: list[str], formats: dict[str, str], models: dict[str, dict],
                  operation: str) -> list[dict]:
    """The resources, and a type no rule names that a top-level map of the request names by its keys: one whose botocore
    key shape is named after the type and is a shape the service's structures hold a member named as the type's one
    variable in (DynamoDB's BatchGetItem `RequestItems`, a `BatchGetRequestMap` keyed by `TableArn`, the shape
    `Put.TableName` holds), each key filling that variable (`RequestItems{}`). A key of a shape any string takes
    (EMR's `EnvironmentVariables`, keyed by `XmlStringMaxLen256`) names nothing."""
    named = {kind for resource in resources for kind in _kinds(resource)}
    found = list(resources)
    for kind in types:
        variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
        if kind in named or len(variables) != 1:
            continue
        held = _held_shapes(models, variables[0])
        for model in models.values():
            spec = model["operations"].get(operation)
            if spec is None or "input" not in spec:
                continue
            for member, value in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
                shape = model["shapes"][value["shape"]]
                row = {"type": kind, "fill": {variables[0]: f"{member}{{}}"}}
                key = shape.get("key", {}).get("shape", "")
                if shape.get("type") == "map" and key in held and related(stem(key), stem(kind)) and row not in found:
                    found.append(row)
            break
    return found


def item_actions(source: dict, namespace: str) -> dict[str, str]:
    """The action each kind of a transaction's item is checked as, as the vendored transactions page pairs them, each
    sentence naming the kinds and then, in the same order, their operations or actions ("Permissions for Put, Update,
    Delete, and Get actions are governed by the permissions used for the underlying PutItem, UpdateItem, DeleteItem, and
    GetItem operations"; "For the ConditionCheck action, you can use the dynamodb:ConditionCheckItem permission")."""
    found: dict[str, str] = {}
    for sentence in source.get("transactions", {}).get("sentences", []):
        codes = sentence["code"]
        if not codes or len(codes) % 2:
            raise ValueError(f"the vendored transactions sentence '{sentence['text']}' pairs no kinds with actions")
        half = len(codes) // 2
        for kind, action in zip(codes[:half], codes[half:]):
            found[kind] = action if ":" in action else f"{namespace}:{action}"
    return found


def item_resources(operation: str, kinds: dict[str, str], known: dict[str, list[str]], formats: dict[str, str],
                   models: dict[str, dict]) -> list[dict]:
    """The resources of a transaction, each item checked as the action the vendored page gives its kind (`kinds`), on
    the resource of each type that action acts on whose one variable a member of that name in the item fills: a
    TransactWriteItems' `TransactItems[].Put.TableName` as `dynamodb:PutItem`, its `TransactItems[].ConditionCheck.
    TableName` as `dynamodb:ConditionCheckItem`. The request's items are the elements of a top-level list whose botocore
    structure holds members named as those kinds. None for a request of no such list."""
    name = operation.split(":", 1)[1]
    rows: list[dict] = []
    for model in models.values():
        spec = model["operations"].get(name)
        if spec is None or "input" not in spec:
            continue
        for member, held in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            listed = model["shapes"][held["shape"]]
            element = model["shapes"].get(listed.get("member", {}).get("shape", ""), {})
            if listed.get("type") != "list" or element.get("type") != "structure":
                continue
            for kind, item in element.get("members", {}).items():
                action = kinds.get(kind)
                fields = model["shapes"][item["shape"]].get("members", {})
                for type_ in (known.get(action) or []) if action else []:
                    variables = [v for v in VARIABLE_RE.findall(formats.get(type_, "")) if v not in KNOWN_VARIABLES]
                    if len(variables) == 1 and variables[0] in fields:
                        row = {"type": type_, "fill": {variables[0]: f"{member}[].{kind}.{variables[0]}"},
                               "action": action}
                        if row not in rows:
                            rows.append(row)
        break
    return rows


def statement_resources(operation: str, source: dict, known: dict[str, list[str]], formats: dict[str, str],
                        models: dict[str, dict]) -> list[dict]:
    """The resources of a request carrying statements in the language the vendored PartiQL page names, each checked as
    the action the page gives its verb, on the types of its "table or index" the action acts on (`statement`, `verb`):
    the reader asks the service for each statement's verb and the names of what it acts on, as its parser reads them,
    and checks it as the action named after that verb (`dynamodb:PartiQLSelect` for a SELECT). The statements are the
    request's members, top-level or a field of each element of a top-level list, holding the botocore shape named as
    that language and `Statement` (`PartiQLStatement`: ExecuteStatement's `Statement`, BatchExecuteStatement's
    `Statements[].Statement`). Each variable of those types takes the name the service gives what the statement acts on
    by the variable's stem (`names`: a table's `TableName` its `table`, an index's `IndexName` its `index`). None for a
    request carrying none."""
    name = operation.split(":", 1)[1]
    items = source.get("partiql", {}).get("items", [])
    languages = {match.group(1) for item in items if (match := re.search(r"\busing (\w+) for\b", item["text"]))}
    if len(languages) != 1:
        raise ValueError(f"the vendored PartiQL page names {sorted(languages)} as its language, not one")
    shape_name = languages.pop() + "Statement"
    paths: list[str] = []
    for model in models.values():
        spec = model["operations"].get(name)
        if spec is None or "input" not in spec:
            continue
        for member, held in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            if held["shape"] == shape_name:
                paths.append(member)
            listed = model["shapes"][held["shape"]]
            element = model["shapes"].get(listed.get("member", {}).get("shape", ""), {})
            if listed.get("type") == "list":
                paths += [f"{member}[].{field}" for field, value in element.get("members", {}).items()
                          if value["shape"] == shape_name]
        break
    rows: list[dict] = []
    for path in paths:
        for item in items:
            action = next((code for code in item["code"] if ":" in code), None)
            words = re.search(r"\bon the ([a-z ]+?)\.?$", item["text"])
            if action not in known or words is None or not action.split(":", 1)[1].startswith(shape_name[:-9]):
                raise ValueError(f"{operation}: the vendored PartiQL permission '{item['text']}' names no action of "
                                 f"the namespace on its resources")
            named = set(re.findall(r"[a-z]+", words.group(1)))
            kinds = [kind for kind in known[action] if kind in named and kind in formats]
            names = {variable: stem(variable) for kind in kinds for variable in VARIABLE_RE.findall(formats[kind])
                     if variable not in KNOWN_VARIABLES}
            rows.append({"types": kinds, "statement": path,
                         "verb": action.split(":", 1)[1][len(shape_name) - 9:].upper(), "names": names,
                         "action": action})
    return rows


def _name_words(phrase: str) -> list[str]:
    """The words of a phrase or a member's name, lowercased, a name split where its case changes (`UserName`: `user`,
    `name`; `VirtualMFADeviceName`: `virtual`, `mfa`, `device`, `name`)."""
    return [word.lower() for word in re.findall(r"[A-Z]+(?![a-z])|[A-Z]?[a-z0-9]+", phrase)]


def path_types(arns: dict, formats: dict[str, str]) -> dict[str, tuple[list[str], str]]:
    """The types whose ARN the vendored identifiers write with a name and its path, each with the words of that name and
    the word naming the path: a type whose format's resource is a literal and one variable, as a line of the
    identifiers' syntax of its namespace writes it with a variable ending `-with-path` ("arn:aws:iam::{account}:user/
    {user-name-with-path}", the type `user` of `user/${UserNameWithPath}`: `user`, `name` and `path`)."""
    found: dict[str, tuple[list[str], str]] = {}
    for line in arns.get("syntax", []):
        parts = line.split(":", 5)
        match = re.fullmatch(r"(?P<literal>[^{]*)\{(?P<name>[a-z0-9-]+)-with-(?P<path>[a-z]+)}", parts[5]) \
            if len(parts) == 6 else None
        if match is None:
            continue
        for kind, arn in formats.items():
            resource = _resource(arn)
            if arn.split(":")[2] == parts[2] and ONE_VARIABLE_RE.match(resource) \
                    and resource.split("${", 1)[0] == match.group("literal"):
                found[kind] = (match.group("name").split("-"), match.group("path"))
    return found


def _named_member(words: list[str], members: list[str]) -> str | None:
    """The one member whose name holds every word given (`user`, `name`: `UserName`; `virtual`, `device`, `name`:
    `VirtualMFADeviceName`, beside its `mfa`) and is not named after another (`New...`)."""
    named = [member for member in members
             if set(words) <= set(_name_words(member)) and "new" not in _name_words(member)]
    return named[0] if len(named) == 1 else None


def with_paths(resources: list[dict], operation: str, arns: dict, formats: dict[str, str],
               models: dict[str, dict]) -> list[dict]:
    """The resources, a type the vendored identifiers write with a name and its path (`path_types`) taking both from
    a request giving its path, as the identifiers say the API's creates do ("If you use the IAM API or AWS Command Line
    Interface (AWS CLI) to create IAM resources, you can add an optional path"): its variable is the name the member
    holding every word of the name gives (`UserName`), after the path the member named as the path gives (`Path`,
    `path`), and is never looked up, the resource being one the request makes. CreateUser's `UserName` and `Path` name
    `user/<path><name>`."""
    if not any(re.search(r"\bcreate\b.*\bpath\b", sentence) for sentence in arns.get("paths", [])):
        return resources
    members = list(_request_shapes(models, operation.split(":", 1)[1]))
    kinds = path_types(arns, formats)
    found = []
    for resource in resources:
        kind = resource.get("type")
        variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
        if kind not in kinds or len(variables) != 1 or "fill" not in resource \
                or {"find", "default", "when", "unless"} & set(resource):
            found.append(resource)
            continue
        words, path = kinds[kind]
        path_member = path[:1].upper() + path[1:]
        name = _named_member(words, members)
        if path_member not in members or name is None:
            found.append(resource)
            continue
        found.append(dict(resource, fill={variables[0]: name}, path=path_member))
    return found


def with_renames(resources: list[dict], operation: str, sentences: list[dict], arns: dict, formats: dict[str, str],
                 models: dict[str, dict]) -> list[dict]:
    """The resources, and the one an operation renames or moves, as its vendored API reference says the caller needs
    permission on both ("must have permission to change the role group with the old name and the new name"; "on both
    the source object and the target object"): of a type the identifiers write with a name and its path, it is the
    resource its request names by the member filling that type's variable (`renames`, UpdateGroup's `GroupName`), with
    the name the member of that name after `New` gives (`NewGroupName`) and the path the one named as the path after
    `New` does (`NewPath`), each where the request gives it, and the resource's own otherwise."""
    if not sentences:
        return resources
    members = list(_request_shapes(models, operation.split(":", 1)[1]))
    kinds = path_types(arns, formats)
    found = list(resources)
    for resource in resources:
        kind = resource.get("type")
        fill = resource.get("fill") or {}
        if kind not in kinds or len(fill) != 1 or set(resource) - {"type", "fill"}:
            continue
        (variable, member), = fill.items()
        renamed, moved = "New" + member, "New" + kinds[kind][1][:1].upper() + kinds[kind][1][1:]
        if renamed not in members or moved not in members:
            raise ValueError(f"{operation}: its vendored API reference says it renames its {kind}, which its request "
                             f"gives no {renamed} and {moved} of")
        found.append({"type": kind, "fill": {variable: renamed}, "path": moved, "renames": member})
    if found == resources:
        raise ValueError(f"{operation}: its vendored API reference says it renames a resource its request names no "
                         f"type of the identifiers' with a path of")
    return found


def with_signing_user(resources: list[dict], operation: str, types: list[str], sentences: list[dict],
                      models: dict[str, dict]) -> list[dict]:
    """The resources, and the user who signs the request, which the reader asks the service holding the key it is
    signed with for (`signer`), as the operation's vendored API reference says it acts on ("If you do not specify a user
    name, IAM determines the user name implicitly based on the AWS access key ID used to sign the request to this
    operation"; "If this value is not specified, then the operation assumes the user whose credentials are used to
    call the operation"; "Changes the password of the IAM user who is calling this operation"): `when` the request
    leaves out the member the sentence is of, or that its words name (`UserName`, "user name"), and always where they
    name none. The type is the one of the action's the sentences name by a word of theirs."""
    if not sentences:
        return resources
    members = list(_request_shapes(models, operation.split(":", 1)[1]))
    named: set[str | None] = set()
    kinds: set[str] = set()
    for sentence in sentences:
        words = re.findall(r"[A-Za-z]+", sentence["sentence"])
        member = sentence["parameter"] if sentence["parameter"] in members else next(
            (joined for start in range(len(words)) for end in range(start + 1, min(start + 3, len(words)) + 1)
             if (joined := "".join(word[:1].upper() + word[1:] for word in words[start:end])) in members), None)
        named.add(member)
        kinds |= {kind for kind in types if kind in set(_name_words(sentence["sentence"]))}
    if len(named) != 1 or len(kinds) != 1:
        raise ValueError(f"{operation}: its vendored API reference says it acts on the user signing it, when the "
                         f"request leaves out {sorted(named, key=str)}, of the types {sorted(kinds)}, not one of each")
    member = named.pop()
    return resources + [{"types": [kinds.pop()], "signer": True, **({"when": {member: [None]}} if member else {})}]


def with_same_as_arn(resources: list[dict], operation: str, types: list[str], sentences: list[dict],
                     formats: dict[str, str], models: dict[str, dict]) -> list[dict]:
    """The resources, and those a member names whose vendored API reference says its value is the resource's ARN ("For
    virtual MFA devices, the serial number is the same as the ARN", of TagMFADevice's `SerialNumber`), of each type the
    action acts on, each ARN read by its format."""
    members = list(_request_shapes(models, operation.split(":", 1)[1]))
    found = list(resources)
    for sentence in sentences:
        if sentence["parameter"] not in members:
            raise ValueError(f"{operation}: its vendored API reference says {sentence['parameter']} holds an ARN, "
                             f"which its request has no member of")
        row = {"types": [kind for kind in types if kind in formats], "arn": sentence["parameter"]}
        if row["types"] and row not in found:
            found.append(row)
    return found


def with_formats(resources: list[dict], operation: str, types: list[str], sentences: list[dict],
                 models: dict[str, dict]) -> list[dict]:
    """The resources, and the type a member's vendored API reference gives the format of its identifier in, whose
    variables are named by words a type the action acts on is named by ("This identifier is returned by the
    DeleteServiceLinkedRole operation in the format task/aws-service-role/<service-principal-name>/<role-name>/
    <task-uuid>", of GetServiceLinkedRoleDeletionStatus's `DeletionTaskId`, naming its `role`), named through that
    member, which the reader asks the service for, where the rules read no resource of that type."""
    members = list(_request_shapes(models, operation.split(":", 1)[1]))
    found = list(resources)
    for sentence in sentences:
        given = re.search(r"\bin the format (?P<format>\S+)", sentence["sentence"])
        placeholders = {word for name in re.findall(r"<([a-z-]+)>", given.group("format") if given else "")
                        for word in name.split("-")}
        kinds = [kind for kind in types if kind in placeholders
                 and not any(kind in _kinds(resource) for resource in resources)]
        if sentence["parameter"] in members and kinds:
            found.append({"types": kinds, "through": sentence["parameter"]})
    return found


def foreign_arn_members(models: dict[str, dict], operation: str, formats: dict[str, str]) -> set[str]:
    """The top-level members of the operation's request whose botocore pattern is the ARN of a service other than the
    namespace's (Route 53 Resolver's CreateOutpostResolver `OutpostArn`, `^arn:aws([a-z-]+)?:outposts:...`): they hold
    no resource of the namespace, whatever their name shares with one of its types, so the rules read the request
    without them."""
    services = {fmt.split(":")[2] for fmt in formats.values()}
    return {member for member, patterns in string_patterns(models, operation).items()
            if any(pattern and (service := PATTERN_SERVICE_RE.match(pattern)) and service.group(1) not in services
                   for pattern in patterns)}


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
                                                                          member_shapes(models, name),
                                                                          alias_members(models, name),
                                                                          foreign_arn_members(models, name, formats)),
                                                            formats, types, members),
                                            types, formats, members, found_keys(models, formats),
                                            requested_members(models)),
                                 types, members, through_members(models, formats))
        resources = with_documented(operation, types, formats, models, resources)
    resources = with_nested_record_find(resources, types, formats, members, models)
    resources = with_find_scope(resources, models, name, formats)
    resources = with_typed_targets(resources, formats, typed_target_fields(models, name))
    resources = with_integer_members(resources, formats, models, name)
    resources = with_minted_names(operation, resources, formats, models)
    optional = optional_members(models, name)
    resources = with_absent_arn(resources, formats, optional)
    resources = with_absent_account(resources, formats, account_members(models, name), optional)
    resources = with_map_fields(resources, types, formats, map_fields(models, name))
    resources = with_account_variables(resources, formats, account_members(models, name), region_members(models, name))
    resources = with_account(resources, formats, account_members(models, name))
    resources = with_structure_account(resources, formats, structure_account_members(models, name, types))
    resources = with_structure_names(resources, formats, structure_name_fields(models, name, types))
    resources = with_change_lists(resources, types, formats, members)
    resources = with_type_named_members(resources, types, formats, members, stem(SUBJECT_RE.sub("", name)))
    resources = with_arn_of(resources, formats, name_or_arn_members(models, name),
                            documented_name_or_arn_members(models, name))
    resources = with_default_pairs(resources, formats, optional)
    resources = with_enum_named(operation, resources, formats, models)
    resources = with_subject_through(operation, resources, members, map_fields(models, name), optional, models)
    resources = with_documented_defaults(resources, formats, defaulted_members(models, name))
    resources = with_optional_qualifier(resources, optional)
    resources = with_qualifier(resources, types, formats, members, models, name)
    resources = with_partial_arns(resources, partial_arn_members(models, name, formats))
    resources = with_map_keys(resources, types, formats, models, name)
    # Two rules naming the same resource the same way name it once.
    return [resource for index, resource in enumerate(resources) if resource not in resources[:index]]


def typed_target_fields(models: dict[str, dict], operation: str) -> dict[str, list[str]]:
    """The id fields of each element of a top-level list of structures in the operation's request beside a field of
    the same stem holding the target's type, an enum (`attributes[].targetId` beside `targetType`), by their path, each
    with that enum's values."""
    found: dict[str, list[str]] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or _reading(model) != "all":
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            element = model["shapes"][shape["member"]["shape"]] if shape["type"] == "list" else {}
            fields = element.get("members", {}) if element.get("type") == "structure" else {}
            for field, nested in fields.items():
                kind = fields.get(field[:-2] + "Type") if field.endswith("Id") else None
                enum = model["shapes"][kind["shape"]].get("enum") if kind else None
                if enum and model["shapes"][nested["shape"]]["type"] == "string":
                    found.setdefault(f"{name}[].{field}", list(enum))
    return found


def with_typed_targets(resources: list[dict], formats: dict[str, str], typed: dict[str, list[str]]) -> list[dict]:
    """The resources, a type named by its variables taking the last one no member fills from each element's id field
    whose target type enum has that type alone: ECS's `PutAttributes` attaches each attribute to the container
    instance its `targetId` names, its `targetType` being `container-instance`."""
    found = []
    for resource in resources:
        fill = resource.get("fill")
        variables = [v for v in VARIABLE_RE.findall(formats.get(resource.get("type"), "")) if v not in KNOWN_VARIABLES]
        paths = [path for path, kinds in typed.items() if kinds == [resource.get("type")]]
        if fill is None or "find" in resource or not variables or variables[-1] in fill or len(paths) != 1 \
                or any("[]" in path for path in fill.values()):
            found.append(resource)
            continue
        found.append(dict(resource, fill=dict(sorted({**fill, variables[-1]: paths[0]}.items()))))
    return found


def integer_members(models: dict[str, dict], operation: str) -> dict[str, str]:
    """Unambiguous integer request members keyed by their shape name without a trailing Number."""
    candidates: dict[str, set[str]] = {}
    for model in models.values():
        spec = model["operations"].get(operation, {})
        if "input" not in spec or _reading(model) is None:
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            shape = model["shapes"][member["shape"]]
            if shape["type"] not in ("integer", "long"):
                continue
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
                continue
            variable = member["shape"].removesuffix("Number")
            candidates.setdefault(variable, set()).add(name)
    return {variable: next(iter(names)) for variable, names in candidates.items() if len(names) == 1}


def with_integer_members(resources: list[dict], formats: dict[str, str], models: dict[str, dict],
                         operation: str) -> list[dict]:
    """Fill an otherwise unnamed ARN variable from the integer shape bearing its name."""
    members = integer_members(models, operation)
    result = []
    for resource in resources:
        if "fill" not in resource or resource.get("find"):
            result.append(resource)
            continue
        variables = set(VARIABLE_RE.findall(formats[resource["type"]])) - KNOWN_VARIABLES
        fill = dict(resource["fill"])
        for variable in sorted(variables & members.keys()):
            fill.setdefault(variable, members[variable])
        result.append(dict(resource, fill=dict(sorted(fill.items()))))
    return result


def with_minted_names(operation: str, resources: list[dict], formats: dict[str, str],
                      models: dict[str, dict]) -> list[dict]:
    """The resources, the type a create makes named by the member its documentation gives as the name its service
    follows with characters it mints (`CreateSecret`'s `Name`, of the secret), its one variable filled from it."""
    name = operation.split(":", 1)[1]
    said = [found for model in models.values() for found in documented_minted_suffixes(model, name)]
    found = []
    for resource in resources:
        kind = resource.get("type")
        named = [member for word, _, member in said if kind is not None and named_by_word(word, formats) == kind]
        variables = [v for v in VARIABLE_RE.findall(formats.get(kind or "", "")) if v not in KNOWN_VARIABLES]
        if len(named) == 1 and len(variables) == 1 and resource.get("fill") == {} \
                and not {"find", "when", "unless", "default"} & set(resource):
            resource = dict(resource, fill={variables[0]: named[0]})
        found.append(resource)
    return found


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
    # A pattern whose account is optional, `([0-9]{12}|)`, gives each type word in an alternative of its own, as
    # `:word/` or `:(one|two)/`: Bedrock's taggable ARNs, `...:([0-9]{12}|)((:(custom-model|...)/...)|(:guardrail/...))`.
    for account in re.finditer(r"\{12\}\|\)", pattern):
        for found in re.finditer(r":\(?([a-z][a-z0-9-]*(?:\|[a-z][a-z0-9-]*)*)\)?\\?/", pattern[account.end():]):
            words |= set(found.group(1).split("|"))
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
    prefixes = {client: models[client]["metadata"].get("endpointPrefix") for client in clients}
    shared = len(set(prefixes.values())) < len(clients)
    # Clients serving one endpoint cannot be told apart by a request, so an ARN member of theirs keeps every type the
    # action acts on, each ARN read by its format: Lambda's `lambda` and `lambda-microvms` both tag on
    # `/2017-03-31/tags/{Resource}`.
    for client in clients:
        apart[client] = narrowed(apart[client], client_words(models[client]), models[client], name, formats,
                                 arn_types=not shared)
    resources: list[dict] = []
    for client in clients:
        for resource in apart[client]:
            everywhere = all(resource in apart[other] for other in clients)
            tagged = resource if everywhere or shared else dict(resource, endpoint=prefixes[client])
            if tagged not in resources:
                resources.append(tagged)
    return resources


def narrowed(resources: list[dict], words: set[str], model: dict, operation: str,
             formats: dict[str, str] | None = None, arn_types: bool = True) -> list[dict]:
    """A client's resources, each ARN member's types narrowed to those its own pattern names, by the type's name or its
    ARN's resource word (`event-source-mapping` for `eventSourceMapping`), unless `arn_types` is false, and a type its
    patterns never name read as its versioned twin they do (`event-bus` as `event-busv2`, EventBridge V2's own), when
    that twin's format has the variables it fills."""
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
        if arn_types and "arn" in resource and "." not in resource["arn"]:
            member = root.get("members", {}).get(resource["arn"].removesuffix("[]"))
            shape = model["shapes"][member["shape"]] if member else {}
            element = model["shapes"][shape["member"]["shape"]] if shape.get("type") == "list" else shape
            own = pattern_words(element.get("pattern") or "")
            kinds = [kind for kind in resource["types"] if kind in own or (word(kind) and word(kind) in own)]
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


# Documentation saying what a service uses for a member a request leaves out: "If you do not specify a cluster, the
# default cluster is assumed", "If you don't specify a cluster, default is used", or, naming the type after
# `default`, "If you omit this, the default schedule group is used", "If you omit this, EventBridge Scheduler assumes
# that the schedule is associated with the default group".
DOC_DEFAULT_RE = re.compile(r"\bif you do(?: not|n't) specify an? ([a-z][a-z -]*?), (?:the )?default(?: [a-z -]+?)? is "
                            r"(?:assumed|used)\b"
                            r"|\bif you omit this(?: value)?, (?:the default ([a-z][a-z -]*?) is (?:assumed|used)\b"
                            r"|[a-z0-9 -]+? assumes (?:that )?the [a-z -]+? is associated (?:with|to) the default "
                            r"([a-z][a-z -]*?)(?:[.,;]|$))")


def defaulted_members(models: dict[str, dict], operation: str) -> dict[str, str]:
    """The optional top-level members of the operation's request, strings or lists of them, whose documentation says
    its service uses its default of a type for a request leaving them out, by their path, each with the word it
    names that type by: ECS's `cluster`, "If you do not specify a cluster, the default cluster is assumed";
    EventBridge Scheduler's `GroupName`, "If you omit this, the default schedule group is used"."""
    found: dict[str, str] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or _reading(model) != "all":
            continue
        shape = model["shapes"][spec["input"]["shape"]]
        for name, member in shape.get("members", {}).items():
            strings = _strings(model, model["shapes"][member["shape"]])
            said = DOC_DEFAULT_RE.search(_lead_text(member.get("documentation")))
            if name not in shape.get("required", []) and strings is not None and said:
                found.setdefault(name + strings, next(word for word in said.groups() if word).replace(" ", "-"))
    return found


def with_documented_defaults(resources: list[dict], formats: dict[str, str], defaulted: dict[str, str]) -> list[dict]:
    """The resources, each variable of a type named by its variables filled from a member whose documentation says its
    service uses its default for a request leaving it out marked so (`defaults`), when the variable is the one of a
    type whose format is that variable alone named by the word the documentation uses: a service's
    `service/${ClusterName}/${ServiceName}` is on the default cluster, `cluster/${ClusterName}`, for a request with
    no `cluster`. The reader asks the type's service for that default. A resource of a pair that already applies by
    whether the member names the default (`whenDefault`, `unlessDefault`: a rule on the default event bus or on
    another) is left as it is, the pair's other resource being the one a request leaving it out names."""
    found = []
    for resource in resources:
        defaults = {}
        for variable, path in resource.get("fill", {}).items():
            kind = named_by_variable(variable, formats)
            if path in defaulted and kind is not None and related(stem(defaulted[path]), stem(kind)):
                defaults[path] = kind
        found.append(dict(resource, defaults=dict(sorted(defaults.items()))) if defaults and "type" in resource
                     and not {"when", "unless", "find", "default", "whenDefault", "unlessDefault"} & set(resource)
                     else resource)
    return found


def with_optional_qualifier(resources: list[dict], optional: set[str]) -> list[dict]:
    """The resources, one filling a variable from an optional member no other resource reads, beside another it
    fills from a member that is, named only when the request gives that member: Redshift's `GetClusterCredentials`
    checks the database it names only 'If the DbName parameter is specified'. A member naming its service's default
    when left out (`defaults`) names one then."""
    read = [path for resource in resources for path in resource.get("fill", {}).values()]
    found = []
    for resource in resources:
        fill = resource.get("fill", {})
        own = [path for path in fill.values() if path in optional and read.count(path) == 1
               and path not in resource.get("defaults", {})]
        if len(fill) >= 2 and len(own) == 1 and len(own) < len(fill) \
                and not {"when", "unless", "find", "whenDefault", "unlessDefault"} & set(resource):
            resource = dict(resource, unless={own[0]: [None]})
        found.append(resource)
    return found


def qualified_kinds(formats: dict[str, str]) -> dict[str, dict[str, str]]:
    """Each type the reference gives qualified types of, by type: those whose format is the type's and one more
    `:${<variable>}`, each by the word its name adds to the type's. Lambda's `function alias`,
    `function:${FunctionName}:${Alias}`, adds `alias`, and `function version` adds `version`."""
    found: dict[str, dict[str, str]] = {}
    for kind, arn in formats.items():
        for other, extended in formats.items():
            if other != kind and extended.startswith(arn) and QUALIFIER_RE.fullmatch(extended[len(arn):]) \
                    and other.lower().startswith(kind.lower()):
                found.setdefault(kind, {})[other.lower()[len(kind):].strip()] = other
    return found


def qualifier_members(models: dict[str, dict], operation: str, words: set[str]) -> list[str]:
    """The top-level members of the operation's request documented as naming one of a type's qualified types, by the
    words those add to the type's name, each of them: Lambda's `Qualifier`, "The version number or alias name"."""
    found: list[str] = []
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
                continue
            if model["shapes"][member["shape"]]["type"] != "string":
                continue
            lead = re.match(DOC_QUALIFIER.format(words="|".join(sorted(map(re.escape, words)))),
                            _lead(member.get("documentation")))
            if not lead or name in found:
                continue
            given = {word for word in (lead.group("first"), lead.group("second")) if word}
            # One naming another of those words after its own names something else of that kind: `CreateAlias`'s
            # `FunctionVersion`, "The function version that the alias invokes", is what the alias points to, and
            # `ListAliases`'s, "Specify a function version to only list aliases that invoke that version", filters.
            if not any(re.search(r"\b" + re.escape(word) + r"(?:e?s)?\b", lead.group("rest")) for word in words - given):
                found.append(name)
    return found


def with_qualifier(resources: list[dict], types: list[str], formats: dict[str, str], members: dict[str, str],
                   models: dict[str, dict], operation: str) -> list[dict]:
    """The resources, and each qualified type the action acts on (`qualified_kinds`) that no resource names, named by
    its type's variable and the request's one member documented as naming the qualified types (`qualifier_members`)
    for its own, the type's member giving its name or its ARN: `GetProvisionedConcurrencyConfig` acts on the
    function's alias or version its `Qualifier` names. An action acting on the type alone is checked on it as its
    members name it."""
    found = list(resources)
    for kind, qualified in qualified_kinds(formats).items():
        variables = [v for v in VARIABLE_RE.findall(formats[kind]) if v not in KNOWN_VARIABLES]
        named = qualifier_members(models, operation, set(qualified))
        if len(variables) != 1 or len(named) != 1 or members.get(variables[0]) != variables[0]:
            continue
        variable, qualifier = variables[0], named[0]
        for other in qualified.values():
            own = [v for v in VARIABLE_RE.findall(formats[other]) if v not in KNOWN_VARIABLES and v != variable]
            if other in types and len(own) == 1 and not any(resource.get("type") == other for resource in found):
                # The type's member may give the type's own ARN, whose name fills the variable.
                found.append({"type": other, "fill": {variable: variable, own[0]: qualifier}, "arnOf": {variable: kind}})
    return found


def partial_arn_members(models: dict[str, dict], operation: str, formats: dict[str, str]) -> dict[str, set[str]]:
    """The top-level members of the operation's request whose botocore pattern takes an ARN of a type with its leading
    parts left out, its account and resource (`123456789012:function:my-function`), each with those types: the pattern
    spells the type's resource word and takes such a tail. Lambda's `FunctionName` takes "Partial ARN -
    123456789012:function:my-function"."""
    found: dict[str, set[str]] = {}
    for model in models.values():
        spec = model["operations"].get(operation)
        if spec is None or "input" not in spec or not READ_PROTOCOLS & _protocols(model):
            continue
        for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
            if member.get("location") not in BODY_LOCATIONS and _located(member) is None:
                continue
            pattern = model["shapes"][member["shape"]].get("pattern")
            if not pattern:
                continue
            for kind, arn in formats.items():
                word = RESOURCE_PART_RE.match(arn)
                if not word or word.group(1) + ":" not in pattern:
                    continue
                tail = "123456789012:" + VARIABLE_RE.sub("x", arn.split(":", 5)[5])  # partition-literal: a sample
                try:
                    if re.fullmatch(pattern, tail):
                        found.setdefault(name, set()).add(kind)
                except re.error:
                    continue
    return found


def with_partial_arns(resources: list[dict], partial: dict[str, set[str]]) -> list[dict]:
    """The resources, each a type named by a member that may give its ARN with the leading parts left out
    (`partial_arn_members`) marked so: the reader completes such a value in the request's partition, and in its
    Region where the value leaves that out too."""
    return [dict(resource, partialArn=True) if "type" in resource and any(
        resource["type"] in partial.get(path, set()) for path in resource.get("fill", {}).values()) else resource
        for resource in resources]


def _named(value: object) -> set[str]:
    """The member names an operation's resources read: every key and string they hold, each up to its first `[` or
    `.`, a superset that keeps `located` to the members something reads."""
    if isinstance(value, dict):
        return set().union(*(_named(key) | _named(item) for key, item in value.items())) if value else set()
    if isinstance(value, list):
        return set().union(*(_named(item) for item in value)) if value else set()
    return {re.split(r"[\[{.]", value, maxsplit=1)[0]} if isinstance(value, str) else set()


def with_found(resources: list[dict], types: list[str], formats: dict[str, str], members: dict[str, str],
               keys: dict[str, list[str]], requested: set[str]) -> list[dict]:
    """The resources, a type whose ARN is all an id its service mints named instead by the members its service finds
    it by, when the request has them all, rather than by any it fills:
    - the members botocore documents as its key (`found_keys`: a scalable target by its `ServiceNamespace`,
      `ResourceId` and `ScalableDimension`, its own id being one Application Auto Scaling assigns);
    - or else a name of it, a member named after the type or its format's resource word and ending as a name, where
      the format's one variable is an id no request of the namespace gives (`requested`, every member of its requests),
      which the service mints: a Redshift Serverless namespace, `namespace/${NamespaceId}`, by its `namespaceName`; a
      VPC endpoint, `managedvpcendpoint/${EndpointAccessId}`, by its `endpointName`. Where some request gives the id,
      as Cognito's `IdentityPoolId`, a name beside it is a new name, not the resource's."""
    by_kind = {}
    for kind in types:
        variables = [v for v in VARIABLE_RE.findall(formats.get(kind, "")) if v not in KNOWN_VARIABLES]
        key = keys.get(kind)
        if key and all(members.get(member) == member for member in key):
            by_kind[kind] = key
        elif len(variables) == 1 and kind_of(variables[0]) == "id" and kind not in keys:
            word = RESOURCE_PART_RE.match(formats[kind])
            stems = {stem(kind)} | ({stem(word.group(1))} if word else set())
            if any(kind_of(given) in ("id", "identifier") and (given.lower() == variables[0].lower()
                   or (stem(variables[0]) == "resource" and stem(given)
                       and any(related(stem(given), known) for known in stems))) for given in requested):
                continue
            named = sorted(member for member, path in members.items() if path == member and kind_of(member) == "name"
                           and any(related(stem(member), known) for known in stems))
            if len(named) == 1:
                by_kind[kind] = named
    # A type botocore documents a key of is named by that key alone, never by a member filling its id, which is not one
    # a request gives: a request lacking a member of its key names none.
    found = [resource for resource in resources if resource.get("type") not in by_kind and resource.get("type") not in keys]
    return found + [{"type": kind, "fill": {}, "find": list(by)} for kind, by in by_kind.items()]


def with_nested_record_find(resources: list[dict], types: list[str], formats: dict[str, str],
                            members: dict[str, str], models: dict[str, dict]) -> list[dict]:
    """An id of a nested record linking two reference types selects both resource endpoints.

    A NetworkInterface holds an Attachment whose AttachmentId and InstanceId identify its attachment and the
    instance at its other end. Only a record named exactly after a reference type, holding its own id, supplies
    this relationship; request wrappers and unrelated sibling fields do not. Existing explicit selectors win.
    """
    candidates: dict[str, set[str]] = {}
    for model in models.values():
        shapes = model["shapes"]
        for shape_name, shape in shapes.items():
            parents = [kind for kind in types if stem(shape_name) == stem(kind)]
            fields = shape.get("members", {}) if shape.get("type") == "structure" else {}
            if not parents or not any(kind_of(field) == "id" and stem(field) == stem(shape_name)
                                      for field in fields):
                continue
            for child_name, spec in fields.items():
                child = shapes[spec["shape"]]
                nested = child.get("members", {}) if child.get("type") == "structure" else {}
                for key, key_spec in nested.items():
                    if members.get(key) != key or kind_of(key) != "id" or stem(key) != stem(child_name) \
                            or shapes[key_spec["shape"]]["type"] != "string" \
                            or any(stem(key) == stem(kind) for kind in formats):
                        continue
                    reached = set(parents) | {kind for field, field_spec in nested.items()
                                              if field != key and kind_of(field) == "id"
                                              and shapes[field_spec["shape"]]["type"] == "string"
                                              for kind in types if stem(field) == stem(kind)}
                    if len(reached) < 2:
                        continue
                    for kind in reached:
                        candidates.setdefault(kind, set()).add(key)
    selected = {kind for resource in resources for kind in resource.get("types", [resource.get("type")])}
    return resources + [{"type": kind, "fill": {}, "find": sorted(keys)}
                        for kind, keys in sorted(candidates.items()) if kind not in selected and len(keys) == 1]


def with_find_scope(resources: list[dict], models: dict[str, dict], operation: str,
                    formats: dict[str, str]) -> list[dict]:
    """A found name is scoped by a request member its record says is associated with that resource."""
    members = request_members(models, operation)
    found = []
    for resource in resources:
        if "find" not in resource:
            found.append(resource)
            continue
        scope = set()
        for model in models.values():
            for shape_name, shape in model["shapes"].items():
                fields = shape.get("members", {}) if shape.get("type") == "structure" else {}
                if not stem(shape_name).endswith(stem(resource["type"])) \
                        or any(stem(member) == stem(shape_name) for member in resource["find"]) \
                        or not all(member in fields for member in resource["find"]):
                    continue
                for member, spec in fields.items():
                    associated = re.search(r"\bassociated with (?:this|the) (.+)$", _lead(spec.get("documentation")))
                    if member in members and member not in resource["find"] and associated \
                            and stem(member) and stem(shape_name).startswith(stem(member)) \
                            and stem(shape_name) != stem(member) \
                            and doc_names("the name of the " + associated.group(1), resource["type"], formats):
                        scope.add(member)
        scoped = dict(resource, find=sorted(scope) + resource["find"])
        if scope and len(resource["find"]) == 1 and resource["find"][0] in optional_members(models, operation):
            absent = {resource["find"][0]: [None]}
            found.append(dict(scoped, unless=absent))
            # With no child name, the handler uses its default child of the explicitly named parent.
            found.append(dict(resource, find=sorted(scope), when=absent))
        else:
            found.append(scoped)
    return found


def requested_members(models: dict[str, dict]) -> set[str]:
    """Every top-level member of every request of the clients given."""
    return {member for model in models.values() for spec in model["operations"].values() if "input" in spec
            for member in model["shapes"][spec["input"]["shape"]].get("members", {})}


def found_keys(models: dict[str, dict], formats: dict[str, str]) -> dict[str, list[str]]:
    """The members botocore documents as a type's key, by type: "Scalable targets are uniquely identified by the
    combination of resource ID, scalable dimension, and namespace" (Application Auto Scaling's
    `RegisterScalableTarget`) keys a `ScalableTarget` by `ServiceNamespace`, `ResourceId` and `ScalableDimension`, the
    members of the documenting request each part names, in the request's order."""
    found: dict[str, list[str]] = {}
    for model in models.values():
        for operation, spec in model["operations"].items():
            text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", spec.get("documentation") or ""))
            match = UNIQUE_KEY_RE.search(text)
            if not match or "input" not in spec:
                continue
            kinds = [kind for kind in formats if stem(kind) == stem(match.group("things"))]
            request = list(model["shapes"][spec["input"]["shape"]].get("members", {}))
            parts = [re.sub(r"[^a-z0-9]", "", part.lower())
                     for part in re.split(r",\s*(?:and\s+)?|\s+and\s+", match.group("parts")) if part.strip()]
            key = [member for member in request if any(member.lower().endswith(part) for part in parts)]
            if len(kinds) == 1 and len(key) == len(parts):
                found[kinds[0]] = key
    return found


def with_through(resources: list[dict], types: list[str], members: dict[str, str],
                 reached: dict[str, list[str]]) -> list[dict]:
    """The resources, and the types the action acts on that each member of the request naming a resource of its
    service's own reaches (`through_members`), named through that member: the reader asks the service for the ARN of
    the caller's resource at the other end."""
    through = []
    for member, kinds in sorted(reached.items()):
        on = [kind for kind in types if kind in kinds]
        # The request may name the thing by its identifier where its record holds its id: a MicroVM's
        # `microvmIdentifier` and the `microvmId` its record holds.
        given = member if members.get(member) == member else next(
            (other for other, path in sorted(members.items()) if path == other and stem(other) == stem(member)
             and kind_of(other) in ("id", "identifier")), None)
        if on and given and not any(resource.get("through") == given for resource in through):
            through.append({"types": on, "through": given})
    return resources + through


def through_members(models: dict[str, dict], formats: dict[str, str]) -> dict[str, list[str]]:
    """The members naming a resource of their service's own that the reference gives no type, each with the types the
    resource runs on or is in: botocore's record of it, a structure named after it that holds the member, holds members
    named after those types. A Data API session's `SessionData` holds its `SessionId` beside the `ClusterIdentifier`
    or `WorkgroupName` of the cluster or the serverless workgroup it runs on, so a request naming a session alone
    reaches that one."""
    stems = {stem(kind): kind for kind in formats}
    found: dict[str, set[str]] = {}
    for model in models.values():
        for shape_name, shape in model["shapes"].items():
            fields = shape.get("members", {}) if shape.get("type") == "structure" else {}
            for member in fields:
                thing = stem(member)
                if not member.endswith("Id") or not thing or not shape_name.lower().startswith(thing) \
                        or any(related(thing, known) for known in stems):
                    continue
                # A field named after a type, or after the end of one's name: a MicroVM's `imageArn` beside its
                # `microvmId` names the `microvmImage` it runs.
                kinds = {kind for field in fields if field != member and stem(field)
                         for known, kind in stems.items() if related(stem(field), known)}
                if kinds:
                    found.setdefault(member, set()).update(kinds)
            source = fields.get("SourceArn")
            if source is not None:
                head, of = doc_head(_lead(source.get("documentation")))
                if of and re.search(r"(?:arn|amazon resource name \(arn\))$", head):
                    kinds = {kind for kind in formats for phrase in re.split(r",| or ", of)
                             if doc_names("the name of the " + phrase.strip(), kind, formats)}
                    for member in fields:
                        if kinds and kind_of(member) == "identifier" and stem(member) == stem(shape_name):
                            found.setdefault(member, set()).update(kinds)
    for member, kinds in documented_through(models, formats).items():
        found.setdefault(member, set()).update(kinds)
    return {member: sorted(kinds) for member, kinds in sorted(found.items())}


def documented_through(models: dict[str, dict], formats: dict[str, str]) -> dict[str, set[str]]:
    """The members naming a thing of their service's own the reference gives no type, each with the type an operation
    taking it is documented as acting on it in: "Sends a successful completion response for a callback operation in a
    durable execution" (`SendDurableExecutionCallbackSuccess`), whose request names the callback by its
    `CallbackId`."""
    found: dict[str, set[str]] = {}
    for model in models.values():
        for spec in model["operations"].values():
            if "input" not in spec:
                continue
            documented = DOC_THING_IN_RE.search(_lead(spec.get("documentation")))
            kinds = [kind for kind in formats if documented and kind.lower() == documented.group("phrase")]
            if len(kinds) != 1:
                continue
            thing = re.sub(r"[^a-z0-9]", "", documented.group("thing"))
            for member in model["shapes"][spec["input"]["shape"]].get("members", {}):
                if kind_of(member) in ("id", "identifier") and stem(member) and thing.startswith(stem(member)) \
                        and not any(related(stem(member), stem(kind)) for kind in formats):
                    found.setdefault(member, set()).add(kinds[0])
    return found


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
    mapped = action_mapping(documents)
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
    read_namespaces = set(NAMESPACES)
    namespaces = {namespace: sorted({client for operation, value in covered.items()
                                     if namespace_of(operation) == namespace for client in value["clients"]})
                  for namespace in sorted(read_namespaces)}
    formats = arn_formats(documents, read_namespaces)
    minted = minted_types(formats, namespaces, data)
    suffixes = minted_suffixes(formats, namespaces, data)
    check_forwarded(authorized_by(documents))
    forwarded = forwarded_actions(documents)
    sources = load_statement_sources()
    statement_types = statement_actions(documents, sources)
    routed, path_formats = namespace_routed(covered, formats, namespaces, data)
    # The actions a namespace's vendored permissions may name, with their resource types.
    permission_actions = {document["Name"]: {f"{document['Name']}:{action['Name']}":
                                             [resource["Name"] for resource in action.get("Resources") or []]
                                             for action in document.get("Actions", [])}
                          for document in documents if document["Name"] in PERMISSION_SOURCES}
    operations = {}
    prefixes = {namespace: namespace_id_prefixes(namespace, load_models(data, set(namespaces[namespace])),
                                                 load_examples(data, set(namespaces[namespace])))
                for namespace in ID_TARGETS if namespace in namespaces}
    for operation, value in sorted(covered.items()):
        models = load_models(data, set(namespaces[namespace_of(operation)]))
        if namespace_of(operation) in permission_actions:
            value = {**value, "actions": permission_actions[namespace_of(operation)]}
        operations[operation] = entry_for(operation, value, {**formats.get(namespace_of(operation), {}),
                                                             **path_formats.get(namespace_of(operation), {})}, models,
                                          prefixes.get(namespace_of(operation)), forwarded, routed.get(operation))
    unqualified = unqualified_types(formats, operations, namespaces, data)
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
        "notCovered": dict(sorted(NOT_COVERED.items())),
        "otherNamespaces": sorted(namespace for namespace in _with_resource_types(documents)
                                  if namespace not in NAMESPACES and namespace not in NOT_COVERED),
        "arnFormats": formats,
        "pathFormats": path_formats,
        "resourceTagKeys": resource_tag_keys(documents, formats),
        "resourceKeys": resource_keys(documents, data, namespaces),
        "unqualified": unqualified,
        "mintedIds": minted,
        "mintedSuffixes": suffixes,
        "integerVariables": integer_variables(operations, namespaces, data),
        "forwardedActions": forwarded,
        "statementActions": statement_types,
        "statements": statements_for(sources, statement_types, formats),
        "operations": operations,
    }


def integer_variables(operations: dict[str, dict], namespaces: dict[str, list[str]],
                      data: Path) -> dict[str, dict[str, list[str]]]:
    """ARN variables filled by integer shapes, also normalized when a request supplies the whole ARN."""
    found: dict[str, dict[str, set[str]]] = {}
    loaded: dict[str, dict[str, dict]] = {}
    for operation, entry in operations.items():
        if not any(resource.get("fill") for resource in entry["resources"]):
            continue
        namespace, name = operation.split(":", 1)
        if namespace not in loaded:
            loaded[namespace] = load_models(data, set(namespaces[namespace]))
        members = integer_members(loaded[namespace], name)
        for resource in entry["resources"]:
            for variable, member in resource.get("fill", {}).items():
                if members.get(variable) == member:
                    types = found.setdefault(namespace, {})
                    types.setdefault(resource["type"], set()).add(variable)
    result = {}
    for namespace, types in sorted(found.items()):
        result[namespace] = {kind: sorted(variables) for kind, variables in sorted(types.items())}
    return result


def unqualified_types(formats: dict[str, dict[str, str]], operations: dict[str, dict],
                      namespaces: dict[str, list[str]], data: Path) -> dict[str, list[str]]:
    """The types of each namespace whose ARN a request may give with a qualifier, one more `:<name>` past its format,
    and whose service checks it without one:
    - the reference gives types whose formats are the type's and one more `:${...}`, and no action acts on any of them:
      Step Functions' `statemachinealias` and `statemachineversion`, `stateMachine:${StateMachineName}:${...}`, which
      no action acts on, while `DescribeStateMachineAlias` and `StartExecution` act on the `statemachine`;
    - botocore documents a member that fills the type as taking `"<member>": "name:<selector>"`, a selector the
      reference gives no type: Parameter Store's `GetParameter` `Name` ("To query by parameter version, use \"Name\":
      \"name:version\""), the parameter being what is checked."""
    acted: dict[str, set[str]] = {}
    for operation, entry in operations.items():
        acted.setdefault(namespace_of(operation), set()).update(entry["types"])
    found: dict[str, set[str]] = {}
    for namespace, kinds in formats.items():
        for kind, arn in kinds.items():
            longer = [other for other, extended in kinds.items()
                      if other != kind and extended.startswith(arn) and QUALIFIER_RE.fullmatch(extended[len(arn):])]
            if longer and not set(longer) & acted.get(namespace, set()):
                found.setdefault(namespace, set()).add(kind)
    loaded: dict[str, dict[str, dict]] = {}
    for operation, entry in operations.items():
        namespace, name = namespace_of(operation), operation.split(":", 1)[1]
        filled = [(resource["type"], path) for resource in entry["resources"] if "type" in resource
                  for path in resource.get("fill", {}).values() if "." not in path and "[" not in path]
        if not filled:
            continue
        if namespace not in loaded:
            loaded[namespace] = load_models(data, set(namespaces.get(namespace, [])))
        models = loaded[namespace]
        for kind, path in filled:
            text = re.sub(r"<[^>]+>", "", _member_documentation(models, name, path) or "")
            if re.search(r'"' + re.escape(path) + r'":\s*"name:\w+"', text):
                found.setdefault(namespace, set()).add(kind)
    return {namespace: sorted(kinds) for namespace, kinds in sorted(found.items())}


def minted_types(formats: dict[str, dict[str, str]], namespaces: dict[str, list[str]],
                 data: Path) -> dict[str, list[str]]:
    """The types of each namespace whose service follows the one variable their format ends in with an id it mints,
    which the format leaves out and a request naming one by its name does not give: botocore's ARN shape for the type
    (`<Type>Arn`) takes one more path segment past the format's, two each of its own (EventBridge's `ConnectionArn`
    ends in `connection\\/[...]+\\/[...]+`, the format in `connection/${ConnectionName}`)."""
    found: dict[str, list[str]] = {}
    for namespace in sorted(formats):
        shapes = {name.lower(): shape["pattern"]
                  for model in load_models(data, set(namespaces.get(namespace, []))).values()
                  for name, shape in model["shapes"].items() if shape.get("type") == "string" and shape.get("pattern")}
        for kind, arn in sorted(formats[namespace].items()):
            word = MINTED_WORD_RE.match(_resource(arn))
            pattern = shapes.get(re.sub(r"[^a-z0-9]", "", kind.lower()) + "arn")
            if word and pattern and re.search(re.escape(word.group(1)) + MINTED_TAIL, pattern):
                found.setdefault(namespace, []).append(kind)
    return found


def minted_suffixes(formats: dict[str, dict[str, str]], namespaces: dict[str, list[str]],
                    data: Path) -> dict[str, dict[str, str]]:
    """The types of each namespace whose service follows the name their one variable takes with characters it mints,
    which a request naming one by its name does not give, each with those characters as IAM matches them, one `?`
    each after the separator: botocore documents a create's name member so ("Secrets Manager automatically adds a
    hyphen and six random characters after the secret name at the end of the ARN": `-??????`)."""
    found: dict[str, dict[str, str]] = {}
    for namespace in sorted(formats):
        for model in load_models(data, set(namespaces.get(namespace, []))).values():
            for operation in model["operations"]:
                for said in documented_minted_suffixes(model, operation):
                    kind = named_by_word(said[0], formats[namespace])
                    if kind is not None:
                        found.setdefault(namespace, {})[kind] = said[1]
    return {namespace: dict(sorted(kinds.items())) for namespace, kinds in sorted(found.items())}


def documented_minted_suffixes(model: dict, operation: str) -> list[tuple[str, str, str]]:
    """The members of a create's request documented as the name its service follows with characters it mints, each as
    the type's word the documentation names, the characters as IAM matches them and the member: `("secret", "-??????",
    "Name")` for Secrets Manager's `CreateSecret`."""
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec or not CREATING_RE.match(operation):
        return []
    found = []
    for name, member in model["shapes"][spec["input"]["shape"]].get("members", {}).items():
        said = DOC_MINTED_SUFFIX_RE.search(_lead_text(member.get("documentation")))
        count = COUNTS.get(said.group("count")) if said else None
        if said and count:
            found.append((said.group("word").replace(" ", "-"), SEPARATORS[said.group("separator")] + "?" * count, name))
    return found


def named_by_word(word: str, formats: dict[str, str]) -> str | None:
    """The one type whose format's resource is the word and one variable, after a `/` or a `:`
    (`secret:${SecretId}`)."""
    found = [kind for kind, arn in formats.items()
             if re.fullmatch(re.escape(word) + r"[/:]\$\{\w+}", _resource(arn), re.IGNORECASE)]
    return found[0] if len(found) == 1 else None


def _resource(arn_format: str) -> str:
    """The resource part of an ARN format, past its fifth colon."""
    return arn_format.split(":", 5)[5]


def _with_resource_types(documents: list[dict]) -> set[str]:
    """The namespaces with an action that acts on a resource type."""
    return {document["Name"] for document in documents
            if any(action.get("Resources") for action in document.get("Actions", []))}


def reads(operation: str) -> bool:
    """Every operation of a namespace in `NAMESPACES` is read, whole."""
    return namespace_of(operation) in NAMESPACES


def _check_overrides(covered: dict[str, dict]) -> None:
    """Every operation `MEASURED` gives is one the file reads, and no namespace is in two places."""
    stale = [f"MEASURED gives {operation}, which the file does not read" for operation in MEASURED
             if operation not in covered]
    stale += [f"{namespace} is in both NAMESPACES and NOT_COVERED"
              for namespace in sorted(set(NAMESPACES) & set(NOT_COVERED))]
    if stale:
        raise ValueError("; ".join(stale))


def render(document: dict) -> str:
    """The document with one operation to a line."""
    lines = ["{"]
    for key in ("_source", "namespaces", "notCovered", "otherNamespaces", "arnFormats", "pathFormats",
                "resourceTagKeys",
                "resourceKeys", "unqualified", "mintedIds", "mintedSuffixes", "integerVariables", "forwardedActions",
                "statementActions", "statements"):
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
# A member a service finds a resource by: one list, or a value at any depth of structures
# (`AlternateIdentifier.ExternalId.Id`).
FIND_PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[]|(\.[A-Za-z][A-Za-z0-9_]*)*)$")
# A member of a request's body at any depth, as `body_only_identifiers` writes it: `Delete.Objects[].Key`.
BODY_PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[])?(\.[A-Za-z][A-Za-z0-9_]*(\[])?)*$")
# A member filling a variable may also be a field of a structure, or of each element of a list of structures, one
# value for each.
FILL_PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[]|\[]\.[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)?|\{}"
                          r"|\{}\.[A-Za-z][A-Za-z0-9_]*|\.[A-Za-z][A-Za-z0-9_]*)?$")
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
    path_formats = document.get("pathFormats")
    if not isinstance(path_formats, dict) or set(path_formats) - (set(namespaces) & set(PATH_SOURCES)) or not all(
            isinstance(kinds, dict) and kinds and all(
                isinstance(kind, str) and kind not in formats.get(namespace, {}) and isinstance(arn, str)
                and arn.startswith(f"arn:${{Partition}}:{namespace}:") for kind, arn in kinds.items())
            for namespace, kinds in path_formats.items()):
        return ["pathFormats is not an ARN format for each route type, of no reference type's name, of namespaces the "
                "file reads routes of by their method"]
    # A route type is a resource type as the reference's are, wherever the file names one.
    formats = {namespace: {**formats.get(namespace, {}), **path_formats.get(namespace, {})}
               for namespace in sorted(set(formats) | set(path_formats))}
    if list(operations) != sorted(operations):
        problems.append("operations is not sorted")
    integers = document.get("integerVariables", {})
    if not isinstance(integers, dict):
        return ["integerVariables is not an object"]
    for namespace, types in integers.items():
        if namespace not in formats or not isinstance(types, dict):
            return [f"integerVariables has no resource formats for {namespace}"]
        for kind, variables in types.items():
            available = set(VARIABLE_RE.findall(formats[namespace].get(kind, ""))) - KNOWN_VARIABLES
            if not isinstance(variables, list) or not variables or not all(
                    isinstance(variable, str) and variable in available for variable in variables):
                return [f"integerVariables names no ARN variables for {namespace}:{kind}"]
            if variables != sorted(set(variables)):
                return [f"integerVariables repeats or misorders variables for {namespace}:{kind}"]
    tag_keys = document.get("resourceTagKeys", {})
    if not isinstance(tag_keys, dict) or any(
            not isinstance(kinds, dict) or any(kind not in formats.get(namespace, {})
                or not isinstance(keys, list) or not all(isinstance(key, str) for key in keys) or keys != sorted(set(keys))
                or any(not isinstance(key, str) or (not re.fullmatch(r"[a-z0-9-]+:(?i:ResourceTag)/", key)
                    and key != f"{namespace}:{kind}-tag/") for key in keys)
                for kind, keys in kinds.items()) for namespace, kinds in tag_keys.items()):
        problems.append("resourceTagKeys is not stored-tag prefixes for supported resource types")
    resource_keys_document = document.get("resourceKeys", {})
    if not isinstance(resource_keys_document, dict) or any(
            not isinstance(kinds, dict) or any(
                kind not in formats.get(namespace, {}) or not isinstance(keys, dict) or not keys
                or list(keys) != sorted(keys) or any(
                    not re.fullmatch(rf"{re.escape(namespace)}:[A-Za-z0-9]+", key) or not isinstance(spec, dict)
                    or set(spec) - {"member", "arn"} or not isinstance(spec.get("member"), str)
                    or ("arn" in spec and spec["arn"] not in formats.get(namespace, {}))
                    for key, spec in keys.items())
                for kind, keys in kinds.items())
            for namespace, kinds in resource_keys_document.items()):
        problems.append("resourceKeys is not member-named condition keys of supported resource types")
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
    suffixes = document.get("mintedSuffixes")
    if not isinstance(suffixes, dict) or not all(
            isinstance(kinds, dict) and kinds and all(
                isinstance(kind, str) and kind in formats.get(namespace, {})
                and ONE_VARIABLE_RE.match(_resource(formats[namespace][kind]))
                and isinstance(suffix, str) and re.fullmatch(r"[-_.]\?+", suffix) for kind, suffix in kinds.items())
            for namespace, kinds in suffixes.items()):
        problems.append("mintedSuffixes is not resource types of each namespace the file reads whose ARN format ends "
                        "in its one variable, each with a separator and the characters its service mints")
    if not isinstance(document.get("otherNamespaces"), list) or not all(
            isinstance(n, str) and NAMESPACE_RE.match(n) for n in document["otherNamespaces"]):
        problems.append("otherNamespaces is not a list of namespaces")
    if not isinstance(document.get("notCovered"), dict) or not all(
            isinstance(reason, str) and reason for reason in document["notCovered"].values()):
        problems.append("notCovered does not give each a reason")
    recorded_actions = document.get("forwardedActions")
    if not isinstance(recorded_actions, dict) or not all(
            isinstance(action, str) and OPERATION_RE.match(action) and isinstance(facts, dict)
            and {"type", "format"} <= set(facts) <= {"type", "format", "viaService"}
            and isinstance(facts["type"], str) and isinstance(facts["format"], str)
            and facts["format"].startswith(f"arn:${{Partition}}:{namespace_of(action)}:")
            and len(set(VARIABLE_RE.findall(facts["format"])) - KNOWN_VARIABLES) == 1
            and facts.get("viaService", f"{namespace_of(action)}:ViaService") == f"{namespace_of(action)}:ViaService"
            for action, facts in recorded_actions.items()):
        problems.append("forwardedActions is not each forwarded action's type, its ARN format of one variable and its "
                        "namespace's ViaService key")
        recorded_actions = {}
    problems += _statement_problems(document.get("statementActions"), document.get("statements"), operations, formats)
    for operation, entry in operations.items():
        namespace = namespace_of(operation)
        if not OPERATION_RE.match(operation) or namespace not in namespaces:
            problems.append(f"{operation!r} is not an operation of a namespace the file reads")
            continue
        if not isinstance(entry, dict) or not {"action", "types", "resources"} <= set(entry) <= {
                "action", "types", "actions", "resources", "creates", "makes", "located", "locatedApart", "targets",
                "forwarded", "ec2Query", "unread"}:
            problems.append(f"{operation} is not an action, its types and its resources")
            continue
        if not isinstance(entry["action"], str) or not OPERATION_RE.match(entry["action"]) \
                or namespace_of(entry["action"]) != namespace:
            problems.append(f"{operation} is checked as no action of its namespace")
        types = entry["types"]
        if not isinstance(types, list) or not all(isinstance(kind, str) for kind in types):
            problems.append(f"{operation} has no list of resource types")
            continue
        several = entry.get("actions")
        if several is not None and (namespace not in PERMISSION_SOURCES or not isinstance(several, dict)
                                    or not several or not all(
                isinstance(action, str) and OPERATION_RE.match(action) and namespace_of(action) == namespace
                and isinstance(kinds, list)
                and all(isinstance(kind, str) and kind in formats.get(namespace, {}) for kind in kinds)
                for action, kinds in several.items())):
            problems.append(f"{operation} is checked as {several!r}, which are not several actions of a namespace with "
                            f"vendored permissions, with their resource types")
            continue
        for resource in entry["resources"] if isinstance(entry["resources"], list) else [None]:
            # A resource of an operation checked as several actions is checked as one of them, on that one's types.
            acting = set(types)
            if several is not None:
                if not isinstance(resource, dict) or resource.get("action") not in several:
                    problems.append(f"{operation} names {resource!r} as none of the actions it is checked as")
                    continue
                acting = set(several[resource["action"]])
                resource = {key: value for key, value in resource.items() if key != "action"}
            # Only a measured entry names a type the reference omits for the action; MEASURED's admission checks
            # (see `measured_resources`) say when it may.
            measured = set(_kinds(resource)) if isinstance(resource, dict) and operation in MEASURED else set()
            # A route authorized by its method names the type of its path, which its action may not list (`--check`
            # says whether it is the one its path is).
            routed = set(formats.get(namespace, {})) if namespace in PATH_SOURCES else set()
            problems += _resource_problems(operation, resource, acting | measured | routed,
                                           formats.get(namespace, {}))
        for created in entry.get("creates", []) if isinstance(entry.get("creates", []), list) else [None]:
            problems += _created_problems(operation, created, set(types), formats.get(namespace, {}))
        for made in entry.get("makes", []) if isinstance(entry.get("makes", []), list) else [None]:
            if not isinstance(made, dict) or set(made) != {"type", "fill"} or made.get("type") in types:
                problems.append(f"{operation} makes {made!r}, which is no type's variables of a create acting on "
                                "none or on other types alone")
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
        query_members = entry.get("ec2Query")
        if query_members is not None and (namespace != "ec2" or not isinstance(query_members, dict)
                or not query_members or not all(isinstance(member, str)
                    and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\[\])?", member)
                    and isinstance(wire, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", wire)
                    for member, wire in query_members.items())):
            problems.append(f"{operation} has invalid EC2 Query member names")
        located = entry.get("located", {})
        if not isinstance(located, dict) or not located and "located" in entry or not all(
                isinstance(member, str) and PATH_RE.match(member) and isinstance(where, str) and LOCATED_RE.match(where)
                for member, where in located.items()):
            problems.append(f"{operation} does not give each member it locates a label of its path or a parameter of "
                            f"its query")
        apart = entry.get("locatedApart", {})
        if not isinstance(apart, dict) or not apart and "locatedApart" in entry or not all(
                client in REST_MODELS and isinstance(found, dict) and found and all(
                    isinstance(member, str) and PATH_RE.match(member) and isinstance(where, str)
                    and LOCATED_RE.match(where) for member, where in found.items())
                for client, found in apart.items()):
            problems.append(f"{operation} does not give each member it locates apart, under a client whose routes are "
                            f"vendored, a label of its path or a parameter of its query")
        unread = entry.get("unread", [])
        if not isinstance(unread, list) or not unread and "unread" in entry or not all(
                isinstance(member, str) and BODY_PATH_RE.match(member) for member in unread):
            problems.append(f"{operation} does not give each body member it leaves unread as a path")
        forwarded = entry.get("forwarded", [])
        if not isinstance(forwarded, list) or not forwarded and "forwarded" in entry or not all(
                isinstance(use, dict) and set(use) == {"action", "of", "member", "endpoint"}
                and use["action"] in recorded_actions and use["of"] in types and use["of"] in formats.get(namespace, {})
                and isinstance(use["member"], str) and PATH_RE.match(use["member"]) and not use["member"].endswith("[]")
                and isinstance(use["endpoint"], str) and use["endpoint"]
                for use in forwarded):
            problems.append(f"{operation} forwards no recorded action on a member of one of its types' records, from an "
                            f"endpoint prefix")
    return problems


def _resource_problems(operation: str, resource: object, types: set[str], formats: dict[str, str]) -> list[str]:
    if isinstance(resource, dict) and "statement" in resource:
        kinds = _kinds(resource)
        names = resource.get("names")
        if set(resource) != {"types", "statement", "verb", "names"} or not kinds or not isinstance(names, dict) \
                or set(names) != {v for kind in kinds for v in VARIABLE_RE.findall(formats.get(kind, ""))
                                  if v not in KNOWN_VARIABLES} or not all(
                isinstance(word, str) and re.fullmatch(r"[a-z0-9]+", word) for word in names.values()) or not all(
                kind in types and kind in formats for kind in kinds) or not isinstance(resource["statement"], str) \
                or not FILL_PATH_RE.match(resource["statement"]) or not re.fullmatch(r"[A-Z]+", str(resource["verb"])):
            return [f"{operation} names {resource!r}, which is no statement member, verb and types its action acts on"]
        return []
    if isinstance(resource, dict) and "signer" in resource:
        kinds = _kinds(resource)
        if not {"types", "signer"} <= set(resource) <= {"types", "signer", "when"} or resource["signer"] is not True \
                or len(kinds) != 1 or not all(kind in types and kind in formats for kind in kinds):
            return [f"{operation} names {resource!r}, which is no type its action acts on signed for"]
        return _conditions_problems(operation, kinds, resource)
    if not isinstance(resource, dict) or not ({"types", "arn"} <= set(resource) <= {"types", "arn", "made", "endpoint"}
                                              or {"types", "through"} <= set(resource) <= {"types", "through", "when",
                                                                                            "endpoint"}
                                              or {"type", "fill"} <= set(resource) <= {"type", "fill", "when", "unless", "arnOf", "account",
                                                                                       "fixed", "made", "find", "endpoint",
                                                                                       "default", "whenDefault", "unlessDefault",
                                                                                       "defaults", "partialArn", "path",
                                                                                       "renames"}):
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
            or not all(isinstance(path, str) and FIND_PATH_RE.match(path)
                       and (not path.endswith("[]") or len(find) == 1) for path in find):
        return [f"{operation} finds {kinds[0]} by {find!r}, which are no members of one value each or one list, or "
                f"beside a fill or members reading it otherwise"]
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
    defaults = resource.get("defaults", {})
    if not isinstance(defaults, dict) or ("defaults" in resource and not defaults) or not all(
            isinstance(kind, str) and kind in formats and variable in fill and fill[variable] == path
            and ONE_VARIABLE_RE.match(_resource(formats[kind])) and _resource(formats[kind]).endswith("${" + variable + "}")
            for path, kind in defaults.items()
            for variable in [next((v for v, p in fill.items() if p == path), "")]):
        return [f"{operation} names {defaults!r} as defaults of types, which are no members filling a variable that is "
                f"such a type's alone"]
    account = resource.get("account")
    if account is not None and (not isinstance(account, str) or not PATH_RE.match(account) or account.endswith("[]")
                                or "${Account}" not in formats[kinds[0]]):
        return [f"{operation} takes {kinds[0]}'s account from {account!r}, which is no member or no account of its ARN"]
    for key in ("path", "renames"):
        if key in resource and (not isinstance(resource[key], str) or not PATH_RE.match(resource[key])
                                or resource[key].endswith("[]") or len(fill) != 1
                                or set(resource) & {"find", "default", "when", "unless", "partialArn"}):
            return [f"{operation} reads {kinds[0]}'s {key} from {resource[key]!r}, which is no member beside the one "
                    f"filling its variable"]
    if "renames" in resource and "path" not in resource:
        return [f"{operation} renames {kinds[0]} with no member naming its path"]
    if resource.get("partialArn", True) is not True or ("partialArn" in resource and not fill):
        return [f"{operation} reads {kinds[0]} from a partial ARN {resource.get('partialArn')!r}, which is not true for a "
                f"type filled by its members"]
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


def _statement_problems(actions: object, statements: object, operations: dict,
                        formats: dict[str, dict[str, str]]) -> list[str]:
    """Whether `statementActions` gives each action resource types and `statements` gives each operation's statements
    as requests of those actions on resources of those types, each variable filled from a word or left open."""
    actions = {} if actions is None else actions
    statements = {} if statements is None else statements
    if not isinstance(actions, dict) or not all(
            isinstance(action, str) and OPERATION_RE.match(action) and isinstance(types, list) and types == sorted(types)
            and all(isinstance(kind, str) for kind in types) for action, types in actions.items()):
        return ["statementActions is not each statement action's resource types"]
    if not isinstance(statements, dict):
        return ["statements is not each operation's statements"]
    problems = []
    for operation, by_statement in statements.items():
        if operation not in operations or not isinstance(by_statement, dict) or not by_statement:
            problems.append(f"statements gives {operation!r}, no operation of the file, or no statement of it")
            continue
        for statement, requests in by_statement.items():
            if not isinstance(requests, list) or not requests or not all(
                    _statement_request_ok(request, actions, formats) for request in requests):
                problems.append(f"statements gives {operation} {statement!r} something other than requests of "
                                f"statementActions on resources of the types they act on")
    return problems


def _statement_request_ok(request: object, actions: dict, formats: dict[str, dict[str, str]]) -> bool:
    if not isinstance(request, dict) or not {"action", "resources"} <= set(request) <= {"action", "resources",
                                                                                     "partitioned"}:
        return False
    if request["action"] not in actions or request.get("partitioned", True) is not True:
        return False
    namespace = namespace_of(request["action"])
    return isinstance(request["resources"], list) and bool(request["resources"]) and all(
        isinstance(resource, dict) and set(resource) == {"type", "fill"}
        and resource["type"] in actions[request["action"]] and resource["type"] in formats.get(namespace, {})
        and isinstance(resource["fill"], dict) and all(
            variable in set(VARIABLE_RE.findall(formats[namespace][resource["type"]])) - KNOWN_VARIABLES
            and isinstance(word, str) and word for variable, word in resource["fill"].items())
        for resource in request["resources"])


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
    integers = integer_variables(document["operations"], document["namespaces"], data)
    if document.get("integerVariables", {}) != integers:
        problems.append(f"integerVariables differ from botocore: expected {json.dumps(integers)}")
    unqualified = unqualified_types(document["arnFormats"], document["operations"], document["namespaces"], data)
    if document["unqualified"] != unqualified:
        problems.append(f"the file checks {json.dumps(document['unqualified'])} without a qualifier, the reference "
                        f"and botocore give {json.dumps(unqualified)}")
    minted = minted_types(document["arnFormats"], document["namespaces"], data)
    if document["mintedIds"] != minted:
        problems.append(f"the file gives {json.dumps(document['mintedIds'])} a minted id, botocore's ARN patterns "
                        f"give {json.dumps(minted)}")
    suffixes = minted_suffixes(document["arnFormats"], document["namespaces"], data)
    if document.get("mintedSuffixes") != suffixes:
        problems.append(f"the file gives {json.dumps(document.get('mintedSuffixes'))} a minted suffix, botocore's "
                        f"documentation gives {json.dumps(suffixes)}")
    named = sorted({use["action"] for uses, _ in measured_actions().values() for use in uses})
    if sorted(document["forwardedActions"]) != named:
        problems.append(f"the file records {sorted(document['forwardedActions'])} as forwarded actions, MEASURED "
                        f"names {named}")
    sources = load_statement_sources()
    if sorted(document["statementActions"]) != source_actions(sources):
        problems.append(f"the file records {sorted(document['statementActions'])} as statement actions, the "
                        f"statement sources name {source_actions(sources)}")
    else:
        try:
            statements = statements_for(sources, document["statementActions"], document["arnFormats"])
        except ValueError as e:
            problems.append(str(e))
        else:
            if document["statements"] != statements:
                problems.append(f"the file's statements differ from what its sources give: expected "
                                f"{json.dumps(statements)}")
    try:
        routed, path_formats = namespace_routed(
            {operation: {"action": entry["action"], "types": entry["types"]}
             for operation, entry in document["operations"].items()},
            document["arnFormats"], document["namespaces"], data)
    except ValueError as e:
        return problems + [str(e)]
    if document.get("pathFormats", {}) != path_formats:
        problems.append(f"the file gives {json.dumps(document.get('pathFormats', {}))} as route types, the path "
                        f"sources and botocore give {json.dumps(path_formats)}")
    prefixes = {namespace: namespace_id_prefixes(namespace, load_models(data, set(document["namespaces"][namespace])),
                                                 load_examples(data, set(document["namespaces"][namespace])))
                for namespace in ID_TARGETS if namespace in document["namespaces"]}
    for operation, entry in document["operations"].items():
        namespace = namespace_of(operation)
        models = load_models(data, set(document["namespaces"][namespace]))
        expected = entry_for(operation, {"action": entry["action"], "types": entry["types"],
                                         **({"actions": entry["actions"]} if "actions" in entry else {})},
                             {**document["arnFormats"].get(namespace, {}), **path_formats.get(namespace, {})}, models,
                             prefixes.get(namespace), document["forwardedActions"], routed.get(operation))
        if expected != entry:
            problems.append(f"{operation}: the file has {json.dumps(entry)}, botocore gives {json.dumps(expected)}")
    return problems


def verify_forwarded(vendored: dict, documents: list[dict]) -> list[str]:
    """What the file records of forwarded actions and AWS no longer gives: each action's type, format and `ViaService`
    key, and its absence from what the reference lists for the operations forwarding it."""
    untrue: list[str] = []
    try:
        actual = forwarded_actions(documents)
    except ValueError as e:
        return [str(e)]
    for action, facts in vendored.get("forwardedActions", {}).items():
        if actual.get(action) != facts:
            untrue.append(f"AWS gives {action} {json.dumps(actual.get(action))}, not {json.dumps(facts)}")
    authorized = authorized_by(documents)
    for operation, entry in vendored["operations"].items():
        listed = sorted({use["action"] for use in entry.get("forwarded", [])} & authorized.get(operation, set()))
        if listed:
            untrue.append(f"AWS now lists {listed} for {operation}: a rule reads those, so MEASURED should not "
                          f"give them")
    return untrue


def verify_statements(vendored: dict, documents: list[dict]) -> list[str]:
    """What the file records of statement actions and AWS no longer gives, and a vendored page AWS has changed."""
    sources = load_statement_sources()
    try:
        actual = statement_actions(documents, sources)
    except ValueError as e:
        return [str(e)]
    untrue = [f"AWS gives {action} the resource types {actual.get(action)}, not {types}"
              for action, types in vendored.get("statementActions", {}).items() if actual.get(action) != types]
    for operation, source in sorted(sources.items()):
        try:
            changed = fetch_athena_glue_ddl_permissions.stale(source, fetch_athena_glue_ddl_permissions.fetch_url(
                source["_source"]["page"]))
        except (OSError, ValueError) as e:
            raise OSError(f"could not read {source['_source']['page']} ({e})") from e
        if changed:
            untrue.append(f"{source['_source']['page']} no longer gives what {STATEMENT_SOURCES[operation].name} "
                          f"holds for {operation}: run {source['_source']['fetcher']}")
    return untrue


def verify_path_sources() -> list[str]:
    """A vendored path source whose pages AWS has changed."""
    untrue: list[str] = []
    for namespace, source in sorted(load_path_sources().items()):
        pages = source["_source"]["pages"]
        try:
            changed = fetch_apigateway_arn_formats.stale(
                source, *(fetch_apigateway_arn_formats.fetch_url(page) for page in pages))
        except (OSError, ValueError) as e:
            raise OSError(f"could not read {' and '.join(pages)} ({e})") from e
        if changed:
            untrue.append(f"{' and '.join(pages)} no longer give what {PATH_SOURCES[namespace].name} holds for "
                          f"{namespace}: run {source['_source']['fetcher']}")
    return untrue


def verify_permission_sources() -> list[str]:
    """A vendored permission source whose pages AWS has changed."""
    untrue: list[str] = []
    for namespace, path in sorted(PERMISSION_SOURCES.items()):
        source = json.loads(path.read_text(encoding="utf-8"))
        fetcher = PERMISSION_FETCHERS[namespace]
        try:
            changed = fetcher.stale(source, *fetcher.fetch_all())
        except (OSError, ValueError) as e:
            raise OSError(f"could not read {' and '.join(source['_source']['pages'])} ({e})") from e
        if changed:
            untrue.append(f"{' and '.join(source['_source']['pages'])} and the pages they link to no longer give what "
                          f"{path.name} holds for {namespace}: run {source['_source']['fetcher']}")
    return untrue


def verify_required_type_source() -> list[str]:
    """The vendored required types (`REQUIRED_TYPE_SOURCE`) when the pages they come from now say otherwise."""
    source = json.loads(REQUIRED_TYPE_SOURCE.read_text(encoding="utf-8"))
    try:
        changed = fetch_required_resource_types.stale(source, *fetch_required_resource_types.fetch_all())
    except (OSError, ValueError) as e:
        raise OSError(f"could not read {' and '.join(source['_source']['pages'])} ({e})") from e
    return [f"{' and '.join(source['_source']['pages'])} no longer give what {REQUIRED_TYPE_SOURCE.name} holds: run "
            f"{source['_source']['fetcher']}"] if changed else []


def verify_permission_actions(vendored: dict, documents: list[dict]) -> list[str]:
    """An operation checked as several actions whose actions the Service Reference no longer gives those types."""
    actions = {f"{document['Name']}:{action['Name']}": [resource["Name"] for resource in action.get("Resources") or []]
               for document in documents for action in document.get("Actions", [])}
    return [f"AWS gives {action} the resource types {actions.get(action)}, not {kinds} as {operation} records"
            for operation, entry in sorted(vendored["operations"].items())
            for action, kinds in (entry.get("actions") or {}).items() if actions.get(action) != kinds]


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
        elif not set(actual["clients"]) <= set(vendored["namespaces"][namespace_of(operation)]):
            untrue.append(f"AWS lists {operation} for {actual['clients']}, which the file does not read")
    for namespace, types in vendored["arnFormats"].items():
        changed = sorted(kind for kind, arn in types.items() if formats.get(namespace, {}).get(kind) != arn)
        if changed:
            untrue.append(f"AWS no longer gives {namespace}'s {', '.join(changed)} the ARN format the file does")
    added = sorted(operation for operation in upstream
                   if namespace_of(operation) in vendored["namespaces"] and operation not in vendored["operations"])
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
        if document.get("resourceTagKeys", {}) != resource_tag_keys(documents, formats):
            untrue.append("resourceTagKeys differ from AWS Service Reference")
        if document.get("resourceKeys", {}) != resource_keys(documents, resolve_botocore_data(args.botocore_data)[0],
                                                             document["namespaces"]):
            untrue.append("resourceKeys differ from AWS Service Reference and botocore")
        untrue += verify_forwarded(document, documents)
        untrue += verify_permission_actions(document, documents)
        try:
            untrue += verify_statements(document, documents)
            untrue += verify_path_sources()
            untrue += verify_permission_sources()
            untrue += verify_required_type_source()
        except OSError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
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
