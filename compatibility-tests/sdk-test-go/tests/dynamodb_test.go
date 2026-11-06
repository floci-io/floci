package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	ddbtypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDynamoDB(t *testing.T) {
	ctx := context.Background()
	svc := testutil.DynamoDBClient()
	table := "go-test-table"

	t.Cleanup(func() {
		svc.DeleteTable(ctx, &dynamodb.DeleteTableInput{TableName: aws.String(table)})
	})

	t.Run("CreateTable", func(t *testing.T) {
		_, err := svc.CreateTable(ctx, &dynamodb.CreateTableInput{
			TableName:   aws.String(table),
			BillingMode: ddbtypes.BillingModePayPerRequest,
			AttributeDefinitions: []ddbtypes.AttributeDefinition{
				{AttributeName: aws.String("pk"), AttributeType: ddbtypes.ScalarAttributeTypeS},
				{AttributeName: aws.String("sk"), AttributeType: ddbtypes.ScalarAttributeTypeS},
			},
			KeySchema: []ddbtypes.KeySchemaElement{
				{AttributeName: aws.String("pk"), KeyType: ddbtypes.KeyTypeHash},
				{AttributeName: aws.String("sk"), KeyType: ddbtypes.KeyTypeRange},
			},
		})
		require.NoError(t, err)
	})

	t.Run("DescribeTable", func(t *testing.T) {
		r, err := svc.DescribeTable(ctx, &dynamodb.DescribeTableInput{TableName: aws.String(table)})
		require.NoError(t, err)
		assert.Equal(t, ddbtypes.TableStatusActive, r.Table.TableStatus)
	})

	t.Run("ListTables", func(t *testing.T) {
		r, err := svc.ListTables(ctx, &dynamodb.ListTablesInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.TableNames)
	})

	t.Run("PutItem", func(t *testing.T) {
		_, err := svc.PutItem(ctx, &dynamodb.PutItemInput{
			TableName: aws.String(table),
			Item: map[string]ddbtypes.AttributeValue{
				"pk":   &ddbtypes.AttributeValueMemberS{Value: "user#1"},
				"sk":   &ddbtypes.AttributeValueMemberS{Value: "profile"},
				"name": &ddbtypes.AttributeValueMemberS{Value: "Alice"},
				"age":  &ddbtypes.AttributeValueMemberN{Value: "30"},
			},
		})
		require.NoError(t, err)
	})

	t.Run("GetItem", func(t *testing.T) {
		r, err := svc.GetItem(ctx, &dynamodb.GetItemInput{
			TableName: aws.String(table),
			Key: map[string]ddbtypes.AttributeValue{
				"pk": &ddbtypes.AttributeValueMemberS{Value: "user#1"},
				"sk": &ddbtypes.AttributeValueMemberS{Value: "profile"},
			},
		})
		require.NoError(t, err)
		attr, ok := r.Item["name"].(*ddbtypes.AttributeValueMemberS)
		require.True(t, ok)
		assert.Equal(t, "Alice", attr.Value)
	})

	t.Run("UpdateItem", func(t *testing.T) {
		_, err := svc.UpdateItem(ctx, &dynamodb.UpdateItemInput{
			TableName: aws.String(table),
			Key: map[string]ddbtypes.AttributeValue{
				"pk": &ddbtypes.AttributeValueMemberS{Value: "user#1"},
				"sk": &ddbtypes.AttributeValueMemberS{Value: "profile"},
			},
			UpdateExpression:         aws.String("SET #n = :n"),
			ExpressionAttributeNames: map[string]string{"#n": "name"},
			ExpressionAttributeValues: map[string]ddbtypes.AttributeValue{
				":n": &ddbtypes.AttributeValueMemberS{Value: "Alice Updated"},
			},
		})
		require.NoError(t, err)
	})

	t.Run("Query", func(t *testing.T) {
		r, err := svc.Query(ctx, &dynamodb.QueryInput{
			TableName:              aws.String(table),
			KeyConditionExpression: aws.String("pk = :pk"),
			ExpressionAttributeValues: map[string]ddbtypes.AttributeValue{
				":pk": &ddbtypes.AttributeValueMemberS{Value: "user#1"},
			},
		})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Items)
	})

	t.Run("Scan", func(t *testing.T) {
		r, err := svc.Scan(ctx, &dynamodb.ScanInput{TableName: aws.String(table)})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Items)
	})

	t.Run("DeleteItem", func(t *testing.T) {
		_, err := svc.DeleteItem(ctx, &dynamodb.DeleteItemInput{
			TableName: aws.String(table),
			Key: map[string]ddbtypes.AttributeValue{
				"pk": &ddbtypes.AttributeValueMemberS{Value: "user#1"},
				"sk": &ddbtypes.AttributeValueMemberS{Value: "profile"},
			},
		})
		require.NoError(t, err)
	})

	t.Run("DeleteTable", func(t *testing.T) {
		_, err := svc.DeleteTable(ctx, &dynamodb.DeleteTableInput{TableName: aws.String(table)})
		require.NoError(t, err)
	})
}
