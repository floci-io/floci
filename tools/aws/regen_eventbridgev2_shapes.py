#!/usr/bin/env python3
"""Vendor the EventBridge V2 event source operations' request constraints for RequestConstraintCheck.

The shapes the model marks `sensitive` keep that mark, so a violation does not quote their value.
The order of every enum's values and of every structure's members is the order AWS answers in,
which the model does not give.
"""
from __future__ import annotations

from pathlib import Path

import request_shapes

MODEL = Path("eventbridgev2/2025-05-15")
OUTPUT = request_shapes.REPO_ROOT / "src/main/resources/aws/eventbridgev2-request-shapes.json"

OPERATIONS = ("CreateEventSource", "DeleteEventSource", "DescribeEventSource", "ListEventSources",
              "RevokeResource", "RotateWebhookCredentials", "UpdateEventSource")

# The order AWS lists the values of a violated enum in. It follows no rule, so each is measured.
ENUM_ORDER = {
    "WebhookHmacAlgorithm": ["SHA1", "SHA256"],
    "WebhookHmacEncoding": ["BASE64", "HEX"],
    "WebhookSignedMessageComposition": ["TIMESTAMP_DOT_BODY", "BODY", "MESSAGE_ID_DOT_TIMESTAMP_DOT_BODY"],
    "WebhookSecretEncoding": ["RAW", "BASE64"],
    "WebhookDeduplicationType": ["CONTENT_BASED"],
    "LogLevel": ["ERROR", "INFO", "OFF"],
    "IncludePayload": ["ON_ERROR_ONLY", "FULL"],
}

# The one structure AWS walks out of hash order, as measured.
MEMBER_ORDER = {
    "ListEventSourcesRequest": ["MaxResults", "NextToken", "NamePrefix", "EventBusArn"],
}


def java_hash(text: str) -> int:
    """`String.hashCode` of `text`, as an unsigned 32-bit integer."""
    value = 0
    data = text.encode("utf-16-be")
    for index in range(0, len(data), 2):
        value = (value * 31 + int.from_bytes(data[index:index + 2], "big")) & 0xFFFFFFFF
    return value


def table_size(count: int) -> int:
    """The bucket count of a default `java.util.HashMap` holding `count` entries."""
    size = 16
    while count > size * 3 // 4:
        size *= 2
    return size


def hash_order(members: list[str]) -> list[str]:
    """`members` in the iteration order of a `java.util.HashMap` keyed by each name with a lower-case
    first letter. Names that share a bucket come in alphabetical order."""
    size = table_size(len(members))

    def bucket(member: str) -> int:
        code = java_hash(member[0].lower() + member[1:])
        return (code ^ (code >> 16)) & (size - 1)

    return sorted(members, key=lambda member: (bucket(member), member[0].lower() + member[1:]))


def reachable(model: dict, operations: tuple[str, ...]) -> set[str]:
    """The names of the shapes the input of `operations` reaches."""
    seen: set[str] = set()
    pending = [model["operations"][name]["input"]["shape"] for name in operations]
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        shape = model["shapes"][name]
        if shape["type"] == "structure":
            pending.extend(spec["shape"] for spec in shape.get("members", {}).values())
        elif shape["type"] == "list":
            pending.append(shape["member"]["shape"])
        elif shape["type"] == "map":
            pending.append(shape["key"]["shape"])
            pending.append(shape["value"]["shape"])
    return seen


def prepare(model: dict) -> dict:
    """The model with each vendored enum's values and each structure's members in the order AWS answers in."""
    for name in sorted(reachable(model, OPERATIONS)):
        shape = model["shapes"][name]
        if "enum" in shape:
            if name not in ENUM_ORDER:
                raise SystemExit(f"error: no measured order for the values of {name}: {shape['enum']}")
            if sorted(ENUM_ORDER[name]) != sorted(shape["enum"]):
                raise SystemExit(f"error: the model's values of {name} are {shape['enum']}, "
                                 f"not the measured {ENUM_ORDER[name]}")
            shape["enum"] = list(ENUM_ORDER[name])
    for name, shape in model["shapes"].items():
        if shape["type"] != "structure" or not shape.get("members"):
            continue
        members = shape["members"]
        order = MEMBER_ORDER.get(name) or hash_order(list(members))
        if sorted(order) != sorted(members):
            raise SystemExit(f"error: the measured members of {name} are {order}, the model's are {list(members)}")
        shape["members"] = {member: members[member] for member in order}
    return model


def main(argv: list[str] | None = None) -> int:
    return request_shapes.main(argv, script=__file__, model=MODEL, output=OUTPUT,
                               description=__doc__, operations=OPERATIONS, sensitive=True,
                               prepare=prepare)


if __name__ == "__main__":
    raise SystemExit(main())
