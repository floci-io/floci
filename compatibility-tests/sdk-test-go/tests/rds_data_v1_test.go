package tests

import (
	"context"
	"strconv"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	awsV2 "github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/rds"
	"github.com/aws/aws-sdk-go-v2/service/rds/types"
	awsV1 "github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/awserr"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/rdsdataservice"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRdsDataApiGoSdkV1(t *testing.T) {
	ctx := context.Background()
	rdsSvc := testutil.RDSClient()
	dataSvc := rdsDataV1Client(t)

	clusterID := "go-rds-data-mysql-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	database := "app"
	username := "admin"
	password := "secret123"
	secretArn := "arn:aws:secretsmanager:us-east-1:000000000000:secret:local/rds-data"
	var resourceArn string

	t.Cleanup(func() {
		if resourceArn != "" {
			_, _ = rdsSvc.DeleteDBCluster(ctx, &rds.DeleteDBClusterInput{
				DBClusterIdentifier: awsV2.String(clusterID),
				SkipFinalSnapshot:   awsV2.Bool(true),
			})
		}
	})

	create, err := rdsSvc.CreateDBCluster(ctx, &rds.CreateDBClusterInput{
		DBClusterIdentifier: awsV2.String(clusterID),
		Engine:              awsV2.String("aurora-mysql"),
		EngineVersion:       awsV2.String("8.0.mysql_aurora.3.08.0"),
		MasterUsername:      awsV2.String(username),
		MasterUserPassword:  awsV2.String(password),
		DatabaseName:        awsV2.String(database),
		EngineMode:          awsV2.String("provisioned"),
		Tags:                []types.Tag{{Key: awsV2.String("test"), Value: awsV2.String("rds-data")}},
	})
	if err != nil {
		t.Skipf("RDS MySQL cluster unavailable in this environment: %v", err)
	}
	require.NotNil(t, create.DBCluster)
	resourceArn = awsV2.ToString(create.DBCluster.DBClusterArn)
	require.NotEmpty(t, resourceArn)

	executeEventually(t, dataSvc, &rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql: awsV1.String(`create table if not exists data_api_items (
			id varchar(64) primary key,
			title varchar(255),
			score bigint,
			payload blob null,
			observed_at datetime(6) null,
			stamped_at timestamp(6) null,
			due_date date null,
			due_time time null
		)`),
	})

	tx, err := dataSvc.BeginTransaction(&rdsdataservice.BeginTransactionInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
	})
	require.NoError(t, err)
	require.NotEmpty(t, awsV1.StringValue(tx.TransactionId))

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: tx.TransactionId,
		Sql: awsV1.String(`insert into data_api_items
			(id, title, score, payload, observed_at, stamped_at, due_date, due_time)
			values (
				'commit-1',
				'first item',
				7,
				UNHEX('010203'),
				'2021-03-04 05:06:07.891000',
				'2021-03-04 05:06:07.891000',
				'2021-03-04',
				'05:06:07'
			)`),
	})
	require.NoError(t, err)

	_, err = dataSvc.CommitTransaction(&rdsdataservice.CommitTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: tx.TransactionId,
	})
	require.NoError(t, err)

	selectOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select title, score, payload, null as nothing, observed_at, stamped_at, due_date, due_time from data_api_items where id = 'commit-1'"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	require.Len(t, selectOut.ColumnMetadata, 8)
	assert.Equal(t, "title", awsV1.StringValue(selectOut.ColumnMetadata[0].Name))
	require.Len(t, selectOut.Records, 1)
	require.Len(t, selectOut.Records[0], 8)
	assert.Equal(t, "first item", awsV1.StringValue(selectOut.Records[0][0].StringValue))
	assert.Equal(t, int64(7), awsV1.Int64Value(selectOut.Records[0][1].LongValue))
	assert.Equal(t, []byte{1, 2, 3}, selectOut.Records[0][2].BlobValue)
	assert.True(t, awsV1.BoolValue(selectOut.Records[0][3].IsNull))
	assert.Equal(t, "2021-03-04 05:06:07.891", awsV1.StringValue(selectOut.Records[0][4].StringValue))
	assert.Equal(t, "2021-03-04 05:06:07.891", awsV1.StringValue(selectOut.Records[0][5].StringValue))
	assert.Equal(t, "2021-03-04", awsV1.StringValue(selectOut.Records[0][6].StringValue))
	assert.Equal(t, "05:06:07", awsV1.StringValue(selectOut.Records[0][7].StringValue))

	rollbackTx, err := dataSvc.BeginTransaction(&rdsdataservice.BeginTransactionInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
	})
	require.NoError(t, err)
	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV1.String("insert into data_api_items (id, title, score) values ('rollback-1', 'rolled back', 9)"),
	})
	require.NoError(t, err)
	_, err = dataSvc.RollbackTransaction(&rdsdataservice.RollbackTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: rollbackTx.TransactionId,
	})
	require.NoError(t, err)

	countOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select count(*) as count from data_api_items where id in ('commit-1', 'rollback-1')"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	assert.Equal(t, "count", awsV1.StringValue(countOut.ColumnMetadata[0].Name))
	assert.Equal(t, int64(1), awsV1.Int64Value(countOut.Records[0][0].LongValue))

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score) values ('commit-1', 'duplicate', 1)"),
	})
	require.Error(t, err)
	var awsErr awserr.Error
	require.ErrorAs(t, err, &awsErr)
	assert.Equal(t, "DatabaseErrorException", awsErr.Code())
}

func rdsDataV1Client(t *testing.T) *rdsdataservice.RDSDataService {
	t.Helper()
	sess, err := session.NewSession(&awsV1.Config{
		Region:      awsV1.String("us-east-1"),
		Endpoint:    awsV1.String(testutil.Endpoint()),
		DisableSSL:  awsV1.Bool(true),
		Credentials: credentials.NewStaticCredentials("test", "test", ""),
	})
	require.NoError(t, err)
	return rdsdataservice.New(sess)
}

func executeEventually(t *testing.T, svc *rdsdataservice.RDSDataService, input *rdsdataservice.ExecuteStatementInput) {
	t.Helper()
	require.Eventually(t, func() bool {
		_, err := svc.ExecuteStatement(input)
		return err == nil
	}, 90*time.Second, time.Second)
}
