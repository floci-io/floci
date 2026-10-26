"""CloudFormation AWS::EC2::PrefixList creates, updates and deletes the prefix list EC2 serves."""

import json

import pytest
from botocore.exceptions import ClientError

WAITER = {"Delay": 1, "MaxAttempts": 60}


def _template(properties):
    outputs = {name: {"Value": {"Fn::GetAtt": ["Firewall", name]}} for name in ("PrefixListId", "Arn", "OwnerId")}
    outputs["Ref"] = {"Value": {"Ref": "Firewall"}}
    return json.dumps({
        "Resources": {"Firewall": {"Type": "AWS::EC2::PrefixList", "Properties": properties}},
        "Outputs": outputs,
    })


def _prefix_list(ec2_client, prefix_list_id):
    return ec2_client.describe_managed_prefix_lists(PrefixListIds=[prefix_list_id])["PrefixLists"][0]


def test_prefix_list_lifecycle(cloudformation_client, ec2_client, unique_name):
    stack = "prefix-list-" + unique_name
    properties = {
        "PrefixListName": stack,
        "AddressFamily": "IPv4",
        "MaxEntries": 1,
        "Entries": [{"Cidr": "192.0.2.0/24", "Description": "office"}],
        "Tags": [{"Key": "fixture", "Value": unique_name}],
    }
    cloudformation_client.create_stack(StackName=stack, TemplateBody=_template(properties))
    prefix_list_id = None
    try:
        cloudformation_client.get_waiter("stack_create_complete").wait(StackName=stack, WaiterConfig=WAITER)
        outputs = {
            row["OutputKey"]: row["OutputValue"]
            for row in cloudformation_client.describe_stacks(StackName=stack)["Stacks"][0]["Outputs"]
        }
        prefix_list_id = outputs["Ref"]
        created = _prefix_list(ec2_client, prefix_list_id)
        assert outputs["PrefixListId"] == prefix_list_id
        assert outputs["Arn"] == created["PrefixListArn"]
        assert outputs["OwnerId"] == created["OwnerId"]
        assert created["Version"] == 1
        assert {"Key": "fixture", "Value": unique_name} in created["Tags"]
        assert ec2_client.get_managed_prefix_list_entries(PrefixListId=prefix_list_id)["Entries"] == properties["Entries"]

        # Capacity alone changes in place and makes no new version.
        properties["MaxEntries"] = 2
        cloudformation_client.update_stack(StackName=stack, TemplateBody=_template(properties))
        cloudformation_client.get_waiter("stack_update_complete").wait(StackName=stack, WaiterConfig=WAITER)
        resized = _prefix_list(ec2_client, prefix_list_id)
        assert (resized["MaxEntries"], resized["Version"]) == (2, 1)

        # New entries make a new version of the same list; dropping Tags removes them.
        properties["Entries"] = [{"Cidr": "198.51.100.0/24", "Description": "branch"}]
        del properties["Tags"]
        cloudformation_client.update_stack(StackName=stack, TemplateBody=_template(properties))
        cloudformation_client.get_waiter("stack_update_complete").wait(StackName=stack, WaiterConfig=WAITER)
        physical_id = cloudformation_client.describe_stack_resource(
            StackName=stack, LogicalResourceId="Firewall")["StackResourceDetail"]["PhysicalResourceId"]
        assert physical_id == prefix_list_id
        changed = _prefix_list(ec2_client, prefix_list_id)
        assert (changed["MaxEntries"], changed["Version"]) == (2, 2)
        assert all(tag["Key"] != "fixture" for tag in changed.get("Tags", []))
        assert ec2_client.get_managed_prefix_list_entries(PrefixListId=prefix_list_id)["Entries"] == properties["Entries"]
    finally:
        cloudformation_client.delete_stack(StackName=stack)
        cloudformation_client.get_waiter("stack_delete_complete").wait(StackName=stack, WaiterConfig=WAITER)

    with pytest.raises(ClientError) as missing:
        ec2_client.get_managed_prefix_list_entries(PrefixListId=prefix_list_id)
    assert missing.value.response["Error"]["Code"] == "InvalidPrefixListID.NotFound"
