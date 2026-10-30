#!/usr/bin/env bash
# Run against a disposable Floci process with FLOCI_SERVICES_S3_ENFORCE_AUTH=true.
set -euo pipefail

endpoint="${AWS_ENDPOINT_URL:-http://localhost:4566}"
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
export AWS_EC2_METADATA_DISABLED=true AWS_PAGER=""
unset AWS_SESSION_TOKEN AWS_PROFILE

for command in aws curl jq; do
    command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done

aws_local() { aws --endpoint-url "$endpoint" "$@"; }
work_dir="$(mktemp -d)"
bucket="private-origin-$(date +%s)-${RANDOM}"
distributions=()
oac=""
bucket_created=false

cleanup() {
    local id etag
    for id in "${distributions[@]}"; do
        if ! aws_local cloudfront get-distribution-config --id "$id" > "$work_dir/current.json"; then
            echo "Could not read distribution $id for cleanup" >&2
            continue
        fi
        etag="$(jq -r .ETag "$work_dir/current.json")"
        jq '.DistributionConfig | .Enabled = false' "$work_dir/current.json" > "$work_dir/disabled.json"
        if aws_local cloudfront update-distribution --id "$id" --if-match "$etag" \
                --distribution-config "file://$work_dir/disabled.json" > "$work_dir/updated.json"; then
            etag="$(jq -r .ETag "$work_dir/updated.json")"
            aws_local cloudfront delete-distribution --id "$id" --if-match "$etag" \
                || echo "Could not delete distribution $id" >&2
        fi
    done
    if [[ -n "$oac" ]]; then
        etag="$(aws_local cloudfront get-origin-access-control --id "$oac" --query ETag --output text)"
        aws_local cloudfront delete-origin-access-control --id "$oac" --if-match "$etag" \
            || echo "Could not delete origin access control $oac" >&2
    fi
    if [[ "$bucket_created" == true ]]; then
        aws_local s3api delete-object --bucket "$bucket" --key example.txt >/dev/null
        aws_local s3api delete-bucket --bucket "$bucket" || echo "Could not delete bucket $bucket" >&2
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT

aws_local s3api create-bucket --bucket "$bucket" >/dev/null
bucket_created=true
printf 'private origin example\n' > "$work_dir/example.txt"
aws_local s3api put-object --bucket "$bucket" --key example.txt --body "$work_dir/example.txt" >/dev/null
aws_local s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration \
    'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'

oac="$(aws_local cloudfront create-origin-access-control --origin-access-control-config \
    "Name=$bucket,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=s3" \
    --query OriginAccessControl.Id --output text)"

for label in allowed denied; do
    jq -n --arg reference "$bucket-$label" --arg bucket "$bucket" --arg oac "$oac" '{
        CallerReference: $reference, Enabled: true, Comment: "Private S3 origin example",
        Origins: {Quantity: 1, Items: [{Id: "private-s3", DomainName: ($bucket + ".s3.us-east-1.amazonaws.com"),
            OriginAccessControlId: $oac, S3OriginConfig: {OriginAccessIdentity: ""}}]},
        DefaultCacheBehavior: {TargetOriginId: "private-s3", ViewerProtocolPolicy: "allow-all",
            TrustedSigners: {Enabled: false, Quantity: 0},
            ForwardedValues: {QueryString: false, Cookies: {Forward: "none"}}, MinTTL: 0}
    }' > "$work_dir/distribution.json"
    aws_local cloudfront create-distribution --distribution-config "file://$work_dir/distribution.json" \
        > "$work_dir/$label.json"
    distributions+=("$(jq -r .Distribution.Id "$work_dir/$label.json")")
done

allowed_arn="$(jq -r .Distribution.ARN "$work_dir/allowed.json")"
jq -n --arg resource "arn:aws:s3:::$bucket/*" --arg source "$allowed_arn" '{
    Version: "2012-10-17", Statement: [{Effect: "Allow",
        Principal: {Service: "cloudfront.amazonaws.com"}, Action: "s3:GetObject", Resource: $resource,
        Condition: {StringEquals: {"AWS:SourceArn": $source}}}]
}' > "$work_dir/policy.json"
aws_local s3api put-bucket-policy --bucket "$bucket" --policy "file://$work_dir/policy.json"

check_get() {
    local label="$1" expected="$2" host="$3" path="$4" status
    # curl has no SigV4 credentials: these are anonymous viewer/direct requests.
    status="$(curl --noproxy '*' --silent --show-error --max-time 10 \
        -H "Host: $host" -o "$work_dir/response" -w '%{http_code}' "$endpoint$path")"
    if [[ "$status" != "$expected" ]]; then
        echo "$label: expected HTTP $expected, received HTTP $status" >&2
        exit 1
    fi
    if [[ "$expected" == 200 ]]; then
        cmp "$work_dir/example.txt" "$work_dir/response"
    fi
    echo "$label: HTTP $status"
}

allowed_host="$(jq -r .Distribution.DomainName "$work_dir/allowed.json")"
denied_host="$(jq -r .Distribution.DomainName "$work_dir/denied.json")"
check_get 'Authorized CloudFront distribution' 200 "$allowed_host" /example.txt
check_get 'Anonymous direct S3 request' 403 localhost "/$bucket/example.txt"
check_get 'Other CloudFront distribution' 403 "$denied_host" /example.txt
