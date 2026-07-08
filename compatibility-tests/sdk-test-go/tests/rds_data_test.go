package tests

import (
	"context"
	"fmt"
	"strconv"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	awsV2 "github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/rds"
	"github.com/aws/aws-sdk-go-v2/service/rds/types"
	"github.com/aws/aws-sdk-go-v2/service/rdsdata"
	rdsdatatypes "github.com/aws/aws-sdk-go-v2/service/rdsdata/types"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
	awsV1 "github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/awserr"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/rdsdataservice"
	"github.com/aws/smithy-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRdsDataApiGoSdkV1(t *testing.T) {
	ctx := context.Background()
	rdsSvc := testutil.RDSClient()
	secretsSvc := testutil.SecretsManagerClient()
	dataSvc := rdsDataV1Client(t)

	clusterID := "go-rds-data-mysql-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	database := "app"
	username := "admin"
	password := "secret123"
	secretName := "go-rds-data/" + clusterID
	var secretArn string
	var resourceArn string

	t.Cleanup(func() {
		if secretArn != "" {
			_, _ = secretsSvc.DeleteSecret(ctx, &secretsmanager.DeleteSecretInput{
				SecretId:                   awsV2.String(secretArn),
				ForceDeleteWithoutRecovery: awsV2.Bool(true),
			})
		}
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

	secret, err := secretsSvc.CreateSecret(ctx, &secretsmanager.CreateSecretInput{
		Name:         awsV2.String(secretName),
		SecretString: awsV2.String(fmt.Sprintf(`{"username":%q,"password":%q}`, username, password)),
	})
	require.NoError(t, err)
	secretArn = awsV2.ToString(secret.ARN)
	require.NotEmpty(t, secretArn)

	executeEventually(t, dataSvc, &rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql: awsV1.String(`create table if not exists data_api_items (
			id varchar(64) primary key,
			title varchar(255),
			score bigint,
			payload blob null,
			active boolean null,
			ratio double null,
			json_text text null,
			rfc3339_text varchar(64) null,
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

	insertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: tx.TransactionId,
		Sql: awsV1.String(`insert into data_api_items
			(id, title, score, payload, active, ratio, json_text, rfc3339_text, observed_at, stamped_at, due_date, due_time)
			values (
				'commit-1',
				'first item',
				7,
				UNHEX('010203'),
				true,
				1.5,
				'{"ok":true}',
				'2021-03-04T05:06:07Z',
				'2021-03-04 05:06:07.891000',
				'2021-03-04 05:06:07.891000',
				'2021-03-04',
				'05:06:07'
			)`),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(insertOut.NumberOfRecordsUpdated))

	inTxRead, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV1.String("select count(*) as count from data_api_items where id = 'commit-1'"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(inTxRead.Records[0][0].LongValue))

	_, err = dataSvc.CommitTransaction(&rdsdataservice.CommitTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: tx.TransactionId,
	})
	require.NoError(t, err)

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV1.String("select 1"),
	})
	assertAwsErrorCode(t, err, "TransactionNotFoundException")

	selectOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select title, score, payload, null as nothing, observed_at, stamped_at, due_date, due_time, active, ratio, json_text, rfc3339_text from data_api_items where id = 'commit-1'"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	require.Len(t, selectOut.ColumnMetadata, 12)
	assert.Equal(t, "title", awsV1.StringValue(selectOut.ColumnMetadata[0].Name))
	require.Len(t, selectOut.Records, 1)
	require.Len(t, selectOut.Records[0], 12)
	assert.Equal(t, "first item", awsV1.StringValue(selectOut.Records[0][0].StringValue))
	assert.Equal(t, int64(7), awsV1.Int64Value(selectOut.Records[0][1].LongValue))
	assert.Equal(t, []byte{1, 2, 3}, selectOut.Records[0][2].BlobValue)
	assert.True(t, awsV1.BoolValue(selectOut.Records[0][3].IsNull))
	assert.Equal(t, "2021-03-04 05:06:07.891", awsV1.StringValue(selectOut.Records[0][4].StringValue))
	assert.Equal(t, "2021-03-04 05:06:07.891", awsV1.StringValue(selectOut.Records[0][5].StringValue))
	assert.Equal(t, "2021-03-04", awsV1.StringValue(selectOut.Records[0][6].StringValue))
	assert.Equal(t, "05:06:07", awsV1.StringValue(selectOut.Records[0][7].StringValue))
	assert.True(t, awsV1.BoolValue(selectOut.Records[0][8].BooleanValue))
	assert.Equal(t, 1.5, awsV1.Float64Value(selectOut.Records[0][9].DoubleValue))
	assert.Equal(t, `{"ok":true}`, awsV1.StringValue(selectOut.Records[0][10].StringValue))
	assert.Equal(t, "2021-03-04T05:06:07Z", awsV1.StringValue(selectOut.Records[0][11].StringValue))

	updateOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("update data_api_items set score = 8 where id = 'commit-1' and score = 7"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(updateOut.NumberOfRecordsUpdated))

	staleUpdateOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("update data_api_items set score = 9 where id = 'commit-1' and score = 7"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(0), awsV1.Int64Value(staleUpdateOut.NumberOfRecordsUpdated))

	upsertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score, payload) values ('commit-1', 'upserted', 11, UNHEX('0A0B')) on duplicate key update title = values(title), score = values(score), payload = values(payload)"),
	})
	require.NoError(t, err)
	assert.GreaterOrEqual(t, awsV1.Int64Value(upsertOut.NumberOfRecordsUpdated), int64(1))

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score) values ('page-1', 'alpha', 1), ('page-2', 'beta', 2), ('page-3', 'alphabet', 3)"),
	})
	require.NoError(t, err)

	predicateOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select id from data_api_items where (title like 'alpha%' or id in (select id from data_api_items where score = 11)) order by score desc limit 2 offset 0"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	require.Len(t, predicateOut.Records, 2)
	assert.Equal(t, "commit-1", awsV1.StringValue(predicateOut.Records[0][0].StringValue))
	assert.Equal(t, "page-3", awsV1.StringValue(predicateOut.Records[1][0].StringValue))

	lessThanAndNotInOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select count(*) as count from data_api_items where score < 3 and id not in ('commit-1')"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	assert.Equal(t, "count", awsV1.StringValue(lessThanAndNotInOut.ColumnMetadata[0].Name))
	assert.Equal(t, int64(2), awsV1.Int64Value(lessThanAndNotInOut.Records[0][0].LongValue))

	emptyOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select id from data_api_items where id = 'missing'"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	assert.Equal(t, "id", awsV1.StringValue(emptyOut.ColumnMetadata[0].Name))
	assert.Empty(t, emptyOut.Records)
	assert.Equal(t, int64(0), awsV1.Int64Value(emptyOut.NumberOfRecordsUpdated))

	replaceTx, err := dataSvc.BeginTransaction(&rdsdataservice.BeginTransactionInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
	})
	require.NoError(t, err)
	deleteOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: replaceTx.TransactionId,
		Sql:           awsV1.String("delete from data_api_items where id = 'page-2'"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(deleteOut.NumberOfRecordsUpdated))
	replaceInsertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: replaceTx.TransactionId,
		Sql:           awsV1.String("insert into data_api_items (id, title, score) values ('page-4', 'replacement', 4)"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(replaceInsertOut.NumberOfRecordsUpdated))
	_, err = dataSvc.CommitTransaction(&rdsdataservice.CommitTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: replaceTx.TransactionId,
	})
	require.NoError(t, err)
	replaceCountOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("select count(*) as count from data_api_items where id in ('page-2', 'page-4')"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(replaceCountOut.Records[0][0].LongValue))

	rollbackTx, err := dataSvc.BeginTransaction(&rdsdataservice.BeginTransactionInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
	})
	require.NoError(t, err)
	rollbackInsertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV1.String("insert into data_api_items (id, title, score) values ('rollback-1', 'rolled back', 9)"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(rollbackInsertOut.NumberOfRecordsUpdated))
	_, err = dataSvc.RollbackTransaction(&rdsdataservice.RollbackTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: rollbackTx.TransactionId,
	})
	require.NoError(t, err)

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV1.String("select 1"),
	})
	assertAwsErrorCode(t, err, "TransactionNotFoundException")

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

	_, err = dataSvc.BatchExecuteStatement(&rdsdataservice.BatchExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score) values ('batch-1', 'batch', 1)"),
	})
	require.Error(t, err)
	assertAwsErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteSql(&rdsdataservice.ExecuteSqlInput{
		DbClusterOrInstanceArn: awsV1.String(resourceArn),
		AwsSecretStoreArn:      awsV1.String(secretArn),
		Database:               awsV1.String(database),
		SqlStatements:          awsV1.String("select 1"),
	})
	assertAwsErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("select :id"),
		Parameters: []*rdsdataservice.SqlParameter{{
			Name:  awsV1.String("id"),
			Value: &rdsdataservice.Field{LongValue: awsV1.Int64(1)},
		}},
	})
	assertAwsErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score) values ('commit-1', 'duplicate', 1)"),
	})
	assertAwsErrorCode(t, err, "DatabaseErrorException")

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("select from data_api_items"),
	})
	assertAwsErrorCode(t, err, "DatabaseErrorException")
}

// TestRdsDataApiGoSdkPostgresV1 mirrors TestRdsDataApiGoSdkV1 against an
// aurora-postgresql cluster to verify RDS Data API parity across engines
// (transactions, result field mapping, upsert, and error codes).
func TestRdsDataApiGoSdkPostgresV1(t *testing.T) {
	ctx := context.Background()
	rdsSvc := testutil.RDSClient()
	secretsSvc := testutil.SecretsManagerClient()
	dataSvc := rdsDataV1Client(t)

	clusterID := "go-rds-data-postgres-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	database := "app"
	username := "admin"
	password := "secret123"
	secretName := "go-rds-data-postgres/" + clusterID
	var secretArn string
	var resourceArn string

	t.Cleanup(func() {
		if secretArn != "" {
			_, _ = secretsSvc.DeleteSecret(ctx, &secretsmanager.DeleteSecretInput{
				SecretId:                   awsV2.String(secretArn),
				ForceDeleteWithoutRecovery: awsV2.Bool(true),
			})
		}
		if resourceArn != "" {
			_, _ = rdsSvc.DeleteDBCluster(ctx, &rds.DeleteDBClusterInput{
				DBClusterIdentifier: awsV2.String(clusterID),
				SkipFinalSnapshot:   awsV2.Bool(true),
			})
		}
	})

	create, err := rdsSvc.CreateDBCluster(ctx, &rds.CreateDBClusterInput{
		DBClusterIdentifier: awsV2.String(clusterID),
		Engine:              awsV2.String("aurora-postgresql"),
		MasterUsername:      awsV2.String(username),
		MasterUserPassword:  awsV2.String(password),
		DatabaseName:        awsV2.String(database),
		EngineMode:          awsV2.String("provisioned"),
		Tags:                []types.Tag{{Key: awsV2.String("test"), Value: awsV2.String("rds-data-postgres")}},
	})
	if err != nil {
		t.Skipf("RDS PostgreSQL cluster unavailable in this environment: %v", err)
	}
	require.NotNil(t, create.DBCluster)
	resourceArn = awsV2.ToString(create.DBCluster.DBClusterArn)
	require.NotEmpty(t, resourceArn)

	secret, err := secretsSvc.CreateSecret(ctx, &secretsmanager.CreateSecretInput{
		Name:         awsV2.String(secretName),
		SecretString: awsV2.String(fmt.Sprintf(`{"username":%q,"password":%q}`, username, password)),
	})
	require.NoError(t, err)
	secretArn = awsV2.ToString(secret.ARN)
	require.NotEmpty(t, secretArn)

	executeEventually(t, dataSvc, &rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql: awsV1.String(`create table if not exists data_api_items (
			id varchar(64) primary key,
			title varchar(255),
			score bigint,
			payload bytea null,
			active boolean null,
			ratio double precision null,
			json_text text null,
			rfc3339_text varchar(64) null,
			observed_at timestamp(6) null,
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

	insertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: tx.TransactionId,
		Sql: awsV1.String(`insert into data_api_items
			(id, title, score, payload, active, ratio, json_text, rfc3339_text, observed_at, stamped_at, due_date, due_time)
			values (
				'commit-1',
				'first item',
				7,
				decode('010203', 'hex'),
				true,
				1.5,
				'{"ok":true}',
				'2021-03-04T05:06:07Z',
				'2021-03-04 05:06:07.891000',
				'2021-03-04 05:06:07.891000',
				'2021-03-04',
				'05:06:07'
			)`),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(insertOut.NumberOfRecordsUpdated))

	inTxRead, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV1.String("select count(*) as count from data_api_items where id = 'commit-1'"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(inTxRead.Records[0][0].LongValue))

	_, err = dataSvc.CommitTransaction(&rdsdataservice.CommitTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: tx.TransactionId,
	})
	require.NoError(t, err)

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV1.String("select 1"),
	})
	assertAwsErrorCode(t, err, "TransactionNotFoundException")

	selectOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select title, score, payload, null as nothing, observed_at, stamped_at, due_date, due_time, active, ratio, json_text, rfc3339_text from data_api_items where id = 'commit-1'"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	require.Len(t, selectOut.ColumnMetadata, 12)
	assert.Equal(t, "title", awsV1.StringValue(selectOut.ColumnMetadata[0].Name))
	require.Len(t, selectOut.Records, 1)
	require.Len(t, selectOut.Records[0], 12)
	assert.Equal(t, "first item", awsV1.StringValue(selectOut.Records[0][0].StringValue))
	assert.Equal(t, int64(7), awsV1.Int64Value(selectOut.Records[0][1].LongValue))
	assert.Equal(t, []byte{1, 2, 3}, selectOut.Records[0][2].BlobValue)
	assert.True(t, awsV1.BoolValue(selectOut.Records[0][3].IsNull))
	assert.Equal(t, "2021-03-04 05:06:07.891", awsV1.StringValue(selectOut.Records[0][4].StringValue))
	assert.Equal(t, "2021-03-04 05:06:07.891", awsV1.StringValue(selectOut.Records[0][5].StringValue))
	assert.Equal(t, "2021-03-04", awsV1.StringValue(selectOut.Records[0][6].StringValue))
	assert.Equal(t, "05:06:07", awsV1.StringValue(selectOut.Records[0][7].StringValue))
	assert.True(t, awsV1.BoolValue(selectOut.Records[0][8].BooleanValue))
	assert.Equal(t, 1.5, awsV1.Float64Value(selectOut.Records[0][9].DoubleValue))
	assert.Equal(t, `{"ok":true}`, awsV1.StringValue(selectOut.Records[0][10].StringValue))
	assert.Equal(t, "2021-03-04T05:06:07Z", awsV1.StringValue(selectOut.Records[0][11].StringValue))

	updateOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("update data_api_items set score = 8 where id = 'commit-1' and score = 7"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(updateOut.NumberOfRecordsUpdated))

	staleUpdateOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("update data_api_items set score = 9 where id = 'commit-1' and score = 7"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(0), awsV1.Int64Value(staleUpdateOut.NumberOfRecordsUpdated))

	upsertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql: awsV1.String(`insert into data_api_items (id, title, score, payload) values ('commit-1', 'upserted', 11, decode('0A0B', 'hex'))
			on conflict (id) do update set title = excluded.title, score = excluded.score, payload = excluded.payload`),
	})
	require.NoError(t, err)
	assert.GreaterOrEqual(t, awsV1.Int64Value(upsertOut.NumberOfRecordsUpdated), int64(1))

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score) values ('page-1', 'alpha', 1), ('page-2', 'beta', 2), ('page-3', 'alphabet', 3)"),
	})
	require.NoError(t, err)

	predicateOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select id from data_api_items where (title like 'alpha%' or id in (select id from data_api_items where score = 11)) order by score desc limit 2 offset 0"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	require.Len(t, predicateOut.Records, 2)
	assert.Equal(t, "commit-1", awsV1.StringValue(predicateOut.Records[0][0].StringValue))
	assert.Equal(t, "page-3", awsV1.StringValue(predicateOut.Records[1][0].StringValue))

	lessThanAndNotInOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select count(*) as count from data_api_items where score < 3 and id not in ('commit-1')"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	assert.Equal(t, "count", awsV1.StringValue(lessThanAndNotInOut.ColumnMetadata[0].Name))
	assert.Equal(t, int64(2), awsV1.Int64Value(lessThanAndNotInOut.Records[0][0].LongValue))

	emptyOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:           awsV1.String(resourceArn),
		SecretArn:             awsV1.String(secretArn),
		Database:              awsV1.String(database),
		Sql:                   awsV1.String("select id from data_api_items where id = 'missing'"),
		IncludeResultMetadata: awsV1.Bool(true),
	})
	require.NoError(t, err)
	assert.Equal(t, "id", awsV1.StringValue(emptyOut.ColumnMetadata[0].Name))
	assert.Empty(t, emptyOut.Records)
	assert.Equal(t, int64(0), awsV1.Int64Value(emptyOut.NumberOfRecordsUpdated))

	replaceTx, err := dataSvc.BeginTransaction(&rdsdataservice.BeginTransactionInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
	})
	require.NoError(t, err)
	deleteOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: replaceTx.TransactionId,
		Sql:           awsV1.String("delete from data_api_items where id = 'page-2'"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(deleteOut.NumberOfRecordsUpdated))
	replaceInsertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: replaceTx.TransactionId,
		Sql:           awsV1.String("insert into data_api_items (id, title, score) values ('page-4', 'replacement', 4)"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(replaceInsertOut.NumberOfRecordsUpdated))
	_, err = dataSvc.CommitTransaction(&rdsdataservice.CommitTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: replaceTx.TransactionId,
	})
	require.NoError(t, err)
	replaceCountOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("select count(*) as count from data_api_items where id in ('page-2', 'page-4')"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(replaceCountOut.Records[0][0].LongValue))

	rollbackTx, err := dataSvc.BeginTransaction(&rdsdataservice.BeginTransactionInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
	})
	require.NoError(t, err)
	rollbackInsertOut, err := dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV1.String("insert into data_api_items (id, title, score) values ('rollback-1', 'rolled back', 9)"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), awsV1.Int64Value(rollbackInsertOut.NumberOfRecordsUpdated))
	_, err = dataSvc.RollbackTransaction(&rdsdataservice.RollbackTransactionInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		TransactionId: rollbackTx.TransactionId,
	})
	require.NoError(t, err)

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn:   awsV1.String(resourceArn),
		SecretArn:     awsV1.String(secretArn),
		Database:      awsV1.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV1.String("select 1"),
	})
	assertAwsErrorCode(t, err, "TransactionNotFoundException")

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

	_, err = dataSvc.BatchExecuteStatement(&rdsdataservice.BatchExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score) values ('batch-1', 'batch', 1)"),
	})
	require.Error(t, err)
	assertAwsErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteSql(&rdsdataservice.ExecuteSqlInput{
		DbClusterOrInstanceArn: awsV1.String(resourceArn),
		AwsSecretStoreArn:      awsV1.String(secretArn),
		Database:               awsV1.String(database),
		SqlStatements:          awsV1.String("select 1"),
	})
	assertAwsErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("select :id"),
		Parameters: []*rdsdataservice.SqlParameter{{
			Name:  awsV1.String("id"),
			Value: &rdsdataservice.Field{LongValue: awsV1.Int64(1)},
		}},
	})
	assertAwsErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		Sql:         awsV1.String("insert into data_api_items (id, title, score) values ('commit-1', 'duplicate', 1)"),
	})
	assertAwsErrorCode(t, err, "DatabaseErrorException")

	_, err = dataSvc.ExecuteStatement(&rdsdataservice.ExecuteStatementInput{
		ResourceArn: awsV1.String(resourceArn),
		SecretArn:   awsV1.String(secretArn),
		Database:    awsV1.String(database),
		// Unlike MySQL, PostgreSQL accepts "select from t" as a valid zero-column
		// query, so use a statement that is malformed under any SQL dialect.
		Sql: awsV1.String("select * from data_api_items where"),
	})
	assertAwsErrorCode(t, err, "DatabaseErrorException")
}

func TestRdsDataApiGoSdkV2(t *testing.T) {
	ctx := context.Background()
	rdsSvc := testutil.RDSClient()
	secretsSvc := testutil.SecretsManagerClient()
	dataSvc := rdsDataV2Client()

	clusterID := "go-rds-data-v2-mysql-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	database := "app"
	username := "admin"
	password := "secret123"
	secretName := "go-rds-data-v2/" + clusterID
	var secretArn string
	var resourceArn string

	t.Cleanup(func() {
		if secretArn != "" {
			_, _ = secretsSvc.DeleteSecret(ctx, &secretsmanager.DeleteSecretInput{
				SecretId:                   awsV2.String(secretArn),
				ForceDeleteWithoutRecovery: awsV2.Bool(true),
			})
		}
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
		Tags:                []types.Tag{{Key: awsV2.String("test"), Value: awsV2.String("rds-data-v2")}},
	})
	if err != nil {
		t.Skipf("RDS MySQL cluster unavailable in this environment: %v", err)
	}
	require.NotNil(t, create.DBCluster)
	resourceArn = awsV2.ToString(create.DBCluster.DBClusterArn)
	require.NotEmpty(t, resourceArn)

	secret, err := secretsSvc.CreateSecret(ctx, &secretsmanager.CreateSecretInput{
		Name:         awsV2.String(secretName),
		SecretString: awsV2.String(fmt.Sprintf(`{"username":%q,"password":%q}`, username, password)),
	})
	require.NoError(t, err)
	secretArn = awsV2.ToString(secret.ARN)
	require.NotEmpty(t, secretArn)

	executeEventuallyV2(t, ctx, dataSvc, &rdsdata.ExecuteStatementInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
		Sql: awsV2.String(`create table if not exists data_api_v2_items (
			id varchar(64) primary key,
			name varchar(255),
			qty bigint,
			payload blob null,
			active boolean null,
			ratio double null
		)`),
	})

	tx, err := dataSvc.BeginTransaction(ctx, &rdsdata.BeginTransactionInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
	})
	require.NoError(t, err)
	require.NotEmpty(t, awsV2.ToString(tx.TransactionId))

	insertOut, err := dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV2.String("insert into data_api_v2_items (id, name, qty, payload, active, ratio) values ('v2-1', 'second client', 12, UNHEX('0A0B'), true, 2.5)"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), insertOut.NumberOfRecordsUpdated)

	readInTx, err := dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV2.String("select count(*) as count from data_api_v2_items where id = 'v2-1'"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), fieldLongValue(t, readInTx.Records[0][0]))

	commitOut, err := dataSvc.CommitTransaction(ctx, &rdsdata.CommitTransactionInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		TransactionId: tx.TransactionId,
	})
	require.NoError(t, err)
	assert.NotEmpty(t, awsV2.ToString(commitOut.TransactionStatus))

	selectOut, err := dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:           awsV2.String(resourceArn),
		SecretArn:             awsV2.String(secretArn),
		Database:              awsV2.String(database),
		Sql:                   awsV2.String("select name, qty, payload, active, ratio from data_api_v2_items where id = 'v2-1'"),
		IncludeResultMetadata: true,
	})
	require.NoError(t, err)
	require.Len(t, selectOut.ColumnMetadata, 5)
	assert.Equal(t, "name", awsV2.ToString(selectOut.ColumnMetadata[0].Name))
	require.Len(t, selectOut.Records, 1)
	assert.Equal(t, "second client", fieldStringValue(t, selectOut.Records[0][0]))
	assert.Equal(t, int64(12), fieldLongValue(t, selectOut.Records[0][1]))
	assert.Equal(t, []byte{10, 11}, fieldBlobValue(t, selectOut.Records[0][2]))
	assert.True(t, fieldBooleanValue(t, selectOut.Records[0][3]))
	assert.Equal(t, 2.5, fieldDoubleValue(t, selectOut.Records[0][4]))

	rollbackTx, err := dataSvc.BeginTransaction(ctx, &rdsdata.BeginTransactionInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
	})
	require.NoError(t, err)
	_, err = dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV2.String("insert into data_api_v2_items (id, name, qty) values ('v2-rollback', 'gone', 1)"),
	})
	require.NoError(t, err)
	_, err = dataSvc.RollbackTransaction(ctx, &rdsdata.RollbackTransactionInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		TransactionId: rollbackTx.TransactionId,
	})
	require.NoError(t, err)

	_, err = dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV2.String("select 1"),
	})
	assertSmithyErrorCode(t, err, "TransactionNotFoundException")

	_, err = dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
		Sql:         awsV2.String("select :id"),
		Parameters: []rdsdatatypes.SqlParameter{{
			Name:  awsV2.String("id"),
			Value: &rdsdatatypes.FieldMemberLongValue{Value: 1},
		}},
	})
	assertSmithyErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteSql(ctx, &rdsdata.ExecuteSqlInput{
		DbClusterOrInstanceArn: awsV2.String(resourceArn),
		AwsSecretStoreArn:      awsV2.String(secretArn),
		Database:               awsV2.String(database),
		SqlStatements:          awsV2.String("select 1"),
	})
	assertSmithyErrorCode(t, err, "BadRequestException")
}

// TestRdsDataApiGoSdkPostgresV2 mirrors TestRdsDataApiGoSdkV2 against an
// aurora-postgresql cluster to verify RDS Data API parity across engines.
func TestRdsDataApiGoSdkPostgresV2(t *testing.T) {
	ctx := context.Background()
	rdsSvc := testutil.RDSClient()
	secretsSvc := testutil.SecretsManagerClient()
	dataSvc := rdsDataV2Client()

	clusterID := "go-rds-data-v2-postgres-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	database := "app"
	username := "admin"
	password := "secret123"
	secretName := "go-rds-data-v2-postgres/" + clusterID
	var secretArn string
	var resourceArn string

	t.Cleanup(func() {
		if secretArn != "" {
			_, _ = secretsSvc.DeleteSecret(ctx, &secretsmanager.DeleteSecretInput{
				SecretId:                   awsV2.String(secretArn),
				ForceDeleteWithoutRecovery: awsV2.Bool(true),
			})
		}
		if resourceArn != "" {
			_, _ = rdsSvc.DeleteDBCluster(ctx, &rds.DeleteDBClusterInput{
				DBClusterIdentifier: awsV2.String(clusterID),
				SkipFinalSnapshot:   awsV2.Bool(true),
			})
		}
	})

	create, err := rdsSvc.CreateDBCluster(ctx, &rds.CreateDBClusterInput{
		DBClusterIdentifier: awsV2.String(clusterID),
		Engine:              awsV2.String("aurora-postgresql"),
		MasterUsername:      awsV2.String(username),
		MasterUserPassword:  awsV2.String(password),
		DatabaseName:        awsV2.String(database),
		EngineMode:          awsV2.String("provisioned"),
		Tags:                []types.Tag{{Key: awsV2.String("test"), Value: awsV2.String("rds-data-v2-postgres")}},
	})
	if err != nil {
		t.Skipf("RDS PostgreSQL cluster unavailable in this environment: %v", err)
	}
	require.NotNil(t, create.DBCluster)
	resourceArn = awsV2.ToString(create.DBCluster.DBClusterArn)
	require.NotEmpty(t, resourceArn)

	secret, err := secretsSvc.CreateSecret(ctx, &secretsmanager.CreateSecretInput{
		Name:         awsV2.String(secretName),
		SecretString: awsV2.String(fmt.Sprintf(`{"username":%q,"password":%q}`, username, password)),
	})
	require.NoError(t, err)
	secretArn = awsV2.ToString(secret.ARN)
	require.NotEmpty(t, secretArn)

	executeEventuallyV2(t, ctx, dataSvc, &rdsdata.ExecuteStatementInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
		Sql: awsV2.String(`create table if not exists data_api_v2_items (
			id varchar(64) primary key,
			name varchar(255),
			qty bigint,
			payload bytea null,
			active boolean null,
			ratio double precision null
		)`),
	})

	tx, err := dataSvc.BeginTransaction(ctx, &rdsdata.BeginTransactionInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
	})
	require.NoError(t, err)
	require.NotEmpty(t, awsV2.ToString(tx.TransactionId))

	insertOut, err := dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV2.String("insert into data_api_v2_items (id, name, qty, payload, active, ratio) values ('v2-1', 'second client', 12, decode('0A0B', 'hex'), true, 2.5)"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), insertOut.NumberOfRecordsUpdated)

	readInTx, err := dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: tx.TransactionId,
		Sql:           awsV2.String("select count(*) as count from data_api_v2_items where id = 'v2-1'"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(1), fieldLongValue(t, readInTx.Records[0][0]))

	commitOut, err := dataSvc.CommitTransaction(ctx, &rdsdata.CommitTransactionInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		TransactionId: tx.TransactionId,
	})
	require.NoError(t, err)
	assert.NotEmpty(t, awsV2.ToString(commitOut.TransactionStatus))

	selectOut, err := dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:           awsV2.String(resourceArn),
		SecretArn:             awsV2.String(secretArn),
		Database:              awsV2.String(database),
		Sql:                   awsV2.String("select name, qty, payload, active, ratio from data_api_v2_items where id = 'v2-1'"),
		IncludeResultMetadata: true,
	})
	require.NoError(t, err)
	require.Len(t, selectOut.ColumnMetadata, 5)
	assert.Equal(t, "name", awsV2.ToString(selectOut.ColumnMetadata[0].Name))
	require.Len(t, selectOut.Records, 1)
	assert.Equal(t, "second client", fieldStringValue(t, selectOut.Records[0][0]))
	assert.Equal(t, int64(12), fieldLongValue(t, selectOut.Records[0][1]))
	assert.Equal(t, []byte{10, 11}, fieldBlobValue(t, selectOut.Records[0][2]))
	assert.True(t, fieldBooleanValue(t, selectOut.Records[0][3]))
	assert.Equal(t, 2.5, fieldDoubleValue(t, selectOut.Records[0][4]))

	rollbackTx, err := dataSvc.BeginTransaction(ctx, &rdsdata.BeginTransactionInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
	})
	require.NoError(t, err)
	_, err = dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV2.String("insert into data_api_v2_items (id, name, qty) values ('v2-rollback', 'gone', 1)"),
	})
	require.NoError(t, err)
	_, err = dataSvc.RollbackTransaction(ctx, &rdsdata.RollbackTransactionInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		TransactionId: rollbackTx.TransactionId,
	})
	require.NoError(t, err)

	_, err = dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn:   awsV2.String(resourceArn),
		SecretArn:     awsV2.String(secretArn),
		Database:      awsV2.String(database),
		TransactionId: rollbackTx.TransactionId,
		Sql:           awsV2.String("select 1"),
	})
	assertSmithyErrorCode(t, err, "TransactionNotFoundException")

	_, err = dataSvc.ExecuteStatement(ctx, &rdsdata.ExecuteStatementInput{
		ResourceArn: awsV2.String(resourceArn),
		SecretArn:   awsV2.String(secretArn),
		Database:    awsV2.String(database),
		Sql:         awsV2.String("select :id"),
		Parameters: []rdsdatatypes.SqlParameter{{
			Name:  awsV2.String("id"),
			Value: &rdsdatatypes.FieldMemberLongValue{Value: 1},
		}},
	})
	assertSmithyErrorCode(t, err, "BadRequestException")

	_, err = dataSvc.ExecuteSql(ctx, &rdsdata.ExecuteSqlInput{
		DbClusterOrInstanceArn: awsV2.String(resourceArn),
		AwsSecretStoreArn:      awsV2.String(secretArn),
		Database:               awsV2.String(database),
		SqlStatements:          awsV2.String("select 1"),
	})
	assertSmithyErrorCode(t, err, "BadRequestException")
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

func rdsDataV2Client() *rdsdata.Client {
	return rdsdata.NewFromConfig(testutil.Config())
}

func executeEventually(t *testing.T, svc *rdsdataservice.RDSDataService, input *rdsdataservice.ExecuteStatementInput) {
	t.Helper()
	require.Eventually(t, func() bool {
		_, err := svc.ExecuteStatement(input)
		return err == nil
	}, 90*time.Second, time.Second)
}

func executeEventuallyV2(t *testing.T, ctx context.Context, svc *rdsdata.Client, input *rdsdata.ExecuteStatementInput) {
	t.Helper()
	require.Eventually(t, func() bool {
		_, err := svc.ExecuteStatement(ctx, input)
		return err == nil
	}, 90*time.Second, time.Second)
}

func assertAwsErrorCode(t *testing.T, err error, code string) {
	t.Helper()
	require.Error(t, err)
	var awsErr awserr.Error
	require.ErrorAs(t, err, &awsErr)
	assert.Equal(t, code, awsErr.Code())
}

func assertSmithyErrorCode(t *testing.T, err error, code string) {
	t.Helper()
	require.Error(t, err)
	var apiErr smithy.APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, code, apiErr.ErrorCode())
}

func fieldStringValue(t *testing.T, field rdsdatatypes.Field) string {
	t.Helper()
	value, ok := field.(*rdsdatatypes.FieldMemberStringValue)
	require.Truef(t, ok, "expected stringValue field, got %T", field)
	return value.Value
}

func fieldLongValue(t *testing.T, field rdsdatatypes.Field) int64 {
	t.Helper()
	value, ok := field.(*rdsdatatypes.FieldMemberLongValue)
	require.Truef(t, ok, "expected longValue field, got %T", field)
	return value.Value
}

func fieldBlobValue(t *testing.T, field rdsdatatypes.Field) []byte {
	t.Helper()
	value, ok := field.(*rdsdatatypes.FieldMemberBlobValue)
	require.Truef(t, ok, "expected blobValue field, got %T", field)
	return value.Value
}

func fieldBooleanValue(t *testing.T, field rdsdatatypes.Field) bool {
	t.Helper()
	value, ok := field.(*rdsdatatypes.FieldMemberBooleanValue)
	require.Truef(t, ok, "expected booleanValue field, got %T", field)
	return value.Value
}

func fieldDoubleValue(t *testing.T, field rdsdatatypes.Field) float64 {
	t.Helper()
	value, ok := field.(*rdsdatatypes.FieldMemberDoubleValue)
	require.Truef(t, ok, "expected doubleValue field, got %T", field)
	return value.Value
}
