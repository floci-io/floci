"""Tests for regen_action_docs.

Run with: pytest tools/docs -q  (or: make docs-test)
"""
from __future__ import annotations

from pathlib import Path

import pytest

import regen_action_docs as r


# --------------------------------------------------------------------------- #
# Extraction: switch
# --------------------------------------------------------------------------- #
def test_extract_switch_single_label():
    src = """
        return switch (action) {
            case "CreateSecret" -> handleCreate(req);
            case "GetSecretValue" -> handleGet(req);
            default -> error();
        };
    """
    assert r.extract_switch_actions(src) == ["CreateSecret", "GetSecretValue"]


def test_extract_switch_multi_label_arm():
    # A single arm carrying several labels (real case: CloudFormation StackSets).
    src = 'case "ListStackSets", "DescribeStackSet", "CreateStackSet" -> handleStackSets(req);'
    assert r.extract_switch_actions(src) == [
        "ListStackSets",
        "DescribeStackSet",
        "CreateStackSet",
    ]


def test_extract_switch_multiline_arm():
    # Labels wrapped across lines (valid Java 14+ switch syntax) must all be captured,
    # and the capture must stop at the arm's `->` rather than running into the next
    # arm's `{ ... }` body. A miss here would be invisible: the doc and the regenerated
    # output would agree on the omission and docs-check would stay green.
    src = '''
        return switch (action) {
            case "CreateStackSet",
                 "DescribeStackSet",
                 "ListStackSets" -> handleStackSets(req);
            case "GetTemplate" -> { return handleGet(req); }
        };
    '''
    assert r.extract_switch_actions(src) == [
        "CreateStackSet",
        "DescribeStackSet",
        "ListStackSets",
        "GetTemplate",
    ]


def test_extract_switch_ignores_non_pascal_and_default():
    src = '''
        case "lowercase" -> a();
        case null, default -> b();
        case "Good" -> c();
    '''
    assert r.extract_switch_actions(src) == ["Good"]


def test_extract_switch_excludes_screaming_case_enum_labels():
    # A handler may contain non-action switches (e.g. state enums). SCREAMING_CASE
    # labels must not be picked up as actions; PascalCase actions still are.
    src = '''
        return switch (action) {
            case "EnableRule" -> enable();
            case "DisableRule" -> disable();
        };
        return switch (state.toUpperCase()) {
            case "DISABLED" -> RuleState.DISABLED;
            case "ENABLED" -> RuleState.ENABLED;
        };
    '''
    assert r.extract_switch_actions(src) == ["EnableRule", "DisableRule"]


# --------------------------------------------------------------------------- #
# Extraction: rest + overrides
# --------------------------------------------------------------------------- #
REST_CONTROLLER = """
@Path("/")
public class ThingController {
    @POST
    public Response createThing(Req r) { return ok(); }

    @GET
    public Response listThingsPost(Req r) { return ok(); }

    @PUT
    public Response updateV2Thing(Req r) { return ok(); }
}
"""


def test_extract_rest_ucfirsts_method_names():
    assert r.extract_rest_actions(REST_CONTROLLER) == [
        "CreateThing",
        "ListThingsPost",
        "UpdateV2Thing",
    ]


def test_extract_rest_requires_class_path():
    no_path = REST_CONTROLLER.replace('@Path("/")\n', "")
    assert r.extract_rest_actions(no_path) == []


def test_extract_rest_skips_comments_between_annotation_and_method():
    # A Javadoc or inline comment sitting between the verb annotation and the method
    # signature must not reset the lookahead and drop the action.
    src = '''
@Path("/")
public class C {
    @POST
    /** Creates a thing. */
    public Response createThing(Req r) { return ok(); }

    @GET
    // list them
    public Response listThings(Req r) { return ok(); }
}
'''
    assert r.extract_rest_actions(src) == ["CreateThing", "ListThings"]


def test_extract_actions_applies_rename_and_exclude(tmp_path: Path):
    src = tmp_path / "ThingController.java"
    src.write_text(REST_CONTROLLER)
    entry = r.ServiceEntry(
        service="thing",
        doc=tmp_path / "thing.md",
        sources=[r.Source(path=src, mode="rest")],
        rename_actions={"UpdateV2Thing": "UpdateThing"},
        exclude_actions=frozenset({"ListThingsPost"}),
    )
    assert r.extract_actions(entry) == ["CreateThing", "UpdateThing"]


def test_extract_actions_dedupes_across_sources(tmp_path: Path):
    a = tmp_path / "A.java"
    b = tmp_path / "B.java"
    a.write_text('case "Foo" -> x();\ncase "Bar" -> y();')
    b.write_text('case "Bar" -> y();\ncase "Baz" -> z();')
    entry = r.ServiceEntry(
        service="s",
        doc=tmp_path / "s.md",
        sources=[r.Source(path=a, mode="switch"), r.Source(path=b, mode="switch")],
    )
    assert r.extract_actions(entry) == ["Foo", "Bar", "Baz"]


# --------------------------------------------------------------------------- #
# Table parsing: robustness
# --------------------------------------------------------------------------- #
def test_split_row_honors_escaped_pipe():
    assert r._split_row(r"| `Foo` | a \| b |") == ["`Foo`", "a | b"]


def test_parse_marker_block_preserves_descriptions():
    md = (
        "pre\n"
        f"{r.MARKER_START}\n"
        "| Action | Description |\n"
        "| --- | --- |\n"
        "| `CreateSecret` | Create a new secret |\n"
        "| `GetSecretValue` | - |\n"
        f"{r.MARKER_END}\n"
        "post\n"
    )
    _, descs, _ = r.parse_marker_block(md)
    assert descs == {"CreateSecret": "Create a new secret", "GetSecretValue": ""}


def test_parse_marker_block_rejects_missing_markers():
    with pytest.raises(ValueError):
        r.parse_marker_block("no markers here")


def test_parse_table_rejects_non_table_line():
    body = "| Action | Description |\n| --- | --- |\nstray prose line\n"
    with pytest.raises(ValueError):
        r._parse_table(body)


def test_parse_table_rejects_extra_columns():
    # An unescaped pipe in a hand-edited description yields a 3-cell row; reject it
    # loudly rather than silently dropping the trailing text.
    body = "| Action | Description |\n| --- | --- |\n| `Foo` | a | b |\n"
    with pytest.raises(ValueError):
        r._parse_table(body)


def test_pipe_in_description_round_trips():
    # A description containing a literal pipe survives render -> parse -> render
    # unchanged (render escapes, the parser unescapes), and stays idempotent.
    block = r.render_marker_block(["Foo"], {"Foo": "does a | b"})
    assert r"\|" in block  # escaped on render
    md = f"x\n{r.MARKER_START}\n{block}{r.MARKER_END}\n"
    _, descs, _ = r.parse_marker_block(md)
    assert descs["Foo"] == "does a | b"  # unescaped on parse
    assert r.render_marker_block(["Foo"], descs) == block  # idempotent


# --------------------------------------------------------------------------- #
# Regeneration: preservation, placeholders, orphans, idempotency
# --------------------------------------------------------------------------- #
DOC = (
    "# Svc\n\n## Supported Actions\n\n"
    f"{r.MARKER_START}\n"
    "| Action | Description |\n"
    "| --- | --- |\n"
    "| `CreateSecret` | Create a new secret |\n"
    "| `OldAction` | gone from code |\n"
    f"{r.MARKER_END}\n\n## Configuration\nprose\n"
)


def test_regenerate_preserves_known_and_placeholders_new():
    actions = ["CreateSecret", "GetSecretValue"]
    new, orphans = r.regenerate_doc_content(actions, DOC)
    _, descs, _ = r.parse_marker_block(new)
    assert descs["CreateSecret"] == "Create a new secret"  # preserved
    assert descs["GetSecretValue"] == ""  # new -> placeholder
    assert orphans == ["OldAction"]  # dropped + reported
    assert "OldAction" not in new


def test_regenerate_is_idempotent():
    actions = ["CreateSecret", "GetSecretValue"]
    once, _ = r.regenerate_doc_content(actions, DOC)
    twice, _ = r.regenerate_doc_content(actions, once)
    assert once == twice


def test_regenerate_touches_only_marker_block():
    actions = ["CreateSecret", "GetSecretValue"]
    new, _ = r.regenerate_doc_content(actions, DOC)
    assert new.split(r.MARKER_START)[0] == DOC.split(r.MARKER_START)[0]
    assert new.split(r.MARKER_END)[1] == DOC.split(r.MARKER_END)[1]


def test_render_marker_block_shape():
    out = r.render_marker_block(["Foo"], {"Foo": "does foo"})
    assert out == "| Action | Description |\n| --- | --- |\n| `Foo` | does foo |\n"
