package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCognitoDescribeUserPoolStandardAttributes(t *testing.T) {
	ctx := context.Background()
	svc := testutil.CognitoClient()

	poolName := "go-test-cognito-standard-attrs"
	created, err := svc.CreateUserPool(ctx, &cognitoidentityprovider.CreateUserPoolInput{
		PoolName: aws.String(poolName),
	})
	require.NoError(t, err)
	poolID := created.UserPool.Id

	t.Cleanup(func() {
		svc.DeleteUserPool(ctx, &cognitoidentityprovider.DeleteUserPoolInput{
			UserPoolId: poolID,
		})
	})

	resp, err := svc.DescribeUserPool(ctx, &cognitoidentityprovider.DescribeUserPoolInput{
		UserPoolId: poolID,
	})
	require.NoError(t, err)

	schema := resp.UserPool.SchemaAttributes
	assert.Len(t, schema, 20, "DescribeUserPool must return all 20 standard Cognito attributes")

	names := make(map[string]bool, len(schema))
	for _, attr := range schema {
		names[aws.ToString(attr.Name)] = true
	}

	expected := []string{
		"sub", "name", "given_name", "family_name", "middle_name", "nickname",
		"preferred_username", "profile", "picture", "website", "email",
		"email_verified", "gender", "birthdate", "zoneinfo", "locale",
		"phone_number", "phone_number_verified", "address", "updated_at",
	}
	for _, attr := range expected {
		assert.True(t, names[attr], "missing standard attribute: %s", attr)
	}

	// spot-check sub
	for _, attr := range schema {
		if aws.ToString(attr.Name) == "sub" {
			assert.True(t, aws.ToBool(attr.Required), "sub must be Required")
			assert.False(t, aws.ToBool(attr.Mutable), "sub must not be Mutable")
		}
	}
}
