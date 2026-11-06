package tests

import (
	"context"
	"database/sql"
	"fmt"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/rds"
	"github.com/aws/aws-sdk-go-v2/service/rds/types"
	_ "github.com/lib/pq"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	rdsUsername = "admin"
	rdsPassword = "secret123"
	rdsDatabase = "app"
)

// TestRDSInstance covers management-plane CRUD for a DB instance (fix #567 — delete
// cleans up properly) and direct proxy connectivity (fix #503 — non-master pass-through).
func TestRDSInstance(t *testing.T) {
	ctx := context.Background()
	svc := testutil.RDSClient()
	instanceId := fmt.Sprintf("go-rds-%d", time.Now().UnixMilli()%100000)

	var proxyPort int32

	t.Cleanup(func() {
		svc.DeleteDBInstance(ctx, &rds.DeleteDBInstanceInput{ //nolint:errcheck
			DBInstanceIdentifier: aws.String(instanceId),
			SkipFinalSnapshot:    aws.Bool(true),
		})
	})

	t.Run("CreateDBInstance", func(t *testing.T) {
		out, err := svc.CreateDBInstance(ctx, &rds.CreateDBInstanceInput{
			DBInstanceIdentifier:            aws.String(instanceId),
			DBInstanceClass:                 aws.String("db.t3.micro"),
			Engine:                          aws.String("postgres"),
			MasterUsername:                  aws.String(rdsUsername),
			MasterUserPassword:              aws.String(rdsPassword),
			DBName:                          aws.String(rdsDatabase),
			AllocatedStorage:                aws.Int32(20),
			EnableIAMDatabaseAuthentication: aws.Bool(false),
		})
		require.NoError(t, err)
		require.NotNil(t, out.DBInstance)
		assert.Equal(t, instanceId, aws.ToString(out.DBInstance.DBInstanceIdentifier))
		assert.Equal(t, "postgres", aws.ToString(out.DBInstance.Engine))

		proxyPort = aws.ToInt32(out.DBInstance.Endpoint.Port)
		assert.Greater(t, proxyPort, int32(0))
	})

	t.Run("DescribeDBInstance", func(t *testing.T) {
		out, err := svc.DescribeDBInstances(ctx, &rds.DescribeDBInstancesInput{
			DBInstanceIdentifier: aws.String(instanceId),
		})
		require.NoError(t, err)
		require.Len(t, out.DBInstances, 1)

		inst := out.DBInstances[0]
		assert.Equal(t, instanceId, aws.ToString(inst.DBInstanceIdentifier))
		assert.Equal(t, "postgres", aws.ToString(inst.Engine))
		assert.Equal(t, rdsUsername, aws.ToString(inst.MasterUsername))
	})

	// Connect master user via the RDS proxy using plain password.
	t.Run("ConnectMasterUser", func(t *testing.T) {
		if proxyPort == 0 {
			t.Skip("proxy port not set — CreateDBInstance step skipped")
		}
		conn := awaitPostgresConn(t, testutil.ProxyHost(), int(proxyPort), rdsUsername, rdsPassword, rdsDatabase)
		defer conn.Close()

		var result int
		require.NoError(t, conn.QueryRowContext(ctx, "SELECT 1").Scan(&result))
		assert.Equal(t, 1, result)
	})

	// Fix #503: non-master users must pass through to the backend without
	// the proxy intercepting or rejecting their credentials.
	t.Run("ConnectNonMasterUser", func(t *testing.T) {
		if proxyPort == 0 {
			t.Skip("proxy port not set — CreateDBInstance step skipped")
		}
		masterConn := awaitPostgresConn(t, testutil.ProxyHost(), int(proxyPort), rdsUsername, rdsPassword, rdsDatabase)
		defer masterConn.Close()

		_, err := masterConn.ExecContext(ctx,
			"CREATE USER rds_testuser WITH PASSWORD 'testpass' LOGIN")
		require.NoError(t, err)

		userConn, err := openPostgresConn(testutil.ProxyHost(), int(proxyPort), "rds_testuser", "testpass", rdsDatabase)
		require.NoError(t, err, "non-master user should connect via proxy pass-through")
		defer userConn.Close()

		var result int
		require.NoError(t, userConn.QueryRowContext(ctx, "SELECT 1").Scan(&result))
		assert.Equal(t, 1, result)
	})

	// Fix #567: deleting an instance must remove it from the describe list. Real AWS
	// faults DBInstanceNotFound when describing a deleted instance by identifier —
	// this is what lets the DBInstanceDeleted waiter complete.
	t.Run("DeleteDBInstance", func(t *testing.T) {
		_, err := svc.DeleteDBInstance(ctx, &rds.DeleteDBInstanceInput{
			DBInstanceIdentifier: aws.String(instanceId),
			SkipFinalSnapshot:    aws.Bool(true),
		})
		require.NoError(t, err)

		_, err = svc.DescribeDBInstances(ctx, &rds.DescribeDBInstancesInput{
			DBInstanceIdentifier: aws.String(instanceId),
		})
		var notFound *types.DBInstanceNotFoundFault
		require.ErrorAs(t, err, &notFound,
			"describe of a deleted instance must fault DBInstanceNotFound")
	})
}

// TestRDSCluster covers management-plane CRUD for a DB cluster and validates
// that DBSubnetGroup is returned as a plain string field (fix #548).
func TestRDSCluster(t *testing.T) {
	ctx := context.Background()
	svc := testutil.RDSClient()
	clusterId := fmt.Sprintf("go-cluster-%d", time.Now().UnixMilli()%100000)

	t.Cleanup(func() {
		svc.DeleteDBCluster(ctx, &rds.DeleteDBClusterInput{ //nolint:errcheck
			DBClusterIdentifier: aws.String(clusterId),
			SkipFinalSnapshot:   aws.Bool(true),
		})
	})

	t.Run("CreateDBCluster", func(t *testing.T) {
		out, err := svc.CreateDBCluster(ctx, &rds.CreateDBClusterInput{
			DBClusterIdentifier: aws.String(clusterId),
			Engine:              aws.String("aurora-postgresql"),
			MasterUsername:      aws.String(rdsUsername),
			MasterUserPassword:  aws.String(rdsPassword),
			DatabaseName:        aws.String(rdsDatabase),
		})
		require.NoError(t, err)
		require.NotNil(t, out.DBCluster)
		assert.Equal(t, clusterId, aws.ToString(out.DBCluster.DBClusterIdentifier))
		assert.Equal(t, rdsUsername, aws.ToString(out.DBCluster.MasterUsername))
	})

	// Fix #548: DBCluster.DBSubnetGroup must unmarshal as a plain *string, not a nested
	// struct. The AWS service model defines it as shape: String. If the XML had nested
	// elements (DBSubnetGroupName, etc.), the Go SDK would return nil for this field.
	t.Run("DescribeDBCluster_DBSubnetGroupIsString", func(t *testing.T) {
		out, err := svc.DescribeDBClusters(ctx, &rds.DescribeDBClustersInput{
			DBClusterIdentifier: aws.String(clusterId),
		})
		require.NoError(t, err)
		require.Len(t, out.DBClusters, 1)

		cluster := out.DBClusters[0]
		assert.Equal(t, clusterId, aws.ToString(cluster.DBClusterIdentifier))
		assert.Equal(t, rdsUsername, aws.ToString(cluster.MasterUsername))
		assert.NotNil(t, cluster.DBSubnetGroup,
			"DBCluster.DBSubnetGroup must be a non-nil string (shape: String in AWS service model)")
	})

	// Fix #567: deleting a cluster must remove it from the describe list. Real AWS
	// faults DBClusterNotFoundFault when describing a deleted cluster by identifier —
	// this is what lets the DBClusterDeleted waiter complete.
	t.Run("DeleteDBCluster", func(t *testing.T) {
		_, err := svc.DeleteDBCluster(ctx, &rds.DeleteDBClusterInput{
			DBClusterIdentifier: aws.String(clusterId),
			SkipFinalSnapshot:   aws.Bool(true),
		})
		require.NoError(t, err)

		_, err = svc.DescribeDBClusters(ctx, &rds.DescribeDBClustersInput{
			DBClusterIdentifier: aws.String(clusterId),
		})
		var notFound *types.DBClusterNotFoundFault
		require.ErrorAs(t, err, &notFound,
			"describe of a deleted cluster must fault DBClusterNotFoundFault")
	})
}

// ── Helpers ──────────────────────────────────────────────────────────────────

func openPostgresConn(host string, port int, user, password, dbname string) (*sql.DB, error) {
	dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=disable connect_timeout=5",
		host, port, user, password, dbname)
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func awaitPostgresConn(t *testing.T, host string, port int, user, password, dbname string) *sql.DB {
	t.Helper()
	deadline := time.Now().Add(60 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		db, err := openPostgresConn(host, port, user, password, dbname)
		if err == nil {
			return db
		}
		lastErr = err
		time.Sleep(time.Second)
	}
	t.Fatalf("timed out waiting for postgres connection on %s:%d: %v", host, port, lastErr)
	return nil
}
