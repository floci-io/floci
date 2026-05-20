"""Neptune cluster and instance integration tests."""

import pytest
from botocore.exceptions import ClientError


class TestNeptuneCluster:
    """Test Neptune DB cluster operations."""

    def test_create_cluster(self, neptune_client, unique_name):
        """Test CreateDBCluster returns a valid cluster descriptor."""
        cluster_id = f"pytest-{unique_name}"

        try:
            response = neptune_client.create_db_cluster(
                DBClusterIdentifier=cluster_id,
                Engine="neptune",
            )
            cluster = response["DBCluster"]
            assert cluster["DBClusterIdentifier"] == cluster_id
            assert cluster["Engine"] == "neptune"
            assert cluster["Status"] == "available"
            assert cluster["DBClusterArn"].startswith("arn:aws:neptune:")
            assert cluster["Port"] > 0
        finally:
            neptune_client.delete_db_cluster(
                DBClusterIdentifier=cluster_id, SkipFinalSnapshot=True
            )

    def test_create_cluster_duplicate_fails(self, neptune_client, unique_name):
        """Test CreateDBCluster fails with DBClusterAlreadyExistsFault on duplicate."""
        cluster_id = f"pytest-{unique_name}"

        neptune_client.create_db_cluster(
            DBClusterIdentifier=cluster_id, Engine="neptune"
        )
        try:
            with pytest.raises(ClientError) as exc_info:
                neptune_client.create_db_cluster(
                    DBClusterIdentifier=cluster_id, Engine="neptune"
                )
            assert exc_info.value.response["Error"]["Code"] == "DBClusterAlreadyExistsFault"
        finally:
            neptune_client.delete_db_cluster(
                DBClusterIdentifier=cluster_id, SkipFinalSnapshot=True
            )

    def test_describe_clusters(self, neptune_client, unique_name):
        """Test DescribeDBClusters lists created cluster."""
        cluster_id = f"pytest-{unique_name}"

        neptune_client.create_db_cluster(
            DBClusterIdentifier=cluster_id, Engine="neptune"
        )
        try:
            response = neptune_client.describe_db_clusters(
                DBClusterIdentifier=cluster_id
            )
            clusters = response["DBClusters"]
            assert len(clusters) == 1
            assert clusters[0]["DBClusterIdentifier"] == cluster_id
        finally:
            neptune_client.delete_db_cluster(
                DBClusterIdentifier=cluster_id, SkipFinalSnapshot=True
            )

    def test_modify_cluster(self, neptune_client, unique_name):
        """Test ModifyDBCluster updates cluster properties."""
        cluster_id = f"pytest-{unique_name}"

        neptune_client.create_db_cluster(
            DBClusterIdentifier=cluster_id, Engine="neptune"
        )
        try:
            response = neptune_client.modify_db_cluster(
                DBClusterIdentifier=cluster_id,
                EnableIAMDatabaseAuthentication=True,
            )
            assert response["DBCluster"]["IAMDatabaseAuthenticationEnabled"] is True
        finally:
            neptune_client.delete_db_cluster(
                DBClusterIdentifier=cluster_id, SkipFinalSnapshot=True
            )

    def test_delete_cluster_with_instances_fails(self, neptune_client, unique_name):
        """Test DeleteDBCluster fails when instances still exist."""
        cluster_id = f"pytest-{unique_name}"
        instance_id = f"pytest-inst-{unique_name}"

        neptune_client.create_db_cluster(
            DBClusterIdentifier=cluster_id, Engine="neptune"
        )
        neptune_client.create_db_instance(
            DBInstanceIdentifier=instance_id,
            DBClusterIdentifier=cluster_id,
            DBInstanceClass="db.r5.large",
            Engine="neptune",
        )
        try:
            with pytest.raises(ClientError) as exc_info:
                neptune_client.delete_db_cluster(
                    DBClusterIdentifier=cluster_id, SkipFinalSnapshot=True
                )
            assert exc_info.value.response["Error"]["Code"] == "InvalidDBClusterStateFault"
        finally:
            neptune_client.delete_db_instance(DBInstanceIdentifier=instance_id)
            neptune_client.delete_db_cluster(
                DBClusterIdentifier=cluster_id, SkipFinalSnapshot=True
            )


class TestNeptuneInstance:
    """Test Neptune DB instance operations."""

    @pytest.fixture
    def neptune_cluster(self, neptune_client, unique_name):
        """Create and tear down a Neptune cluster for instance tests."""
        cluster_id = f"pytest-cluster-{unique_name}"
        neptune_client.create_db_cluster(
            DBClusterIdentifier=cluster_id, Engine="neptune"
        )
        yield cluster_id
        try:
            neptune_client.delete_db_cluster(
                DBClusterIdentifier=cluster_id, SkipFinalSnapshot=True
            )
        except ClientError:
            pass

    def test_create_instance(self, neptune_client, neptune_cluster, unique_name):
        """Test CreateDBInstance returns a valid instance descriptor."""
        instance_id = f"pytest-inst-{unique_name}"

        try:
            response = neptune_client.create_db_instance(
                DBInstanceIdentifier=instance_id,
                DBClusterIdentifier=neptune_cluster,
                DBInstanceClass="db.r5.large",
                Engine="neptune",
            )
            instance = response["DBInstance"]
            assert instance["DBInstanceIdentifier"] == instance_id
            assert instance["DBClusterIdentifier"] == neptune_cluster
            assert instance["DBInstanceStatus"] == "available"
            assert instance["DBInstanceArn"].startswith("arn:aws:neptune:")
        finally:
            neptune_client.delete_db_instance(DBInstanceIdentifier=instance_id)

    def test_describe_instances(self, neptune_client, neptune_cluster, unique_name):
        """Test DescribeDBInstances lists created instance."""
        instance_id = f"pytest-inst-{unique_name}"

        neptune_client.create_db_instance(
            DBInstanceIdentifier=instance_id,
            DBClusterIdentifier=neptune_cluster,
            DBInstanceClass="db.r5.large",
            Engine="neptune",
        )
        try:
            response = neptune_client.describe_db_instances(
                DBInstanceIdentifier=instance_id
            )
            instances = response["DBInstances"]
            assert len(instances) == 1
            assert instances[0]["DBInstanceIdentifier"] == instance_id
        finally:
            neptune_client.delete_db_instance(DBInstanceIdentifier=instance_id)

    def test_modify_instance(self, neptune_client, neptune_cluster, unique_name):
        """Test ModifyDBInstance updates instance class."""
        instance_id = f"pytest-inst-{unique_name}"

        neptune_client.create_db_instance(
            DBInstanceIdentifier=instance_id,
            DBClusterIdentifier=neptune_cluster,
            DBInstanceClass="db.r5.large",
            Engine="neptune",
        )
        try:
            response = neptune_client.modify_db_instance(
                DBInstanceIdentifier=instance_id,
                DBInstanceClass="db.r5.xlarge",
            )
            assert response["DBInstance"]["DBInstanceClass"] == "db.r5.xlarge"
        finally:
            neptune_client.delete_db_instance(DBInstanceIdentifier=instance_id)
