package tests

import (
	"context"
	"fmt"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/neptune"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNeptuneCluster(t *testing.T) {
	ctx := context.Background()
	svc := testutil.NeptuneClient()
	clusterId := fmt.Sprintf("go-neptune-%d", time.Now().UnixMilli()%100000)

	t.Cleanup(func() {
		svc.DeleteDBCluster(ctx, &neptune.DeleteDBClusterInput{ //nolint:errcheck
			DBClusterIdentifier: aws.String(clusterId),
			SkipFinalSnapshot:   aws.Bool(true),
		})
	})

	t.Run("CreateDBCluster", func(t *testing.T) {
		out, err := svc.CreateDBCluster(ctx, &neptune.CreateDBClusterInput{
			DBClusterIdentifier: aws.String(clusterId),
			Engine:              aws.String("neptune"),
		})
		require.NoError(t, err)
		require.NotNil(t, out.DBCluster)
		assert.Equal(t, clusterId, aws.ToString(out.DBCluster.DBClusterIdentifier))
		assert.Equal(t, "neptune", aws.ToString(out.DBCluster.Engine))
		assert.Equal(t, "available", aws.ToString(out.DBCluster.Status))
		assert.Contains(t, aws.ToString(out.DBCluster.DBClusterArn), "arn:aws:neptune:")
		assert.Greater(t, aws.ToInt32(out.DBCluster.Port), int32(0))
	})

	t.Run("DescribeDBClusters", func(t *testing.T) {
		out, err := svc.DescribeDBClusters(ctx, &neptune.DescribeDBClustersInput{
			DBClusterIdentifier: aws.String(clusterId),
		})
		require.NoError(t, err)
		require.Len(t, out.DBClusters, 1)
		assert.Equal(t, clusterId, aws.ToString(out.DBClusters[0].DBClusterIdentifier))
	})

	t.Run("ModifyDBCluster", func(t *testing.T) {
		out, err := svc.ModifyDBCluster(ctx, &neptune.ModifyDBClusterInput{
			DBClusterIdentifier:             aws.String(clusterId),
			EnableIAMDatabaseAuthentication: aws.Bool(true),
		})
		require.NoError(t, err)
		assert.True(t, aws.ToBool(out.DBCluster.IAMDatabaseAuthenticationEnabled))
	})

	t.Run("DeleteDBCluster", func(t *testing.T) {
		_, err := svc.DeleteDBCluster(ctx, &neptune.DeleteDBClusterInput{
			DBClusterIdentifier: aws.String(clusterId),
			SkipFinalSnapshot:   aws.Bool(true),
		})
		require.NoError(t, err)
	})
}

func TestNeptuneInstance(t *testing.T) {
	ctx := context.Background()
	svc := testutil.NeptuneClient()
	clusterId := fmt.Sprintf("go-neptune-cl-%d", time.Now().UnixMilli()%100000)
	instanceId := fmt.Sprintf("go-neptune-inst-%d", time.Now().UnixMilli()%100000)

	// Create cluster first
	_, err := svc.CreateDBCluster(ctx, &neptune.CreateDBClusterInput{
		DBClusterIdentifier: aws.String(clusterId),
		Engine:              aws.String("neptune"),
	})
	require.NoError(t, err, "setup: create Neptune cluster")

	t.Cleanup(func() {
		svc.DeleteDBInstance(ctx, &neptune.DeleteDBInstanceInput{ //nolint:errcheck
			DBInstanceIdentifier: aws.String(instanceId),
		})
		svc.DeleteDBCluster(ctx, &neptune.DeleteDBClusterInput{ //nolint:errcheck
			DBClusterIdentifier: aws.String(clusterId),
			SkipFinalSnapshot:   aws.Bool(true),
		})
	})

	t.Run("CreateDBInstance", func(t *testing.T) {
		out, err := svc.CreateDBInstance(ctx, &neptune.CreateDBInstanceInput{
			DBInstanceIdentifier: aws.String(instanceId),
			DBClusterIdentifier:  aws.String(clusterId),
			DBInstanceClass:      aws.String("db.r5.large"),
			Engine:               aws.String("neptune"),
		})
		require.NoError(t, err)
		require.NotNil(t, out.DBInstance)
		assert.Equal(t, instanceId, aws.ToString(out.DBInstance.DBInstanceIdentifier))
		assert.Equal(t, clusterId, aws.ToString(out.DBInstance.DBClusterIdentifier))
		assert.Equal(t, "available", aws.ToString(out.DBInstance.DBInstanceStatus))
	})

	t.Run("DescribeDBInstances", func(t *testing.T) {
		out, err := svc.DescribeDBInstances(ctx, &neptune.DescribeDBInstancesInput{
			DBInstanceIdentifier: aws.String(instanceId),
		})
		require.NoError(t, err)
		require.Len(t, out.DBInstances, 1)
		assert.Equal(t, instanceId, aws.ToString(out.DBInstances[0].DBInstanceIdentifier))
	})

	t.Run("ModifyDBInstance", func(t *testing.T) {
		out, err := svc.ModifyDBInstance(ctx, &neptune.ModifyDBInstanceInput{
			DBInstanceIdentifier: aws.String(instanceId),
			DBInstanceClass:      aws.String("db.r5.xlarge"),
		})
		require.NoError(t, err)
		assert.Equal(t, "db.r5.xlarge", aws.ToString(out.DBInstance.DBInstanceClass))
	})

	t.Run("DeleteDBInstance", func(t *testing.T) {
		_, err := svc.DeleteDBInstance(ctx, &neptune.DeleteDBInstanceInput{
			DBInstanceIdentifier: aws.String(instanceId),
		})
		require.NoError(t, err)
	})
}
