"""ECR control-plane compatibility tests.

Test-first: this file is committed before the server-side ECR implementation
lands. With ECR unimplemented, every test below should fail.
"""

import base64
import logging
import re

import pytest
from botocore.exceptions import ClientError

logger = logging.getLogger(__name__)


REPO_NAME = "floci-it/app-py"


@pytest.fixture
def repo(ecr_client):
    """Create a repository for the test and clean it up afterwards."""
    try:
        ecr_client.create_repository(repositoryName=REPO_NAME)
    except ClientError as e:
        if e.response["Error"]["Code"] != "RepositoryAlreadyExistsException":
            raise
    yield REPO_NAME
    try:
        ecr_client.delete_repository(repositoryName=REPO_NAME, force=True)
    except ClientError as e:
        logger.warning("Failed to clean up ECR repository %s: %s", REPO_NAME, e)


class TestECRRepositoryLifecycle:
    def test_create_repository_returns_loopback_uri(self, ecr_client):
        try:
            resp = ecr_client.create_repository(repositoryName=REPO_NAME)
            repo = resp["repository"]
            assert repo["repositoryName"] == REPO_NAME
            assert re.match(r"arn:aws:ecr:.*:.*:repository/" + REPO_NAME, repo["repositoryArn"])
            assert REPO_NAME in repo["repositoryUri"]
            assert "localhost:" in repo["repositoryUri"]
        finally:
            try:
                ecr_client.delete_repository(repositoryName=REPO_NAME, force=True)
            except ClientError as e:
                logger.warning("Failed to clean up ECR repository %s: %s", REPO_NAME, e)

    def test_create_duplicate_raises(self, ecr_client, repo):
        with pytest.raises(ClientError) as exc:
            ecr_client.create_repository(repositoryName=repo)
        assert exc.value.response["Error"]["Code"] == "RepositoryAlreadyExistsException"

    def test_describe_repositories(self, ecr_client, repo):
        resp = ecr_client.describe_repositories(repositoryNames=[repo])
        assert any(r["repositoryName"] == repo for r in resp["repositories"])

    def test_describe_missing_raises(self, ecr_client):
        with pytest.raises(ClientError) as exc:
            ecr_client.describe_repositories(repositoryNames=["does-not-exist-py"])
        assert exc.value.response["Error"]["Code"] == "RepositoryNotFoundException"

    def test_delete_force(self, ecr_client):
        ecr_client.create_repository(repositoryName="floci-it/del-py")
        ecr_client.delete_repository(repositoryName="floci-it/del-py", force=True)
        with pytest.raises(ClientError) as exc:
            ecr_client.describe_repositories(repositoryNames=["floci-it/del-py"])
        assert exc.value.response["Error"]["Code"] == "RepositoryNotFoundException"


class TestECRAuthorization:
    def test_get_authorization_token(self, ecr_client):
        resp = ecr_client.get_authorization_token()
        assert resp["authorizationData"]
        data = resp["authorizationData"][0]
        assert data["authorizationToken"]
        assert data["proxyEndpoint"].startswith("http")
        assert "expiresAt" in data
        decoded = base64.b64decode(data["authorizationToken"]).decode("utf-8")
        assert decoded.startswith("AWS:")


class TestECRImageOperations:
    def test_list_images_empty(self, ecr_client, repo):
        resp = ecr_client.list_images(repositoryName=repo)
        assert resp["imageIds"] == []


class TestECRPolicies:
    def test_lifecycle_policy_round_trip(self, ecr_client, repo):
        policy = '{"rules":[{"rulePriority":1,"selection":{"tagStatus":"untagged","countType":"imageCountMoreThan","countNumber":5},"action":{"type":"expire"}}]}'
        ecr_client.put_lifecycle_policy(repositoryName=repo, lifecyclePolicyText=policy)
        resp = ecr_client.get_lifecycle_policy(repositoryName=repo)
        assert resp["lifecyclePolicyText"] == policy

    def test_repository_policy_round_trip(self, ecr_client, repo):
        policy = '{"Version":"2012-10-17","Statement":[{"Sid":"AllowAll","Effect":"Allow","Principal":"*","Action":"ecr:*"}]}'
        ecr_client.set_repository_policy(repositoryName=repo, policyText=policy)
        resp = ecr_client.get_repository_policy(repositoryName=repo)
        assert resp["policyText"] == policy

    def test_image_tag_mutability_round_trip(self, ecr_client, repo):
        resp = ecr_client.put_image_tag_mutability(
            repositoryName=repo, imageTagMutability="IMMUTABLE"
        )
        assert resp["imageTagMutability"] == "IMMUTABLE"
        desc = ecr_client.describe_repositories(repositoryNames=[repo])
        assert desc["repositories"][0]["imageTagMutability"] == "IMMUTABLE"
