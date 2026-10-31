package tests

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	awshttp "github.com/aws/aws-sdk-go-v2/aws/transport/http"
	"github.com/aws/aws-sdk-go-v2/service/iam"
	"github.com/aws/aws-sdk-go-v2/service/lambdaweb"
	webtypes "github.com/aws/aws-sdk-go-v2/service/lambdaweb/types"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	webWait        = 2 * time.Minute
	webLambdaTrust = `{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}`
)

// One function through its life. The subtests run in order and share it.
func TestLambdaWeb(t *testing.T) {
	ctx := context.Background()
	web := testutil.LambdaWebClient()
	s3Client := testutil.S3Client()
	iamClient := testutil.IAMClient()
	region := testutil.Config().Region
	suffix := strconv.FormatInt(time.Now().UnixNano(), 36)
	roleName := "go-web-role-" + suffix
	bucket := "go-web-code-" + suffix
	functionName := "go-web-fn-" + suffix

	createdRole, err := iamClient.CreateRole(ctx, &iam.CreateRoleInput{
		RoleName:                 aws.String(roleName),
		AssumeRolePolicyDocument: aws.String(webLambdaTrust),
	})
	require.NoError(t, err)
	role := aws.ToString(createdRole.Role.Arn)
	t.Cleanup(func() {
		_, err := iamClient.DeleteRole(ctx, &iam.DeleteRoleInput{RoleName: aws.String(roleName)})
		assert.NoError(t, err)
	})

	_, err = s3Client.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucket)})
	require.NoError(t, err)
	t.Cleanup(func() {
		versions, err := s3Client.ListObjectVersions(ctx, &s3.ListObjectVersionsInput{Bucket: aws.String(bucket)})
		if !assert.NoError(t, err) {
			return
		}
		for _, version := range versions.Versions {
			_, err := s3Client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(bucket), Key: version.Key, VersionId: version.VersionId})
			assert.NoError(t, err)
		}
		_, err = s3Client.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucket)})
		assert.NoError(t, err)
	})
	// AWS takes code only from a versioned bucket that lets Lambda read it.
	_, err = s3Client.PutBucketVersioning(ctx, &s3.PutBucketVersioningInput{
		Bucket:                  aws.String(bucket),
		VersioningConfiguration: &s3types.VersioningConfiguration{Status: s3types.BucketVersioningStatusEnabled},
	})
	require.NoError(t, err)
	_, err = s3Client.PutBucketPolicy(ctx, &s3.PutBucketPolicyInput{
		Bucket: aws.String(bucket),
		Policy: aws.String(`{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},` +
			`"Action":["s3:GetObject","s3:GetObjectVersion"],"Resource":"arn:aws:s3:::` + bucket + `/*"}]}`),
	})
	require.NoError(t, err)
	_, err = s3Client.PutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(bucket), Key: aws.String("app.zip"), Body: bytes.NewReader(testutil.MinimalZip())})
	require.NoError(t, err)

	t.Cleanup(func() {
		_, err := web.DeleteWebFunction(ctx, &lambdaweb.DeleteWebFunctionInput{FunctionName: aws.String(functionName)})
		// The last subtest deletes the function itself.
		var notFound *webtypes.ResourceNotFoundException
		if err != nil && !errors.As(err, &notFound) {
			t.Errorf("delete web function: %v", err)
		}
	})

	buildConfig := func() *webtypes.BuildConfig {
		return &webtypes.BuildConfig{
			CodeConfig:    &webtypes.CodeConfig{S3Object: &webtypes.S3Object{Bucket: aws.String(bucket), Key: aws.String("app.zip")}},
			RuntimeConfig: &webtypes.RuntimeConfig{Runtime: aws.String("nodejs24.x")},
		}
	}
	devEndpoint := &lambdaweb.GetWebFunctionEndpointInput{FunctionName: aws.String(functionName), EndpointName: aws.String("dev")}
	var functionArn, firstRevision, secondRevision string

	t.Run("CreateWebFunctionReportsPendingChildren", func(t *testing.T) {
		created, err := web.CreateWebFunction(ctx, &lambdaweb.CreateWebFunctionInput{
			FunctionName: aws.String(functionName),
			RevisionConfig: &webtypes.RevisionConfig{
				BuildConfig:   buildConfig(),
				ServiceConfig: &webtypes.ServiceConfig{ExecutionRoleArn: aws.String(role)},
			},
			EndpointConfig: &webtypes.EndpointConfig{
				EndpointName:       aws.String("dev"),
				EndpointType:       webtypes.EndpointTypeHomeRegion,
				AuthType:           webtypes.AuthTypeApplicationManaged,
				AutoDeploymentMode: webtypes.AutoDeploymentModeLatestRevision,
			},
			Tags: map[string]string{"env": "sdk"},
		})
		require.NoError(t, err)
		require.NotNil(t, created.Revision)
		require.NotNil(t, created.Endpoint)
		functionArn = aws.ToString(created.FunctionArn)
		firstRevision = aws.ToString(created.Revision.RevisionId)
		assert.True(t, strings.HasSuffix(functionArn, ":web-function/"+functionName), functionArn)
		assert.Equal(t, webtypes.FunctionStateActive, created.State)
		assert.Equal(t, webtypes.RevisionStatePending, created.Revision.State)
		assert.Equal(t, webtypes.EndpointStatePending, created.Endpoint.State)
		assert.Contains(t, aws.ToString(created.Endpoint.DomainName), ".lambda-web.")
		assert.NotNil(t, created.CreatedAt)

		require.NoError(t, lambdaweb.NewWebFunctionEndpointActiveWaiter(web).Wait(ctx, devEndpoint, webWait))
		endpoint, err := web.GetWebFunctionEndpoint(ctx, devEndpoint)
		require.NoError(t, err)
		assert.Equal(t, webtypes.EndpointStateActive, endpoint.State)
		assert.Equal(t, []string{firstRevision}, weightRevisionIds(endpoint.RevisionWeights))
		assert.Contains(t, endpoint.RegionalEndpoints, region)
		assert.Empty(t, endpoint.UpdateStatus)
	})

	t.Run("FunctionsAreAddressableByNameAndArn", func(t *testing.T) {
		got, err := web.GetWebFunction(ctx, &lambdaweb.GetWebFunctionInput{FunctionName: aws.String(functionArn)})
		require.NoError(t, err)
		assert.Equal(t, functionName, aws.ToString(got.FunctionName))
		var names []string
		pages := lambdaweb.NewListWebFunctionsPaginator(web, &lambdaweb.ListWebFunctionsInput{})
		for pages.HasMorePages() {
			page, err := pages.NextPage(ctx)
			require.NoError(t, err)
			for _, function := range page.Functions {
				names = append(names, aws.ToString(function.FunctionName))
			}
		}
		assert.Contains(t, names, functionName)
		settings, err := web.GetWebAccountSettings(ctx, &lambdaweb.GetWebAccountSettingsInput{})
		require.NoError(t, err)
		assert.Equal(t, int32(10), aws.ToInt32(settings.AccountQuotas.MaxEndpointsPerFunction))

		otherRegion := "eu-west-1"
		if region == "eu-west-1" {
			otherRegion = "us-west-2"
		}
		foreignArn := strings.Replace(functionArn, ":"+region+":", ":"+otherRegion+":", 1)
		_, err = web.GetWebFunction(ctx, &lambdaweb.GetWebFunctionInput{FunctionName: aws.String(foreignArn)})
		assertWebRefusal[*webtypes.ValidationException](t, err,
			"The operation is only allowed in the function's home region "+otherRegion+".")
		_, err = web.GetWebFunction(ctx, &lambdaweb.GetWebFunctionInput{FunctionName: aws.String("go-no-such-web-fn")})
		assertWebRefusal[*webtypes.ResourceNotFoundException](t, err, "does not exist.")
	})

	t.Run("NewRevisionMovesLatestRevisionEndpoint", func(t *testing.T) {
		revision, err := web.CreateWebFunctionRevision(ctx, &lambdaweb.CreateWebFunctionRevisionInput{
			FunctionName: aws.String(functionName),
			Description:  aws.String("second"),
			BuildConfig:  buildConfig(),
			ServiceConfig: &webtypes.ServiceConfig{
				ExecutionRoleArn:     aws.String(role),
				TimeoutSeconds:       aws.Int32(60),
				EnvironmentVariables: map[string]string{"APP_VERSION": "2"},
			},
		})
		require.NoError(t, err)
		secondRevision = aws.ToString(revision.RevisionId)
		assert.Equal(t, webtypes.RevisionStatePending, revision.State)
		assert.Equal(t, int32(60), aws.ToInt32(revision.ServiceConfig.TimeoutSeconds))
		assert.Equal(t, int32(64), aws.ToInt32(revision.ServiceConfig.MaxConcurrencyPerEnvironment))
		assert.Equal(t, "/aws/lambda/web/"+functionName, aws.ToString(revision.ServiceConfig.TelemetryConfig.LoggingConfig.LogGroup))

		require.NoError(t, lambdaweb.NewWebFunctionRevisionActiveWaiter(web).Wait(ctx, &lambdaweb.GetWebFunctionRevisionInput{
			FunctionName: aws.String(functionName),
			RevisionId:   aws.String(secondRevision),
		}, webWait))
		revisions, err := web.ListWebFunctionRevisions(ctx, &lambdaweb.ListWebFunctionRevisionsInput{FunctionName: aws.String(functionName)})
		require.NoError(t, err)
		var revisionIds []string
		for _, summary := range revisions.Revisions {
			revisionIds = append(revisionIds, aws.ToString(summary.RevisionId))
		}
		assert.Equal(t, []string{secondRevision, firstRevision}, revisionIds)
		endpoint, err := web.GetWebFunctionEndpoint(ctx, devEndpoint)
		require.NoError(t, err)
		assert.Equal(t, []string{secondRevision}, weightRevisionIds(endpoint.RevisionWeights))
	})

	t.Run("WeightedRoutingAndTheInUseGuard", func(t *testing.T) {
		weights := []webtypes.RevisionWeight{
			{RevisionId: aws.String(firstRevision), Weight: aws.Int32(90)},
			{RevisionId: aws.String(secondRevision), Weight: aws.Int32(10)},
		}
		update, err := web.UpdateWebFunctionEndpoint(ctx, &lambdaweb.UpdateWebFunctionEndpointInput{
			FunctionName:       aws.String(functionName),
			EndpointName:       aws.String("dev"),
			AutoDeploymentMode: webtypes.AutoDeploymentModeDisabled,
			RevisionWeights:    weights,
		})
		require.NoError(t, err)
		assert.Equal(t, webtypes.EndpointUpdateStatusInProgress, update.UpdateStatus)
		assert.Len(t, update.RevisionWeights, 2)

		require.NoError(t, lambdaweb.NewWebFunctionEndpointUpdatedWaiter(web).Wait(ctx, devEndpoint, webWait))
		endpoint, err := web.GetWebFunctionEndpoint(ctx, devEndpoint)
		require.NoError(t, err)
		assert.Equal(t, webtypes.EndpointUpdateStatusSuccessful, endpoint.UpdateStatus)
		assert.ElementsMatch(t, weights, endpoint.RevisionWeights)

		_, err = web.DeleteWebFunctionRevision(ctx, &lambdaweb.DeleteWebFunctionRevisionInput{
			FunctionName: aws.String(functionName),
			RevisionId:   aws.String(firstRevision),
		})
		assertWebRefusal[*webtypes.ConflictException](t, err, "the endpoints [dev] are using it")
		_, err = web.UpdateWebFunctionEndpoint(ctx, &lambdaweb.UpdateWebFunctionEndpointInput{
			FunctionName:    aws.String(functionName),
			EndpointName:    aws.String("dev"),
			RevisionWeights: []webtypes.RevisionWeight{{RevisionId: aws.String("rev-zzzzz"), Weight: aws.Int32(100)}},
		})
		assertWebRefusal[*webtypes.ResourceNotFoundException](t, err, "The revision rev-zzzzz does not exist.")
	})

	t.Run("ValidationErrorsUseTheAwsWording", func(t *testing.T) {
		_, err := web.CreateWebFunctionRevision(ctx, &lambdaweb.CreateWebFunctionRevisionInput{
			FunctionName:  aws.String(functionName),
			BuildConfig:   buildConfig(),
			ServiceConfig: &webtypes.ServiceConfig{ExecutionRoleArn: aws.String(role), TimeoutSeconds: aws.Int32(2)},
		})
		assertWebRefusal[*webtypes.ValidationException](t, err,
			"1 validation error detected: Value '2' at 'serviceConfig.timeoutSeconds' failed to satisfy constraint:"+
				" Member must have value greater than or equal to 3")
		_, err = web.GetWebFunctionRevision(ctx, &lambdaweb.GetWebFunctionRevisionInput{
			FunctionName: aws.String(functionName),
			RevisionId:   aws.String("nope"),
		})
		assertWebRefusal[*webtypes.ValidationException](t, err, "Invalid revision id or ARN 'nope'")
		_, err = web.CreateWebFunctionEndpoint(ctx, &lambdaweb.CreateWebFunctionEndpointInput{
			FunctionName: aws.String(functionName),
			EndpointName: aws.String("prod"),
			EndpointType: webtypes.EndpointTypeHomeRegion,
			AuthType:     webtypes.AuthTypeApplicationManaged,
		})
		assertWebRefusal[*webtypes.ValidationException](t, err,
			"The revisionWeights must be specified when the autoDeploymentMode is Disabled.")

		missingRole := strings.Replace(role, roleName, roleName+"-missing", 1)
		_, err = web.CreateWebFunctionRevision(ctx, &lambdaweb.CreateWebFunctionRevisionInput{
			FunctionName:  aws.String(functionName),
			BuildConfig:   buildConfig(),
			ServiceConfig: &webtypes.ServiceConfig{ExecutionRoleArn: aws.String(missingRole)},
		})
		assertWebRefusal[*webtypes.ValidationException](t, err,
			"The execution role "+missingRole+" cannot be assumed by lambda.amazonaws.com."+
				" Verify its trust policy. If the role was just created, retry in a few seconds.")
		_, err = web.CreateWebFunctionRevision(ctx, &lambdaweb.CreateWebFunctionRevisionInput{
			FunctionName:  aws.String(functionName),
			BuildConfig:   buildConfig(),
			ServiceConfig: &webtypes.ServiceConfig{ExecutionRoleArn: aws.String("arn:aws:iam::111122223333:role/" + roleName)},
		})
		assertWebRefusal[*webtypes.AccessDeniedException](t, err, "Cross-account pass role is not allowed.")
		var response *awshttp.ResponseError
		require.True(t, errors.As(err, &response), "expected a response error, got %T", err)
		assert.Equal(t, 403, response.HTTPStatusCode())
	})

	t.Run("TagsAndTheResourcePolicy", func(t *testing.T) {
		_, err := web.TagResource(ctx, &lambdaweb.TagResourceInput{Resource: aws.String(functionArn), Tags: map[string]string{"team": "web"}})
		require.NoError(t, err)
		tags, err := web.ListTags(ctx, &lambdaweb.ListTagsInput{Resource: aws.String(functionArn)})
		require.NoError(t, err)
		assert.Equal(t, map[string]string{"env": "sdk", "team": "web"}, tags.Tags)
		_, err = web.UntagResource(ctx, &lambdaweb.UntagResourceInput{Resource: aws.String(functionArn), TagKeys: []string{"env"}})
		require.NoError(t, err)
		tags, err = web.ListTags(ctx, &lambdaweb.ListTagsInput{Resource: aws.String(functionArn)})
		require.NoError(t, err)
		assert.Equal(t, map[string]string{"team": "web"}, tags.Tags)

		_, err = web.PutResourcePolicy(ctx, &lambdaweb.PutResourcePolicyInput{
			ResourceArn: aws.String(functionArn),
			Policy:      aws.String(webInvokePolicy(t, "*", functionArn)),
		})
		assertWebRefusal[*webtypes.ValidationException](t, err, "Lambda blocked the creation of this policy")
		_, err = web.PutResourcePolicy(ctx, &lambdaweb.PutResourcePolicyInput{ResourceArn: aws.String(functionArn), Policy: aws.String("{}")})
		assertWebRefusal[*webtypes.ValidationException](t, err, "Policy has syntax errors.")

		account := strings.Split(role, ":")[4]
		policy := webInvokePolicy(t, map[string]string{"AWS": "arn:aws:iam::" + account + ":root"}, functionArn)
		put, err := web.PutResourcePolicy(ctx, &lambdaweb.PutResourcePolicyInput{ResourceArn: aws.String(functionArn), Policy: aws.String(policy)})
		require.NoError(t, err)
		// json.Marshal sorts the keys. Lambda writes them back in its own order.
		kept := `{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::` + account + `:root"},` +
			`"Action":"lambda:InvokeWebFunctionEndpoint","Resource":"` + functionArn + `/endpoint/*"}]}`
		assert.Equal(t, kept, aws.ToString(put.Policy))
		assert.Len(t, aws.ToString(put.RevisionId), 36)
		got, err := web.GetResourcePolicy(ctx, &lambdaweb.GetResourcePolicyInput{ResourceArn: aws.String(functionArn)})
		require.NoError(t, err)
		assert.Equal(t, kept, aws.ToString(got.Policy))
		assert.Equal(t, aws.ToString(put.RevisionId), aws.ToString(got.RevisionId))
		_, err = web.DeleteResourcePolicy(ctx, &lambdaweb.DeleteResourcePolicyInput{ResourceArn: aws.String(functionArn), RevisionId: put.RevisionId})
		require.NoError(t, err)
		_, err = web.GetResourcePolicy(ctx, &lambdaweb.GetResourcePolicyInput{ResourceArn: aws.String(functionArn)})
		assertWebRefusal[*webtypes.ResourceNotFoundException](t, err, "The function does not have a policy.")
	})

	t.Run("DeleteRemovesTheFunction", func(t *testing.T) {
		function := &lambdaweb.GetWebFunctionInput{FunctionName: aws.String(functionName)}
		_, err := web.DeleteWebFunction(ctx, &lambdaweb.DeleteWebFunctionInput{FunctionName: aws.String(functionName)})
		require.NoError(t, err)
		require.NoError(t, lambdaweb.NewWebFunctionDeletedWaiter(web).Wait(ctx, function, webWait))
		_, err = web.GetWebFunction(ctx, function)
		assertWebRefusal[*webtypes.ResourceNotFoundException](t, err, "does not exist.")
	})
}

func assertWebRefusal[T smithy.APIError](t *testing.T, err error, message string) {
	t.Helper()
	var refusal T
	if assert.True(t, errors.As(err, &refusal), "expected %T, got %T: %v", refusal, err, err) {
		assert.Contains(t, refusal.ErrorMessage(), message)
	}
}

func webInvokePolicy(t *testing.T, principal any, functionArn string) string {
	policy, err := json.Marshal(map[string]any{
		"Version": "2012-10-17",
		"Statement": []map[string]any{{
			"Effect":    "Allow",
			"Principal": principal,
			"Action":    "lambda:InvokeWebFunctionEndpoint",
			"Resource":  functionArn + "/endpoint/*",
		}},
	})
	require.NoError(t, err)
	return string(policy)
}

func weightRevisionIds(weights []webtypes.RevisionWeight) []string {
	var ids []string
	for _, weight := range weights {
		ids = append(ids, aws.ToString(weight.RevisionId))
	}
	return ids
}
