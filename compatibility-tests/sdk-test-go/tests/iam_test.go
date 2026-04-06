package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/iam"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestIAM(t *testing.T) {
	ctx := context.Background()
	svc := testutil.IAMClient()
	roleName := "go-test-role"
	assumePolicy := `{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}`

	t.Cleanup(func() {
		svc.DeleteRole(ctx, &iam.DeleteRoleInput{RoleName: aws.String(roleName)})
	})

	t.Run("CreateRole", func(t *testing.T) {
		_, err := svc.CreateRole(ctx, &iam.CreateRoleInput{
			RoleName:                 aws.String(roleName),
			AssumeRolePolicyDocument: aws.String(assumePolicy),
		})
		require.NoError(t, err)
	})

	t.Run("GetRole", func(t *testing.T) {
		r, err := svc.GetRole(ctx, &iam.GetRoleInput{RoleName: aws.String(roleName)})
		require.NoError(t, err)
		assert.Equal(t, roleName, aws.ToString(r.Role.RoleName))
	})

	t.Run("ListRoles", func(t *testing.T) {
		r, err := svc.ListRoles(ctx, &iam.ListRolesInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Roles)
	})

	t.Run("DeleteRole", func(t *testing.T) {
		_, err := svc.DeleteRole(ctx, &iam.DeleteRoleInput{RoleName: aws.String(roleName)})
		require.NoError(t, err)
	})
}
