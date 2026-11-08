import json

import regen_apigateway_types as r


def test_extracts_new_model_values_without_a_handwritten_allowlist():
    model = {"shapes": {"IntegrationType": {"type": "string", "enum": ["FUTURE_TYPE", "MOCK"]}}}
    assert r.build(model, "test")["integrationTypes"] == ["FUTURE_TYPE", "MOCK"]


def test_check_detects_drift_without_rewriting_the_catalog(tmp_path, monkeypatch):
    data = tmp_path / r.MODEL
    data.mkdir(parents=True)
    model = {"shapes": {"IntegrationType": {"type": "string", "enum": ["MOCK", "FUTURE_TYPE"]}}}
    (data / "service-2.json").write_text(json.dumps(model))
    output = tmp_path / "types.json"
    stale = json.dumps({"integrationTypes": ["MOCK"]})
    output.write_text(stale)
    monkeypatch.setattr(r, "resolve_botocore_data", lambda explicit: (tmp_path, "test"))

    assert r.main(["--check", "--output", str(output)]) == 1
    assert output.read_text() == stale

    output.write_text(json.dumps(r.build(model, "different provenance")))
    assert r.main(["--check", "--output", str(output)]) == 0
