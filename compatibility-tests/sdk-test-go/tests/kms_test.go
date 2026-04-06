package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/kms"
	kmstypes "github.com/aws/aws-sdk-go-v2/service/kms/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestKMS(t *testing.T) {
	ctx := context.Background()
	svc := testutil.KMSClient()
	var keyID string

	// Note: KMS keys cannot be deleted immediately, so we don't clean up

	t.Run("CreateKey", func(t *testing.T) {
		r, err := svc.CreateKey(ctx, &kms.CreateKeyInput{
			Description: aws.String("go-test key"),
		})
		require.NoError(t, err)
		keyID = aws.ToString(r.KeyMetadata.KeyId)
		assert.NotEmpty(t, keyID)
	})

	t.Run("DescribeKey", func(t *testing.T) {
		r, err := svc.DescribeKey(ctx, &kms.DescribeKeyInput{KeyId: aws.String(keyID)})
		require.NoError(t, err)
		assert.Equal(t, keyID, aws.ToString(r.KeyMetadata.KeyId))
	})

	t.Run("ListKeys", func(t *testing.T) {
		r, err := svc.ListKeys(ctx, &kms.ListKeysInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Keys)
	})

	var ciphertext []byte

	t.Run("Encrypt", func(t *testing.T) {
		r, err := svc.Encrypt(ctx, &kms.EncryptInput{
			KeyId:     aws.String(keyID),
			Plaintext: []byte("hello-go-test"),
		})
		require.NoError(t, err)
		ciphertext = r.CiphertextBlob
		assert.NotEmpty(t, ciphertext)
	})

	t.Run("Decrypt", func(t *testing.T) {
		if len(ciphertext) == 0 {
			t.Skip("No ciphertext from Encrypt")
		}
		r, err := svc.Decrypt(ctx, &kms.DecryptInput{
			CiphertextBlob: ciphertext,
			KeyId:          aws.String(keyID),
		})
		require.NoError(t, err)
		assert.Equal(t, "hello-go-test", string(r.Plaintext))
	})

	t.Run("GenerateDataKey", func(t *testing.T) {
		r, err := svc.GenerateDataKey(ctx, &kms.GenerateDataKeyInput{
			KeyId:   aws.String(keyID),
			KeySpec: kmstypes.DataKeySpecAes256,
		})
		require.NoError(t, err)
		assert.Len(t, r.Plaintext, 32)
	})
}
