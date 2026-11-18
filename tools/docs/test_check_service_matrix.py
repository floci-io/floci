"""Tests for check_service_matrix.

Run with: pytest tools/docs -q  (or: make docs-test)
"""
from __future__ import annotations

from datetime import date

import pytest

import check_service_matrix as c


def _repo(tmp_path, rows, registry, sources=None):
    """A minimal repo: a Service Matrix holding `rows`, the registry text, and Java
    sources keyed by their path relative to the package root."""
    matrix = tmp_path / c.MATRIX_DOC
    matrix.parent.mkdir(parents=True, exist_ok=True)
    matrix.write_text(
        "## Service Matrix\n\n" + "".join(f"{row}\n" for row in rows) + "\n## Common Setup\n",
        encoding="utf-8",
    )
    registry_path = tmp_path / c.REGISTRY
    registry_path.parent.mkdir(parents=True, exist_ok=True)
    registry_path.write_text(registry, encoding="utf-8")
    for relative, text in (sources or {}).items():
        path = tmp_path / c.PACKAGE_DIR / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return tmp_path


# --------------------------------------------------------------------------- #
# Extraction
# --------------------------------------------------------------------------- #
def test_extract_descriptor_keys_basic():
    src = '''
        descriptor("ssm", "ssm", config.services().ssm().enabled(), true,
                "ssm", storageMode(...), 5000L, null, ServiceProtocol.JSON,
                protocols(ServiceProtocol.JSON),
                Set.of("AmazonSSM."), Set.of("ssm"), Set.of(), Set.of()),
        descriptor("sqs", "sqs", config.services().sqs().enabled(), true, ...),
    '''
    assert c.extract_descriptor_keys(src) == ["ssm", "sqs"]


def test_extract_descriptor_keys_ignores_other_string_literals():
    # Only the first argument of descriptor(...) counts; targetPrefixes, credential
    # scopes and the like must not be picked up even though they're also quoted.
    src = 'descriptor("kafka", "msk", true, true, "msk", null, 5000L, null, X, Y, Set.of("kafka-signing-prefix."), Set.of("kafka"), Set.of(), Set.of())'
    assert c.extract_descriptor_keys(src) == ["kafka"]


def test_count_descriptor_calls_matches_literal_keys_when_all_literal():
    src = '''
        descriptor("ssm", "ssm", true, true, ...),
        descriptor("sqs", "sqs", true, true, ...),
    '''
    assert c.count_descriptor_calls(src) == len(c.extract_descriptor_keys(src)) == 2


def test_count_descriptor_calls_excludes_the_definition():
    src = '''
        private static ServiceDescriptor descriptor(
                String externalKey,
                String configKey) {
            return new ServiceDescriptor(externalKey, configKey);
        }
    '''
    assert c.count_descriptor_calls(src) == 0
    assert c.extract_descriptor_keys(src) == []


def test_count_descriptor_calls_catches_a_non_literal_key():
    # A future externalKey built from a variable/constant instead of a plain string
    # literal must not be silently invisible: the call-site count and the literal-key
    # count have to disagree so main() can fail loudly instead of under-counting.
    src = '''
        descriptor("ssm", "ssm", true, true, ...),
        descriptor(SOME_CONSTANT, "other", true, true, ...),
    '''
    assert c.count_descriptor_calls(src) == 2
    assert len(c.extract_descriptor_keys(src)) == 1


def test_extract_matrix_slugs_scoped_to_table():
    md = """
# Services Overview

Some prose with an unrelated [link](not-a-service.md) that must not be picked up.

## Service Matrix

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [SSM](ssm.md) | ... | JSON 1.1 | 22 |
| [CloudWatch Metrics](cloudwatch.md#metrics) | ... | Query | 11 |

## Common Setup

Another [link](also-not-a-service.md) below the table.
"""
    assert c.extract_matrix_slugs(md) == {"ssm", "cloudwatch"}


def test_extract_matrix_slugs_strips_anchor():
    md = "## Service Matrix\n\n| [API Gateway v2](api-gateway.md#v2) | | | 48 |\n\n## Common Setup\n"
    assert c.extract_matrix_slugs(md) == {"api-gateway"}

def test_extract_matrix_action_counts():
    md = """
# Services Overview

## Service Matrix

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [SSM](ssm.md) | ... | JSON 1.1 | 22 |
| [CloudFormation](cloudformation.md) | ... | Query | 19 |
| [API Gateway v2](api-gateway.md#v2) | ... | REST JSON | 53 + data-plane |
| [DynamoDB Streams](dynamodb.md#streams) | ... | JSON 1.1 | 4 |
| [Lightsail](lightsail.md) | ... | JSON 1.1 | 79 (of 161 recognized; the rest answer `UnsupportedOperation`) |
| [Bedrock AgentCore Control](bedrock-agentcore.md) | ... | REST JSON | 71 (+ 3 tagging via shared `/tags/{arn}` route) |

## Common Setup
"""

    # An anchored row is keyed by its anchor, and a parenthetical note after the
    # count leaves the leading number as the count; "53 + data-plane" is no count.
    assert c.extract_matrix_action_counts(md) == {
        "ssm": 22,
        "cloudformation": 19,
        "dynamodb#streams": 4,
        "lightsail": 79,
        "bedrock-agentcore": 71,
    }


def test_extract_matrix_action_counts_ignores_links_outside_matrix():
    md = """
# Services Overview

A link to [SSM](ssm.md) outside the matrix must not be counted.

## Service Matrix

| Service | Endpoint | Protocol | Supported operations |
|---|---|---|---|
| [CloudFormation](cloudformation.md) | ... | Query | 19 |

## Common Setup

Another link to [SQS](sqs.md).
"""

    assert c.extract_matrix_action_counts(md) == {
        "cloudformation": 19,
    }


@pytest.mark.parametrize("slug,handler", [
    ("ec2", "ec2/Ec2QueryHandler.java"),
    ("ecs", "ecs/EcsJsonHandler.java"),
    ("elb", "elbv2/ElbV2QueryHandler.java"),
    ("glue", "glue/GlueJsonHandler.java"),
])
def test_non_tabular_action_counts_follow_dispatcher(slug, handler):
    root = c._repo_root()
    source = root / c.PACKAGE_DIR / "services" / handler
    expected = len(set(c.regen_action_docs.extract_switch_actions(
        source.read_text(encoding="utf-8")
    )))

    assert c.extract_action_counts(root)[slug] == expected


def test_non_tabular_action_count_tracks_new_dispatch_cases(tmp_path):
    root = _repo(
        tmp_path,
        ["| [Glue](glue.md) | Query | JSON | 2 |"],
        "action_count_sources:\n  glue: services/GlueJsonHandler.java\n",
        {"services/GlueJsonHandler.java": 'case "CreateTable" -> create();\ncase "GetTable" -> get();\n'},
    )

    assert c.extract_action_counts(root)["glue"] == 2

    handler = root / c.PACKAGE_DIR / "services/GlueJsonHandler.java"
    handler.write_text(
        handler.read_text(encoding="utf-8") + 'case "DeleteTable" -> delete();\n',
        encoding="utf-8",
    )
    assert c.extract_action_counts(root)["glue"] == 3


def test_generated_action_table_requires_numeric_matrix_count(tmp_path):
    root = _repo(
        tmp_path,
        ["| [EFS](efs.md) | REST | REST | TBD |", "| [Glue](glue.md) | Query | JSON | 2 |"],
        "action_count_sources:\n  glue: services/GlueJsonHandler.java\n",
        {"services/GlueJsonHandler.java": 'case "CreateTable" -> create();\ncase "GetTable" -> get();\n'},
    )
    (root / c.SERVICES_DIR / "efs.md").write_text(
        "<!-- floci:actions:start -->\n| Action | Description |\n"
        "| --- | --- |\n| CreateFileSystem | Creates a file system |\n"
        "<!-- floci:actions:end -->\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="efs.*numeric matrix row"):
        c.extract_action_counts(root)


@pytest.mark.parametrize("source_entry", ["[]", "{}", "null"])
def test_non_tabular_action_sources_cannot_be_disabled(tmp_path, source_entry):
    root = _repo(tmp_path, ["| [Glue](glue.md) | Query | JSON | 2 |"], f"action_count_sources: {source_entry}\n")

    with pytest.raises(ValueError, match="non-empty mapping"):
        c.extract_action_counts(root)


def test_removing_a_rows_source_leaves_it_checked_by_nothing(tmp_path):
    root = _repo(
        tmp_path,
        ["| [Glue](glue.md) | Query | JSON | 2 |", "| [EC2](ec2.md) | Query | Query | 1 |"],
        "action_count_sources:\n  glue: services/GlueJsonHandler.java\n",
        {"services/GlueJsonHandler.java": 'case "CreateTable" -> create();\ncase "GetTable" -> get();\n'},
    )
    md = (root / c.MATRIX_DOC).read_text(encoding="utf-8")

    warnings = c.find_unchecked_rows(c.extract_matrix_row_keys(md), c.extract_matrix_action_counts(md),
                                     set(c.extract_action_counts(root)), {})

    assert len(warnings) == 1
    assert "nothing checks the count of row 'ec2'" in warnings[0]


def test_an_anchored_row_is_counted_from_its_own_dispatcher(tmp_path):
    root = _repo(
        tmp_path,
        ["| [DynamoDB](dynamodb.md) | ... | JSON 1.1 | 2 |",
         "| [DynamoDB Streams](dynamodb.md#streams) | ... | JSON 1.1 | 1 |"],
        "action_count_sources:\n  dynamodb: services/DynamoDbJsonHandler.java\n"
        "  dynamodb#streams: services/DynamoDbStreamsJsonHandler.java\n",
        {
            "services/DynamoDbJsonHandler.java": 'case "PutItem" -> put();\ncase "GetItem" -> get();\n',
            "services/DynamoDbStreamsJsonHandler.java": 'case "GetRecords" -> records();\n',
        },
    )

    assert c.extract_action_counts(root) == {"dynamodb": 2, "dynamodb#streams": 1}


def test_a_row_served_by_several_dispatchers_counts_each_action_once(tmp_path):
    # SNS answers the same actions over Query and JSON, so the row counts the union.
    root = _repo(
        tmp_path,
        ["| [SNS](sns.md) | ... | Query / JSON | 3 |"],
        "action_count_sources:\n  sns:\n    sources:\n"
        "      - services/SnsJsonHandler.java\n      - services/SnsQueryHandler.java\n",
        {
            "services/SnsJsonHandler.java": 'case "Publish" -> a();\ncase "Subscribe" -> b();\n',
            "services/SnsQueryHandler.java": 'case "Publish" -> a();\ncase "Subscribe" -> b();\n'
                                             'case "ListTopics" -> c();\n',
        },
    )

    assert c.extract_action_counts(root) == {"sns": 3}


def test_excluded_labels_are_not_counted(tmp_path):
    # CodePipeline's dispatcher shares its file with switches over action providers
    # and execution states, whose labels look like action names.
    root = _repo(
        tmp_path,
        ["| [CodePipeline](codepipeline.md) | ... | JSON 1.1 | 1 |"],
        "action_count_sources:\n  codepipeline:\n    sources:\n"
        "      - services/CodePipelineService.java\n    exclude_actions:\n      - Succeeded\n",
        {"services/CodePipelineService.java": 'case "CreatePipeline" -> create();\n'
                                              'case "Succeeded" -> done();\n'},
    )

    assert c.extract_action_counts(root) == {"codepipeline": 1}


def test_map_entry_keys_count_beside_switch_arms(tmp_path):
    # Lightsail answers some actions from switch arms and the rest from a lookup table.
    root = _repo(
        tmp_path,
        ["| [Lightsail](lightsail.md) | ... | JSON 1.1 | 3 (of 5 recognized) |"],
        "action_count_sources:\n  lightsail:\n    sources:\n"
        "      - services/LightsailJsonHandler.java\n"
        "      - { path: services/LightsailJsonHandler.java, mode: map_entries }\n",
        {"services/LightsailJsonHandler.java": 'case "GetDisk" -> disk();\ncase "GetDisks" -> disks();\n'
                                               'Map.entry("GetAlarms", "alarms"),\n'
                                               'Map.entry("GetDisks", "disks")\n'},
    )

    assert c.extract_action_counts(root) == {"lightsail": 3}


def test_a_source_without_actions_fails_rather_than_lowering_the_count(tmp_path):
    root = _repo(
        tmp_path,
        ["| [SNS](sns.md) | ... | Query / JSON | 1 |"],
        "action_count_sources:\n  sns:\n    sources:\n"
        "      - services/SnsJsonHandler.java\n      - services/SnsQueryHandler.java\n",
        {
            "services/SnsJsonHandler.java": 'case "Publish" -> a();\n',
            "services/SnsQueryHandler.java": "// moved elsewhere\n",
        },
    )

    with pytest.raises(ValueError, match="'sns' has no actions in .*SnsQueryHandler.java"):
        c.extract_action_counts(root)


@pytest.mark.parametrize("entry,message", [
    ("{ sources: [services/SnsJsonHandler.java], exclude_action: [Publish] }", "unknown key\\(s\\): exclude_action"),
    ("{ sources: [] }", "non-empty sources list"),
    ("{ sources: [{ path: services/SnsJsonHandler.java }] }", "without a path and mode"),
    ("{ sources: [services/SnsJsonHandler.java], exclude_actions: Publish }", "exclude_actions as a list"),
    ("[services/SnsJsonHandler.java]", "needs a source path"),
    ("{ sources: [{ path: services/SnsJsonHandler.java, mode: rst }] }", "'sns' unknown mode: 'rst'"),
    ("{ sources: [{ path: services/SnsJsonHandler.java, mode: rest, prefix: /v2/ }] }",
     "a source with unknown key\\(s\\): prefix"),
    ("{ sources: [services/SnsJsonHandler.java], rename_actions: [Publish] }", "rename_actions as a mapping"),
])
def test_a_malformed_source_entry_is_refused(tmp_path, entry, message):
    root = _repo(
        tmp_path,
        ["| [SNS](sns.md) | ... | Query / JSON | 1 |"],
        f"action_count_sources:\n  sns: {entry}\n",
        {"services/SnsJsonHandler.java": 'case "Publish" -> a();\n'},
    )

    with pytest.raises(ValueError, match=message):
        c.extract_action_counts(root)


def test_every_row_of_the_real_matrix_is_checked_or_hand_counted():
    root = c._repo_root()
    md = (root / c.MATRIX_DOC).read_text(encoding="utf-8")

    assert c.find_unchecked_rows(c.extract_matrix_row_keys(md), c.extract_matrix_action_counts(md),
                                 set(c.extract_action_counts(root)), c.load_hand_counted(root)) == []


# --------------------------------------------------------------------------- #
# Coverage: every row checked or hand-counted
# --------------------------------------------------------------------------- #
def test_a_formatted_first_cell_link_still_makes_a_row():
    # Presence and coverage read the same rows, so a bold link cannot count as documented
    # while escaping the count checks.
    md = "## Service Matrix\n\n| **[Glue](glue.md)** | ... | JSON 1.1 | 2 |\n\n## Common Setup\n"

    assert c.extract_matrix_row_keys(md) == ["glue"]
    assert c.extract_matrix_slugs(md) == {"glue"}
    assert c.extract_matrix_action_counts(md) == {"glue": 2}


def test_a_row_linking_no_page_is_refused():
    md = (
        "## Service Matrix\n\n| Service | Endpoint | Protocol | Supported operations |\n|---|---|---|---|\n"
        "| Glue | ... | JSON 1.1 | 2 |\n\n## Common Setup\n"
    )

    with pytest.raises(ValueError, match="row 'Glue' links no docs/services page"):
        c.extract_matrix_row_keys(md)


def test_extract_matrix_row_keys_keeps_rows_without_a_count():
    md = (
        "## Service Matrix\n\n"
        "| [S3](s3.md) | ... | REST XML | 58 |\n"
        "| [API Gateway v2](api-gateway.md#v2) | ... | REST JSON | 53 + data-plane |\n"
        "| [IoT Data](iot.md) | ... | REST JSON | 11 |\n"
        "\n## Common Setup\n"
    )

    assert c.extract_matrix_row_keys(md) == ["s3", "api-gateway#v2", "iot"]


def test_an_unchecked_row_needs_a_source_or_a_hand_counted_reason():
    keys = ["s3", "glue"]
    counts = {"s3": 58, "glue": 2}

    assert len(c.find_unchecked_rows(keys, counts, {"glue"}, {})) == 1
    assert c.find_unchecked_rows(keys, counts, {"glue"}, {"s3": "query-string subresources"}) == []


def test_a_checked_row_cannot_also_be_hand_counted():
    warnings = c.find_unchecked_rows(["glue"], {"glue": 2}, {"glue"}, {"glue": "why"})

    assert warnings == [f"row 'glue' is checked, so drop it from hand_counted in {c.REGISTRY}"]


def test_a_hand_counted_entry_must_name_a_row():
    warnings = c.find_unchecked_rows(["glue"], {"glue": 2}, {"glue"}, {"s3": "why"})

    assert warnings == [f"hand_counted row 's3' in {c.REGISTRY} is not a row of {c.MATRIX_DOC}"]


def test_two_rows_on_one_link_target_are_refused():
    # IoT Core and IoT Data both linked iot.md; the second row's count was never read.
    warnings = c.find_unchecked_rows(["iot", "iot"], {"iot": 83}, {"iot"}, {})

    assert len(warnings) == 1
    assert "two rows" in warnings[0] and "'iot'" in warnings[0]


def test_a_row_without_a_numeric_count_is_refused():
    warnings = c.find_unchecked_rows(["api-gateway#v2"], {}, set(), {})

    assert warnings == [f"row 'api-gateway#v2' in {c.MATRIX_DOC} has no numeric operation count"]


@pytest.mark.parametrize("entry", ["[s3]", "{ s3: '' }", "{ s3: 3 }"])
def test_a_hand_counted_row_needs_its_reason(tmp_path, entry):
    root = _repo(tmp_path, ["| [S3](s3.md) | ... | REST XML | 58 |"], f"hand_counted: {entry}\n")

    with pytest.raises(ValueError, match="hand_counted"):
        c.load_hand_counted(root)


def test_route_prefixes_split_one_controller_between_two_rows(tmp_path):
    # API Gateway serves v1 and, under /v2/, v2 from the same controller.
    controller = (
        '@Path("/")\npublic class ApiGatewayController {\n'
        '    @POST\n    @Path("/restapis")\n    public Response createRestApi() { return null; }\n'
        '    @POST\n    @Path("/v2/apis")\n    public Response createApi() { return null; }\n'
        '    @GET\n    @Path("/v2/apis")\n    public Response getApis() { return null; }\n'
        '}\n'
    )
    root = _repo(
        tmp_path,
        ["| [API Gateway v1](api-gateway.md) | ... | REST JSON | 1 |",
         "| [API Gateway v2](api-gateway.md#v2) | ... | REST JSON | 2 |"],
        "action_count_sources:\n"
        "  api-gateway:\n    sources:\n"
        "      - { path: services/ApiGatewayController.java, mode: rest, skip_route_prefix: /v2/ }\n"
        "  api-gateway#v2:\n    sources:\n"
        "      - { path: services/ApiGatewayController.java, mode: rest, route_prefix: /v2/ }\n",
        {"services/ApiGatewayController.java": controller},
    )

    assert c.extract_action_counts(root) == {"api-gateway": 1, "api-gateway#v2": 2}


def test_renames_and_tag_handlers_count_the_actions_a_row_serves(tmp_path):
    controller = (
        '@Path("/")\npublic class EksController {\n'
        '    @POST\n    @Path("/clusters/{name}/node-groups")\n    public Response createNodeGroup() { return null; }\n'
        '    @GET\n    @Path("/clusters/{name}/provisioned")\n    public Response get() { return null; }\n'
        '}\n'
    )
    root = _repo(
        tmp_path,
        ["| [EKS](eks.md) | ... | REST JSON | 6 |"],
        "action_count_sources:\n  eks:\n    sources:\n"
        "      - { path: services/EksController.java, mode: rest }\n"
        "      - { path: services/EksService.java, mode: tag_handler }\n"
        "    rename_actions:\n"
        "      CreateNodeGroup: CreateNodegroup\n"
        "      Get: [GetThing, ListThings]\n",
        {
            "services/EksController.java": controller,
            "services/EksService.java": "public class EksService implements Startable, TagHandler {\n}\n",
        },
    )

    assert c.extract_action_counts(root) == {"eks": 6}




def test_extract_matrix_slugs_raises_on_missing_heading():
    # A renamed/reordered heading must fail loudly rather than silently returning an
    # empty slug set, which would make every registered service look undocumented.
    md = "# Services Overview\n\nNo matrix heading here.\n"
    with pytest.raises(ValueError, match="Service Matrix"):
        c.extract_matrix_slugs(md)


# --------------------------------------------------------------------------- #
# Classification
# --------------------------------------------------------------------------- #
def test_find_undocumented_exact_match_needs_no_alias():
    undocumented, expired = c.find_undocumented(
        keys=["ssm", "sqs"],
        slugs={"ssm", "sqs"},
        aliases={},
        deferred=[],
        today=date(2026, 1, 1),
    )
    assert undocumented == []
    assert expired == []


def test_find_undocumented_alias_resolves():
    undocumented, expired = c.find_undocumented(
        keys=["kafka"],
        slugs={"msk"},
        aliases={"kafka": "msk"},
        deferred=[],
        today=date(2026, 1, 1),
    )
    assert undocumented == []


def test_find_undocumented_flags_genuine_gap():
    undocumented, expired = c.find_undocumented(
        keys=["swf"],
        slugs={"ssm"},
        aliases={},
        deferred=[],
        today=date(2026, 1, 1),
    )
    assert undocumented == ["swf"]
    assert expired == []


def test_find_undocumented_unexpired_deferred_is_silenced():
    d = c.DeferredEntry(key="bedrock-agentcore", reason="tracked in #2436", by=date(2026, 9, 15))
    undocumented, expired = c.find_undocumented(
        keys=["bedrock-agentcore"],
        slugs=set(),
        aliases={},
        deferred=[d],
        today=date(2026, 8, 22),
    )
    assert undocumented == []
    assert expired == []


def test_find_undocumented_expired_deferred_reports_and_still_flags():
    # This is the case the mandatory `by:` date exists to catch: a deferred entry that
    # has run past its expiry is reported distinctly (so the message names the date
    # and reason) AND still counts as undocumented - it cannot silently keep passing.
    d = c.DeferredEntry(key="bedrock-agentcore", reason="tracked in #2436", by=date(2026, 9, 15))
    undocumented, expired = c.find_undocumented(
        keys=["bedrock-agentcore"],
        slugs=set(),
        aliases={},
        deferred=[d],
        today=date(2026, 10, 1),
    )
    assert undocumented == ["bedrock-agentcore"]
    assert expired == [d]


def test_find_undocumented_deferred_entry_already_resolved_is_not_flagged():
    # If the row was added but the registry entry wasn't cleaned up, the key resolves
    # via the matrix itself regardless of expiry - no warning either way.
    d = c.DeferredEntry(key="bedrock-agentcore", reason="tracked in #2436", by=date(2020, 1, 1))
    undocumented, expired = c.find_undocumented(
        keys=["bedrock-agentcore"],
        slugs={"bedrock-agentcore"},
        aliases={},
        deferred=[d],
        today=date(2026, 8, 22),
    )
    assert undocumented == []
    assert expired == []


# --------------------------------------------------------------------------- #
# Unlinked pages
# --------------------------------------------------------------------------- #
def test_find_unlinked_pages_flags_a_page_with_no_row(tmp_path):
    for name in ("index.md", "ssm.md", "elb.md", "elb-classic.md"):
        (tmp_path / name).write_text("# page\n")
    # index.md is the matrix page itself; elb.md is linked; elb-classic.md is not.
    assert c.find_unlinked_pages(tmp_path, slugs={"ssm", "elb"}, facet_pages=set()) == ["elb-classic"]


def test_find_unlinked_pages_honours_facet_exemption(tmp_path):
    for name in ("index.md", "lambda.md", "lambda-microvms.md"):
        (tmp_path / name).write_text("# page\n")
    assert c.find_unlinked_pages(tmp_path, slugs={"lambda"}, facet_pages={"lambda-microvms"}) == []


def test_find_unlinked_pages_ignores_non_markdown(tmp_path):
    (tmp_path / "ssm.md").write_text("# page\n")
    (tmp_path / "diagram.svg").write_text("<svg/>")
    assert c.find_unlinked_pages(tmp_path, slugs={"ssm"}, facet_pages=set()) == []


def test_action_count_source_paths_are_relative_to_the_package_root(tmp_path):
    root = _repo(
        tmp_path,
        ["| [Glue](glue.md) | Query | JSON | 2 |"],
        f"action_count_sources:\n  glue: {c.PACKAGE_DIR}/services/GlueJsonHandler.java\n",
    )

    with pytest.raises(ValueError, match="relative to"):
        c.extract_action_counts(root)
