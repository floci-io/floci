"""Opt-in AWS oracle capture. Only synthetic, read-only TestMetricFilter requests."""

import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess
import time


def cases():
    result = []

    def add(name, pattern, messages):
        result.append({"id": name, "request": {
            "filterPattern": pattern, "logEventMessages": messages}})

    terms = ["ERROR", "ERRORS", "xERROR", "error", "[ERROR]", "ERROR_code",
             "ERROR timeout", "INFO timeout", "WARN", "two words", "two  words"]
    for name, pattern in [
        ("substring", "ERROR"), ("case", "error"), ("and", "ERROR timeout"),
        ("negative", "ERROR -timeout"), ("negative-only", "-ERROR"),
        ("optional", "?ERROR ?WARN"), ("optional-ignored", "?ERROR timeout"),
        ("optional-negative", "?ERROR -timeout"), ("phrase", '"two words"'),
        ("negative-phrase", '-"two words"'), ("optional-phrase", '?"two words" ?WARN'),
        ("wildcard", "ERR*"), ("quoted-wildcard", '"ERR*"'),
        ("bare-symbol", "ERROR!"), ("bare-colon", "ERROR:"),
        ("empty", ""), ("blank", " "), ("quoted-empty", '""'),
        ("missing-quote", '"ERROR'), ("adjacent-quote", '"ERROR"WARN'),
    ]:
        add("terms-" + name, pattern, terms)

    regex_messages = ["ERROR", "WARN", "aaaa", "aa", "cat", "a cat", "hat",
                      "color", "colour", "a-b", "a b", "a\nb", "123", "_", "café",
                      "key:value", "ab", "back\\slash", "!"]
    for name, pattern in [
        ("alternation", "%ERROR|WARN%"), ("anchors", "%^[hc]at$%"),
        ("quantifier", "%^a{3,5}$%"), ("optional", "%colou?r%"),
        ("digits", "%\\d+%"), ("word", "%\\w+%"), ("space", "%a\\sb%"),
        ("hex", "%\\x3A%"), ("dot-newline", "%a.b%"),
        ("escaped-symbol", "%a\\-b%"), ("group-rejected", "%(ab)+%"),
        ("unicode-rejected", "%café%"), ("symbol-rejected", "%!%"),
        ("escape-rejected", "%\\p%"), ("escaped-group", "%\\(ab\\)%"),
        ("empty", "%%"), ("unclosed", "%abc"), ("bad-range", "%[z-a]%"),
        ("lazy", "%a+?%"), ("possessive", "%a++%"),
        ("class-intersection", "%[a&&b]%"), ("two", "%ERROR% %WARN%"),
        ("three", "%ERROR% %WARN% %INFO%"),
    ]:
        add("regex-" + name, pattern, regex_messages)

    json_messages = [
        '{"a":1}', '{"a":"1"}', '{"a":1.0}', '{"a":"01"}', '{"a":true}',
        '{"a":false}', '{"a":"true"}', '{"a":null}', '{}', '{"a":{}}',
        '{"a":[]}', '{"a":"foo"}', '{"a":"foobar"}', '{"a":"FOO"}',
        '{"a":1e3}', '{"a":"1e3"}', '{"a":1e309}', '{"a":"1e309"}',
        '{"a":1e-999}', '{"a":-0.0}', '{"a":9007199254740993}',
    ]
    for name, pattern in [
        ("numeric", "{ $.a = 1 }"), ("quoted-number", '{ $.a = "1" }'),
        ("numeric-ne", "{ $.a != 1 }"), ("string-ne", '{ $.a != "foo" }'),
        ("ordering", "{ $.a > 0 }"), ("quoted-order", '{ $.a > "0" }'),
        ("bare-word", "{ $.a = foo }"), ("bare-boolean", "{ $.a = true }"),
        ("quoted-boolean", '{ $.a = "true" }'), ("is-true", "{ $.a IS TRUE }"),
        ("is-false", "{ $.a IS FALSE }"), ("null", "{ $.a IS NULL }"),
        ("missing", "{ $.a NOT EXISTS }"), ("star", "{ $.a = * }"),
        ("quoted-star", '{ $.a = "*" }'), ("glob", "{ $.a = foo* }"),
        ("exponent", "{ $.a = 1e3 }"), ("plus", "{ $.a = +1 }"),
        ("overflow", "{ $.a = 1e309 }"), ("underflow", "{ $.a = 1e-999 }"),
        ("precision", "{ $.a = 9007199254740992 }"),
        ("regex-number", "{ $.a = %1% }"), ("keyword-and", "{ $.a = 1 AND $.b = 2 }"),
        ("double-equal", "{ $.a == 1 }"), ("trailing", "{ $.a = 1 } x"),
    ]:
        add("json-" + name, pattern, json_messages)

    selectors = [
        '{"a":[1,2]}', '{"a":[2,1]}', '{"a":[null,2]}',
        '{"a":{"x":1,"y":2}}', '{"a":{"x":2,"y":1}}',
        '{"a":[{"b":1},{"b":2}]}', '{"a.b":2}', '{"a-b":2}',
        '{"a":null}', '{}', '[1,2]', '{"a":2} trailing',
    ]
    for name, pattern in [
        ("index", "{ $.a[1] = 2 }"), ("wildcard", "{ $.a[*] = 2 }"),
        ("object-wildcard", "{ $.a.* = 2 }"), ("wildcard-child", "{ $.a[*].b = 2 }"),
        ("quoted-property", "{ $.['a.b'] = 2 }"), ("bracket-property", "{ $['a.b'] = 2 }"),
        ("double-property", '{ $.["a.b"] = 2 }'), ("hyphen", "{ $.a-b = 2 }"),
        ("root-index", "{ $[1] = 2 }"), ("negative-index", "{ $.a[-1] = 2 }"),
        ("null-extraction", "{ $.a IS NULL }"), ("or-extraction", "{ $.a = 9 || $.missing NOT EXISTS }"),
        ("array-ne", "{ $.a[*] != 2 }"), ("nested-wildcard", "{ $.a[*].* = 2 }"),
    ]:
        add("selector-" + name, pattern, selectors)

    spaced = ["ERROR", "ERROR one", "ERROR one two", "WARN one", "INFO one",
              'a "two words" c', "a [two words] c", 'a "two words"',
              "a [two words]", "a  b   c ", "a\tb\tc", 'a "unclosed', "a [unclosed",
              'a "b"c d', "one", "1 2", "1.0 2", "a b c d"]
    for name, pattern in [
        ("fields", "[a, b, c]"), ("last-rest", "[a, rest]"), ("single", "[a]"),
        ("empty", "[]"), ("leading-ellipsis", "[..., last]"),
        ("middle-ellipsis", "[first, ..., last]"), ("trailing-ellipsis", "[first, ...]"),
        ("unnamed", "[, b,]"), ("trailing-comma", "[a,]"),
        ("constraint", "[level=ERROR, rest]"),
        ("or", "[level=ERROR || level=WARN, rest]"),
        ("numeric", "[a=1, rest]"), ("quoted-numeric", '[a="1", rest]'),
        ("cross-field", "[a=ERROR || b=one, b]"),
        ("duplicate", "[a, a]"), ("invalid-name", "[1a]"),
        ("double-ellipsis", "[a, ..., b, ...]"),
        ("bad-operator", "[a == 1]"), ("regex", "[a=%ERR%, rest]"),
    ]:
        add("space-" + name, pattern, spaced)
    return result


def edge_cases():
    numbers = ['{"a":' + value + '}' for value in [
        '1', '1.0', '1e0', '"1"', '"1.0"', '"01"', '0.0', '-0.0', '-1',
        '9007199254740992', '9007199254740993', '1e308', '1e309', '"1e309"',
        '1e-999', '"1e-999"', '0', 'true', '"true"', 'null', '[1,2]', '[null,2]',
    ]]
    structures = ['{}', '{"a":null}', '{"a":2}', '{"a":1}', '{"a":"2"}',
                  '{"a":[]}', '{"a":[1,2]}', '{"a":[2,1]}', '{"a":[1]}',
                  '{"a":[null,2]}', '{"a":{}}', '{"a":{"b":2}}',
                  '{"a":[{"b":1},{"b":2}]}', '{"a":[{"b":null}]}',
                  '{"a":[[1,2]]}', '{"a":[true,false]}']
    result = []
    for name, pattern, messages in [
        ("decimal", "{ $.a = 1.0 }", numbers),
        ("zero", "{ $.a = 0 }", numbers),
        ("negative-zero", "{ $.a = -0.0 }", numbers),
        ("precise-long", "{ $.a = 9007199254740993 }", numbers),
        ("large-finite", "{ $.a = 1e308 }", numbers),
        ("large-ordered", "{ $.a > 1e307 }", numbers),
        ("underflow-ordered", "{ $.a > 1e-999 }", numbers),
        ("string-exponent", '{ $.a = "1e0" }', numbers),
        ("is-null", "{ $.a IS NULL }", structures),
        ("equals", "{ $.a = 2 }", structures),
        ("not-equals", "{ $.a != 2 }", structures),
        ("wildcard-not-equals", "{ $.a[*] != 2 }", structures),
        ("wildcard-null", "{ $.a[*] IS NULL }", structures),
        ("wildcard-missing", "{ $.a[*] NOT EXISTS }", structures),
        ("missing", "{ $.a NOT EXISTS }", structures),
        ("child", "{ $.a.b = 2 }", structures),
        ("child-missing", "{ $.a.b NOT EXISTS }", structures),
        ("star", "{ $.a = * }", structures),
        ("quoted-number", '{ $.a = "2" }', structures),
        ("bare-null", "{ $.a = null }", structures),
        ("is-not-null", "{ $.a IS NOT NULL }", structures),
        ("selector-root", "{ $ = 2 }", structures),
        ("json-negative-regex", "{ $.a != %2% }", structures),
        ("regex-text", "ERROR %WARN%", ["ERROR WARN", "ERROR", "WARN"]),
        ("regex-optional", "?%WARN%", ["WARN", "WARN INFO"]),
        ("term-punctuation", "a-b", ["a-b", "a b"]),
        ("term-underscore", "a_b", ["a_b", "a b"]),
        ("term-dot", "a.b", ["a.b", "a b"]),
        ("term-question", "a?b", ["a?b", "a b"]),
        ("regex-structured-two", "{ $.a = %1% || $.a = %2% }", structures),
        ("regex-structured-three", "{ $.a = %1% || $.a = %2% || $.a = %3% }", structures),
        ("quoted-order-space", '[a>"1", b]', ["2 x", "0 x"]),
        ("json-keyword-case", "{ $.a is null }", structures),
        ("json-unquoted-symbol", "{ $.a = a:b }", ['{"a":"a:b"}']),
        ("space-quote-trailing", "[a, b]", ['x "y" ', "x [y] ", "x y ", "x  ", 'x ""', "x []"]),
        ("space-incomplete", "[a, b, c]", ['x "y z', "x [y z", 'x "y z q', "x [y z q"]),
    ]:
        result.append({"id": "edge-" + name, "request": {
            "filterPattern": pattern, "logEventMessages": messages}})
    return result


def boundary_cases():
    messages = [
        '{"a":true}', '{"a":false}', '{"a":[true,false]}', '{"a":[null]}',
        '{"a":[[null]]}', '{"a":[{"b":2},{"b":1}]}', '{"a":[{}]}', '{}',
    ]
    result = []
    for name, pattern, inputs in [
        ("array-true", "{ $.a IS TRUE }", messages),
        ("array-false", "{ $.a IS FALSE }", messages),
        ("array-null", "{ $.a IS NULL }", messages),
        ("nested-negative", "{ $.a[*].b != 2 }", messages),
        ("wildcard-null", "{ $.a[*] IS NULL }", messages),
        ("term-slash", "a/b", ["a/b", "a b"]),
        ("term-at", "a@b", ["a@b", "a b"]),
        ("term-unicode", "café", ["café", "cafe"]),
        ("quoted-space", '" "', ["x", "two words", " "]),
        ("huge-exponent", "{ $.a = 1e2147483648 }", ['{"a":1}', '{"a":"1e2147483648"}']),
        ("bare-negative", "a-b", ["a", "b", "a-b", "ab", "a b"]),
    ]:
        result.append({"id": "boundary-" + name, "request": {
            "filterPattern": pattern, "logEventMessages": inputs}})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--endpoint-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--suite", choices=("base", "edges", "boundaries"), default="base")
    args = parser.parse_args()
    if args.endpoint_url != f"https://logs.{args.region}.amazonaws.com":
        parser.error("capture requires the explicit regional AWS Logs endpoint")
    corpus = {"base": cases, "edges": edge_cases, "boundaries": boundary_cases}[args.suite]()
    assert len(cases()) + len(edge_cases()) + len(boundary_cases()) <= 149
    for case in corpus:
        for attempt in range(4):
            command = ["aws", "--profile", args.profile, "--region", args.region,
                       "--endpoint-url", args.endpoint_url, "--no-cli-pager",
                       "logs", "test-metric-filter", "--output", "json",
                       "--cli-input-json", json.dumps(case["request"])]
            completed = subprocess.run(command, capture_output=True, text=True)
            if "Throttling" not in completed.stderr:
                break
            time.sleep(2 ** attempt)
        if completed.returncode == 0:
            case["response"] = json.loads(completed.stdout)
        else:
            error = re.search(r"An error occurred \((\w+)\).*?: (.*)", completed.stderr)
            if not error or error.group(1) != "InvalidParameterException":
                raise RuntimeError("AWS capture blocked: " + completed.stderr)
            case["error"] = {"code": error.group(1), "message": error.group(2)}
        print(case["id"], "error" if "error" in case else
              str(len(case["response"]["matches"])) + " matches", flush=True)
        time.sleep(0.15)
    fixture = {
        "provenance": {
            "source": "AWS CloudWatch Logs TestMetricFilter",
            "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "region": args.region,
            "endpoint": args.endpoint_url,
            "inputs": "Synthetic only. No resource writes or user logs.",
            "eventNumberBase": 1,
            "reference": "https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_TestMetricFilter.html",
        },
        "cases": corpus,
    }
    args.output.write_text(json.dumps(fixture, indent=2) + "\n")


if __name__ == "__main__":
    main()
