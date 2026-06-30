#!/usr/bin/env bats
# CloudFormation tests

setup() {
    load 'test_helper/common-setup'
    STACK_NAME="bats-cfn-stack-$(unique_name)"
    TEMPLATE_FILE=$(mktemp /tmp/cfn-bats-XXXXXX.yaml)
}

teardown() {
    aws_cmd cloudformation delete-stack --stack-name "$STACK_NAME" >/dev/null 2>&1 || true
    [ -n "$TEMPLATE_FILE" ] && rm -f "$TEMPLATE_FILE"
}

# ── CreateStack / DescribeStacks ──────────────────────────────────────────────

@test "CloudFormation: create stack reaches CREATE_COMPLETE" {
    cat > "$TEMPLATE_FILE" << 'EOF'
AWSTemplateFormatVersion: '2010-09-09'
Resources:
  MyQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: bats-cfn-basic-queue
EOF
    run aws_cmd cloudformation create-stack \
        --stack-name "$STACK_NAME" \
        --template-body "file://$TEMPLATE_FILE"
    assert_success

    run aws_cmd cloudformation describe-stacks --stack-name "$STACK_NAME"
    assert_success
    local stack_status
    stack_status=$(json_get "$output" '.Stacks[0].StackStatus')
    [ "$stack_status" = "CREATE_COMPLETE" ]
}

@test "CloudFormation: describe-stack-resources lists provisioned resources" {
    cat > "$TEMPLATE_FILE" << 'EOF'
AWSTemplateFormatVersion: '2010-09-09'
Resources:
  MyQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: bats-cfn-resources-queue
EOF
    aws_cmd cloudformation create-stack \
        --stack-name "$STACK_NAME" \
        --template-body "file://$TEMPLATE_FILE" >/dev/null

    run aws_cmd cloudformation describe-stack-resources --stack-name "$STACK_NAME"
    assert_success
    local count
    count=$(json_get "$output" '.StackResources | length')
    [ "$count" -gt 0 ]
}

@test "CloudFormation: delete stack removes resources" {
    cat > "$TEMPLATE_FILE" << 'EOF'
AWSTemplateFormatVersion: '2010-09-09'
Resources:
  MyQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: bats-cfn-delete-queue
EOF
    aws_cmd cloudformation create-stack \
        --stack-name "$STACK_NAME" \
        --template-body "file://$TEMPLATE_FILE" >/dev/null

    run aws_cmd cloudformation delete-stack --stack-name "$STACK_NAME"
    assert_success

    run aws_cmd cloudformation describe-stacks --stack-name "$STACK_NAME"
    # Stack should no longer exist
    [[ "$output" == *"does not exist"* ]]
    STACK_NAME=""  # prevent teardown from trying again
}

# ── aws cloudformation deploy (CreateChangeSet + ExecuteChangeSet by ARN) ─────
#
# Regression: DescribeChangeSet / ExecuteChangeSet failed when called with the
# changeset ARN (the AWS CLI always passes the ARN, not the short name).
# See: https://github.com/floci-io/floci/issues/606

@test "CloudFormation: deploy creates stack via changeset" {
    cat > "$TEMPLATE_FILE" << 'EOF'
AWSTemplateFormatVersion: '2010-09-09'
Resources:
  MyQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: bats-cfn-deploy-queue
EOF
    run aws_cmd cloudformation deploy \
        --stack-name "$STACK_NAME" \
        --template-file "$TEMPLATE_FILE"
    assert_success

    run aws_cmd cloudformation describe-stacks --stack-name "$STACK_NAME"
    assert_success
    local stack_status
    stack_status=$(json_get "$output" '.Stacks[0].StackStatus')
    [ "$stack_status" = "CREATE_COMPLETE" ]
}

@test "CloudFormation: deploy provisions resources correctly" {
    cat > "$TEMPLATE_FILE" << 'EOF'
AWSTemplateFormatVersion: '2010-09-09'
Resources:
  MyQueue:
    Type: AWS::SQS::Queue
    Properties:
      QueueName: bats-cfn-deploy-res-queue
EOF
    aws_cmd cloudformation deploy \
        --stack-name "$STACK_NAME" \
        --template-file "$TEMPLATE_FILE" >/dev/null

    run aws_cmd cloudformation describe-stack-resources --stack-name "$STACK_NAME"
    assert_success
    local resource_status
    resource_status=$(json_get "$output" '.StackResources[0].ResourceStatus')
    [ "$resource_status" = "CREATE_COMPLETE" ]
}

# ── EC2 VPC/Subnet provisioning (regression: issue #1297) ─────────────────────
#
# CloudFormation must create AWS::EC2::VPC / AWS::EC2::Subnet resources for real
# (in EC2), not stub them. Before the fix, describe-stacks reported CREATE_COMPLETE
# and exported the subnet ids, but ec2 describe-subnets did not return them and
# elbv2 create-load-balancer failed with "The subnet ID '...' does not exist".
# https://github.com/floci-io/floci/issues/1297

@test "CloudFormation: VPC/subnet stack exports subnet ids that EC2 and ELBv2 can use" {
    cat > "$TEMPLATE_FILE" << 'EOF'
{
  "Resources": {
    "Vpc": {
      "Type": "AWS::EC2::VPC",
      "Properties": {"CidrBlock": "10.20.0.0/16"}
    },
    "Subnet1": {
      "Type": "AWS::EC2::Subnet",
      "Properties": {
        "VpcId": {"Ref": "Vpc"},
        "CidrBlock": "10.20.1.0/24",
        "AvailabilityZone": "us-east-1a"
      }
    },
    "Subnet2": {
      "Type": "AWS::EC2::Subnet",
      "Properties": {
        "VpcId": {"Ref": "Vpc"},
        "CidrBlock": "10.20.2.0/24",
        "AvailabilityZone": "us-east-1b"
      }
    }
  },
  "Outputs": {
    "Subnet1Id": {"Value": {"Ref": "Subnet1"}},
    "Subnet2Id": {"Value": {"Ref": "Subnet2"}}
  }
}
EOF
    run aws_cmd cloudformation create-stack \
        --stack-name "$STACK_NAME" \
        --template-body "file://$TEMPLATE_FILE"
    assert_success

    run aws_cmd cloudformation describe-stacks --stack-name "$STACK_NAME"
    assert_success
    local stack_status
    stack_status=$(json_get "$output" '.Stacks[0].StackStatus')
    [ "$stack_status" = "CREATE_COMPLETE" ]

    # The exported subnet ids must be real subnet-xxxx ids (not the old stub shape).
    local subnet1 subnet2
    subnet1=$(json_get "$output" '.Stacks[0].Outputs[] | select(.OutputKey=="Subnet1Id") | .OutputValue')
    subnet2=$(json_get "$output" '.Stacks[0].Outputs[] | select(.OutputKey=="Subnet2Id") | .OutputValue')
    [[ "$subnet1" == subnet-* ]]
    [[ "$subnet2" == subnet-* ]]

    # describe-subnets must return the exact subnet ids CloudFormation exported.
    run aws_cmd ec2 describe-subnets --subnet-ids "$subnet1" "$subnet2"
    assert_success
    local count
    count=$(json_get "$output" '.Subnets | length')
    [ "$count" -eq 2 ]

    # ELBv2 create-load-balancer must accept the exported subnets (no SubnetNotFound).
    run aws_cmd elbv2 create-load-balancer \
        --name "bats-vpc-alb-${RANDOM}" \
        --type application \
        --scheme internal \
        --subnets "$subnet1" "$subnet2"
    assert_success
    local lb_arn
    lb_arn=$(json_get "$output" '.LoadBalancers[0].LoadBalancerArn')
    [ -n "$lb_arn" ]
    [[ "$lb_arn" == *":loadbalancer/"* ]]
}

# ── DeleteStack with managed S3 buckets (regression: issue #1539) ──────────────
#
# Real AWS CloudFormation does not report a stack as DELETE_COMPLETE while a
# managed resource still exists. Deleting a stack that owns a NON-EMPTY
# AWS::S3::Bucket must put the stack into DELETE_FAILED (S3 DeleteBucket refuses
# a non-empty bucket) and leave the bucket in place — it must NOT silently report
# success while the bucket and its objects remain.
# An EMPTY managed bucket must be deleted as part of the stack delete.
# https://github.com/floci-io/floci/issues/1539

# Poll describe-stacks until the stack reaches the expected status, or report the
# special value DOES_NOT_EXIST once the stack is gone.
cfn_wait_status() {
    local stack="$1" expected="$2" tries="${3:-40}"
    local i out status
    for ((i = 0; i < tries; i++)); do
        # `|| true`: describe-stacks exits non-zero once the stack is gone, which
        # would otherwise abort this command substitution under bats' errexit.
        out=$(aws_cmd cloudformation describe-stacks --stack-name "$stack" 2>&1 || true)
        if [[ "$out" == *"does not exist"* ]]; then
            status="DOES_NOT_EXIST"
        else
            status=$(echo "$out" | jq -r '.Stacks[0].StackStatus' 2>/dev/null)
        fi
        if [ "$status" = "$expected" ]; then
            return 0
        fi
        sleep 0.25
    done
    echo "timeout waiting for stack '$stack' to reach '$expected' (last status: '$status')" >&2
    return 1
}

@test "CloudFormation: delete-stack with non-empty S3 bucket fails and keeps the bucket (issue #1539)" {
    local bucket="bats-cfn-nonempty-$(unique_name bkt)"
    cat > "$TEMPLATE_FILE" << EOF
{
  "AWSTemplateFormatVersion": "2010-09-09",
  "Resources": {
    "Bucket": {
      "Type": "AWS::S3::Bucket",
      "Properties": { "BucketName": "$bucket" }
    }
  }
}
EOF
    run aws_cmd cloudformation create-stack \
        --stack-name "$STACK_NAME" \
        --template-body "file://$TEMPLATE_FILE"
    assert_success
    cfn_wait_status "$STACK_NAME" "CREATE_COMPLETE"

    # Put an object so the managed bucket is non-empty.
    local body_file
    body_file=$(mktemp)
    echo -n "hello floci 1539" > "$body_file"
    run aws_cmd s3api put-object --bucket "$bucket" --key "object.txt" --body "$body_file"
    assert_success
    rm -f "$body_file"

    run aws_cmd cloudformation delete-stack --stack-name "$STACK_NAME"
    assert_success

    # AWS leaves the stack in DELETE_FAILED — it must NOT report DELETE_COMPLETE
    # (i.e. the stack must NOT silently disappear).
    cfn_wait_status "$STACK_NAME" "DELETE_FAILED"

    # The managed bucket and its object must still exist after the failed delete.
    run aws_cmd s3api head-bucket --bucket "$bucket"
    assert_success

    # Cleanup: empty + remove the leftover bucket, then drop the stack.
    aws_cmd s3 rm "s3://$bucket" --recursive >/dev/null 2>&1 || true
    aws_cmd s3api delete-bucket --bucket "$bucket" >/dev/null 2>&1 || true
}

@test "CloudFormation: delete-stack with empty S3 bucket removes the bucket" {
    local bucket="bats-cfn-empty-$(unique_name bkt)"
    cat > "$TEMPLATE_FILE" << EOF
{
  "AWSTemplateFormatVersion": "2010-09-09",
  "Resources": {
    "Bucket": {
      "Type": "AWS::S3::Bucket",
      "Properties": { "BucketName": "$bucket" }
    }
  }
}
EOF
    run aws_cmd cloudformation create-stack \
        --stack-name "$STACK_NAME" \
        --template-body "file://$TEMPLATE_FILE"
    assert_success
    cfn_wait_status "$STACK_NAME" "CREATE_COMPLETE"

    run aws_cmd s3api head-bucket --bucket "$bucket"
    assert_success

    run aws_cmd cloudformation delete-stack --stack-name "$STACK_NAME"
    assert_success
    cfn_wait_status "$STACK_NAME" "DOES_NOT_EXIST"
    STACK_NAME=""  # prevent teardown from trying again

    # The empty managed bucket must be gone.
    run aws_cmd s3api head-bucket --bucket "$bucket"
    assert_failure
}
