package tests

import (
	"context"
	"encoding/json"
	"errors"
	"net"
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
		if err != nil {
			// Only skip on transport-level failures (timeout, connection refused).
			// Let unexpected service errors fail the test.
			var netErr net.Error
			if errors.As(err, &netErr) {
				t.Skipf("Lambda REQUEST_RESPONSE dispatch unavailable (transport error): %v", err)
			}
			require.NoError(t, err)
		}
		assert.Equal(t, int32(200), r.StatusCode)
		assert.Nil(t, r.FunctionError)
	})

	t.Run("DeleteFunction", func(t *testing.T) {
		_, err := svc.DeleteFunction(ctx, &lambda.DeleteFunctionInput{FunctionName: aws.String(funcName)})
		require.NoError(t, err)
	})
}

func TestLambdaImageConfigWorkingDirectory(t *testing.T) {
	ctx := context.Background()
	svc := testutil.LambdaClient()
	roleARN := "arn:aws:iam::000000000000:role/test-role"
	imageURI := "000000000000.dkr.ecr.us-east-1.amazonaws.com/fake-repo:latest"

	t.Run("RoundTripsWorkingDirectoryOnCreate", func(t *testing.T) {
		fnName := "go-test-imgwd-create"
		t.Cleanup(func() {
			svc.DeleteFunction(ctx, &lambda.DeleteFunctionInput{FunctionName: aws.String(fnName)})
		})

		createResp, err := svc.CreateFunction(ctx, &lambda.CreateFunctionInput{
			FunctionName: aws.String(fnName),
			PackageType:  lambdatypes.PackageTypeImage,
			Role:         aws.String(roleARN),
			Code:         &lambdatypes.FunctionCode{ImageUri: aws.String(imageURI)},
			ImageConfig:  &lambdatypes.ImageConfig{WorkingDirectory: aws.String("/app")},
		})
		require.NoError(t, err)
		require.NotNil(t, createResp.ImageConfigResponse)
		require.NotNil(t, createResp.ImageConfigResponse.ImageConfig)
		assert.Equal(t, "/app", aws.ToString(createResp.ImageConfigResponse.ImageConfig.WorkingDirectory))

		getResp, err := svc.GetFunctionConfiguration(ctx, &lambda.GetFunctionConfigurationInput{
			FunctionName: aws.String(fnName),
		})
		require.NoError(t, err)
		assert.Equal(t, "/app", aws.ToString(getResp.ImageConfigResponse.ImageConfig.WorkingDirectory))
	})

	t.Run("UpdatesWorkingDirectory", func(t *testing.T) {
		fnName := "go-test-imgwd-update"
		t.Cleanup(func() {
			svc.DeleteFunction(ctx, &lambda.DeleteFunctionInput{FunctionName: aws.String(fnName)})
		})

		_, err := svc.CreateFunction(ctx, &lambda.CreateFunctionInput{
			FunctionName: aws.String(fnName),
			PackageType:  lambdatypes.PackageTypeImage,
			Role:         aws.String(roleARN),
			Code:         &lambdatypes.FunctionCode{ImageUri: aws.String(imageURI)},
			ImageConfig:  &lambdatypes.ImageConfig{WorkingDirectory: aws.String("/initial")},
		})
		require.NoError(t, err)

		updateResp, err := svc.UpdateFunctionConfiguration(ctx, &lambda.UpdateFunctionConfigurationInput{
			FunctionName: aws.String(fnName),
			ImageConfig:  &lambdatypes.ImageConfig{WorkingDirectory: aws.String("/updated")},
		})
		require.NoError(t, err)
		assert.Equal(t, "/updated", aws.ToString(updateResp.ImageConfigResponse.ImageConfig.WorkingDirectory))
	})
}
