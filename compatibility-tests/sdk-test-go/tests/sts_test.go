package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sts"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSTS(t *testing.T) {
	ctx := context.Background()
	svc := testutil.STSClient()

	t.Run("GetCallerIdentity", func(t *testing.T) {
		r, err := svc.GetCallerIdentity(ctx, &sts.GetCallerIdentityInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.Account))
		assert.NotEmpty(t, aws.ToString(r.Arn))
		assert.NotEmpty(t, aws.ToString(r.UserId))
	})
}
