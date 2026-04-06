package tests

import (
	"context"
	"encoding/json"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/lambda"
	lambdatypes "github.com/aws/aws-sdk-go-v2/service/lambda/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLambda(t *testing.T) {
	ctx := context.Background()
	svc := testutil.LambdaClient()
	funcName := "go-test-func"
	roleARN := "arn:aws:iam::000000000000:role/test-role"

	t.Cleanup(func() {
		svc.DeleteFunction(ctx, &lambda.DeleteFunctionInput{FunctionName: aws.String(funcName)})
	})

	t.Run("CreateFunction", func(t *testing.T) {
		_, err := svc.CreateFunction(ctx, &lambda.CreateFunctionInput{
			FunctionName: aws.String(funcName),
			Runtime:      lambdatypes.RuntimeNodejs18x,
			Role:         aws.String(roleARN),
			Handler:      aws.String("index.handler"),
			Code:         &lambdatypes.FunctionCode{ZipFile: testutil.MinimalZip()},
		})
		require.NoError(t, err)
	})

	t.Run("GetFunction", func(t *testing.T) {
		r, err := svc.GetFunction(ctx, &lambda.GetFunctionInput{FunctionName: aws.String(funcName)})
		require.NoError(t, err)
		assert.Equal(t, funcName, aws.ToString(r.Configuration.FunctionName))
	})

	t.Run("ListFunctions", func(t *testing.T) {
		r, err := svc.ListFunctions(ctx, &lambda.ListFunctionsInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Functions)
	})

	t.Run("Invoke", func(t *testing.T) {
		payload, _ := json.Marshal(map[string]string{"name": "GoTest"})
		r, err := svc.Invoke(ctx, &lambda.InvokeInput{
			FunctionName: aws.String(funcName),
			Payload:      payload,
		})
		require.NoError(t, err)
		assert.Equal(t, int32(200), r.StatusCode)
		assert.Nil(t, r.FunctionError)
	})

	t.Run("DeleteFunction", func(t *testing.T) {
		_, err := svc.DeleteFunction(ctx, &lambda.DeleteFunctionInput{FunctionName: aws.String(funcName)})
		require.NoError(t, err)
	})
}
