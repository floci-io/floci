"""Tests for fetch_athena_glue_ddl_permissions.

Run with: pytest tools/aws -q  (or: make aws-data-test)
"""
from __future__ import annotations

import json

import pytest

import fetch_athena_glue_ddl_permissions as f


def code_block(*statements: str) -> str:
    """A policy as the page renders it: each statement opened by a span, its example names marked replaceable."""
    return ('<pre class="programlisting"><div class="code-btn-container"><div class="btn-copy-code" title="Copy">'
            '<awsui-icon name="copy"></awsui-icon></div></div><!--DEBUG: cli (json)--><code class="json ">'
            + ",\n".join("<span>{</span>" + statement for statement in statements) + "</code></pre>")


def arn(path: str) -> str:
    return ('"arn:aws:glue:<code class="replaceable">us-east-1</code>:<code class="replaceable">123456789012</code>:'
            + path + '"')


CREATE_DATABASE = ('\n "Effect": "Allow",\n "Action": [\n "glue:GetDatabase",\n "glue:CreateDatabase"\n ],\n'
                   ' "Resource": [\n ' + arn("catalog") + ',\n '
                   + arn('database/<code class="replaceable">example_db</code>') + '\n ]\n}')
CREATE_TABLE = ('\n "Sid": "TablePermissions",\n "Effect": "Allow",\n "Action": [\n "glue:CreateTable"\n ],\n'
                ' "Resource": [\n ' + arn("catalog") + ',\n '
                + arn('table/<code class="replaceable">example_db</code>/<code class="replaceable">test</code>')
                + '\n ]\n}')


def page(rows: str, sentence: str = 'In the examples, replace the <code class="code">example_db</code> database and '
                                    '<code class="code">test</code>\n table with your own database and table names.'):
    return (f'<html><body><table><tr><th>To perform this task</th><th>See</th></tr></table><p>{sentence}</p>'
            '<div class="table-container"><table id="d0e2"><thead><tr><th>DDL statement</th>'
            '<th>Example of an IAM access policy granting access to the resource</th></tr></thead>'
            f'<tr>{rows}</tr></table></div></body></html>')


def row(name: str, text: str, *statements: str) -> str:
    return f'<td tabindex="-1">{name}</td><td tabindex="-1">{text}{code_block(*statements)}</td></tr><tr>'


ROWS = (row("CREATE DATABASE", 'Allows you to create the database named <code class="code">example_db</code>.',
            CREATE_DATABASE)
        + row("CREATE TABLE", 'Allows you to create a table named <code class="code">test</code> in the\n'
              '<code class="code">example_db</code>\n database.', CREATE_DATABASE, CREATE_TABLE))
DATABASE = {"Effect": "Allow", "Action": ["glue:GetDatabase", "glue:CreateDatabase"],
            "Resource": ["arn:aws:glue:us-east-1:123456789012:catalog",
                         "arn:aws:glue:us-east-1:123456789012:database/example_db"]}


def test_each_statement_keeps_its_sentence_and_its_example_policy_as_the_page_gives_them():
    assert f.read_page(page(ROWS)) == {
        "examples": {"example_db": "database", "test": "table"},
        "statements": {
            "CREATE DATABASE": {"text": "Allows you to create the database named example_db.", "policy": [DATABASE]},
            "CREATE TABLE": {"text": "Allows you to create a table named test in the example_db database.",
                             "policy": [DATABASE, {"Sid": "TablePermissions", "Effect": "Allow",
                                                   "Action": ["glue:CreateTable"],
                                                   "Resource": ["arn:aws:glue:us-east-1:123456789012:catalog",
                                                                "arn:aws:glue:us-east-1:123456789012:table/"
                                                                "example_db/test"]}]},
        },
    }


@pytest.mark.parametrize("served, problem", [
    # The table is gone or renamed.
    (page(ROWS).replace("DDL statement", "Statement"), "no table headed 'DDL statement'"),
    # The sentence naming the example database and table is gone.
    (page(ROWS, "Replace the names."), "no longer says which database and table"),
    # A policy that is no longer JSON, or no longer allows lists of actions on lists of resources.
    (page(ROWS.replace('"Effect": "Allow",', '"Effect": "Allow"', 1)), "is not JSON"),
    (page(ROWS.replace('"Effect": "Allow"', '"Effect": "Deny"', 1)), "is not statements allowing"),
])
def test_a_page_whose_layout_changed_is_refused_and_points_at_the_prompt(served, problem):
    with pytest.raises(ValueError, match=problem) as error:
        f.read_page(served)
    assert f.PROMPT in str(error.value)


def test_verify_compares_the_vendored_file_with_the_page_its_date_aside(tmp_path, capsys):
    output = tmp_path / "source.json"
    served = {f.PAGE: page(ROWS)}
    assert f.main(["--output", str(output)], fetch=served.get) == 0
    vendored = json.loads(output.read_text(encoding="utf-8"))
    assert vendored["_source"]["page"] == f.PAGE and len(vendored["statements"]) == 2
    vendored["_source"]["retrieved"] = "2020-01-01"
    output.write_text(json.dumps(vendored), encoding="utf-8")
    assert f.main(["--verify", "--output", str(output)], fetch=served.get) == 0

    served[f.PAGE] = page(ROWS.replace("glue:CreateTable", "glue:CreateTables"))
    assert f.main(["--verify", "--output", str(output)], fetch=served.get) == 1
    assert "no longer gives what" in capsys.readouterr().err
