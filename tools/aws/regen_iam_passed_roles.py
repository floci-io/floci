#!/usr/bin/env python3
"""Regenerate src/main/resources/aws/iam-passed-roles.json from AWS's Service Reference and botocore.

A request that passes a role to a service also needs `iam:PassRole` on that role. The IAM user guide:
"To pass a role (and its permissions) to an AWS service, a user must have permissions to pass the
role to the service." `PassedRoles` finds the roles a request passes, and this file tells it where
each operation's request carries them. It comes from two sources, each answering what the other
cannot:

- AWS's Service Reference, which lists `iam:PassRole` among an operation's `AuthorizedActions`, with
  the services the role goes to as `iam:PassedToService`, but does not say which request member
  names the role:

      https://servicereference.us-east-1.amazonaws.com/            (the index of services)
      https://servicereference.us-east-1.amazonaws.com/v1/<svc>/<svc>.json

- botocore's model of each SDK client the reference lists the operation for (Apache-2.0,
  https://github.com/boto/botocore, the pinned release), which gives the request's members but
  not which of them AWS checks.

A member of the operation's request is read as a role it passes when its name ends in `Role`,
`RoleArn`, `RoleName`, the plural of one, or one of those followed by `Update` or `Override`
(`RoleARNUpdate`, `serviceRoleOverride`), and it is a string or a list of strings without an enum.
That leaves out `RoleSessionName` and `AmbiguousRoleResolution`, which name no role. A member whose
own documentation says it takes a role by name as well as by ARN is marked `byName`, and Floci
resolves the name in the request's account, as the services do. A REST-XML request's members are
read from its XML body by the elements botocore serializes them as (S3's `PutBucketReplication`, the
`Role` of its `ReplicationConfiguration`), and one reached through a map or a flattened list, which
`PassedRoles` does not read there, fails generation. What the rule gets wrong for an
operation is corrected here, each with its reason: a role member it does not see (`EXTRA_MEMBERS`),
a member it sees that passes no role (`EXCLUDED_MEMBERS`), and which service a member's role goes to
when the reference names several (`MEMBER_SERVICES`). Where a namespace's documentation says it, which
service that is comes from a vendored source instead (`ROLE_SOURCES`): EKS's API reference names the
role each member takes, and its user guide the service each role's trust policy is for; EMR's
management guide names the default role of each role RunJobFlow takes and the service its full access
policy passes each to, and its API reference which member names an instance profile, whose roles are
the ones passed (`instanceProfile`), and which names a runtime role, whose access a condition key
controls instead, so it passes none.

Only the namespaces in `NAMESPACES` are read, and in them every operation the reference lists with
`iam:PassRole`, whether Floci serves it or not, so an operation Floci adds is checked from the
start. Each of those operations ends up in exactly one of three places, and generation fails on an
operation that would end up in none:

- `roles`: the members it reads, each with the service its role is passed to.
- `byHand`: operations `PassedRoles` reads itself, because their roles are not a request member: an
  instance profile's roles, a task definition's, a launch template's, or one that depends on the
  resource the request names. `PassedRolesCoverageTest` holds the two lists to each other.
- `notRead`: operations whose request names no role this table can read, each with its reason.

The Service Reference is live rather than a pinned dependency, so a fresh generation is not
byte-identical. The two gates split along the sources:

- `--check` is offline and needs the pinned botocore. The file must be well-formed, and every
  operation's members must be exactly what the rule and the corrections give from botocore for the
  models the file names, so a hand-edited member fails.
  Its namespaces, the reasons it gives for those it does not read, and its other namespaces must be
  the generator's, so a namespace taken out of `NAMESPACES` without the file regenerated fails.
- `--verify` is online and stdlib-only. Every operation the file carries must still list
  `iam:PassRole`, to the same services, for the same SDK clients. An operation AWS has added is
  reported without failing: until the next sync it passes roles unchecked, which is where it was
  before this file existed.

Run from anywhere in the repo:
    python3 tools/aws/regen_iam_passed_roles.py           # rewrite the vendored file in place
    python3 tools/aws/regen_iam_passed_roles.py --check   # offline: members match botocore
    python3 tools/aws/regen_iam_passed_roles.py --verify  # online: operations match AWS
    python3 tools/aws/regen_iam_passed_roles.py --source-dir <dir>  # read saved <svc>.json files
"""
from __future__ import annotations

import argparse
import functools
import json
import re
import sys
from collections.abc import Callable, Iterable
from datetime import datetime, timezone
from pathlib import Path

import fetch_apigateway_roles
import fetch_eks_roles
import fetch_emr_roles
from regen_iam_authorized_actions import fetch_references
from regen_partitions import resolve_botocore_data
from regen_query_services import latest_model_dir, load_model

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/aws/iam-passed-roles.json"

PASSED_TO_SERVICE = "iam:PassedToService"
SERVICE_SUFFIX = ".amazonaws.com"

# The IAM namespaces whose operations this file reads: those Floci serves over the JSON and Query
# protocols and Smithy RPC v2 CBOR, and the REST ones whose routes Floci authorizes by operation.
NAMESPACES = (
    "apigateway", "application-autoscaling", "aps", "athena", "autoscaling", "budgets", "cloudformation", "cloudtrail",
    "cloudwatch", "codebuild", "codedeploy", "codepipeline", "cognito-identity", "cognito-idp",
    "comprehend", "config", "datasync", "dms", "dsql", "ec2", "ecr", "ecs", "eks",
    "elasticfilesystem", "elasticloadbalancing", "elasticmapreduce",
    "emr-containers", "emr-serverless", "events", "firehose", "glue", "iam", "kinesis", "kinesisanalytics", "lambda",
    "logs", "pipes",
    "rds", "redshift", "redshift-serverless", "rekognition", "resource-groups", "s3", "s3tables", "sagemaker",
    "scheduler", "secretsmanager", "servicecatalog", "ses", "sns", "ssm", "states", "swf", "textract",
    "transcribe", "transfer", "translate", "wafv2",
)

# Namespaces Floci serves whose operations list iam:PassRole but are not read here yet, each with
# why. `PassedRolesCoverageTest` fails for a namespace Floci serves that is in neither list.
NOT_COVERED = {
    "access-analyzer": "REST routes no rule maps yet",
    "airflow": "REST routes no rule maps yet",
    "appconfig": "CreateExtension and UpdateExtension pass a role in Actions{}[].RoleArn, a map of lists, which this file "
                 "does not read yet",
    "appsync": "REST routes no rule maps yet",
    "aws-marketplace": "REST routes no rule maps yet",
    "backup": "REST routes no rule maps yet",
    "batch": "REST routes no rule maps yet",
    "bedrock": "REST routes no rule maps yet",
    "bedrock-agentcore": "REST routes no rule maps yet",
    "cloudfront": "REST routes no rule maps yet",
    "dlm": "REST routes no rule maps yet",
    "es": "REST routes no rule maps yet",
    "fis": "REST routes no rule maps yet",
    "guardduty": "REST routes no rule maps yet",
    "iot": "REST routes no rule maps yet",
    "kafka": "REST routes no rule maps yet",
    "macie2": "REST routes no rule maps yet",
    "mq": "REST routes no rule maps yet",
    "quicksight": "metadata-only REST subset; role passing and iam:PassRole checks are not implemented",
    "rum": "REST routes no rule maps yet",
}

# A member that names a role: Role, RoleArn, RoleName, their plurals, and an Update or Override of one.
ROLE_MEMBER_RE = re.compile(r"role(arn|name)?s?(update|override)?$", re.IGNORECASE)
# A member named for a role's name rather than its ARN.
ROLE_NAME_MEMBER_RE = re.compile(r"rolenames?$", re.IGNORECASE)
# Documentation saying the member takes a role's name: "The name or Amazon Resource Name (ARN) of the
# IAM role", "The short name or full Amazon Resource Name (ARN)", "The IAM role or Amazon Resource
# Name (ARN) of an IAM role", "The name of a service role".
BY_NAME_DOC_RE = re.compile(r"\bname or (the |its )?(full )?(ARN|Amazon Resource Name)\b"
                            r"|\bshort name or\b|\b(ARN|Amazon Resource Name)( \(ARN\))? or (the )?name\b"
                            r"|\brole or (the )?(ARN|Amazon Resource Name)\b"
                            r"|(?<!Resource )\bname of (the |a |an )?(\S+ ){0,5}role\b",
                            re.IGNORECASE)
# The protocols whose requests PassedRoles reads: form parameters, or a JSON, CBOR or XML body.
READ_PROTOCOLS = {"json", "query", "rest-json", "rest-xml", "smithy-rpc-v2-cbor"}
# How a vendored member path is written: dotted segments, a list step as `[]` or `[Name]` when its
# shape names its members, a map step as `{}` for every value or `{KEY}` for one key's.
MEMBER_PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[[A-Za-z0-9_]*\]|\[[A-Za-z0-9_]+=/[A-Za-z0-9_]+\]"
                            r"|\{[A-Za-z0-9_]*\})?"
                            r"(\.[A-Za-z][A-Za-z0-9_]*(\[[A-Za-z0-9_]*\]|\[[A-Za-z0-9_]+=/[A-Za-z0-9_]+\]"
                            r"|\{[A-Za-z0-9_]*\})?)*$")
NAMESPACE_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
OPERATION_RE = re.compile(r"^[a-z0-9-]+:[A-Za-z][A-Za-z0-9]{0,127}$")
SERVICE_RE = re.compile(r"^[a-z0-9][a-z0-9.-]*$")

BODY_LOCATIONS = (None, "")

# Operations PassedRoles reads itself, and what it reads.
BY_HAND = {
    "autoscaling:CreateAutoScalingGroup": "the roles of each launch template's instance profile, and the "
                                          "service-linked and lifecycle hook roles",
    "autoscaling:CreateLaunchConfiguration": "the roles of the instance profile it names or takes from an "
                                             "instance",
    "autoscaling:PutLifecycleHook": "the hook's role",
    "autoscaling:UpdateAutoScalingGroup": "the roles of each launch template's instance profile, and the "
                                          "service-linked and lifecycle hook roles",
    "application-autoscaling:RegisterScalableTarget": "the scalable target's role",
    "cloudformation:ContinueUpdateRollback": "the stack's service role",
    "cloudformation:CreateChangeSet": "the stack's service role",
    "cloudformation:CreateStack": "the stack's service role",
    "cloudformation:CreateStackSet": "the administration role, named or the default one",
    "cloudformation:DeleteStack": "the stack's service role",
    "cloudformation:UpdateStack": "the stack's service role",
    "cloudformation:UpdateStackSet": "the administration role, by the StackSet's permission model",
    "dms:CreateEndpoint": "the endpoint's role and the roles of the settings structures DMS stores",
    "dms:CreateReplicationInstance": "the role of the Kerberos settings",
    "dms:ModifyEndpoint": "the endpoint's role and the roles of the settings structures DMS stores",
    "dms:ModifyReplicationInstance": "the role of the Kerberos settings",
    "ec2:AssociateIamInstanceProfile": "the roles of the instance profile it names",
    "ec2:CreateFlowLogs": "the delivery role",
    "ec2:CreateLaunchTemplate": "the roles of the instance profile the template names",
    "ec2:CreateLaunchTemplateVersion": "the roles of the instance profile the template names",
    "ec2:ReplaceIamInstanceProfileAssociation": "the roles of the instance profile it names",
    "ec2:RequestSpotInstances": "the roles of the instance profile it names",
    "ec2:RunInstances": "the roles of the instance profile it names",
    "ecs:CreateCapacityProvider": "the infrastructure role, by name or ARN",
    "ecs:CreateService": "the task definition's roles and the service's infrastructure roles",
    "ecs:CreateTaskSet": "the task definition's roles",
    "ecs:RegisterTaskDefinition": "the task and task execution roles, by name or ARN",
    "ecs:RunTask": "the task definition's roles, their overrides and the volumes' infrastructure role",
    "ecs:StartTask": "the task definition's roles, their overrides and the volumes' infrastructure role",
    "ecs:UpdateCapacityProvider": "the infrastructure role, by name or ARN",
    "ecs:UpdateService": "the task definition's roles and the service's infrastructure roles",
    "eks:CreateAccessEntry": "the principal, for the node types alone",
    "eks:CreatePodIdentityAssociation": "the association's role, not its target role",
    "eks:UpdatePodIdentityAssociation": "the association's role, not its target role",
    "events:PutRule": "the rule's role",
    "events:PutTargets": "each target's role",
    "iam:AddRoleToInstanceProfile": "the role it adds, on the ARN IAM holds it under",
    "lambda:CreateFunction": "the execution role",
    "lambda:CreateMicrovmImage": "the build role",
    "lambda:CreateNetworkConnector": "the operator role",
    "lambda:CreateWebFunction": "the execution role",
    "lambda:CreateWebFunctionRevision": "the execution role",
    "lambda:RunMicrovm": "the execution role",
    "lambda:UpdateFunctionConfiguration": "the execution role",
    "lambda:UpdateMicrovmImage": "the build role",
    "lambda:UpdateNetworkConnector": "the operator role",
    "pipes:CreatePipe": "the pipe's role",
    "pipes:UpdatePipe": "the pipe's role",
    "rds:AddRoleToDBCluster": "the role it adds",
    "rds:AddRoleToDBInstance": "the role it adds",
    "rds:CreateDBCluster": "the directory, monitoring and associated roles",
    "rds:CreateDBInstance": "the directory and monitoring roles",
    "rds:CreateDBInstanceReadReplica": "the directory and monitoring roles",
    "rds:CreateDBProxy": "the proxy's role",
    "rds:ModifyDBCluster": "the directory and monitoring roles",
    "rds:ModifyDBInstance": "the directory and monitoring roles",
    "rds:ModifyDBProxy": "the proxy's role",
    "rds:ModifyOptionGroup": "the role each option takes in its IAM_ROLE_ARN setting",
    "rds:RemoveRoleFromDBCluster": "the role it removes",
    "rds:RemoveRoleFromDBInstance": "the role it removes",
    "rds:RestoreDBClusterFromSnapshot": "the directory, monitoring and associated roles",
    "rds:RestoreDBClusterToPointInTime": "the directory, monitoring and associated roles",
    "rds:RestoreDBInstanceFromDBSnapshot": "the directory role",
    "rds:RestoreDBInstanceToPointInTime": "the directory role",
    "rds:StartExportTask": "the export role",
    "redshift:CreateCluster": "the roles it associates",
    "redshift:ModifyClusterIamRoles": "the roles it associates",
    "redshift:RestoreFromClusterSnapshot": "the roles it associates",
    "redshift-serverless:CreateNamespace": "the namespace's roles and its default",
    "redshift-serverless:CreateScheduledAction": "the scheduled action's role",
    "redshift-serverless:UpdateNamespace": "the namespace's roles and its default",
    "redshift-serverless:UpdateScheduledAction": "the scheduled action's role",
    "scheduler:CreateSchedule": "the target's role",
    "scheduler:UpdateSchedule": "the target's role",
    "states:CreateStateMachine": "the state machine's role",
    "states:UpdateStateMachine": "the state machine's role",
}

BY_HAND.update({
    "logs:PutAccountPolicy": "the role a subscription filter policy's document names",
    "servicecatalog:CreateConstraint": "a launch constraint's role, from its parameters document",
    "servicecatalog:UpdateConstraint": "a launch constraint's role, by the type the constraint holds",
    "sns:CreatePlatformApplication": "the delivery status roles among its attributes",
    "sns:CreateTopic": "the delivery status roles among its attributes",
    "sns:SetPlatformApplicationAttributes": "the delivery status roles among its attributes",
    "sns:SetSMSAttributes": "the delivery status role among its attributes",
    "sns:SetSubscriptionAttributes": "the subscription role, when it is the attribute set",
    "sns:SetTopicAttributes": "a delivery status role, when it is the attribute set",
    "sns:Subscribe": "the subscription role among its attributes",
    "swf:RespondDecisionTaskCompleted": "the Lambda role of each decision that starts an execution, read from "
                                        "the attributes its decisionType names",
})

NO_ROLE_MEMBER = "no member of the request names a role"
# For an operation no botocore release models, as a legacy one the SDKs dropped.
NO_SUCH_OPERATION = "botocore's model has no such operation"
# For an operation the reference lists that no model of the pinned botocore has, one AWS has shipped since the
# pin. It lasts until a bump brings the operation in, when entry_for reads the role its request names.
NOT_IN_BOTOCORE = "the pinned botocore does not have the operation yet, so its request cannot be read"
# For an operation the pinned botocore has without the member that names its role, one AWS has added to the request
# since the pin. NOT_READ wins over what the request names, so take the entry out with the bump that brings the
# member in, and entry_for reads it.
MEMBER_NOT_IN_BOTOCORE = "the pinned botocore does not have the member naming the role yet, so it cannot be read"
RUNBOOK_PARAMETER = "the role is a parameter of the runbook it runs, not a member of the request"

# Operations whose request names no role this file can read.
NOT_READ = {
    "config:PutConformancePack": NO_ROLE_MEMBER,
    "config:PutOrganizationConformancePack": NO_ROLE_MEMBER,
    "config:PutRemediationConfigurations": RUNBOOK_PARAMETER,
    "dms:CreateInstanceProfile": NO_ROLE_MEMBER,
    "dms:CreateReplicationTask": NO_ROLE_MEMBER,
    "dms:ModifyInstanceProfile": NO_ROLE_MEMBER,
    "dms:ModifyReplicationTask": NO_ROLE_MEMBER,
    "ec2:RequestSpotFleet": "the request names its roles in EC2's own protocol, one of them as an instance profile",
    "ecs:CreateDaemon": "the roles are the daemon task definition's, which only a lookup finds",
    "ecs:UpdateDaemon": "the roles are the daemon task definition's, which only a lookup finds",
    "elasticmapreduce:UpdateStudio": NO_ROLE_MEMBER,
    "glue:GetJob": NO_ROLE_MEMBER,
    "glue:GetJobs": NO_ROLE_MEMBER,
    "lambda:AddEventSource": NO_SUCH_OPERATION,
    "logs:CreateImportTask": "the reference passes the role to cloudtrail or logs and nothing says which",
    "logs:StartQuery": NO_ROLE_MEMBER,
    "rds:CreateBlueGreenDeployment": NO_ROLE_MEMBER,
    "redshift:ModifyLakehouseConfiguration": NO_ROLE_MEMBER,
    "redshift-serverless:UpdateLakehouseConfiguration": NO_ROLE_MEMBER,
    "sagemaker:CreateTransformJob": NO_ROLE_MEMBER,
    "sagemaker:ImportHubContent": NO_ROLE_MEMBER,
    "ssm:CreateDocument": NO_ROLE_MEMBER,
    "ssm:DescribeAutomationStepExecutions": NO_ROLE_MEMBER,
    "ssm:GetAutomationExecution": NO_ROLE_MEMBER,
    "ssm:StartAutomationExecution": RUNBOOK_PARAMETER,
    "ssm:StartChangeRequestExecution": RUNBOOK_PARAMETER,
    "ssm:UpdateDocument": NO_ROLE_MEMBER,
    "ssm:UpdateServiceSetting": "a role is only ever a setting's value, which the request names by SettingId",
}

# A member the rule reads that passes no role, by operation, or by namespace for all its operations.
EXCLUDED_MEMBERS = {
    # "A list of roles to be attached": the roles the action attaches a policy to.
    "budgets:*": {"Definition.IamActionDefinition.Roles[]": "the roles the action attaches a policy to"},
    # "This is assumed through the roleArn for the pipeline": the pipeline's role assumes it.
    "codepipeline:*": {"pipeline.stages[].actions[].roleArn": "assumed with the pipeline's role, not passed"},
    # "The Snowflake role you wish to configure".
    "firehose:*": {
        "SnowflakeDestinationConfiguration.SnowflakeRoleConfiguration.SnowflakeRole": "a Snowflake role",
        "SnowflakeDestinationUpdate.SnowflakeRoleConfiguration.SnowflakeRole": "a Snowflake role",
    },
    # Glue's code-generated job nodes: "the role to assume using AWS Security Token Service (AWS STS)" for
    # a Kinesis source and "the Amazon Web Services Security Token Service (STS) role to assume" for a
    # DynamoDB one, which the job's own role assumes, and the role a Redshift node's temporary directory
    # is read and written with, which the Redshift cluster holds.
    "glue:*": {
        prefix + node: reason
        for prefix in ("CodeGenConfigurationNodes{}.", "JobUpdate.CodeGenConfigurationNodes{}.")
        for node, reason in (
            ("CatalogKinesisSource.StreamingOptions.RoleArn", "assumed with STS by the job's role"),
            ("DirectKinesisSource.StreamingOptions.RoleArn", "assumed with STS by the job's role"),
            ("DynamoDBELTConnectorSource.ConnectionOptions.DynamodbStsRoleArn", "assumed with STS by the job's role"),
            ("RedshiftSource.TmpDirIAMRole", "a role the Redshift cluster holds"),
            ("RedshiftTarget.TmpDirIAMRole", "a role the Redshift cluster holds"),
        )
    },
    # "The IAM role attached to your Redshift cluster that Amazon SageMaker uses to generate datasets":
    # a role the cluster already holds. "The Amazon Resource Name (ARN) of the SageMaker model registry
    # account": a role in another account.
    "sagemaker:*": {
        "ProcessingInputs[].DatasetDefinition.RedshiftDatasetDefinition.ClusterRoleArn":
            "a role the Redshift cluster already holds",
        "DefaultUserSettings.CanvasAppSettings.ModelRegisterSettings.CrossAccountModelRegisterRoleArn":
            "a role in the model registry's account",
        "UserSettings.CanvasAppSettings.ModelRegisterSettings.CrossAccountModelRegisterRoleArn":
            "a role in the model registry's account",
        # "the IAM roles that the execution role of SageMaker can assume", and those "used by the Amazon
        # EMR cluster instances or job execution environments".
        **{prefix + "JupyterLabAppSettings.EmrSettings." + member: reason
           for prefix in ("DefaultSpaceSettings.", "DefaultUserSettings.", "UserSettings.")
           for member, reason in (("AssumableRoleArns[]", "assumed by the execution role"),
                                  ("ExecutionRoleArns[]", "the EMR clusters' own roles"))},
        # "The name of the in-app role within the SageMaker Partner AI App".
        "ApplicationConfig.RoleGroupAssignments[].RoleName": "an in-app role, not an IAM one",
    },
    # The Automation execution role of each target account, which the run assumes there.
    "ssm:*": {
        "TargetLocations[].ExecutionRoleName": "a role in each target account",
        "Entries[].TargetLocations[].ExecutionRoleName": "a role in each target account",
    },
}

# A member naming a role that the rule does not see: (byName, why it names one).
_SNS_CALLER = ("SnsCallerArn", "\"the ARN of the IAM role in your Amazon Web Services account that Amazon Cognito "
               "will use to send SMS messages\"")
_EUMS_CALLER = ("CallerArn", "\"The ARN of the IAM role that Amazon Cognito assumes to send SMS messages\"")
# Glue's API reference: "ROLE_ARN - The role to be used for running queries."
_GLUE_CONNECTION_ROLE = (False, "the ROLE_ARN connection property, \"The role to be used for running queries\"")
# Lake Formation's view prerequisites: the role that defines a view needs "The iam:PassRole permission
# for AWS Glue and Lake Formation".
_GLUE_VIEW_DEFINER = (False, "the role that defines the view")
EXTRA_MEMBERS = {
    "cognito-idp:CreateUserPool": {
        "SmsConfiguration." + _SNS_CALLER[0]: (False, _SNS_CALLER[1]),
        "SmsConfiguration.EumsSms." + _EUMS_CALLER[0]: (False, _EUMS_CALLER[1]),
    },
    "cognito-idp:UpdateUserPool": {
        "SmsConfiguration." + _SNS_CALLER[0]: (False, _SNS_CALLER[1]),
        "SmsConfiguration.EumsSms." + _EUMS_CALLER[0]: (False, _EUMS_CALLER[1]),
    },
    "cognito-idp:SetUserPoolMfaConfig": {
        "SmsMfaConfiguration.SmsConfiguration." + _SNS_CALLER[0]: (False, _SNS_CALLER[1]),
        "SmsMfaConfiguration.SmsConfiguration.EumsSms." + _EUMS_CALLER[0]: (False, _EUMS_CALLER[1]),
    },
    "glue:CreateConnection": {"ConnectionInput.ConnectionProperties{ROLE_ARN}": _GLUE_CONNECTION_ROLE},
    "glue:UpdateConnection": {"ConnectionInput.ConnectionProperties{ROLE_ARN}": _GLUE_CONNECTION_ROLE},
    "glue:TestConnection": {"TestConnectionInput.ConnectionProperties{ROLE_ARN}": _GLUE_CONNECTION_ROLE},
    "glue:CreateTable": {"TableInput.ViewDefinition.Definer": _GLUE_VIEW_DEFINER},
    "glue:UpdateTable": {"TableInput.ViewDefinition.Definer": _GLUE_VIEW_DEFINER},
    # Keys the model's own enums give the maps: ServiceActionDefinitionKey's AssumeRole and
    # PropertyKey's LAUNCH_ROLE.
    "servicecatalog:CreateServiceAction": {"Definition{AssumeRole}": (False, "the role the action runs as")},
    "servicecatalog:UpdateServiceAction": {"Definition{AssumeRole}": (False, "the role the action runs as")},
    "servicecatalog:UpdateProvisionedProductProperties": {
        "ProvisionedProductProperties{LAUNCH_ROLE}": (False, "the role the product is launched with"),
    },
}

# The service a member's role goes to, for an operation the reference lists with several; "*" for
# every member.
ECS_INFRASTRUCTURE = "ecs"
ECS_TASKS = "ecs-tasks"
MEMBER_SERVICES = {
    # "The Amazon Resource Name (ARN) for CodePipeline to use", and "The pipeline role ARN associated
    # with the rule".
    "codepipeline:*": {
        "pipeline.roleArn": "codepipeline",
        "pipeline.stages[].beforeEntry.conditions[].rules[].roleArn": "codepipeline",
        "pipeline.stages[].onFailure.conditions[].rules[].roleArn": "codepipeline",
        "pipeline.stages[].onSuccess.conditions[].rules[].roleArn": "codepipeline",
    },
    # ECS infrastructure roles go to ecs, task and task execution roles to ecs-tasks, as PassedRoles'
    # ECS cases pass them.
    "ecs:*": {
        "executionRoleArn": ECS_TASKS,
        "taskRoleArn": ECS_TASKS,
        "infrastructureRoleArn": ECS_INFRASTRUCTURE,
    },
    # rds-preview is the principal of RDS's preview environment; PassedRoles' RDS cases pass to rds.
    "rds:*": {"*": "rds"},
}

# ROLE_SOURCES: the namespaces whose documentation says which role a request member names and the service that role is
# for, where the reference passes an operation's roles to several services without saying which member's goes where,
# and the vendored pages that say so. Granted for EKS: the API reference describes CreateCluster's roleArn as the
# cluster role, linking the user guide's page of it, whose trust policy names "eks.amazonaws.com", and EKS Auto Mode's
# computeConfig.nodeRoleArn as "the IAM Role EKS will assign to EC2 Managed Instances", naming the guide's IAM
# reference for EKS Auto Mode, whose section of the node role links the guide's page of it, whose trust policy names
# "ec2.amazonaws.com"; tools/aws/fetch_eks_roles.py vendors those pages and `vendored_service` reads them, choosing
# among the services the reference names. And granted for EMR: the management guide's page of each role RunJobFlow
# takes names its default role ("The default role name is EMR_EC2_DefaultRole"), and the full access policy it gives
# users passes each default role to its service alone ("iam:PassedToService": "ec2.amazonaws.com*"); the API reference
# says JobFlowRole names an instance profile ("Also called instance profile and Amazon EC2 role"), whose roles are the
# ones passed, the CLI reference's create-cluster examples give the others by name ("--service-role EMR_DefaultRole"),
# and the guide's page on runtime roles that access to StepExecutionRoleArn's runtime role is controlled
# by a condition key ("You can control access to the runtime role with the condition key
# elasticmapreduce:ExecutionRoleArn"), so it passes none; tools/aws/fetch_emr_roles.py vendors those pages and
# `vendored_role` reads them. And granted for API Gateway: its API references say which members take a role where no
# name the rule knows does ("To specify an IAM role for API Gateway to assume, use the role's Amazon Resource Name
# (ARN)"), that a REST API update names the property it changes by its patch operation's path ("the path for this
# property is /name"), that an import's body is an OpenAPI definition, and its developer guide which of that
# definition's extensions name a role and where each sits ("an extended property of the OpenAPI Operation object");
# tools/aws/fetch_apigateway_roles.py vendors those pages and `vendored_role_names`, `patch_role_members` and
# `document_members` read them. Each file is written by its own script from the pages, unchanged, and nothing about an
# operation is stated here.
ROLE_SOURCES: dict[str, Path] = {
    "apigateway": REPO_ROOT / "tools/aws/sources/apigateway-roles.json",
    "eks": REPO_ROOT / "tools/aws/sources/eks-roles.json",
    "elasticmapreduce": REPO_ROOT / "tools/aws/sources/emr-roles.json",
}
# The script that writes each namespace's role source, whose pages `verify_role_sources` reads it against.
ROLE_FETCHERS = {
    "apigateway": fetch_apigateway_roles,
    "eks": fetch_eks_roles,
    "elasticmapreduce": fetch_emr_roles,
}


# --------------------------------------------------------------------------- #
# The Service Reference: which operations pass a role, to which services
# --------------------------------------------------------------------------- #
def passed_role_operations(documents: list[dict]) -> dict[str, dict]:
    """Every operation of every namespace that lists iam:PassRole, with the services the role goes
    to, as `<service>` without `.amazonaws.com`, and the SDK clients it is listed for."""
    found: dict[str, dict] = {}
    for document in documents:
        namespace = document.get("Name")
        if not isinstance(namespace, str) or not NAMESPACE_RE.match(namespace):
            raise ValueError(f"{namespace!r} is not usable as an IAM namespace")
        for operation in document.get("Operations", []):
            name = operation.get("Name") if isinstance(operation, dict) else None
            if not isinstance(name, str):
                raise ValueError(f"{namespace}: operation entry without a usable 'Name': {operation!r}")
            authorized = operation.get("AuthorizedActions") or []
            if not isinstance(authorized, list) or not all(isinstance(entry, dict) for entry in authorized):
                raise ValueError(f"{namespace}:{name}: 'AuthorizedActions' is not a list of objects")
            passes = [entry for entry in authorized
                      if entry.get("Service") == "iam" and entry.get("Name") == "PassRole"]
            if not passes:
                continue
            services: set[str] = set()
            for entry in passes:
                context = entry.get("Context") or {}
                if not isinstance(context, dict):
                    raise ValueError(f"{namespace}:{name}: iam:PassRole 'Context' is not an object")
                for service in context.get(PASSED_TO_SERVICE, []):
                    if not isinstance(service, str) or not service.endswith(SERVICE_SUFFIX):
                        raise ValueError(f"{namespace}:{name}: {service!r} is not a service principal")
                    services.add(service[:-len(SERVICE_SUFFIX)])
            clients = sorted({sdk["Name"] for sdk in operation.get("SDK") or []
                              if isinstance(sdk, dict) and sdk.get("Package") == "Boto3"
                              and isinstance(sdk.get("Name"), str)})
            key = f"{namespace}:{name}"
            entry = found.setdefault(key, {"passedTo": set(), "clients": set()})
            entry["passedTo"] |= services
            entry["clients"] |= set(clients)
    return {key: {"passedTo": sorted(value["passedTo"]), "clients": sorted(value["clients"])}
            for key, value in sorted(found.items())}


# --------------------------------------------------------------------------- #
# botocore: which members of the request name a role
# --------------------------------------------------------------------------- #
def _documentation(member: dict, shape: dict) -> str:
    text = member.get("documentation") or shape.get("documentation") or ""
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text))


def _is_role_member(name: str, model: dict, shape_name: str) -> bool:
    return bool(ROLE_MEMBER_RE.search(name)) and _leaf(model, shape_name) is not None


def _leaf(model: dict, shape_name: str) -> dict | None:
    """The string shape a member holds itself, as each element of a list or each value of a map; None
    when it holds anything else, or an enum, which names no role."""
    shape = model["shapes"][shape_name]
    if shape["type"] == "list":
        shape = model["shapes"][shape["member"]["shape"]]
    elif shape["type"] == "map":
        shape = model["shapes"][shape["value"]["shape"]]
    return shape if shape["type"] == "string" and "enum" not in shape else None


def _step(shape: dict) -> str:
    """The path step into a list's elements or a map's values, and nothing for any other shape."""
    if shape["type"] == "list":
        return "[" + shape["member"].get("locationName", "") + "]"
    return "{}" if shape["type"] == "map" else ""


def _takes_name(name: str, member: dict, model: dict) -> bool:
    """Whether the member takes a role by name: it is named for one, or its documentation says it takes
    one, unless its pattern only admits an ARN."""
    shape = _leaf(model, member["shape"])
    if shape.get("pattern", "").lstrip("^").startswith("arn:"):
        return False
    return bool(ROLE_NAME_MEMBER_RE.search(name) or BY_NAME_DOC_RE.search(_documentation(member, shape)))


def readable(model: dict) -> bool:
    """Whether the model's requests come as form parameters or a JSON, CBOR or XML body."""
    metadata = model["metadata"]
    return bool(READ_PROTOCOLS & set(metadata.get("protocols") or [metadata.get("protocol")]))


def role_members(model: dict, operation: str, named: frozenset[str] = frozenset()) -> dict[str, bool]:
    """The members of the operation's request the rule reads, as vendored paths, each with whether
    it takes a role by name. An XML body is read by the elements botocore names, a list's elements by
    the name of its member or as `member`, so a role member reached through a map or a flattened list,
    which do not nest as these paths do, fails rather than being read wrong."""
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec or not readable(model):
        return {}
    root = model["shapes"][spec["input"]["shape"]]
    payload = root.get("payload")
    protocols = model["metadata"].get("protocols") or [model["metadata"].get("protocol")]
    xml = "rest-xml" in protocols
    # A REST JSON model's body member is also read by its botocore name where its wire name differs: Floci serves API
    # Gateway v2 over JSON 1.1 too ("AmazonApiGatewayV2."), whose bodies name members as botocore does
    # (CredentialsArn for credentialsArn), and its handler takes either spelling as the same member.
    by_member_name = "rest-json" in protocols
    found: dict[str, bool] = {}

    def unnested(shape: dict) -> bool:
        return xml and (shape["type"] == "map" or (shape["type"] == "list" and bool(shape.get("flattened"))))

    def walk(shape_name: str, prefixes: tuple[str, ...], top: bool, seen: frozenset, through: bool) -> None:
        if shape_name in seen:
            return
        shape = model["shapes"][shape_name]
        seen = seen | {shape_name}
        if shape["type"] in ("list", "map"):
            nested = shape["member" if shape["type"] == "list" else "value"]["shape"]
            walk(nested, tuple(prefix + _step(shape) for prefix in prefixes), False, seen,
                 through or unnested(shape))
        elif shape["type"] == "structure":
            for name, member in shape.get("members", {}).items():
                if top and member.get("location") not in BODY_LOCATIONS:
                    continue
                serialized = member.get("locationName", name)
                spellings = (serialized, name) if by_member_name and name != serialized else (serialized,)
                paths = tuple(dict.fromkeys(f"{prefix}.{spelling}" if prefix else spelling
                                            for prefix in prefixes for spelling in spellings))
                if _is_role_member(name, model, member["shape"]) or (
                        serialized in named and _leaf(model, member["shape"]) is not None):
                    if through or unnested(model["shapes"][member["shape"]]):
                        raise ValueError(f"{operation}'s {paths[0]} reaches the elements of a map or a flattened "
                                         "list, which PassedRoles does not read in an XML body")
                    by_name = _takes_name(name, member, model)
                    for path in paths:
                        leaf = path + _step(model["shapes"][member["shape"]])
                        found[leaf] = found.get(leaf, False) or by_name
                else:
                    walk(member["shape"], paths, False, seen, through)

    if payload is not None:
        member = root["members"][payload]
        walk(member["shape"], ("",), False, frozenset(), False)
    else:
        walk(spec["input"]["shape"], ("",), True, frozenset(), False)
    return found


PATH_STEP_RE = re.compile(r"^(?P<name>[A-Za-z][A-Za-z0-9_]*)(?:\[(?P<list>[A-Za-z0-9_]*)\]|\{(?P<key>[A-Za-z0-9_]*)\})?$")


def _walk(model: dict, operation: str, path: str) -> tuple[str, str, dict] | None:
    """The structure shape holding the member `path` ends at, that member's name, and the shape it holds past the
    path's last step; None for a path the operation's request does not have, a `{KEY}` step naming a key the map's
    key enum does not list."""
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec or not readable(model):
        return None
    owner = spec["input"]["shape"]
    shape = model["shapes"][owner]
    walked = None
    for step in path.split("."):
        match = PATH_STEP_RE.match(step)
        if match is None or shape["type"] != "structure":
            return None
        found = [(name, member) for name, member in shape.get("members", {}).items()
                 if member.get("locationName", name) == match["name"]]
        if not found:
            return None
        name, member = found[0]
        walked = (owner, name)
        owner = member["shape"]
        shape = model["shapes"][owner]
        if match["list"] is not None:
            if shape["type"] != "list":
                return None
            owner = shape["member"]["shape"]
        elif match["key"] is not None:
            if shape["type"] != "map":
                return None
            keys = model["shapes"][shape["key"]["shape"]].get("enum")
            if match["key"] and keys is not None and match["key"] not in keys:
                return None
            owner = shape["value"]["shape"]
        shape = model["shapes"][owner]
    return None if walked is None else (*walked, shape)


def member_exists(model: dict, operation: str, path: str) -> bool:
    """Whether the operation's request has a string member at `path`, a `{KEY}` step naming a key
    the map's key enum lists when it has one."""
    walked = _walk(model, operation, path)
    return walked is not None and walked[2]["type"] == "string"


@functools.cache
def _role_document(namespace: str) -> dict:
    """The namespace's vendored role source (`ROLE_SOURCES`); empty for a namespace it is not granted."""
    path = ROLE_SOURCES.get(namespace)
    return {} if path is None else json.loads(path.read_text(encoding="utf-8"))


def _role_source(namespace: str) -> tuple[dict, ...]:
    """The members the namespace's vendored role source (`ROLE_SOURCES`) gives; none for a namespace it is not
    granted."""
    return tuple(_role_document(namespace).get("members", ()))


def vendored_role_names(namespace: str, client: str) -> frozenset[str]:
    """The member names the namespace's vendored role source says take a role in the SDK client's requests, where the
    rule's names do not reach: API Gateway's `credentials`, "To specify an IAM Role for API Gateway to assume, use
    the role's Amazon Resource Name (ARN)"."""
    return frozenset(entry["member"] for entry in _role_document(namespace).get("roleMembers", ())
                     if entry["model"] == client)


def patch_role_members(namespace: str, client: str, model: dict, operation: str,
                       named: frozenset[str]) -> dict[str, bool]:
    """The patch operations of an update that change a role member of the resource it updates, where the namespace's
    vendored role source says a patch operation names the property it changes by its path ("the path for this
    property is /name"): `patchOperations[path=/credentials].value` for UpdateIntegration, whose Integration's
    credentials name a role. A top-level member of the operation's output shape names a role by the rule's names or
    the vendored ones."""
    patch = _role_document(namespace).get("patchPath")
    spec = model["operations"].get(operation)
    if patch is None or patch["model"] != client or spec is None or "output" not in spec:
        return {}
    request = model["shapes"][spec["input"]["shape"]]["members"]
    operations = request.get("patchOperations")
    if operations is None:
        return {}
    element = model["shapes"][model["shapes"][operations["shape"]]["member"]["shape"]]
    if not {"path", "value"} <= set(element.get("members", {})):
        return {}
    found = {}
    for name, member in model["shapes"][spec["output"]["shape"]].get("members", {}).items():
        serialized = member.get("locationName", name)
        if model["shapes"][member["shape"]]["type"] == "string" and (
                _is_role_member(name, model, member["shape"]) or serialized in named):
            found[f"patchOperations[path=/{serialized}].value"] = False
    return found


def document_members(namespace: str, client: str, model: dict, operation: str) -> dict[str, dict]:
    """The members of the operation's request the namespace's vendored role source says hold an OpenAPI definition,
    each with where the definition names a role: the extensions an Operation object or a security scheme carries,
    each with its property naming one, and the extension standing for the ANY method's Operation object. A member
    that is the request's payload is the request's whole body (`payload`)."""
    document = _role_document(namespace)
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec:
        return {}
    request = model["shapes"][spec["input"]["shape"]]
    found = {}
    for entry in document.get("documents", ()):
        if entry["model"] != client or entry["operation"] != operation:
            continue
        members = [(name, member) for name, member in request.get("members", {}).items()
                   if member.get("locationName", name) == entry["member"] or name == entry["member"]]
        if len(members) != 1:
            raise ValueError(f"{namespace}:{operation}: the vendored role source names its {entry['member']}, "
                             f"which its request does not have")
        name, member = members[0]
        openapi: dict = {"operation": {}, "securityScheme": {}}
        for extension in document.get("extensions", ()):
            openapi[extension["on"]][extension["extension"]] = extension["member"]
        openapi["anyMethod"] = document["anyMethod"]["extension"]
        field = {"openApi": openapi}
        if request.get("payload") == name:
            field["payload"] = True
            found[member.get("locationName", name)] = field
            continue
        # Each spelling a JSON body names it by, as role_members reads a REST JSON model's members.
        for spelling in dict.fromkeys((member.get("locationName", name), name)):
            found[spelling] = field
    return found


def vendored_role(operation: str, path: str, models: dict[str, dict]) -> dict | None:
    """What the namespace's vendored role source (`ROLE_SOURCES`) gives for the member at `path`, the member given by
    its operation when it is the request's own and by the botocore type holding it otherwise; None for a member the
    source does not give."""
    name = operation.split(":", 1)[1]
    own = PATH_STEP_RE.match(path) is not None
    for model in models.values():
        walked = _walk(model, name, path)
        if walked is None:
            continue
        owner, member, _shape = walked
        for entry in _role_source(namespace_of(operation)):
            if entry["member"] == member and (entry.get("operation") == name if own else entry.get("type") == owner):
                return entry
    return None


def vendored_service(operation: str, path: str, passed_to: list[str], models: dict[str, dict]) -> str | None:
    """The one of `passed_to` the role at `path` is for, by the namespace's vendored role source (`ROLE_SOURCES`):
    the service its documentation passes the role its member names to, or else the service a trust policy of that
    role is for. None for a member the source does not give."""
    entry = vendored_role(operation, path, models)
    if entry is None:
        return None
    services = [service.removesuffix(SERVICE_SUFFIX)
                for service in entry.get("passedToService", entry.get("trustedBy", []))]
    chosen = [service for service in services if service in passed_to]
    if len(chosen) != 1:
        raise ValueError(f"{operation}: the role at {path} is for {services} by {entry.get('rolePage')}, which "
                         f"is not one of {passed_to} the reference passes its roles to")
    return chosen[0]


def load_models(data: Path, clients: set[str]) -> dict[str, dict]:
    return {client: load_model(latest_model_dir(data, client))
            for client in sorted(clients) if (data / client).is_dir()}


# --------------------------------------------------------------------------- #
# The vendored document
# --------------------------------------------------------------------------- #
def namespace_of(operation: str) -> str:
    return operation.split(":", 1)[0]


def namespace_problems(document: dict, reads: Callable[[str], bool], read: Iterable[str],
                       not_covered: dict[str, str]) -> list[str]:
    """Where a vendored table's namespaces part from its generator's, offline: an operation the generator no longer
    reads, a namespace it reads that the table leaves out or one the table holds that it does not read, a reason a
    namespace is not read that differs, and another namespace the generator reads or names as not read. `--check`
    re-derives only what the table holds, so a namespace taken out of `NAMESPACES`, or moved into `NOT_COVERED`,
    without the table regenerated would otherwise pass it."""
    operations = document["operations"]
    problems = [f"{operation} is in the file, but its generator does not read it"
                for operation in sorted(operations) if not reads(operation)]
    expected = set(read) | {namespace_of(operation) for operation in operations if reads(operation)}
    held = set(document["namespaces"])
    if held != expected:
        problems.append(f"the file's namespaces are not its generator's: the file alone has {sorted(held - expected)}, "
                        f"the generator alone reads {sorted(expected - held)}; regenerate it")
    reasons = document.get("notCovered", {})
    differing = sorted(namespace for namespace in set(reasons) | set(not_covered)
                       if reasons.get(namespace) != not_covered.get(namespace))
    if differing:
        problems.append(f"the file's reasons for the namespaces it does not read are not its generator's, for "
                        f"{differing}; regenerate it")
    named = sorted(set(document.get("otherNamespaces", [])) & (expected | set(not_covered)))
    if named:
        problems.append(f"the file's other namespaces include {named}, which its generator reads or names as not read")
    return problems


def stale_namespaces(document: dict) -> list[str]:
    """Where the file's namespaces part from `NAMESPACES` and `NOT_COVERED` (`namespace_problems`)."""
    return namespace_problems(document, lambda operation: namespace_of(operation) in NAMESPACES, NAMESPACES,
                              NOT_COVERED)


def _overrides_for(table: dict, operation: str) -> dict:
    merged = dict(table.get(namespace_of(operation) + ":*", {}))
    merged.update(table.get(operation, {}))
    return merged


def entry_for(operation: str, passed_to: list[str], models: dict[str, dict]) -> dict:
    """What the file says for one operation, from its services and the models of its namespace."""
    if operation in BY_HAND:
        return {"passedTo": passed_to, "byHand": BY_HAND[operation]}
    name = operation.split(":", 1)[1]
    if operation in NOT_READ:
        return {"passedTo": passed_to, "notRead": NOT_READ[operation]}
    if not any(name in model["operations"] for model in models.values()):
        return {"passedTo": passed_to, "notRead": NOT_IN_BOTOCORE}
    members: dict[str, bool] = {}
    fields: dict[str, dict] = {}
    namespace = namespace_of(operation)
    for client, model in models.items():
        named = vendored_role_names(namespace, client)
        for path, by_name in (role_members(model, name, named)
                              | patch_role_members(namespace, client, model, name, named)).items():
            members[path] = members.get(path, False) or by_name
        for path, field in document_members(namespace, client, model, name).items():
            members[path] = False
            fields[path] = field
    excluded = _overrides_for(EXCLUDED_MEMBERS, operation)
    for path in excluded:
        members.pop(path, None)
    for path, (by_name, _reason) in _overrides_for(EXTRA_MEMBERS, operation).items():
        if not any(member_exists(model, name, path) for model in models.values()):
            raise ValueError(f"{operation}: EXTRA_MEMBERS names {path}, which its request does not have")
        members[path] = by_name
    # A member the vendored role source says names a role whose access a condition key controls, rather than passing
    # it, passes none.
    for path in [path for path in members if (vendored_role(operation, path, models) or {}).get("accessControlledBy")]:
        members.pop(path)
    if not members:
        raise ValueError(f"{operation}: no request member names a role; read it by hand (BY_HAND), "
                         f"name the member (EXTRA_MEMBERS) or say why it is not read (NOT_READ)")
    services = _overrides_for(MEMBER_SERVICES, operation)
    roles = []
    for path in sorted(members):
        role: dict = {"member": path}
        if path in services or "*" in services:
            role["passedTo"] = services.get(path, services.get("*"))
        elif len(passed_to) == 1:
            role["passedTo"] = passed_to[0]
        elif passed_to:
            vendored = vendored_service(operation, path, passed_to, models)
            if vendored is None:
                raise ValueError(f"{operation}: the role at {path} can go to any of {passed_to}; "
                                 f"say which in MEMBER_SERVICES or a vendored role source (ROLE_SOURCES)")
            role["passedTo"] = vendored
        vendored = vendored_role(operation, path, models) or {}
        if members[path] or vendored.get("byName"):
            role["byName"] = True
        if vendored.get("instanceProfile"):
            role["instanceProfile"] = True
        role.update(fields.get(path, {}))
        roles.append(role)
    return {"passedTo": passed_to, "roles": roles}


def build(documents: list[dict], provenance: str, data: Path, botocore: str) -> dict:
    upstream = passed_role_operations(documents)
    covered = {operation: value for operation, value in upstream.items()
               if namespace_of(operation) in NAMESPACES}
    _check_overrides(covered)
    namespaces: dict[str, list[str]] = {}
    for namespace in sorted(NAMESPACES):
        clients = sorted({client for operation, value in covered.items()
                          if namespace_of(operation) == namespace for client in value["clients"]})
        namespaces[namespace] = clients
    operations = {}
    unread = []
    for operation, value in covered.items():
        models = load_models(data, set(namespaces[namespace_of(operation)]))
        try:
            operations[operation] = entry_for(operation, value["passedTo"], models)
        except ValueError as e:
            unread.append(str(e))
    unread += _unmatched_corrections(covered, namespaces, data)
    if unread:
        raise ValueError("\n".join(unread))
    elsewhere = sorted({namespace_of(operation) for operation in upstream} - set(NAMESPACES))
    return {
        "_source": {
            "generator": "tools/aws/regen_iam_passed_roles.py",
            "reference": provenance,
            "botocore": botocore,
            "retrieved": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
            "note": "make iam-passed-roles-check re-derives every member from botocore; "
                    "make iam-passed-roles-verify checks every operation against AWS.",
        },
        "namespaces": namespaces,
        "notCovered": dict(sorted(NOT_COVERED.items())),
        "otherNamespaces": [namespace for namespace in elsewhere if namespace not in NOT_COVERED],
        "operations": operations,
    }


def _unmatched_corrections(covered: dict[str, dict], namespaces: dict[str, list[str]], data: Path) -> list[str]:
    """An exclusion, a member's service or a vendored role (`ROLE_SOURCES`) that no operation it applies to has, so
    none outlives its member."""
    found: dict[str, set[str]] = {}
    for operation in covered:
        if operation in BY_HAND or operation in NOT_READ:
            continue
        name = operation.split(":", 1)[1]
        for model in load_models(data, set(namespaces[namespace_of(operation)])).values():
            found.setdefault(operation, set()).update(role_members(model, name))
    problems = []
    for table_name, table in (("EXCLUDED_MEMBERS", EXCLUDED_MEMBERS), ("MEMBER_SERVICES", MEMBER_SERVICES)):
        for key, paths in table.items():
            scope = [operation for operation in found
                     if operation == key or (key.endswith(":*") and namespace_of(operation) == namespace_of(key))]
            for path in paths:
                if path != "*" and not any(path in found[operation] for operation in scope):
                    problems.append(f"{table_name} names {path} for {key}, which no operation there has")
    for namespace in ROLE_SOURCES:
        held = set()
        for operation in found:
            if namespace_of(operation) != namespace:
                continue
            name = operation.split(":", 1)[1]
            for model in load_models(data, set(namespaces[namespace])).values():
                for path in found[operation]:
                    walked = _walk(model, name, path)
                    if walked is not None:
                        held.add(("operation", name, walked[1]) if PATH_STEP_RE.match(path) else
                                 ("type", walked[0], walked[1]))
        for entry in _role_source(namespace):
            kind = "operation" if "operation" in entry else "type"
            if (kind, entry[kind], entry["member"]) not in held:
                problems.append(f"ROLE_SOURCES gives {namespace}'s {entry[kind]}.{entry['member']}, which no "
                                f"operation there reads a role from")
    return problems


def _check_overrides(covered: dict[str, dict]) -> None:
    """Every correction names an operation the reference lists with iam:PassRole, so none outlives it."""
    stale = []
    for table_name, table in (("BY_HAND", BY_HAND), ("NOT_READ", NOT_READ),
                              ("EXCLUDED_MEMBERS", EXCLUDED_MEMBERS), ("EXTRA_MEMBERS", EXTRA_MEMBERS),
                              ("MEMBER_SERVICES", MEMBER_SERVICES)):
        for key in table:
            if key.endswith(":*"):
                if namespace_of(key) not in NAMESPACES:
                    stale.append(f"{table_name} names {key}, a namespace this file does not read")
            elif key not in covered:
                stale.append(f"{table_name} names {key}, which the reference does not list with iam:PassRole")
    overlap = set(BY_HAND) & set(NOT_READ)
    stale += [f"{operation} is both read by hand and not read" for operation in sorted(overlap)]
    if stale:
        raise ValueError("; ".join(stale))


def render(document: dict) -> str:
    """The document with one operation to a line and one role to a line under it."""
    lines = ["{"]
    for key in ("_source", "namespaces", "notCovered", "otherNamespaces"):
        lines.append(f'  "{key}": {json.dumps(document[key])},')
    lines.append('  "operations": {')
    operations = list(document["operations"].items())
    for index, (operation, entry) in enumerate(operations):
        tail = "," if index < len(operations) - 1 else ""
        head = {key: value for key, value in entry.items() if key != "roles"}
        if "roles" not in entry:
            lines.append(f"    {json.dumps(operation)}: {json.dumps(head)}{tail}")
            continue
        opening = json.dumps(head)[:-1] + ', "roles": ['
        lines.append(f"    {json.dumps(operation)}: {opening}")
        roles = entry["roles"]
        for role_index, role in enumerate(roles):
            lines.append(f"      {json.dumps(role)}{',' if role_index < len(roles) - 1 else ''}")
        lines.append(f"    ]}}{tail}")
    lines += ["  }", "}"]
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- #
# Gates
# --------------------------------------------------------------------------- #
def validate(document: dict) -> list[str]:
    """Problems that make the vendored file unusable, rather than out of date."""
    problems: list[str] = []
    namespaces = document.get("namespaces")
    operations = document.get("operations")
    if not isinstance(namespaces, dict) or not namespaces:
        return ["namespaces is missing or empty"]
    if not isinstance(operations, dict) or not operations:
        return ["operations is missing or empty"]
    if list(namespaces) != sorted(namespaces):
        problems.append("namespaces is not sorted")
    if list(operations) != sorted(operations):
        problems.append("operations is not sorted")
    for operation, entry in operations.items():
        if not OPERATION_RE.match(operation) or namespace_of(operation) not in namespaces:
            problems.append(f"{operation!r} is not an operation of a namespace the file reads")
            continue
        kinds = [kind for kind in ("roles", "byHand", "notRead") if kind in entry]
        if len(kinds) != 1:
            problems.append(f"{operation} is not exactly one of roles, byHand and notRead")
            continue
        passed_to = entry.get("passedTo")
        if not isinstance(passed_to, list) or not all(isinstance(s, str) and SERVICE_RE.match(s) for s in passed_to):
            problems.append(f"{operation} has no usable passedTo")
            continue
        if kinds[0] != "roles":
            if not isinstance(entry[kinds[0]], str) or not entry[kinds[0]]:
                problems.append(f"{operation} gives no reason for {kinds[0]}")
            continue
        roles = entry["roles"]
        if not isinstance(roles, list) or not roles:
            problems.append(f"{operation} reads no role")
            continue
        for role in roles:
            path = role.get("member") if isinstance(role, dict) else None
            if not isinstance(path, str) or not MEMBER_PATH_RE.match(path):
                problems.append(f"{operation} reads {role!r}, which is no member path")
                continue
            service = role.get("passedTo")
            if service is None and passed_to:
                problems.append(f"{operation}: the role at {path} goes to no service")
            if service is not None and service not in passed_to:
                problems.append(f"{operation}: the role at {path} goes to {service}, which the reference "
                                f"does not name")
            if (set(role) - {"member", "passedTo", "byName", "instanceProfile", "openApi", "payload"}
                    or role.get("byName", True) is not True or role.get("instanceProfile", True) is not True
                    or role.get("payload", True) is not True
                    or not isinstance(role.get("openApi", {}), dict)):
                problems.append(f"{operation}: the role at {path} carries an unknown field")
    return problems


def derive(document: dict, data: Path) -> list[str]:
    """Where the members the file gives differ from what botocore gives for the models it names."""
    problems: list[str] = []
    for operation, entry in document["operations"].items():
        models = load_models(data, set(document["namespaces"][namespace_of(operation)]))
        try:
            expected = entry_for(operation, entry["passedTo"], models)
        except ValueError as e:
            problems.append(str(e))
            continue
        if expected != entry:
            problems.append(f"{operation}: the file has {json.dumps(entry)}, botocore gives {json.dumps(expected)}")
    return problems


def verify_role_sources() -> list[str]:
    """A vendored role source (`ROLE_SOURCES`) whose pages AWS has changed."""
    untrue: list[str] = []
    for namespace, path in sorted(ROLE_SOURCES.items()):
        source = json.loads(path.read_text(encoding="utf-8"))
        fetcher = ROLE_FETCHERS[namespace]
        try:
            changed = fetcher.stale(source, *fetcher.fetch_all())
        except (OSError, ValueError) as e:
            raise OSError(f"could not read {' and '.join(source['_source']['pages'])} ({e})") from e
        if changed:
            untrue.append(f"{' and '.join(source['_source']['pages'])} no longer give what {path.name} holds for "
                          f"{namespace}: run {source['_source']['fetcher']}")
    return untrue


def verify_against(vendored: dict, upstream: dict[str, dict]) -> tuple[list[str], list[str]]:
    """What the file claims and AWS does not, and the operations AWS has added since."""
    untrue: list[str] = []
    for operation, entry in vendored["operations"].items():
        actual = upstream.get(operation)
        if actual is None:
            untrue.append(f"AWS no longer lists iam:PassRole for {operation}")
        elif actual["passedTo"] != entry["passedTo"]:
            untrue.append(f"AWS passes {operation}'s role to {actual['passedTo']}, not {entry['passedTo']}")
        elif not set(actual["clients"]) <= set(vendored["namespaces"][namespace_of(operation)]):
            untrue.append(f"AWS lists {operation} for {actual['clients']}, which the file does not read")
    added = sorted(operation for operation in upstream
                   if namespace_of(operation) in vendored["namespaces"] and operation not in vendored["operations"])
    return untrue, added


def _shown(path: Path) -> Path:
    try:
        return path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="offline: members match the pinned botocore")
    parser.add_argument("--verify", action="store_true", help="online: operations match AWS")
    parser.add_argument("--source-dir", type=Path, default=None,
                        help="read saved <service>.json reference files instead of fetching them")
    parser.add_argument("--botocore-data", type=Path, default=None, help="botocore data directory")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.check or args.verify:
        if not args.output.exists():
            print(f"error: {args.output} does not exist", file=sys.stderr)
            return 1
        document = json.loads(args.output.read_text(encoding="utf-8"))
        problems = validate(document)
        if not problems and args.check:
            data, _ = resolve_botocore_data(args.botocore_data)
            problems = stale_namespaces(document) + derive(document, data)
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
            upstream = passed_role_operations(documents)
        except (OSError, ValueError) as e:
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        try:
            sources = verify_role_sources()
        except OSError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        untrue, added = verify_against(document, upstream)
        untrue += sources
        for claim in untrue:
            print(f"error: {claim}", file=sys.stderr)
        if untrue:
            print(f"error: {len(untrue)} vendored operation(s) do not hold upstream.", file=sys.stderr)
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
