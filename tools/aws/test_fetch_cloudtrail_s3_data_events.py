#!/usr/bin/env python3
"""Tests for fetch_cloudtrail_s3_data_events.py, on a page in the S3 user guide's markup."""
from __future__ import annotations

import pytest

import fetch_cloudtrail_s3_data_events as f


def row(console: str, kind: str, apis: list[str]) -> str:
    """A row of the data events table, its APIs an itemized list as the guide lays them out."""
    items = "".join(f'<li class="listitem"><p><a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_{api.split()[0]}'
                    f'.html">{api.split(" (")[0]}</a>{api[len(api.split(" (")[0]):]}</p></li>' for api in apis)
    return (f'<tr><td tabindex="-1"><b>{console}</b></td><td tabindex="-1"><code class="code">{kind}</code></td>'
            f'<td tabindex="-1"><div class="itemizedlist"><ul class="itemizedlist">{items}</ul></div></td></tr>')


RECORDED_AS = ('<p>When you call <code class="code">PutObjectRetention</code>, the CloudTrail data event is\n'
               '    recorded with the <code class="code">eventName</code>\n    <code class="code">PutObjectLockRetention'
               '</code>, and includes\n    <code class="code">objectRetentionInfo</code> in\n'
               '    <code class="code">additionalEventData</code>.</p>')
PAGE = ('<html><body><p>Amazon S3 logs all control plane operations as management events.</p>'
        '<table id="w1"><thead><tr><th>Data event type (console)</th><th>resources.type value</th>'
        '<th>Data APIs logged to CloudTrail</th></tr></thead>'
        + row("S3", "AWS::S3::Object", ["GetObject", "ListObjects", "PutObjectRetention"])
        + row("S3 Access Point", "AWS::S3::Access Point",
              ["CopyObject (same-region copies only)", "ListObjects", "ListObjectsV2"])
        + "</table>" + RECORDED_AS + "</body></html>")


def test_each_resource_type_keeps_its_api_cells_as_they_stand():
    assert f.read_data_events(PAGE) == {
        "AWS::S3::Object": ["GetObject", "ListObjects", "PutObjectRetention"],
        "AWS::S3::Access Point": ["CopyObject (same-region copies only)", "ListObjects", "ListObjectsV2"]}


def test_a_sentence_naming_the_event_a_call_is_recorded_with_is_kept_as_it_stands():
    assert f.read_recorded_as(PAGE) == [{
        "call": "PutObjectRetention", "eventName": "PutObjectLockRetention",
        "sentence": "When you call PutObjectRetention, the CloudTrail data event is recorded with the eventName "
                    "PutObjectLockRetention, and includes objectRetentionInfo in additionalEventData."}]
    assert f.read_recorded_as("<p>Amazon S3 logs all control plane operations as management events.</p>") == []


def test_a_page_whose_layout_changed_is_refused():
    with pytest.raises(ValueError, match="no table headed"):
        f.read_data_events(PAGE.replace("resources.type value", "Resource type"))
    with pytest.raises(ValueError, match="lists no resource type"):
        f.read_data_events(PAGE.split("<table")[0] + '<table id="w1"><tr><th>Data event type (console)</th>'
                           "<th>resources.type value</th><th>Data APIs logged to CloudTrail</th></tr></table>")


def test_the_file_is_stale_when_the_page_says_something_else():
    vendored = f.build(PAGE)
    assert vendored["_source"]["page"] == f.PAGE
    assert not f.stale(vendored, PAGE)
    assert f.stale(vendored, PAGE.replace(">ListObjectsV2<", ">ListObjectVersions<"))
    assert f.stale(vendored, PAGE.replace("PutObjectLockRetention", "PutObjectRetention"))
