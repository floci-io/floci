#!/usr/bin/env bats
# CloudFront AWS CLI compatibility tests

setup() {
    load 'test_helper/common-setup'
}

@test "CloudFront: list response headers policies decodes the modeled payload" {
    run aws_cmd cloudfront list-response-headers-policies \
        --type managed \
        --max-items 1 \
        --no-paginate
    assert_success

    [ "$(json_get "$output" '.ResponseHeadersPolicyList.Quantity')" = "1" ]
    [ "$(json_get "$output" '.ResponseHeadersPolicyList.MaxItems')" = "1" ]
    [ "$(json_get "$output" '.ResponseHeadersPolicyList.Items | length')" = "1" ]
    [ "$(json_get "$output" '.ResponseHeadersPolicyList.Items[0].Type')" = "managed" ]
    [ -n "$(json_get "$output" \
        '.ResponseHeadersPolicyList.Items[0].ResponseHeadersPolicy.Id // empty')" ]
    [ -n "$(json_get "$output" \
        '.ResponseHeadersPolicyList.NextMarker // empty')" ]
}
