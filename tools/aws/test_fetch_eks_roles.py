#!/usr/bin/env python3
"""Tests for fetch_eks_roles.py, on pages in the Amazon EKS API reference's and user guide's markup."""
from __future__ import annotations

import pytest

import fetch_eks_roles as f

API = f.API_REFERENCE
GUIDE = f.USER_GUIDE


def trust(service: str) -> str:
    return ('<pre class="programlisting"><div class="code-btn-container"><div class="btn-copy-code" title="Copy">'
            '<awsui-icon name="copy"></awsui-icon></div></div><code class="json ">{ "Version":"2012-10-17", '
            '"Statement": [ { "Effect": "Allow", "Principal": { "Service": "' + service + '" }, '
            '"Action": "sts:AssumeRole" } ] }</code></pre>')


PAGES = {
    API + "API_CreateCluster.html": (
        '<dl><dt id="AmazonEKS-CreateCluster-request-roleArn"><a id="AmazonEKS-CreateCluster-request-roleArn"></a>'
        '<b><code class="code">roleArn</code></b></dt><dd><p>The Amazon Resource Name (ARN) of the IAM role that '
        'provides permissions for the Kubernetes control plane to make calls to AWS API operations on your behalf. '
        'For more information, see <a href="https://docs.aws.amazon.com/eks/latest/userguide/service_IAM_role.html">'
        'Amazon EKS Service IAM Role</a> in the <i>Amazon EKS User Guide</i>.</p><p>Type: String</p></dd></dl>'),
    API + "API_ComputeConfigRequest.html": (
        '<dl><dt id="AmazonEKS-Type-ComputeConfigRequest-nodeRoleArn"><b><code class="code">nodeRoleArn</code></b>'
        '</dt><dd><p>The ARN of the IAM Role EKS will assign to EC2 Managed Instances in your EKS Auto Mode cluster. '
        'For more information, see the IAM Reference in the <i>Amazon EKS User Guide</i>.</p><p>Type: String</p>'
        '</dd></dl>'),
    GUIDE + "service_IAM_role.html": "<title>Amazon EKS cluster IAM role</title>" + trust("eks.amazonaws.com"),
    GUIDE + "auto-learn-iam.html": (
        '<h2 id="auto-learn-cluster-iam-role">Cluster IAM role</h2><p>The Cluster IAM role is used by Amazon EKS.</p>'
        '<h2 id="auto-learn-node-iam-role">Node IAM role</h2><p>This role grants EC2 instances running as Kubernetes '
        'nodes the necessary permissions.</p><p>See <a href="./security-iam-awsmanpol.html">AWS managed policies</a> '
        'and <a href="./create-node-role.html">Amazon EKS node IAM role</a>.</p>'
        '<h2 id="auto-learn-slr">Service-linked role</h2><p>Other.</p>'),
    GUIDE + "security-iam-awsmanpol.html": "<title>AWS managed policies</title><p>No trust policy here.</p>",
    GUIDE + "create-node-role.html": "<title>Amazon EKS node IAM role</title>" + trust("ec2.amazonaws.com"),
}


def test_each_member_gives_its_description_and_the_service_its_roles_page_trusts():
    built = f.build(lambda url: PAGES[url])
    assert built["members"] == [
        {"operation": "CreateCluster", "member": "roleArn",
         "description": "The Amazon Resource Name (ARN) of the IAM role that provides permissions for the Kubernetes "
                        "control plane to make calls to AWS API operations on your behalf. For more information, see "
                        "Amazon EKS Service IAM Role in the Amazon EKS User Guide .",
         "rolePage": GUIDE + "service_IAM_role.html", "title": "Amazon EKS cluster IAM role",
         "trustedBy": ["eks.amazonaws.com"]},
        {"type": "ComputeConfigRequest", "member": "nodeRoleArn",
         "description": "The ARN of the IAM Role EKS will assign to EC2 Managed Instances in your EKS Auto Mode "
                        "cluster. For more information, see the IAM Reference in the Amazon EKS User Guide .",
         "reference": GUIDE + "auto-learn-iam.html", "section": "Node IAM role",
         "sectionText": "This role grants EC2 instances running as Kubernetes nodes the necessary permissions. See "
                        "AWS managed policies and Amazon EKS node IAM role .",
         "rolePage": GUIDE + "create-node-role.html", "title": "Amazon EKS node IAM role",
         "trustedBy": ["ec2.amazonaws.com"]}]


def test_a_page_whose_layout_changed_is_refused():
    missing = dict(PAGES, **{API + "API_CreateCluster.html": "<dl></dl>"})
    with pytest.raises(ValueError, match="has no entry"):
        f.build(lambda url: missing[url])
    untrusted = dict(PAGES, **{GUIDE + "create-node-role.html": "<title>Amazon EKS node IAM role</title>"})
    with pytest.raises(ValueError, match="not one"):
        f.build(lambda url: untrusted[url])
    unsectioned = dict(PAGES, **{GUIDE + "auto-learn-iam.html": "<h2>Cluster IAM role</h2><p>Only.</p>"})
    with pytest.raises(ValueError, match="no section"):
        f.build(lambda url: unsectioned[url])


def test_the_file_is_stale_when_a_page_says_something_else():
    vendored = f.build(lambda url: PAGES[url])
    assert not f.stale(vendored, PAGES)
    changed = dict(PAGES, **{GUIDE + "create-node-role.html": "<title>Amazon EKS node IAM role</title>"
                                                               + trust("eks.amazonaws.com")})
    assert f.stale(vendored, changed)
