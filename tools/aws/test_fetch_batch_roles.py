#!/usr/bin/env python3
"""Tests for fetch_batch_roles.py, on pages in the AWS Batch API reference's and user guide's markup."""
from __future__ import annotations

import pytest

import fetch_batch_roles as f

API = f.API_REFERENCE
GUIDE = f.USER_GUIDE


def entry(anchor: str, member: str, description: str) -> str:
    return (f'<dl><dt id="{anchor}"><b><code class="code">{member}</code></b></dt><dd><p>{description}</p>'
            f'<p>Type: String</p></dd></dl>')


def see(page: str, name: str) -> str:
    return f'For more information, see <a href="{GUIDE}{page}">{name}</a> in the <i>AWS Batch User Guide</i>.'


def trust(service: str) -> str:
    return ('<pre class="programlisting"><div class="code-btn-container"><div class="btn-copy-code" title="Copy">'
            '<awsui-icon name="copy"></awsui-icon></div></div><code class="json ">{ "Version":"2012-10-17", '
            '"Statement": [ { "Effect": "Allow", "Principal": { "Service": "' + service + '" }, '
            '"Action": "sts:AssumeRole" } ] }</code></pre>')


def guide(title: str, content: str) -> str:
    return f'<title>{title}</title><div id="main-col-body">{content}</div><div class="prev-next"></div>'


SERVICE_ROLE = ("The full Amazon Resource Name (ARN) of the IAM role that allows AWS Batch to make calls to other AWS "
                "services on your behalf. " + see("service_IAM_role.html", "AWS Batch service IAM role"))
INSTANCE_ROLE = ("The Amazon ECS instance profile applied to Amazon EC2 instances in a compute environment. You can "
                 "specify the short name or full Amazon Resource Name (ARN) of an instance profile. "
                 + see("instance_IAM_role.html", "Amazon ECS instance role"))
PAGES = {
    API + "API_CreateComputeEnvironment.html": entry("Batch-CreateComputeEnvironment-request-serviceRole",
                                                     "serviceRole", SERVICE_ROLE),
    API + "API_UpdateComputeEnvironment.html": entry(
        "Batch-UpdateComputeEnvironment-request-serviceRole", "serviceRole",
        SERVICE_ROLE + " " + see("updating-compute-environments.html", "Updating compute environments")),
    API + "API_ComputeResource.html": (
        entry("Batch-Type-ComputeResource-instanceRole", "instanceRole", INSTANCE_ROLE)
        + entry("Batch-Type-ComputeResource-spotIamFleetRole", "spotIamFleetRole",
                "The Amazon Resource Name (ARN) of the Amazon EC2 Spot Fleet IAM role applied to a SPOT compute "
                "environment. " + see("spot_fleet_IAM_role.html", "Amazon EC2 spot fleet role"))),
    API + "API_ComputeResourceUpdate.html": entry("Batch-Type-ComputeResourceUpdate-instanceRole", "instanceRole",
                                                  INSTANCE_ROLE),
    API + "API_ManagedInstancesProvider.html": entry(
        "Batch-Type-ManagedInstancesProvider-infrastructureRoleArn", "infrastructureRoleArn",
        "The Amazon Resource Name (ARN) of the IAM role that Amazon ECS assumes. You must have the iam:PassRole "
        "permission for this role with the condition iam:PassedToService: ecs.amazonaws.com ."),
    API + "API_UpdateManagedInstancesProviderConfiguration.html": entry(
        "Batch-Type-UpdateManagedInstancesProviderConfiguration-infrastructureRoleArn", "infrastructureRoleArn",
        "The updated Amazon Resource Name (ARN) of the IAM role that Amazon ECS assumes."),
    # The service role's page lists the service-linked roles' pages, each saying which services it trusts.
    GUIDE + "service_IAM_role.html": guide("Using service-linked roles for AWS Batch",
                                           '<a href="./using-service-linked-roles-batch-general.html">Using roles</a>'),
    GUIDE + "using-service-linked-roles-batch-general.html": guide(
        "Using roles for AWS Batch", "<p>The AWSServiceRoleForBatch service-linked role trusts the following services "
                                     "to assume the role: batch.amazonaws.com</p>"),
    # The instance role's page lists the page holding its trust policy.
    GUIDE + "instance_IAM_role.html": guide("Amazon ECS instance role",
                                            '<a href="./batch-check-ecsinstancerole.html">Check your role</a>'),
    GUIDE + "batch-check-ecsinstancerole.html": guide("Check your account's Amazon ECS instance role",
                                                      trust("ec2.amazonaws.com")),
    # The spot fleet role's trust policy is the argument of the CLI command that creates it.
    GUIDE + "spot_fleet_IAM_role.html": guide("Amazon EC2 spot fleet role",
                                              '<a href="./spot-fleet-roles-cli.html">With the AWS CLI</a>'),
    GUIDE + "spot-fleet-roles-cli.html": guide(
        "Create Amazon EC2 spot fleet roles with the AWS CLI",
        "<pre>$ aws iam create-role --role-name AmazonEC2SpotFleetTaggingRole --assume-role-policy-document '{ "
        '"Version":"2012-10-17", "Statement": [ { "Effect": "Allow", "Principal": { "Service": '
        "\"spotfleet.amazonaws.com\" }, \"Action\": \"sts:AssumeRole\" } ] }'</pre>"),
}


def test_each_member_is_read_with_the_service_its_role_is_for():
    built = f.build(lambda url: PAGES[url])
    by_member = {(m.get("operation") or m["type"], m["member"]): m for m in built["members"]}
    assert by_member[("CreateComputeEnvironment", "serviceRole")]["trustedBy"] == ["batch.amazonaws.com"]
    description = by_member[("CreateComputeEnvironment", "serviceRole")]["description"]
    assert description.startswith("The full Amazon Resource Name (ARN) of the IAM role that allows AWS Batch")
    assert "see AWS Batch service IAM role in the AWS Batch User Guide" in description
    # The later link names what the update depends on, not the role.
    assert by_member[("UpdateComputeEnvironment", "serviceRole")]["rolePage"] == GUIDE + "service_IAM_role.html"
    instance = by_member[("ComputeResource", "instanceRole")]
    assert (instance["trustedBy"], instance["instanceProfile"]) == (["ec2.amazonaws.com"], True)
    assert instance["trustPages"] == [GUIDE + "batch-check-ecsinstancerole.html"]
    assert by_member[("ComputeResource", "spotIamFleetRole")]["trustedBy"] == ["spotfleet.amazonaws.com"]
    assert "instanceProfile" not in by_member[("ComputeResource", "spotIamFleetRole")]
    assert by_member[("ManagedInstancesProvider", "infrastructureRoleArn")]["passedToService"] == ["ecs.amazonaws.com"]
    assert "rolePage" not in by_member[("ManagedInstancesProvider", "infrastructureRoleArn")]
    # The update names the provider's role in the same words, so its role is that one.
    update = by_member[("UpdateManagedInstancesProviderConfiguration", "infrastructureRoleArn")]
    assert update["passedToService"] == ["ecs.amazonaws.com"]
    assert update["sameRoleAs"] == {"type": "ManagedInstancesProvider", "member": "infrastructureRoleArn"}


def test_a_page_whose_layout_changed_is_refused():
    for url, page, message in (
            (API + "API_ComputeResource.html", "<dl></dl>", "has no entry"),
            (GUIDE + "batch-check-ecsinstancerole.html", guide("Check", "<p>No policy.</p>"), "pages of a role"),
            (API + "API_CreateComputeEnvironment.html",
             entry("Batch-CreateComputeEnvironment-request-serviceRole", "serviceRole", "A role."), "pages of a role"),
            (API + "API_UpdateManagedInstancesProviderConfiguration.html",
             entry("Batch-Type-UpdateManagedInstancesProviderConfiguration-infrastructureRoleArn",
                   "infrastructureRoleArn", "The updated ARN of some other role."), "no longer names")):
        changed = dict(PAGES, **{url: page})
        with pytest.raises(ValueError, match=message):
            f.build(lambda u: changed[u])


def test_the_file_is_stale_when_a_page_says_something_else():
    vendored = f.build(lambda url: PAGES[url])
    assert not f.stale(vendored, PAGES)
    assert f.stale(vendored, dict(PAGES, **{GUIDE + "batch-check-ecsinstancerole.html": guide(
        "Check your account's Amazon ECS instance role", trust("ecs-tasks.amazonaws.com"))}))
