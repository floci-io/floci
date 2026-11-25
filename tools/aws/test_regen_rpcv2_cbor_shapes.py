from regen_rpcv2_cbor_shapes import build, project_model


def test_agent_target_prefixes_share_the_string_only_wire_model(tmp_path):
    data = tmp_path / "data"
    data.mkdir()
    catalog = tmp_path / "Catalog.java"
    catalog.write_text('Set.of("AmazonSSMMessageDeliveryService.", "EC2WindowsMessageDeliveryService.")')

    result = build(data, catalog, "test")

    assert {prefix: result["aliases"][prefix] for prefix in (
        "AmazonSSMMessageDeliveryService", "EC2WindowsMessageDeliveryService")
    } == {"AmazonSSMMessageDeliveryService": "ec2messages", "EC2WindowsMessageDeliveryService": "ec2messages"}
    assert result["services"]["ec2messages"] == {
        "timestampFormat": "unixTimestamp", "shapes": {}, "operations": {}}


def model(shapes, operations=None, protocol="json"):
    return {"metadata": {"protocol": protocol}, "shapes": shapes,
            "operations": operations or {"Read": {"input": {"shape": "Root"}, "output": {"shape": "Root"}}}}


def test_projection_keeps_typed_leaves_and_their_containers_only():
    result = project_model(model({
        "Root": {"type": "structure", "members": {"Files": {"shape": "Files"}, "Name": {"shape": "Text"}}},
        "Files": {"type": "list", "member": {"shape": "Lookup"}},
        "Lookup": {"type": "map", "value": {"shape": "Blob"}},
        "Blob": {"type": "blob"}, "Text": {"type": "string"}}))
    assert set(result["shapes"]) == {"Root", "Files", "Lookup", "Blob"}
    assert result["shapes"]["Root"]["members"] == {"Files": {"shape": "Files"}}
    assert result["operations"]["Read"] == {"input": "Root", "output": "Root", "hasOutput": True}


def test_recursive_shape_projection_terminates_and_retains_typed_descendants():
    result = project_model(model({
        "Root": {"type": "structure", "members": {"Child": {"shape": "List"}, "Data": {"shape": "Blob"}}},
        "List": {"type": "list", "member": {"shape": "Root"}}, "Blob": {"type": "blob"}}))
    assert set(result["shapes"]) == {"Root", "List", "Blob"}
    assert result["shapes"]["List"]["member"] == "Root"


def test_projection_preserves_json_member_names_and_timestamp_overrides():
    result = project_model(model({
        "Root": {"type": "structure", "members": {
            "When": {"shape": "Date", "timestampFormat": "unixTimestamp", "locationName": "when"}}},
        "Date": {"type": "timestamp", "timestampFormat": "iso8601"}}, protocol="rest-json"))
    assert result["timestampFormat"] == "iso8601"
    assert result["shapes"]["Date"]["timestampFormat"] == "iso8601"
    assert result["shapes"]["Root"]["members"]["When"] == {
        "shape": "Date", "timestampFormat": "unixTimestamp", "jsonName": "when"}


def test_an_ordinary_output_without_special_types_is_not_unit():
    result = project_model(model({"Root": {"type": "structure", "members": {}}}, {
        "Read": {"output": {"shape": "Root"}}, "Delete": {}}))
    assert "Read" not in result["operations"]
    assert result["operations"]["Delete"] == {"input": None, "output": None, "hasOutput": False}


def test_api_gateway_json_facade_preserves_member_names_and_numeric_dates():
    result = project_model(model({
        "Root": {"type": "structure", "members": {
            "ApiId": {"shape": "Text", "locationName": "apiId"},
            "CreatedDate": {"shape": "Date", "locationName": "createdDate"}}},
        "Text": {"type": "string"},
        "Date": {"type": "timestamp", "timestampFormat": "iso8601"}}, protocol="rest-json"), json_facade=True)
    assert result["timestampFormat"] == "unixTimestamp"
    assert result["shapes"]["Date"] == {"type": "timestamp"}
    assert result["shapes"]["Root"]["members"] == {"CreatedDate": {"shape": "Date"}}


def test_check_command_ignores_provenance_and_detects_data_drift(tmp_path, monkeypatch):
    import json
    import sys
    import regen_rpcv2_cbor_shapes as generator

    output = tmp_path / "wire.json"
    expected = {"_source": {"botocore": "new"}, "aliases": {"Service": "test"}, "services": {}}
    output.write_text(json.dumps({**expected, "_source": {"botocore": "old"}}))
    monkeypatch.setattr(sys, "argv", ["regen_rpcv2_cbor_shapes.py", "--check", "--output", str(output)])
    monkeypatch.setattr(generator, "resolve_botocore_data", lambda unused: (tmp_path, "new"))
    monkeypatch.setattr(generator, "build", lambda *unused: expected)

    assert generator.main() == 0
    output.write_text(json.dumps({**expected, "aliases": {}}))
    assert generator.main() == 1


def test_projection_records_the_output_event_stream_union():
    result = project_model(model({
        "Root": {"type": "structure", "members": {"Events": {"shape": "Events"}}},
        "Events": {"type": "structure", "eventstream": True,
                   "members": {"DataEvent": {"shape": "Event"}}},
        "Event": {"type": "structure", "members": {"Data": {"shape": "Blob"}}},
        "Blob": {"type": "blob"}}))
    assert result["operations"]["Read"]["eventStream"] == "Events"
    assert result["shapes"]["Events"]["members"]["DataEvent"]["shape"] == "Event"
