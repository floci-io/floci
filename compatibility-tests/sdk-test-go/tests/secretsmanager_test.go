package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSecretsManager(t *testing.T) {
	ctx := context.Background()
	svc := testutil.SecretsManagerClient()
	secretName := "go-test/creds"
	secretVal := `{"username":"admin","password":"s3cret"}`
	var secretARN string

	t.Cleanup(func() {
		if secretARN != "" {
			svc.DeleteSecret(ctx, &secretsmanager.DeleteSecretInput{
				SecretId:                   aws.String(secretARN),
				ForceDeleteWithoutRecovery: aws.Bool(true),
			})
		}
	})

	t.Run("CreateSecret", func(t *testing.T) {
		r, err := svc.CreateSecret(ctx, &secretsmanager.CreateSecretInput{
			Name:         aws.String(secretName),
			SecretString: aws.String(secretVal),
		})
		require.NoError(t, err)
		secretARN = aws.ToString(r.ARN)
		assert.NotEmpty(t, secretARN)
	})

	t.Run("GetSecretValue", func(t *testing.T) {
		r, err := svc.GetSecretValue(ctx, &secretsmanager.GetSecretValueInput{
			SecretId: aws.String(secretARN),
		})
		require.NoError(t, err)
		assert.Equal(t, secretVal, aws.ToString(r.SecretString))
	})

	t.Run("DescribeSecret", func(t *testing.T) {
		_, err := svc.DescribeSecret(ctx, &secretsmanager.DescribeSecretInput{
			SecretId: aws.String(secretARN),
		})
		require.NoError(t, err)
	})

	t.Run("ListSecrets", func(t *testing.T) {
		r, err := svc.ListSecrets(ctx, &secretsmanager.ListSecretsInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.SecretList)
	})

	t.Run("PutSecretValue", func(t *testing.T) {
		newVal := `{"username":"admin","password":"n3wsecret"}`
		_, err := svc.PutSecretValue(ctx, &secretsmanager.PutSecretValueInput{
			SecretId:     aws.String(secretARN),
			SecretString: aws.String(newVal),
		})
		require.NoError(t, err)
	})

	t.Run("DeleteSecret", func(t *testing.T) {
		_, err := svc.DeleteSecret(ctx, &secretsmanager.DeleteSecretInput{
			SecretId:                   aws.String(secretARN),
			ForceDeleteWithoutRecovery: aws.Bool(true),
		})
		require.NoError(t, err)
		secretARN = "" // Prevent double cleanup
	})
}
