#!/usr/bin/env python3
"""Tests for fetch_iam_identifiers.py, on pages in the IAM User Guide's and the IAM API reference's markup."""
from __future__ import annotations

import pytest

import fetch_iam_identifiers as f

COPY = '<div class="code-btn-container"><div class="btn-copy-code" title="Copy"></div></div>'
IDENTIFIERS = (
    '<html><body><h1>IAM identifiers</h1><p>If you use the IAM API or AWS Command Line Interface (AWS CLI) to create '
    'IAM resources, you can add an optional path. You can use a single path.</p><p>Syntax:</p>'
    '<pre class="programlisting">'
    + COPY + '<code class="json ">arn:aws:iam::<code class="replaceable">account</code>:root\n'
    'arn:aws:iam::<code class="replaceable">account</code>:user/<code class="replaceable">user-name-with-path</code>\n'
    'arn:aws:iam::<code class="replaceable">account</code>:saml-provider/<code class="replaceable">provider-name</code>'
    '</code></pre><p>Examples:</p><pre class="programlisting">' + COPY + '<code class="json ">'
    'arn:aws:iam::123456789012:user/John\narn:aws:iam::123456789012:user/division_abc/Jane</code></pre></body></html>')
OPERATIONS = ('<html><body><div id="main-col-body"><h1 class="topictitle">Actions</h1><ul>' + "".join(
    f'<li><p><a href="./API_{operation}.html">{operation}</a></p></li>'
    for operation in ("GetUser", "UpdateGroup", "TagMFADevice", "GetServiceLinkedRoleDeletionStatus", "ListUsers"))
    + '</ul></div></body></html>')


def reference(operation: str, description: str, parameters: dict[str, str]) -> str:
    """An API reference page, its description and each request parameter's paragraphs."""
    return (f'<html><body><h1 class="topictitle" id="API_{operation}">{operation}</h1><p>{description}</p>'
            f'<h2 id="API_{operation}_RequestParameters">Request Parameters</h2><div class="variablelist"><dl>'
            + "".join(f'<dt><span class="term"><b>{name}</b></span></dt><dd><p>{text}</p><p>Type: String</p></dd>'
                      for name, text in parameters.items())
            + f'</dl></div><h2 id="API_{operation}_ResponseElements">Response Elements</h2></body></html>')


PAGES = {f.page_of(operation): page for operation, page in {
    "GetUser": reference("GetUser", "Retrieves the user. If you do not specify a user name, IAM determines the user "
                                    "name implicitly based on the AWS access key ID used to sign the request.",
                         {"UserName": "The name of the user. If it is not included, it defaults to the user making "
                                      "the request."}),
    "UpdateGroup": reference("UpdateGroup", "The person making the request (the principal), must have permission to "
                                            "change the role group with the old name and the new name.",
                             {"GroupName": "The name of the group.", "NewGroupName": "New name for the group."}),
    "TagMFADevice": reference("TagMFADevice", "Adds tags.",
                              {"SerialNumber": "The device. For virtual MFA devices, the serial number is the same as "
                                               "the ARN."}),
    "GetServiceLinkedRoleDeletionStatus": reference(
        "GetServiceLinkedRoleDeletionStatus", "Retrieves the status.",
        {"DeletionTaskId": "The deletion task identifier. This identifier is returned by the DeleteServiceLinkedRole "
                           "operation in the format task/aws-service-role/&lt;service-principal-name&gt;/"
                           "&lt;role-name&gt;/&lt;task-uuid&gt;."}),
    "ListUsers": reference("ListUsers", "Lists the users.", {"PathPrefix": "The path prefix."}),
}.items()}


def test_the_identifiers_page_gives_its_arn_syntax_examples_and_paths():
    assert f.read_arns(IDENTIFIERS) == {
        "page": f.IDENTIFIERS_PAGE,
        "syntax": ["arn:aws:iam::{account}:root", "arn:aws:iam::{account}:user/{user-name-with-path}",
                   "arn:aws:iam::{account}:saml-provider/{provider-name}"],
        "examples": ["arn:aws:iam::123456789012:user/John", "arn:aws:iam::123456789012:user/division_abc/Jane"],
        "paths": ["If you use the IAM API or AWS Command Line Interface (AWS CLI) to create IAM resources, you can add "
                  "an optional path."]}


def test_each_api_reference_page_gives_the_sentences_saying_what_it_acts_on(monkeypatch):
    monkeypatch.setattr(f, "MINIMUM_OPERATIONS", 1)
    built = f.build(IDENTIFIERS, OPERATIONS, PAGES)
    assert built["signingUser"] == [
        {"operation": "GetUser", "parameter": None,
         "sentence": "If you do not specify a user name, IAM determines the user name implicitly based on the AWS "
                     "access key ID used to sign the request."},
        {"operation": "GetUser", "parameter": "UserName",
         "sentence": "If it is not included, it defaults to the user making the request."}]
    assert built["bothNames"] == [
        {"operation": "UpdateGroup", "parameter": None,
         "sentence": "The person making the request (the principal), must have permission to change the role group "
                     "with the old name and the new name."}]
    assert built["sameAsArn"] == [
        {"operation": "TagMFADevice", "parameter": "SerialNumber",
         "sentence": "For virtual MFA devices, the serial number is the same as the ARN."}]
    assert built["formats"] == [
        {"operation": "GetServiceLinkedRoleDeletionStatus", "parameter": "DeletionTaskId",
         "sentence": "This identifier is returned by the DeleteServiceLinkedRole operation in the format "
                     "task/aws-service-role/<service-principal-name>/<role-name>/<task-uuid>."}]


def test_a_page_whose_layout_changed_is_refused():
    with pytest.raises(ValueError, match="no code after 'Syntax:'"):
        f.read_arns("<html><body><p>Nothing here.</p></body></html>")
    with pytest.raises(ValueError, match="no ARN with a path"):
        f.read_arns(IDENTIFIERS.replace("-with-path", ""))
    with pytest.raises(ValueError, match="lists 5 operations"):
        f.read_operations(OPERATIONS)


def test_the_file_is_stale_when_a_page_says_something_else(monkeypatch):
    monkeypatch.setattr(f, "MINIMUM_OPERATIONS", 1)
    vendored = f.build(IDENTIFIERS, OPERATIONS, PAGES)
    assert not f.stale(vendored, IDENTIFIERS, OPERATIONS, PAGES)
    changed = dict(PAGES)
    changed[f.page_of("TagMFADevice")] = PAGES[f.page_of("TagMFADevice")].replace("same as the ARN", "device's id")
    assert f.stale(vendored, IDENTIFIERS, OPERATIONS, changed)
