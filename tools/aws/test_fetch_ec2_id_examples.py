import html
import json

import pytest

import fetch_ec2_id_examples as f


def page(xml):
    return '<pre class="programlisting"><code>' + html.escape(xml) + '</code></pre>'


RESPONSE = '''<CreateVpcEndpointResponse xmlns="https://example.test/ec2">
<vpcEndpoint><vpcEndpointId>vpce-abc12345</vpcEndpointId>
<networkInterfaceIdSet><item>eni-cd1a3319</item></networkInterfaceIdSet>
<serviceName>com.amazonaws.us-east-1.s3</serviceName></vpcEndpoint>
</CreateVpcEndpointResponse>'''


def test_copies_response_paths_and_values_without_assigning_resource_types():
    assert f.read_examples("CreateVpcEndpoint", page(RESPONSE)) == [
        {"path": ["vpcEndpoint", "vpcEndpointId"], "value": "vpce-abc12345"},
        {"path": ["vpcEndpoint", "networkInterfaceIdSet", "item"], "value": "eni-cd1a3319"},
    ]


def test_ignores_request_blocks_and_other_operations():
    other = page(RESPONSE.replace("CreateVpcEndpointResponse", "OtherResponse"))
    assert f.read_examples("CreateVpcEndpoint", other + page("VpcEndpointId=vpce-ffffffff") + page(RESPONSE)) == \
        f.read_examples("CreateVpcEndpoint", page(RESPONSE))


@pytest.mark.parametrize("source", ["", page("not XML"), page(RESPONSE.replace("vpce-abc12345", "none")
                                                                         .replace("eni-cd1a3319", "none"))])
def test_missing_response_identifiers_fail_closed(source):
    with pytest.raises(ValueError):
        f.read_examples("CreateVpcEndpoint", source)


def test_malformed_response_is_not_silently_omitted():
    with pytest.raises(ValueError, match="malformed XML"):
        f.read_examples("CreateVpcEndpoint", page(RESPONSE.replace("</vpcEndpoint>", "")))


def test_verification_detects_changed_examples_and_ignores_retrieval_date(tmp_path, monkeypatch):
    output = tmp_path / "examples.json"
    monkeypatch.setattr(f, "OUTPUT", output)
    fetch = lambda url: page(RESPONSE)
    assert f.main([], fetch) == 0
    source = json.loads(output.read_text())
    source["_source"]["retrieved"] = "2000-01-01"
    output.write_text(json.dumps(source))
    assert f.main(["--verify"], fetch) == 0
    assert f.main(["--verify"], lambda url: page(RESPONSE.replace("vpce-abc12345", "vpce-ffffffff"))) == 1


def test_source_declares_namespace_and_pages_for_shared_verification():
    source = f.build({url: page(RESPONSE) for url in f.PAGES.values()})
    assert source["namespace"] == "ec2"
    assert source["_source"]["pages"] == list(f.PAGES.values())
    source["namespace"] = "other"
    assert f.stale(source, {url: page(RESPONSE) for url in f.PAGES.values()})
