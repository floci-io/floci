"""CloudWatch Query GET behavior also exercised against the live AWS endpoint."""

import datetime
import http.client
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
from contextlib import closing

import boto3
from botocore.auth import SigV4Auth, SigV4QueryAuth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials


def test_cloudwatch_query_get(aws_config):
    with closing(boto3.client("cloudwatch", **aws_config)) as client:
        endpoint = client.meta.endpoint_url.rstrip("/")
        region = client.meta.region_name
    credentials = Credentials(aws_config["aws_access_key_id"], aws_config["aws_secret_access_key"],
                              aws_config.get("aws_session_token"))

    def request(method, parameters, authorization_header="Authorization", *,
                presigned=False, headers=None, version="2010-08-01"):
        query = {} if version is None else {"Version": version}
        encoded = urllib.parse.urlencode({**query, **parameters})
        url = endpoint + ("/?" + encoded if method == "GET" else "/")
        body = None if method == "GET" else encoded
        signed = AWSRequest(method=method, url=url, data=body,
                            headers={"Content-Type": "application/x-www-form-urlencoded", **(headers or {})})
        signer = (SigV4QueryAuth(credentials, "monitoring", region, expires=60) if presigned
                  else SigV4Auth(credentials, "monitoring", region))
        signer.add_auth(signed)
        headers = dict(signed.headers.items())
        if not presigned:
            headers[authorization_header] = headers.pop("Authorization")
        parsed = urllib.parse.urlsplit(signed.url)
        connection_type = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
        with closing(connection_type(parsed.hostname, parsed.port, timeout=30)) as connection:
            connection.request(method, parsed.path + ("?" + parsed.query if parsed.query else ""),
                               body=body, headers=headers)
            response = connection.getresponse()
            return response.status, ET.fromstring(response.read())

    namespace = "FlociCompatibility/QueryGet/" + uuid.uuid4().hex
    now = datetime.datetime.now(datetime.timezone.utc)
    status, _ = request("POST", {
        "Action": "PutMetricData", "Namespace": namespace,
        "MetricData.member.1.MetricName": "Requests", "MetricData.member.1.Value": "17",
        "MetricData.member.1.Timestamp": now.isoformat(),
    })
    assert status == 200
    parameters = {
        "Action": "GetMetricStatistics", "Namespace": namespace, "MetricName": "Requests",
        "StartTime": (now - datetime.timedelta(minutes=2)).isoformat(),
        "EndTime": (now + datetime.timedelta(minutes=2)).isoformat(),
        "Period": "60", "Statistics.member.1": "Sum",
    }
    # AWS may need time to expose a newly published custom metric.
    deadline = time.monotonic() + 60
    while True:
        status, result = request("GET", parameters)
        assert status == 200
        sums = [float(node.text) for node in result.iter() if node.tag.endswith("}Sum") or node.tag == "Sum"]
        if sums or time.monotonic() >= deadline:
            assert sums == [17.0]
            break
        time.sleep(1)
    for header in ["Authorization", "authorization", "AUTHORIZATION", "aUtHoRiZaTiOn"]:
        status, result = request("GET", parameters, header)
        assert status == 200
        assert result.tag.endswith("GetMetricStatisticsResponse")
        assert [float(node.text) for node in result.iter()
                if node.tag.endswith("}Sum") or node.tag == "Sum"] == [17.0]
    status, result = request("GET", parameters, presigned=True)
    assert status == 200
    assert [float(node.text) for node in result.iter()
            if node.tag.endswith("}Sum") or node.tag == "Sum"] == [17.0]
    for selection in [{"Operation": "ListMetrics"}, {"Action": "ListMetrics", "Operation": "PutDashboard"}]:
        status, result = request("GET", selection | {"Namespace": namespace})
        assert status == 200
        assert result.tag.endswith("ListMetricsResponse")
    status, result = request("GET", {"Action": "PutDashboard"})
    assert status == 400
    assert result.tag.endswith("ErrorResponse")
    for action in ["DefinitelyUnknownAction", ""]:
        status, result = request("GET", {"Action": action})
        assert status == 404
        assert [node.text for node in result.iter() if node.tag.endswith("}Code") or node.tag == "Code"] == [
            "InvalidAction"]
    status, result = request("GET", {})
    assert status == 404
    assert result.tag.endswith("UnknownOperationException")
    for presigned in [False, True]:
        for headers in [{}, {"Accept": "text/html"}, {"X-Amz-Target": "AmazonSSM.GetParameter"}]:
            status, result = request("GET", {"Action": "ListMetrics", "Namespace": namespace},
                                     presigned=presigned, headers=headers)
            assert status == 200
            assert result.tag.endswith("ListMetricsResponse")
    for action, version, expected in [
        ("ListMetrics", None, 200), ("ListMetrics", "2009-05-15", 200),
        ("ListMetrics", "", 404), ("ListMetrics", "2000-01-01", 404),
        ("ListMetricStreams", None, 404), ("ListMetricStreams", "2009-05-15", 404),
        ("ListMetricStreams", "2010-08-01", 200),
    ]:
        status, result = request("GET", {"Action": action}, version=version)
        assert status == expected
        assert result.tag.endswith(action + "Response" if expected == 200 else "ErrorResponse")
