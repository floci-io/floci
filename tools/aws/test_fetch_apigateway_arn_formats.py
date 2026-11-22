#!/usr/bin/env python3
"""Tests for fetch_apigateway_arn_formats.py, on pages in the API Gateway developer guide's markup."""
from __future__ import annotations

import pytest

import fetch_apigateway_arn_formats as f

ACTION = ('<p>The API-managing <code class="code">Action</code> expression has the format\n'
          '  <code class="code">apigateway:<code class="replaceable">action</code></code>,  where\n'
          '  <code class="replaceable">action</code> is one of the following API Gateway actions: <b>GET</b>,\n'
          '  <b>POST</b>, <b>PUT</b>, <b>DELETE</b>, <b>PATCH</b>\n'
          '  (to update resources), or <b>*</b>, which is all of the previous actions.</p>')
RESOURCE = ('<p>API Gateway resources have the following ARN format:</p>\n'
            '<pre class="programlisting"><div class="code-btn-container"></div><code class="nohighlight">'
            'arn:aws:apigateway:<code class="replaceable">region</code>::'
            '<code class="replaceable">resource-path-specifier</code></code></pre>')


def iam_page(*paragraphs: str) -> str:
    return "<html><body>" + "".join(paragraphs) + "</body></html>"


def row(resource: str, arn: str) -> str:
    return (f'<tr><td tabindex="-1"><p>{resource}</p></td>'
            f'<td tabindex="-1"><p><code class="code">{arn}</code></p></td></tr>')


def arn_page(*tables: tuple[str, list[str]], rows_each: int = 1) -> str:
    body = ""
    for heading, rows in tables:
        body += (f"<h2>{heading}</h2><table><thead><tr><th>Resource</th><th>ARN</th></tr></thead>"
                 + "".join(rows * rows_each) + "</table>")
    return "<html><body>" + body + "</body></html>"


REST_STAGE = row("Stage", 'arn:<code class="replaceable">partition</code>:apigateway:<code class="replaceable">region'
                 '</code>::/restapis/<code class="replaceable">api-id</code>/stages/'
                 '<code class="replaceable">stage-name</code>')
ROUTING_RULE = row("RoutingRule", 'arn:<code class="replaceable">partition</code>:apigateway:<code class="replaceable">'
                   'region</code>:<code class="replaceable">account-id</code>:/domainnames/<code class="replaceable">'
                   'domain-name</code>/routingrules/<code class="replaceable">routing-rule-id</code>')


def test_the_iam_page_gives_the_action_sentence_its_actions_and_the_resource_format():
    read = f.read_iam_page(iam_page(ACTION, RESOURCE))
    assert read == {
        "action": {
            "sentence": "The API-managing Action expression has the format apigateway:{action}, where {action} is "
                        "one of the following API Gateway actions: GET, POST, PUT, DELETE, PATCH (to update "
                        "resources), or *, which is all of the previous actions.",
            "actions": ["GET", "POST", "PUT", "DELETE", "PATCH", "*"],
        },
        "resource": {"sentence": "API Gateway resources have the following ARN format:",
                     "format": "arn:aws:apigateway:{region}::{resource-path-specifier}"},
    }


def test_a_page_without_the_action_sentence_or_the_resource_format_is_refused():
    with pytest.raises(ValueError, match="action sentence"):
        f.read_iam_page(iam_page(RESOURCE))
    with pytest.raises(ValueError, match="ARN format"):
        f.read_iam_page(iam_page(ACTION))


def test_each_row_keeps_its_table_heading_and_marks_its_variables():
    tables = f.read_arn_page(arn_page(("REST API resources", [REST_STAGE]), ("Domain names resources", [ROUTING_RULE])))
    assert tables == [
        {"heading": "REST API resources",
         "rows": [{"resource": "Stage",
                   "arn": "arn:{partition}:apigateway:{region}::/restapis/{api-id}/stages/{stage-name}"}]},
        {"heading": "Domain names resources",
         "rows": [{"resource": "RoutingRule",
                   "arn": "arn:{partition}:apigateway:{region}:{account-id}:/domainnames/{domain-name}/routingrules/"
                          "{routing-rule-id}"}]},
    ]


def test_a_page_of_too_few_tables_or_rows_is_refused():
    with pytest.raises(ValueError, match="layout changed"):
        f.build(iam_page(ACTION, RESOURCE), arn_page(("REST API resources", [REST_STAGE])))


def test_the_file_is_stale_when_a_page_changes_and_not_for_its_date():
    tables = [(f"Heading {index}", [REST_STAGE]) for index in range(f.MINIMUM_TABLES)]
    arns = arn_page(*tables, rows_each=f.MINIMUM_ROWS // f.MINIMUM_TABLES + 1)
    vendored = f.build(iam_page(ACTION, RESOURCE), arns)
    vendored["_source"]["retrieved"] = "2000-01-01"
    assert not f.stale(vendored, iam_page(ACTION, RESOURCE), arns)
    assert f.stale(vendored, iam_page(ACTION, RESOURCE), arns.replace("/stages/", "/stage/"))
