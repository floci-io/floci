"""IAM integration tests."""

import json

import pytest
from botocore.exceptions import ClientError


TRUST_POLICY = json.dumps(
    {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": {"Service": "lambda.amazonaws.com"},
                "Action": "sts:AssumeRole",
            }
        ],
    }
)

POLICY_DOC = json.dumps(
    {
        "Version": "2012-10-17",
        "Statement": [{"Effect": "Allow", "Action": "s3:GetObject", "Resource": "*"}],
    }
)


class TestIAMUser:
    """Test IAM user operations."""

    def test_create_user(self, iam_client, unique_name):
        """Test CreateUser creates a user."""
        user_name = f"pytest-user-{unique_name}"

        try:
            response = iam_client.create_user(UserName=user_name, Path="/")
            assert response["User"]["UserName"] == user_name
            assert response["User"]["Arn"].endswith(user_name)
        finally:
            iam_client.delete_user(UserName=user_name)

    def test_get_user(self, iam_client, unique_name):
        """Test GetUser returns user details."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        try:
            response = iam_client.get_user(UserName=user_name)
            assert response["User"]["UserName"] == user_name
        finally:
            iam_client.delete_user(UserName=user_name)

    def test_list_users(self, iam_client, unique_name):
        """Test ListUsers includes created user."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        try:
            response = iam_client.list_users()
            assert any(u["UserName"] == user_name for u in response["Users"])
        finally:
            iam_client.delete_user(UserName=user_name)

    def test_get_user_not_found(self, iam_client):
        """Test GetUser returns 404 for non-existent user."""
        with pytest.raises(ClientError) as exc_info:
            iam_client.get_user(UserName="nonexistent-user-pytest-xyz")
        assert exc_info.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404


class TestIAMUserTags:
    """Test IAM user tagging operations."""

    def test_tag_user(self, iam_client, unique_name):
        """Test TagUser adds tags to user."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        try:
            iam_client.tag_user(
                UserName=user_name, Tags=[{"Key": "env", "Value": "sdk-test"}]
            )
            # If no exception, test passes
        finally:
            iam_client.delete_user(UserName=user_name)

    def test_list_user_tags(self, iam_client, unique_name):
        """Test ListUserTags returns user tags."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        iam_client.tag_user(
            UserName=user_name, Tags=[{"Key": "env", "Value": "sdk-test"}]
        )
        try:
            response = iam_client.list_user_tags(UserName=user_name)
            assert any(t["Key"] == "env" for t in response["Tags"])
        finally:
            iam_client.delete_user(UserName=user_name)

    def test_untag_user(self, iam_client, unique_name):
        """Test UntagUser removes tags from user."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        iam_client.tag_user(
            UserName=user_name, Tags=[{"Key": "env", "Value": "sdk-test"}]
        )
        try:
            iam_client.untag_user(UserName=user_name, TagKeys=["env"])
            # If no exception, test passes
        finally:
            iam_client.delete_user(UserName=user_name)


class TestIAMAccessKey:
    """Test IAM access key operations."""

    def test_create_access_key(self, iam_client, unique_name):
        """Test CreateAccessKey creates access key."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        try:
            response = iam_client.create_access_key(UserName=user_name)
            key_id = response["AccessKey"]["AccessKeyId"]
            assert key_id.startswith("AKIA")
            assert response["AccessKey"]["Status"] == "Active"

            # Cleanup
            iam_client.delete_access_key(UserName=user_name, AccessKeyId=key_id)
        finally:
            iam_client.delete_user(UserName=user_name)

    def test_list_access_keys(self, iam_client, unique_name):
        """Test ListAccessKeys returns access keys."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        response = iam_client.create_access_key(UserName=user_name)
        key_id = response["AccessKey"]["AccessKeyId"]

        try:
            response = iam_client.list_access_keys(UserName=user_name)
            assert len(response["AccessKeyMetadata"]) > 0
        finally:
            iam_client.delete_access_key(UserName=user_name, AccessKeyId=key_id)
            iam_client.delete_user(UserName=user_name)

    def test_update_access_key(self, iam_client, unique_name):
        """Test UpdateAccessKey changes key status."""
        user_name = f"pytest-user-{unique_name}"

        iam_client.create_user(UserName=user_name)
        response = iam_client.create_access_key(UserName=user_name)
        key_id = response["AccessKey"]["AccessKeyId"]

        try:
            iam_client.update_access_key(
                UserName=user_name, AccessKeyId=key_id, Status="Inactive"
            )
            # If no exception, test passes
        finally:
            iam_client.delete_access_key(UserName=user_name, AccessKeyId=key_id)
            iam_client.delete_user(UserName=user_name)


class TestIAMGroup:
    """Test IAM group operations."""

    def test_create_group(self, iam_client, unique_name):
        """Test CreateGroup creates a group."""
        group_name = f"pytest-group-{unique_name}"

        try:
            response = iam_client.create_group(GroupName=group_name)
            assert response["Group"]["GroupName"] == group_name
        finally:
            iam_client.delete_group(GroupName=group_name)

    def test_add_user_to_group(self, iam_client, unique_name):
        """Test AddUserToGroup adds user to group."""
        user_name = f"pytest-user-{unique_name}"
        group_name = f"pytest-group-{unique_name}"

        iam_client.create_user(UserName=user_name)
        iam_client.create_group(GroupName=group_name)

        try:
            iam_client.add_user_to_group(GroupName=group_name, UserName=user_name)
            # If no exception, test passes
        finally:
            iam_client.remove_user_from_group(GroupName=group_name, UserName=user_name)
            iam_client.delete_group(GroupName=group_name)
            iam_client.delete_user(UserName=user_name)

    def test_get_group(self, iam_client, unique_name):
        """Test GetGroup returns group with users."""
        user_name = f"pytest-user-{unique_name}"
        group_name = f"pytest-group-{unique_name}"

        iam_client.create_user(UserName=user_name)
        iam_client.create_group(GroupName=group_name)
        iam_client.add_user_to_group(GroupName=group_name, UserName=user_name)

        try:
            response = iam_client.get_group(GroupName=group_name)
            assert any(u["UserName"] == user_name for u in response["Users"])
        finally:
            iam_client.remove_user_from_group(GroupName=group_name, UserName=user_name)
            iam_client.delete_group(GroupName=group_name)
            iam_client.delete_user(UserName=user_name)

    def test_list_groups_for_user(self, iam_client, unique_name):
        """Test ListGroupsForUser returns user's groups."""
        user_name = f"pytest-user-{unique_name}"
        group_name = f"pytest-group-{unique_name}"

        iam_client.create_user(UserName=user_name)
        iam_client.create_group(GroupName=group_name)
        iam_client.add_user_to_group(GroupName=group_name, UserName=user_name)

        try:
            response = iam_client.list_groups_for_user(UserName=user_name)
            assert any(g["GroupName"] == group_name for g in response["Groups"])
        finally:
            iam_client.remove_user_from_group(GroupName=group_name, UserName=user_name)
            iam_client.delete_group(GroupName=group_name)
            iam_client.delete_user(UserName=user_name)


class TestIAMRole:
    """Test IAM role operations."""

    def test_create_role(self, iam_client, unique_name):
        """Test CreateRole creates a role."""
        role_name = f"pytest-role-{unique_name}"

        try:
            response = iam_client.create_role(
                RoleName=role_name,
                AssumeRolePolicyDocument=TRUST_POLICY,
                Description="pytest test role",
            )
            assert response["Role"]["RoleName"] == role_name
            assert role_name in response["Role"]["Arn"]
        finally:
            iam_client.delete_role(RoleName=role_name)

    def test_get_role(self, iam_client, unique_name):
        """Test GetRole returns role details."""
        role_name = f"pytest-role-{unique_name}"

        iam_client.create_role(
            RoleName=role_name, AssumeRolePolicyDocument=TRUST_POLICY
        )
        try:
            response = iam_client.get_role(RoleName=role_name)
            assert response["Role"]["RoleName"] == role_name
        finally:
            iam_client.delete_role(RoleName=role_name)

    def test_list_roles(self, iam_client, unique_name):
        """Test ListRoles includes created role."""
        role_name = f"pytest-role-{unique_name}"

        iam_client.create_role(
            RoleName=role_name, AssumeRolePolicyDocument=TRUST_POLICY
        )
        try:
            response = iam_client.list_roles()
            assert any(r["RoleName"] == role_name for r in response["Roles"])
        finally:
            iam_client.delete_role(RoleName=role_name)


class TestIAMPolicy:
    """Test IAM policy operations."""

    def test_create_policy(self, iam_client, unique_name):
        """Test CreatePolicy creates a policy."""
        policy_name = f"pytest-policy-{unique_name}"

        try:
            response = iam_client.create_policy(
                PolicyName=policy_name,
                PolicyDocument=POLICY_DOC,
                Description="pytest test policy",
            )
            policy_arn = response["Policy"]["Arn"]
            assert response["Policy"]["PolicyName"] == policy_name
            assert policy_arn
        finally:
            iam_client.delete_policy(PolicyArn=response["Policy"]["Arn"])

    def test_get_policy(self, iam_client, unique_name):
        """Test GetPolicy returns policy details."""
        policy_name = f"pytest-policy-{unique_name}"

        response = iam_client.create_policy(
            PolicyName=policy_name, PolicyDocument=POLICY_DOC
        )
        policy_arn = response["Policy"]["Arn"]

        try:
            response = iam_client.get_policy(PolicyArn=policy_arn)
            assert response["Policy"]["PolicyName"] == policy_name
        finally:
            iam_client.delete_policy(PolicyArn=policy_arn)

    def test_attach_role_policy(self, iam_client, unique_name):
        """Test AttachRolePolicy attaches policy to role."""
        role_name = f"pytest-role-{unique_name}"
        policy_name = f"pytest-policy-{unique_name}"

        iam_client.create_role(
            RoleName=role_name, AssumeRolePolicyDocument=TRUST_POLICY
        )
        response = iam_client.create_policy(
            PolicyName=policy_name, PolicyDocument=POLICY_DOC
        )
        policy_arn = response["Policy"]["Arn"]

        try:
            iam_client.attach_role_policy(RoleName=role_name, PolicyArn=policy_arn)

            response = iam_client.list_attached_role_policies(RoleName=role_name)
            assert any(
                p["PolicyArn"] == policy_arn for p in response["AttachedPolicies"]
            )
        finally:
            iam_client.detach_role_policy(RoleName=role_name, PolicyArn=policy_arn)
            iam_client.delete_role(RoleName=role_name)
            iam_client.delete_policy(PolicyArn=policy_arn)

    def test_put_role_policy(self, iam_client, unique_name):
        """Test PutRolePolicy adds inline policy to role."""
        role_name = f"pytest-role-{unique_name}"
        inline_policy_name = "inline-exec"

        iam_client.create_role(
            RoleName=role_name, AssumeRolePolicyDocument=TRUST_POLICY
        )

        try:
            iam_client.put_role_policy(
                RoleName=role_name,
                PolicyName=inline_policy_name,
                PolicyDocument='{"Version":"2012-10-17"}',
            )

            response = iam_client.get_role_policy(
                RoleName=role_name, PolicyName=inline_policy_name
            )
            assert response["PolicyName"] == inline_policy_name

            response = iam_client.list_role_policies(RoleName=role_name)
            assert inline_policy_name in response["PolicyNames"]
        finally:
            iam_client.delete_role_policy(
                RoleName=role_name, PolicyName=inline_policy_name
            )
            iam_client.delete_role(RoleName=role_name)


class TestIAMUserPolicy:
    """Test IAM user policy operations."""

    def test_attach_user_policy(self, iam_client, unique_name):
        """Test AttachUserPolicy attaches managed policy to user."""
        user_name = f"pytest-user-{unique_name}"
        policy_name = f"pytest-policy-{unique_name}"

        iam_client.create_user(UserName=user_name)
        response = iam_client.create_policy(
            PolicyName=policy_name, PolicyDocument=POLICY_DOC
        )
        policy_arn = response["Policy"]["Arn"]

        try:
            iam_client.attach_user_policy(UserName=user_name, PolicyArn=policy_arn)
            # If no exception, attachment succeeded
        finally:
            iam_client.detach_user_policy(UserName=user_name, PolicyArn=policy_arn)
            iam_client.delete_user(UserName=user_name)
            iam_client.delete_policy(PolicyArn=policy_arn)

    def test_list_attached_user_policies(self, iam_client, unique_name):
        """Test ListAttachedUserPolicies returns attached managed policies."""
        user_name = f"pytest-user-{unique_name}"
        policy_name = f"pytest-policy-{unique_name}"

        iam_client.create_user(UserName=user_name)
        response = iam_client.create_policy(
            PolicyName=policy_name, PolicyDocument=POLICY_DOC
        )
        policy_arn = response["Policy"]["Arn"]
        iam_client.attach_user_policy(UserName=user_name, PolicyArn=policy_arn)

        try:
            response = iam_client.list_attached_user_policies(UserName=user_name)
            assert any(
                p["PolicyArn"] == policy_arn for p in response["AttachedPolicies"]
            )
        finally:
            iam_client.detach_user_policy(UserName=user_name, PolicyArn=policy_arn)
            iam_client.delete_user(UserName=user_name)
            iam_client.delete_policy(PolicyArn=policy_arn)
