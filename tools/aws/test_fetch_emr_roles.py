#!/usr/bin/env python3
"""Tests for fetch_emr_roles.py, on pages in the Amazon EMR API reference's, management guide's and AWS CLI
reference's markup."""
from __future__ import annotations

import pytest

import fetch_emr_roles as f


def entry(member: str, description: str) -> str:
    return (f'<dt id="EMR-RunJobFlow-request-{member}"><a id="EMR-RunJobFlow-request-{member}"></a>'
            f'<b><code class="code">{member}</code></b></dt><dd><p>{description}</p><p>Type: String</p></dd>')


def role_page(title: str, role: str) -> str:
    return f"<title>{title}</title><p>The default role name is <code class=\"code\">{role}</code>.</p>"


POLICY = ('<pre class="programlisting"><div class="code-btn-container"><div class="btn-copy-code" title="Copy">'
          '<awsui-icon name="copy"></awsui-icon></div></div><code class="json ">{"Version":"2012-10-17","Statement":['
          '{"Sid":"PassRoleForElasticMapReduce","Effect":"Allow","Action":"iam:PassRole",'
          '"Resource":["arn:aws:iam::*:role/EMR_DefaultRole_V2"],'
          '"Condition":{"StringLike":{"iam:PassedToService":"elasticmapreduce.amazonaws.com*"}}},'
          '{"Sid":"PassRoleForEC2","Effect":"Allow","Action":"iam:PassRole",'
          '"Resource":["arn:aws:iam::*:role/EMR_EC2_DefaultRole"],'
          '"Condition":{"StringLike":{"iam:PassedToService":"ec2.amazonaws.com*"}}},'
          '{"Sid":"PassRoleForAutoScaling","Effect":"Allow","Action":"iam:PassRole",'
          '"Resource":["arn:aws:iam::*:role/EMR_AutoScaling_DefaultRole"],'
          '"Condition":{"StringLike":{"iam:PassedToService":"application-autoscaling.amazonaws.com*"}}}]}'
          '</code></pre>')
CLI = ('<pre>aws emr create-cluster [--service-role &lt;value&gt;]</pre><p>InstanceProfile=string</p>'
       '<div class="section" id="examples"><h2>Examples</h2>'
       '<pre>aws emr create-cluster --service-role EMR_DefaultRole --ec2-attributes '
       'InstanceProfile=EMR_EC2_DefaultRole --auto-scaling-role EMR_AutoScaling_DefaultRole</pre></div>')
PAGES = {
    f.API_PAGE: (
        entry("ServiceRole", "The IAM role that Amazon EMR assumes in order to access AWS resources on your behalf.")
        + entry("JobFlowRole", "Also called instance profile and Amazon EC2 role. The default role is "
                               "<code class=\"code\">EMR_EC2_DefaultRole</code>.")
        + entry("AutoScalingRole", "An IAM role for automatic scaling policies. The default role is "
                                   "<code class=\"code\">EMR_AutoScaling_DefaultRole</code>.")
        + entry("StepExecutionRoleArn", "The runtime role can be a cross-account IAM role.")),
    f.POLICY_PAGE: POLICY,
    f.CLI_PAGE: CLI,
    f.GUIDE + "emr-iam-role.html": role_page("Service role for Amazon EMR (EMR role)", "EMR_DefaultRole_V2"),
    f.GUIDE + "emr-iam-role-for-ec2.html": role_page("Service role for cluster EC2 instances (EC2 instance profile)",
                                                     "EMR_EC2_DefaultRole"),
    f.GUIDE + "emr-iam-role-automatic-scaling.html": role_page("Service role for automatic scaling in Amazon EMR",
                                                               "EMR_AutoScaling_DefaultRole"),
    f.RUNTIME_ROLES_PAGE: "<p>You can control access to the runtime role with the condition key "
                          "<code class=\"code\">elasticmapreduce:ExecutionRoleArn</code>.</p>",
}


def by_member(built: dict) -> dict:
    return {member["member"]: member for member in built["members"]}


def test_each_member_gives_its_default_roles_service_and_how_it_names_its_role():
    members = by_member(f.build(lambda url: PAGES[url]))
    assert members["ServiceRole"]["defaultRole"] == "EMR_DefaultRole_V2"
    assert members["ServiceRole"]["passedToService"] == ["elasticmapreduce.amazonaws.com"]
    assert members["ServiceRole"]["byName"] is True and members["ServiceRole"]["cliExample"] == "EMR_DefaultRole"
    assert members["JobFlowRole"]["passedToService"] == ["ec2.amazonaws.com"]
    assert members["JobFlowRole"]["instanceProfile"] is True and "byName" not in members["JobFlowRole"]
    assert members["JobFlowRole"]["cliExample"] == "EMR_EC2_DefaultRole"
    assert members["AutoScalingRole"]["passedToService"] == ["application-autoscaling.amazonaws.com"]
    assert members["StepExecutionRoleArn"]["accessControlledBy"] == "elasticmapreduce:ExecutionRoleArn"
    assert "passedToService" not in members["StepExecutionRoleArn"]


def test_a_description_naming_another_default_role_than_its_page_is_refused():
    changed = dict(PAGES, **{f.GUIDE + "emr-iam-role-for-ec2.html": role_page("EC2", "EMR_EC2_OtherRole")})
    with pytest.raises(ValueError, match="names EMR_EC2_DefaultRole"):
        f.build(lambda url: changed[url])


def test_a_page_whose_layout_changed_is_refused():
    for url, page, message in ((f.POLICY_PAGE, "<p>No policy.</p>", "passes no default role"),
                               (f.RUNTIME_ROLES_PAGE, "<p>Nothing.</p>", "names no condition key"),
                               (f.CLI_PAGE, "<pre>aws emr create-cluster</pre>", "has no examples"),
                               (f.API_PAGE, "<dl></dl>", "has no entry")):
        changed = dict(PAGES, **{url: page})
        with pytest.raises(ValueError, match=message):
            f.build(lambda u: changed[u])


def test_the_file_is_stale_when_a_page_says_something_else():
    vendored = f.build(lambda url: PAGES[url])
    assert not f.stale(vendored, PAGES)
    assert f.stale(vendored, dict(PAGES, **{f.POLICY_PAGE: POLICY.replace("ec2.amazonaws.com", "eks.amazonaws.com")}))
