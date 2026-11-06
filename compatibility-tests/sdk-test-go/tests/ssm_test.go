package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/ssm"
	ssmtypes "github.com/aws/aws-sdk-go-v2/service/ssm/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSSM(t *testing.T) {
	ctx := context.Background()
	svc := testutil.SSMClient()
	name := "/go-test/param"
	value := "go-test-value"

	// Cleanup at end
	t.Cleanup(func() {
		svc.DeleteParameter(ctx, &ssm.DeleteParameterInput{Name: aws.String(name)})
	})

	t.Run("PutParameter", func(t *testing.T) {
		_, err := svc.PutParameter(ctx, &ssm.PutParameterInput{
			Name:      aws.String(name),
			Value:     aws.String(value),
			Type:      ssmtypes.ParameterTypeString,
			Overwrite: aws.Bool(true),
		})
		require.NoError(t, err)
	})

	t.Run("GetParameter", func(t *testing.T) {
		r, err := svc.GetParameter(ctx, &ssm.GetParameterInput{Name: aws.String(name)})
		require.NoError(t, err)
		assert.Equal(t, value, aws.ToString(r.Parameter.Value))
	})

	t.Run("GetParameters", func(t *testing.T) {
		r, err := svc.GetParameters(ctx, &ssm.GetParametersInput{Names: []string{name}})
		require.NoError(t, err)
		assert.Len(t, r.Parameters, 1)
	})

	t.Run("DescribeParameters", func(t *testing.T) {
		r, err := svc.DescribeParameters(ctx, &ssm.DescribeParametersInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Parameters)
	})

	t.Run("GetParametersByPath", func(t *testing.T) {
		r, err := svc.GetParametersByPath(ctx, &ssm.GetParametersByPathInput{
			Path: aws.String("/go-test"),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Parameters)
	})

	t.Run("DeleteParameter", func(t *testing.T) {
		_, err := svc.DeleteParameter(ctx, &ssm.DeleteParameterInput{Name: aws.String(name)})
		require.NoError(t, err)
	})
}
