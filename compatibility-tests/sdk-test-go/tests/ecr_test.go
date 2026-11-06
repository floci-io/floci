package tests

import (
	"context"
	"encoding/base64"
	"errors"
	"strings"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/ecr"
	ecrtypes "github.com/aws/aws-sdk-go-v2/service/ecr/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestECR is the Go control-plane compatibility suite for Floci's emulated ECR.
// Test-first: this file is committed before the server implementation lands.
func TestECR(t *testing.T) {
	ctx := context.Background()
	svc := testutil.ECRClient()

	const repoName = "floci-it/app-go"

	// Cleanup helper
	cleanup := func(name string) {
		_, _ = svc.DeleteRepository(ctx, &ecr.DeleteRepositoryInput{
			RepositoryName: aws.String(name),
			Force:          true,
		})
	}
	t.Cleanup(func() { cleanup(repoName) })

	t.Run("CreateRepository", func(t *testing.T) {
		out, err := svc.CreateRepository(ctx, &ecr.CreateRepositoryInput{
			RepositoryName: aws.String(repoName),
		})
		require.NoError(t, err)
		require.NotNil(t, out.Repository)
		assert.Equal(t, repoName, aws.ToString(out.Repository.RepositoryName))
		assert.Contains(t, aws.ToString(out.Repository.RepositoryArn), ":repository/"+repoName)
		uri := aws.ToString(out.Repository.RepositoryUri)
		assert.Contains(t, uri, "/"+repoName)
		assert.True(t, strings.Contains(uri, "localhost:"), "repositoryUri should target loopback: %s", uri)
	})

	t.Run("CreateRepositoryDuplicate", func(t *testing.T) {
		_, err := svc.CreateRepository(ctx, &ecr.CreateRepositoryInput{
			RepositoryName: aws.String(repoName),
		})
		require.Error(t, err)
		var alreadyExists *ecrtypes.RepositoryAlreadyExistsException
		assert.True(t, errors.As(err, &alreadyExists), "expected RepositoryAlreadyExistsException, got %T", err)
	})

	t.Run("DescribeRepositories", func(t *testing.T) {
		out, err := svc.DescribeRepositories(ctx, &ecr.DescribeRepositoriesInput{
			RepositoryNames: []string{repoName},
		})
		require.NoError(t, err)
		require.Len(t, out.Repositories, 1)
		assert.Equal(t, repoName, aws.ToString(out.Repositories[0].RepositoryName))
	})

	t.Run("GetAuthorizationToken", func(t *testing.T) {
		out, err := svc.GetAuthorizationToken(ctx, &ecr.GetAuthorizationTokenInput{})
		require.NoError(t, err)
		require.NotEmpty(t, out.AuthorizationData)
		data := out.AuthorizationData[0]
		token := aws.ToString(data.AuthorizationToken)
		require.NotEmpty(t, token)
		assert.True(t, strings.HasPrefix(aws.ToString(data.ProxyEndpoint), "http"))
		decoded, err := base64.StdEncoding.DecodeString(token)
		require.NoError(t, err)
		assert.True(t, strings.HasPrefix(string(decoded), "AWS:"), "decoded token must start with AWS:")
	})

	t.Run("ListImagesEmpty", func(t *testing.T) {
		out, err := svc.ListImages(ctx, &ecr.ListImagesInput{
			RepositoryName: aws.String(repoName),
		})
		require.NoError(t, err)
		assert.Empty(t, out.ImageIds)
	})

	t.Run("PutImageTagMutability", func(t *testing.T) {
		out, err := svc.PutImageTagMutability(ctx, &ecr.PutImageTagMutabilityInput{
			RepositoryName:     aws.String(repoName),
			ImageTagMutability: ecrtypes.ImageTagMutabilityImmutable,
		})
		require.NoError(t, err)
		assert.Equal(t, ecrtypes.ImageTagMutabilityImmutable, out.ImageTagMutability)
	})

	t.Run("LifecyclePolicyRoundTrip", func(t *testing.T) {
		policy := `{"rules":[{"rulePriority":1,"selection":{"tagStatus":"untagged","countType":"imageCountMoreThan","countNumber":5},"action":{"type":"expire"}}]}`
		_, err := svc.PutLifecyclePolicy(ctx, &ecr.PutLifecyclePolicyInput{
			RepositoryName:      aws.String(repoName),
			LifecyclePolicyText: aws.String(policy),
		})
		require.NoError(t, err)
		got, err := svc.GetLifecyclePolicy(ctx, &ecr.GetLifecyclePolicyInput{
			RepositoryName: aws.String(repoName),
		})
		require.NoError(t, err)
		assert.Equal(t, policy, aws.ToString(got.LifecyclePolicyText))
	})

	t.Run("RepositoryPolicyRoundTrip", func(t *testing.T) {
		policy := `{"Version":"2012-10-17","Statement":[{"Sid":"AllowAll","Effect":"Allow","Principal":"*","Action":"ecr:*"}]}`
		_, err := svc.SetRepositoryPolicy(ctx, &ecr.SetRepositoryPolicyInput{
			RepositoryName: aws.String(repoName),
			PolicyText:     aws.String(policy),
		})
		require.NoError(t, err)
		got, err := svc.GetRepositoryPolicy(ctx, &ecr.GetRepositoryPolicyInput{
			RepositoryName: aws.String(repoName),
		})
		require.NoError(t, err)
		assert.Equal(t, policy, aws.ToString(got.PolicyText))
	})

	t.Run("DeleteRepositoryForce", func(t *testing.T) {
		_, err := svc.DeleteRepository(ctx, &ecr.DeleteRepositoryInput{
			RepositoryName: aws.String(repoName),
			Force:          true,
		})
		require.NoError(t, err)
		_, err = svc.DescribeRepositories(ctx, &ecr.DescribeRepositoriesInput{
			RepositoryNames: []string{repoName},
		})
		require.Error(t, err)
		var notFound *ecrtypes.RepositoryNotFoundException
		assert.True(t, errors.As(err, &notFound), "expected RepositoryNotFoundException, got %T", err)
	})
}
