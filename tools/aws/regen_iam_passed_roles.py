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
resolves the name in the request's account, as the services do. What the rule gets wrong for an
operation is corrected here, each with its reason: a role member it does not see (`EXTRA_MEMBERS`),
a member it sees that passes no role (`EXCLUDED_MEMBERS`), and which service a member's role goes to
when the reference names several (`MEMBER_SERVICES`).

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
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

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
    "application-autoscaling", "athena", "autoscaling", "budgets", "cloudformation", "cloudtrail",
    "cloudwatch", "codebuild", "codedeploy", "codepipeline", "cognito-identity", "cognito-idp",
    "comprehend", "config", "datasync", "dms", "dsql", "ec2", "ecr", "ecs", "eks", "elasticmapreduce",
    "events", "firehose", "glue", "iam", "kinesis", "kinesisanalytics", "lambda", "logs", "pipes",
    "rds", "redshift", "redshift-serverless", "rekognition", "resource-groups", "s3", "sagemaker", "scheduler",
    "secretsmanager", "servicecatalog", "ses", "sns", "ssm", "states", "swf", "textract",
    "transcribe", "transfer", "translate",
)

# Namespaces Floci serves whose operations list iam:PassRole but are not read here yet, each with
# why. `PassedRolesCoverageTest` fails for a namespace Floci serves that is in neither list.
NOT_COVERED = {
    "apigateway": "authorized by HTTP method, so no request is checked as one of these operations",
    "access-analyzer": "REST routes no rule maps yet",
    "airflow": "REST routes no rule maps yet",
    "appconfig": "REST routes no rule maps yet",
    "appsync": "REST routes no rule maps yet",
    "aps": "REST routes no rule maps yet",
    "aws-marketplace": "REST routes no rule maps yet",
    "backup": "REST routes no rule maps yet",
    "batch": "REST routes no rule maps yet",
    "bedrock": "REST routes no rule maps yet",
    "bedrock-agentcore": "REST routes no rule maps yet",
    "cloudfront": "REST routes no rule maps yet",
    "dlm": "REST routes no rule maps yet",
    "elasticfilesystem": "REST routes no rule maps yet",
    "emr-serverless": "REST routes no rule maps yet",
    "es": "REST routes no rule maps yet",
    "fis": "REST routes no rule maps yet",
    "guardduty": "REST routes no rule maps yet",
    "iot": "REST routes no rule maps yet",
    "kafka": "REST routes no rule maps yet",
    "macie2": "REST routes no rule maps yet",
    "mq": "REST routes no rule maps yet",
    "rum": "REST routes no rule maps yet",
    "s3tables": "REST routes no rule maps yet",
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
# The protocols whose requests PassedRoles reads: form parameters, or a JSON or CBOR body.
READ_PROTOCOLS = {"json", "query", "rest-json", "smithy-rpc-v2-cbor"}
# How a vendored member path is written: dotted segments, a list step as `[]` or `[Name]` when its
# shape names its members, a map step as `{}` for every value or `{KEY}` for one key's.
MEMBER_PATH_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\[[A-Za-z0-9_]*\]|\{[A-Za-z0-9_]*\})?"
                            r"(\.[A-Za-z][A-Za-z0-9_]*(\[[A-Za-z0-9_]*\]|\{[A-Za-z0-9_]*\})?)*$")
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
    "eks:CreateAddon": "the service account role and each Pod Identity association's role",
    "eks:CreateCluster": "the cluster role",
    "eks:CreateFargateProfile": "the pod execution role",
    "eks:CreateNodegroup": "the node role",
    "eks:CreatePodIdentityAssociation": "the association's role, not its target role",
    "eks:UpdateAddon": "the service account role and each Pod Identity association's role",
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
RUNBOOK_PARAMETER = "the role is a parameter of the runbook it runs, not a member of the request"
XML_BODY = "the request body is XML, which PassedRoles does not read"

# Operations whose request names no role this file can read.
NOT_READ = {
    "config:PutConformancePack": NO_ROLE_MEMBER,
    "config:PutOrganizationConformancePack": NO_ROLE_MEMBER,
    "config:PutRemediationConfigurations": RUNBOOK_PARAMETER,
    "dms:CreateInstanceProfile": NO_ROLE_MEMBER,
    "dms:ModifyInstanceProfile": NO_ROLE_MEMBER,
    "ec2:RequestSpotFleet": "the request names its roles in EC2's own protocol, one of them as an instance profile",
    "ecs:CreateDaemon": "the roles are the daemon task definition's, which only a lookup finds",
    "ecs:CreateGateway": "botocore's model has no such operation",
    "ecs:UpdateDaemon": "the roles are the daemon task definition's, which only a lookup finds",
    "ecs:UpdateGateway": "botocore's model has no such operation",
    "eks:UpdateClusterConfig": "the reference passes the node role to ec2 or eks and nothing says which",
    "elasticmapreduce:RunJobFlow": "JobFlowRole names an instance profile, and the reference passes the roles "
                                   "to three services without saying which goes where",
    "elasticmapreduce:UpdateStudio": NO_ROLE_MEMBER,
    "glue:GetJob": NO_ROLE_MEMBER,
    "glue:GetJobs": NO_ROLE_MEMBER,
    "lambda:AddEventSource": "botocore's model has no such operation",
    "logs:CreateImportTask": "the reference passes the role to cloudtrail or logs and nothing says which",
    "rds:CreateBlueGreenDeployment": NO_ROLE_MEMBER,
    "s3:CreateAccessGrantsLocation": XML_BODY,
    "s3:PutBucketReplication": XML_BODY,
    "redshift:ModifyLakehouseConfiguration": NO_ROLE_MEMBER,
    "redshift-serverless:UpdateLakehouseConfiguration": NO_ROLE_MEMBER,
    "sagemaker:CreateTrainingSession": "the reference passes the role to sagemaker or training-session.sagemaker "
                                       "and nothing says which",
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
    """Whether the model's requests come as form parameters or a JSON or CBOR body."""
    metadata = model["metadata"]
    return bool(READ_PROTOCOLS & set(metadata.get("protocols") or [metadata.get("protocol")]))


def role_members(model: dict, operation: str) -> dict[str, bool]:
    """The members of the operation's request the rule reads, as vendored paths, each with whether
    it takes a role by name."""
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec or not readable(model):
        return {}
    root = model["shapes"][spec["input"]["shape"]]
    payload = root.get("payload")
    found: dict[str, bool] = {}

    def walk(shape_name: str, prefix: str, top: bool, seen: frozenset) -> None:
        if shape_name in seen:
            return
        shape = model["shapes"][shape_name]
        seen = seen | {shape_name}
        if shape["type"] == "list":
            walk(shape["member"]["shape"], prefix + _step(shape), False, seen)
        elif shape["type"] == "map":
            walk(shape["value"]["shape"], prefix + _step(shape), False, seen)
        elif shape["type"] == "structure":
            for name, member in shape.get("members", {}).items():
                if top and member.get("location") not in BODY_LOCATIONS:
                    continue
                serialized = member.get("locationName", name)
                path = f"{prefix}.{serialized}" if prefix else serialized
                if _is_role_member(name, model, member["shape"]):
                    leaf = path + _step(model["shapes"][member["shape"]])
                    by_name = _takes_name(name, member, model)
                    found[leaf] = found.get(leaf, False) or by_name
                else:
                    walk(member["shape"], path, False, seen)

    if payload is not None:
        member = root["members"][payload]
        walk(member["shape"], "", False, frozenset())
    else:
        walk(spec["input"]["shape"], "", True, frozenset())
    return found


PATH_STEP_RE = re.compile(r"^(?P<name>[A-Za-z][A-Za-z0-9_]*)(?:\[(?P<list>[A-Za-z0-9_]*)\]|\{(?P<key>[A-Za-z0-9_]*)\})?$")


def member_exists(model: dict, operation: str, path: str) -> bool:
    """Whether the operation's request has a string member at `path`, a `{KEY}` step naming a key
    the map's key enum lists when it has one."""
    spec = model["operations"].get(operation)
    if spec is None or "input" not in spec or not readable(model):
        return False
    shape = model["shapes"][spec["input"]["shape"]]
    for step in path.split("."):
        match = PATH_STEP_RE.match(step)
        if match is None or shape["type"] != "structure":
            return False
        found = [member for name, member in shape.get("members", {}).items()
                 if member.get("locationName", name) == match["name"]]
        if not found:
            return False
        shape = model["shapes"][found[0]["shape"]]
        if match["list"] is not None:
            if shape["type"] != "list":
                return False
            shape = model["shapes"][shape["member"]["shape"]]
        elif match["key"] is not None:
            if shape["type"] != "map":
                return False
            keys = model["shapes"][shape["key"]["shape"]].get("enum")
            if match["key"] and keys is not None and match["key"] not in keys:
                return False
            shape = model["shapes"][shape["value"]["shape"]]
    return shape["type"] == "string"


def load_models(data: Path, clients: set[str]) -> dict[str, dict]:
    return {client: load_model(latest_model_dir(data, client))
            for client in sorted(clients) if (data / client).is_dir()}


# --------------------------------------------------------------------------- #
# The vendored document
# --------------------------------------------------------------------------- #
def namespace_of(operation: str) -> str:
    return operation.split(":", 1)[0]


def _overrides_for(table: dict, operation: str) -> dict:
    merged = dict(table.get(namespace_of(operation) + ":*", {}))
    merged.update(table.get(operation, {}))
    return merged


def entry_for(operation: str, passed_to: list[str], models: dict[str, dict]) -> dict:
    """What the file says for one operation, from its services and the models of its namespace."""
    if operation in BY_HAND:
        return {"passedTo": passed_to, "byHand": BY_HAND[operation]}
    if operation in NOT_READ:
        return {"passedTo": passed_to, "notRead": NOT_READ[operation]}
    name = operation.split(":", 1)[1]
    members: dict[str, bool] = {}
    for model in models.values():
        for path, by_name in role_members(model, name).items():
            members[path] = members.get(path, False) or by_name
    excluded = _overrides_for(EXCLUDED_MEMBERS, operation)
    for path in excluded:
        members.pop(path, None)
    for path, (by_name, _reason) in _overrides_for(EXTRA_MEMBERS, operation).items():
        if not any(member_exists(model, name, path) for model in models.values()):
            raise ValueError(f"{operation}: EXTRA_MEMBERS names {path}, which its request does not have")
        members[path] = by_name
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
            raise ValueError(f"{operation}: the role at {path} can go to any of {passed_to}; "
                             f"say which in MEMBER_SERVICES")
        if members[path]:
            role["byName"] = True
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
    """An exclusion or a member's service that no operation it applies to has, so none outlives its member."""
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
            if set(role) - {"member", "passedTo", "byName"} or role.get("byName", True) is not True:
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
            upstream = passed_role_operations(documents)
        except (OSError, ValueError) as e:
            print(f"error: could not read the Service Reference ({e})", file=sys.stderr)
            return 2
        untrue, added = verify_against(document, upstream)
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
