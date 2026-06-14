package tests

import (
	"context"
	"fmt"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/glue"
	gluetypes "github.com/aws/aws-sdk-go-v2/service/glue/types"
	"github.com/aws/aws-sdk-go-v2/service/resourcegroupstaggingapi"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGlueCreateDatabaseTagsAreVisibleViaResourceGroupsTaggingAPI(t *testing.T) {
	ctx := context.Background()
	glueClient := testutil.GlueClient()
	taggingClient := testutil.ResourceGroupsTaggingAPIClient()
	databaseName := fmt.Sprintf("go-glue-db-%d", time.Now().UnixNano())
	databaseArn := fmt.Sprintf("arn:aws:glue:us-east-1:000000000000:database/%s", databaseName)

	t.Cleanup(func() {
		_, _ = glueClient.DeleteDatabase(ctx, &glue.DeleteDatabaseInput{Name: aws.String(databaseName)})
	})

	_, err := glueClient.CreateDatabase(ctx, &glue.CreateDatabaseInput{
		DatabaseInput: &gluetypes.DatabaseInput{
			Name:        aws.String(databaseName),
			Description: aws.String("go glue database tags"),
		},
		Tags: map[string]string{
			"Environment": "dev",
			"Project":     "project1",
		},
	})
	require.NoError(t, err)

	response, err := taggingClient.GetResources(ctx, &resourcegroupstaggingapi.GetResourcesInput{
		ResourceARNList: []string{databaseArn},
	})
	require.NoError(t, err)
	require.Len(t, response.ResourceTagMappingList, 1)

	mapping := response.ResourceTagMappingList[0]
	assert.Equal(t, databaseArn, aws.ToString(mapping.ResourceARN))

	tags := map[string]string{}
	for _, tag := range mapping.Tags {
		tags[aws.ToString(tag.Key)] = aws.ToString(tag.Value)
	}
	assert.Equal(t, "dev", tags["Environment"])
	assert.Equal(t, "project1", tags["Project"])
}
