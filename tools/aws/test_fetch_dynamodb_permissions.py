#!/usr/bin/env python3
"""Tests for fetch_dynamodb_permissions.py, on pages in the Amazon DynamoDB developer guide's markup."""
from __future__ import annotations

import pytest

import fetch_dynamodb_permissions as f

PARTIQL = ('<html><body><h1>IAM security policies with PartiQL for DynamoDB</h1><p>The following permissions are '
           'required:</p><div class="itemizedlist"> <ul class="itemizedlist">' + "".join(
               f'<li class="listitem"> <p>To {verb} items using PartiQL for DynamoDB, you must have '
               f'<code class="code">dynamodb:PartiQL{action}</code> permission on the table or index.</p> </li>'
               for verb, action in (("read", "Select"), ("insert", "Insert"), ("update", "Update"),
                                    ("delete", "Delete"))) + '</ul></div><h2>Example</h2></body></html>')
TRANSACTIONS = ('<html><body><p>You can use AWS Identity and Access Management (IAM) to restrict the actions.</p>'
                '<p>Permissions for <code class="code">Put</code>, <code class="code">Update</code>, and '
                '<code class="code">Get</code> actions are governed by the permissions used for the underlying '
                '<code class="code">PutItem</code>, <code class="code">UpdateItem</code>, and '
                '<code class="code">GetItem</code> operations. For the <code class="code">ConditionCheck</code> '
                'action, you can use the <code class="code">dynamodb:ConditionCheckItem</code> permission in IAM '
                'policies.</p><p>The following are examples.</p></body></html>')


def test_the_partiql_page_gives_each_required_permission_with_its_action():
    read = f.read_partiql(PARTIQL)
    assert read["lead"] == "The following permissions are required:"
    assert read["items"][0] == {"text": "To read items using PartiQL for DynamoDB, you must have "
                                        "dynamodb:PartiQLSelect permission on the table or index.",
                                "code": ["dynamodb:PartiQLSelect"]}
    assert [item["code"] for item in read["items"]] == [["dynamodb:PartiQLSelect"], ["dynamodb:PartiQLInsert"],
                                                        ["dynamodb:PartiQLUpdate"], ["dynamodb:PartiQLDelete"]]


def test_the_transactions_page_gives_each_sentence_with_its_kinds_and_actions():
    assert f.read_transactions(TRANSACTIONS)["sentences"] == [
        {"text": "Permissions for Put, Update, and Get actions are governed by the permissions used for the "
                 "underlying PutItem, UpdateItem, and GetItem operations.",
         "code": ["Put", "Update", "Get", "PutItem", "UpdateItem", "GetItem"]},
        {"text": "For the ConditionCheck action, you can use the dynamodb:ConditionCheckItem permission in IAM "
                 "policies.", "code": ["ConditionCheck", "dynamodb:ConditionCheckItem"]}]


def test_a_page_whose_layout_changed_is_refused():
    with pytest.raises(ValueError, match="no list"):
        f.read_partiql("<html><body></body></html>")
    with pytest.raises(ValueError, match="four items"):
        f.read_partiql(PARTIQL.replace('<code class="code">dynamodb:PartiQLDelete</code>', "dynamodb:PartiQLDelete"))
    with pytest.raises(ValueError, match="no paragraph"):
        f.read_transactions("<html><body><p>Nothing here.</p></body></html>")


def test_the_file_is_stale_when_a_page_says_something_else():
    vendored = f.build(PARTIQL, TRANSACTIONS)
    assert not f.stale(vendored, PARTIQL, TRANSACTIONS)
    assert f.stale(vendored, PARTIQL, TRANSACTIONS.replace("ConditionCheckItem", "ConditionItem"))
