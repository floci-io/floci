"""Tests for the AWS Session Manager documentation extract."""
from __future__ import annotations

import pytest

import fetch_ssm_session_permissions as f


@pytest.fixture
def pages() -> dict[str, str]:
    default = """
        <p>The system creates a document called <code>SSM-SessionManagerRunShell</code>.
            This is the default session document.</p>
        <p>By default, Session Manager uses the default session document when a user starts a session by using an
            AWS CLI command like the following example:</p>
        <p>To start a default shell session, you must specify the default session document in the IAM policy.</p>
        <pre><code>aws ssm start-session --target i-02573cafcfEXAMPLE</code></pre>
        <pre><code><span>{</span>"Statement": [{"Effect": "Allow", "Action": ["ssm:StartSession"],
          "Resource": ["arn:aws:ec2:us-east-1:111122223333:instance/<code>instance-id</code>",
                       "arn:aws:ssm:us-east-1:111122223333:document/SSM-SessionManagerRunShell"]}]}</code></pre>
    """
    return {
        f.DEFAULT_PAGE: default,
        f.HYBRID_PAGE: '<p>Hybrid managed nodes are distinguished from Amazon EC2 instances with the prefix "mi-". '
                       'Amazon EC2 instance IDs use the prefix "i-".</p>',
        f.SESSIONS_PAGE: "<pre><code>aws ssm start-session --target ecs:&lt;cluster&gt;_&lt;container&gt;_&lt;id&gt;"
                         "</code></pre>",
        **{url: f'<pre><code>POST / HTTP/1.1\nX-Amz-Target: AmazonSSM.{operation}\n'
                '\n<span>{</span>"Target": "i-02573cafcfEXAMPLE"}</code></pre>'
           for operation, url in f.API_PAGES.items()},
    }


def test_extract_copies_the_guide_facts_and_api_requests(pages: dict[str, str]) -> None:
    source = f.build(pages)
    assert source["_source"]["pages"] == list(pages)
    assert source["defaultDescription"] == (
        "The system creates a document called SSM-SessionManagerRunShell. This is the default session document."
    )
    assert source["defaultPolicy"]["Statement"][0]["Resource"] == [
        "arn:aws:ec2:us-east-1:111122223333:instance/instance-id",
        "arn:aws:ssm:us-east-1:111122223333:document/SSM-SessionManagerRunShell",
    ]
    assert source["defaultCommand"] == "aws ssm start-session --target i-02573cafcfEXAMPLE"
    assert source["sessionRequests"] == {name: {"Target": "i-02573cafcfEXAMPLE"} for name in f.API_PAGES}
    assert source["managedNodePrefixes"].endswith('Amazon EC2 instance IDs use the prefix "i-".')
    assert source["ecsCommand"] == "aws ssm start-session --target ecs:<cluster>_<container>_<id>"


def test_changed_api_example_makes_the_extract_stale(pages: dict[str, str]) -> None:
    source = f.build(pages)
    assert not f.stale(source, pages)
    page = f.API_PAGES["GetConnectionStatus"]
    pages[page] = pages[page].replace("i-02573cafcfEXAMPLE", "mi-02573cafcfEXAMPLE")
    assert f.stale(source, pages)


def test_missing_policy_example_fails_extraction(pages: dict[str, str]) -> None:
    pages[f.DEFAULT_PAGE] = pages[f.DEFAULT_PAGE].replace('"Statement"', '"Other"')
    with pytest.raises(ValueError, match="IAM policy example"):
        f.build(pages)


def test_missing_api_sample_request_fails_extraction(pages: dict[str, str]) -> None:
    pages[f.API_PAGES["GetConnectionStatus"]] = "<pre>{}</pre>"
    with pytest.raises(ValueError, match="sample request"):
        f.build(pages)
