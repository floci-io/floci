package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider"
	"github.com/aws/aws-sdk-go-v2/service/cognitoidentityprovider/types"
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

// The four Require* members of PasswordPolicyType are unboxed booleans in the Cognito model, so
// this SDK cannot put them on the wire when they are false: the generated serializer emits each
// one under `if v.RequireLowercase != false`. The request below therefore carries nothing but
// MinimumLength, which is exactly what terraform-provider-aws sends for a password_policy block
// with require_* = false. A pool that answered those members as true made the first plan after
// apply report drift on all four.
func TestCognitoCreateUserPoolLeavesUnsetPasswordRequirementsOff(t *testing.T) {
	ctx := context.Background()
	svc := testutil.CognitoClient()

	created, err := svc.CreateUserPool(ctx, &cognitoidentityprovider.CreateUserPoolInput{
		PoolName: aws.String("go-test-cognito-password-policy"),
		Policies: &types.UserPoolPolicyType{
			PasswordPolicy: &types.PasswordPolicyType{
				MinimumLength:    aws.Int32(7),
				RequireLowercase: false,
				RequireNumbers:   false,
				RequireSymbols:   false,
				RequireUppercase: false,
			},
		},
	})
	require.NoError(t, err)
	poolID := created.UserPool.Id

	t.Cleanup(func() {
		svc.DeleteUserPool(ctx, &cognitoidentityprovider.DeleteUserPoolInput{
			UserPoolId: poolID,
		})
	})

	described, err := svc.DescribeUserPool(ctx, &cognitoidentityprovider.DescribeUserPoolInput{
		UserPoolId: poolID,
	})
	require.NoError(t, err)

	policy := described.UserPool.Policies.PasswordPolicy
	require.NotNil(t, policy)
	assert.Equal(t, int32(7), aws.ToInt32(policy.MinimumLength), "MinimumLength must survive the round trip")
	assert.False(t, policy.RequireLowercase, "an unset RequireLowercase must not come back enabled")
	assert.False(t, policy.RequireNumbers, "an unset RequireNumbers must not come back enabled")
	assert.False(t, policy.RequireSymbols, "an unset RequireSymbols must not come back enabled")
	assert.False(t, policy.RequireUppercase, "an unset RequireUppercase must not come back enabled")
}
