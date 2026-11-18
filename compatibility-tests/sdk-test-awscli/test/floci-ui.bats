#!/usr/bin/env bats
# Floci UI launcher endpoints. These suites run against the native image, where a JSON body
# Jackson needs reflection metadata for answers 500 if that metadata is missing (#5251).

setup() {
    load 'test_helper/common-setup'
}

@test "Floci UI: the status endpoint answers with its JSON body before the UI starts" {
    run curl -sS -w '\n%{http_code}' "$FLOCI_ENDPOINT/_floci/ui/status"
    assert_success
    [ "$(printf '%s\n' "$output" | tail -n 1)" = "200" ]
    body="$(printf '%s\n' "$output" | head -n -1)"
    [ "$(printf '%s' "$body" | jq -r '.ready | type')" = "boolean" ]
    [ "$(printf '%s' "$body" | jq -r 'has("url") and has("error")')" = "true" ]
}
