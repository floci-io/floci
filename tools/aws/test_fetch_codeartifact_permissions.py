#!/usr/bin/env python3
"""Tests for fetch_codeartifact_permissions.py, on pages in the AWS CodeArtifact user guide's markup."""
from __future__ import annotations

import pytest

import fetch_codeartifact_permissions as f

COPY = ('<html><body><h2 id="copypackageversions-iam-actions">Required IAM permissions to copy packages</h2>'
        '<p>To copy package versions in CodeArtifact, the calling user must have the required IAM permissions.</p>'
        '<p>The user calling <code class="code">copy-package-versions</code> must have the '
        '<code class="code">ReadFromRepository</code> permission on the source repository and the '
        '<code class="code">CopyPackageVersions</code> permission on the destination repository.</p>'
        '<p>The source repository must have the <code class="code">ReadFromRepository</code> permission.</p>'
        '</body></html>')
POLICIES = ('<html><body><p>The ARN for an npm package without a scope has the empty string for the namespace field. '
            'For example, the following is the ARN for a package without a scope and with name '
            '<code class="code">react</code>.</p>'
            '<pre class="programlisting"><div class="code-btn-container"><div class="btn-copy-code" title="Copy">'
            '<awsui-icon name="copy"></awsui-icon></div></div><!--DEBUG: cli ()--><code class="nohighlight">'
            'arn:aws:codeartifact:<code class="replaceable">region-id</code>:<code class="replaceable">111122223333'
            '</code>:package/<code class="replaceable">my_domain</code>/<code class="replaceable">my_repo</code>/'
            '<code class="replaceable">npm</code>//<code class="replaceable">react</code></code></pre>'
            '<p>The following policy grants account 123456789012 permission.</p><pre>{"Version":"2012-10-17"}</pre>'
            '</body></html>')


def test_the_copy_page_gives_each_permission_and_the_resource_it_is_on():
    assert f.read_caller_permissions(COPY) == [{
        "operation": "CopyPackageVersions",
        "sentence": "The user calling copy-package-versions must have the ReadFromRepository permission on the source "
                    "repository and the CopyPackageVersions permission on the destination repository.",
        "permissions": [{"action": "codeartifact:ReadFromRepository", "resource": "source repository"},
                        {"action": "codeartifact:CopyPackageVersions", "resource": "destination repository"}]}]


def test_the_policies_page_gives_each_example_arn_with_its_paragraph():
    assert f.read_arn_examples(POLICIES) == [{
        "paragraph": "The ARN for an npm package without a scope has the empty string for the namespace field. For "
                     "example, the following is the ARN for a package without a scope and with name react .",
        "arn": "arn:aws:codeartifact:region-id:111122223333:package/my_domain/my_repo/npm//react"}]


def test_a_page_whose_layout_changed_is_refused():
    with pytest.raises(ValueError, match="no permissions"):
        f.read_caller_permissions("<html><body><p>Nothing here.</p></body></html>")
    with pytest.raises(ValueError, match="does not name each permission"):
        f.read_caller_permissions(COPY.replace("permission on the source repository", "rights on the source"))
    with pytest.raises(ValueError, match="no example ARN"):
        f.read_arn_examples("<html><body><p>Nothing here.</p></body></html>")


def test_the_file_is_stale_when_a_page_says_something_else():
    vendored = f.build(COPY, POLICIES)
    assert not f.stale(vendored, COPY, POLICIES)
    assert f.stale(vendored, COPY.replace("destination repository", "target repository"), POLICIES)
    assert f.stale(vendored, COPY, POLICIES.replace("npm</code>//", "npm</code>/none/"))
