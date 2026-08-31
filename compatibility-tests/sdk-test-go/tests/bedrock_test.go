package tests

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/bedrock"
	"github.com/aws/aws-sdk-go-v2/service/bedrock/types"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBedrockBatchInference(t *testing.T) {
	ctx := context.Background()
	svc := testutil.BedrockClient()
	s3Client := testutil.S3Client()

	bucketName := fmt.Sprintf("go-bedrock-%d", time.Now().UnixMilli()%100000)

	_, err := s3Client.CreateBucket(ctx, &s3.CreateBucketInput{
		Bucket: aws.String(bucketName),
	})
	require.NoError(t, err, "setup: create S3 bucket")

	t.Cleanup(func() {
		list, err := s3Client.ListObjectsV2(ctx, &s3.ListObjectsV2Input{
			Bucket: aws.String(bucketName),
		})
		if err == nil {
			for _, obj := range list.Contents {
				_ = s3Client.DeleteObject(ctx, &s3.DeleteObjectInput{
					Bucket: aws.String(bucketName),
					Key:    obj.Key,
				})
			}
		}
		_ = s3Client.DeleteBucket(ctx, &s3.DeleteBucketInput{
			Bucket: aws.String(bucketName),
		})
	})

	t.Run("CreateAndGetModelInvocationJob", func(t *testing.T) {
		jobName := fmt.Sprintf("go-job-%d", time.Now().UnixMilli()%100000)
		inputUri := fmt.Sprintf("s3://%s/in.jsonl", bucketName)
		outputUri := fmt.Sprintf("s3://%s/out", bucketName)

		createOut, err := svc.CreateModelInvocationJob(ctx, &bedrock.CreateModelInvocationJobInput{
			JobName:  aws.String(jobName),
			ModelId:  aws.String("amazon.titan-text-express-v1"),
			RoleArn:  aws.String("arn:aws:iam::000000000000:role/BedrockBatchRole"),
			InputDataConfig: &types.ModelInvocationJobInputDataConfigMemberS3InputDataConfig{
				Value: types.ModelInvocationJobS3InputDataConfig{
					S3Uri: aws.String(inputUri),
				},
			},
			OutputDataConfig: &types.ModelInvocationJobOutputDataConfigMemberS3OutputDataConfig{
				Value: types.ModelInvocationJobS3OutputDataConfig{
					S3Uri: aws.String(outputUri),
				},
			},
			Tags: []types.Tag{
				{Key: aws.String("Environment"), Value: aws.String("testing")},
			},
		})
		require.NoError(t, err)
		require.NotNil(t, createOut.JobArn)
		assert.Contains(t, aws.ToString(createOut.JobArn), ":model-invocation-job/")

		getOut, err := svc.GetModelInvocationJob(ctx, &bedrock.GetModelInvocationJobInput{
			JobIdentifier: createOut.JobArn,
		})
		require.NoError(t, err)
		assert.Equal(t, aws.ToString(createOut.JobArn), aws.ToString(getOut.JobArn))
		assert.Equal(t, jobName, aws.ToString(getOut.JobName))
		assert.Equal(t, "amazon.titan-text-express-v1", aws.ToString(getOut.ModelId))
		assert.NotEmpty(t, string(getOut.Status))
	})

	t.Run("ListModelInvocationJobsWithFiltering", func(t *testing.T) {
		jobName1 := fmt.Sprintf("go-list-1-%d", time.Now().UnixMilli()%100000)
		jobName2 := fmt.Sprintf("go-list-2-%d", time.Now().UnixMilli()%100000)

		_, err := svc.CreateModelInvocationJob(ctx, &bedrock.CreateModelInvocationJobInput{
			JobName: aws.String(jobName1),
			ModelId: aws.String("amazon.titan-text-express-v1"),
			RoleArn: aws.String("arn:aws:iam::000000000000:role/BedrockBatchRole"),
			InputDataConfig: &types.ModelInvocationJobInputDataConfigMemberS3InputDataConfig{
				Value: types.ModelInvocationJobS3InputDataConfig{
					S3Uri: aws.String(fmt.Sprintf("s3://%s/1.jsonl", bucketName)),
				},
			},
			OutputDataConfig: &types.ModelInvocationJobOutputDataConfigMemberS3OutputDataConfig{
				Value: types.ModelInvocationJobS3OutputDataConfig{
					S3Uri: aws.String(fmt.Sprintf("s3://%s/out1", bucketName)),
				},
			},
		})
		require.NoError(t, err)

		_, err = svc.CreateModelInvocationJob(ctx, &bedrock.CreateModelInvocationJobInput{
			JobName: aws.String(jobName2),
			ModelId: aws.String("anthropic.claude-3-sonnet-20240229-v1:0"),
			RoleArn: aws.String("arn:aws:iam::000000000000:role/BedrockBatchRole"),
			InputDataConfig: &types.ModelInvocationJobInputDataConfigMemberS3InputDataConfig{
				Value: types.ModelInvocationJobS3InputDataConfig{
					S3Uri: aws.String(fmt.Sprintf("s3://%s/2.jsonl", bucketName)),
				},
			},
			OutputDataConfig: &types.ModelInvocationJobOutputDataConfigMemberS3OutputDataConfig{
				Value: types.ModelInvocationJobS3OutputDataConfig{
					S3Uri: aws.String(fmt.Sprintf("s3://%s/out2", bucketName)),
				},
			},
		})
		require.NoError(t, err)

		listOut, err := svc.ListModelInvocationJobs(ctx, &bedrock.ListModelInvocationJobsInput{})
		require.NoError(t, err)

		var jobNames []string
		for _, j := range listOut.InvocationJobSummaries {
			jobNames = append(jobNames, aws.ToString(j.JobName))
		}
		assert.Contains(t, jobNames, jobName1)
		assert.Contains(t, jobNames, jobName2)

		filteredOut, err := svc.ListModelInvocationJobs(ctx, &bedrock.ListModelInvocationJobsInput{
			NameContains: aws.String(jobName1),
		})
		require.NoError(t, err)

		var filteredNames []string
		for _, j := range filteredOut.InvocationJobSummaries {
			filteredNames = append(filteredNames, aws.ToString(j.JobName))
		}
		assert.Contains(t, filteredNames, jobName1)
		assert.NotContains(t, filteredNames, jobName2)
	})

	t.Run("StopModelInvocationJob", func(t *testing.T) {
		jobName := fmt.Sprintf("go-stop-%d", time.Now().UnixMilli()%100000)
		createOut, err := svc.CreateModelInvocationJob(ctx, &bedrock.CreateModelInvocationJobInput{
			JobName: aws.String(jobName),
			ModelId: aws.String("amazon.titan-text-express-v1"),
			RoleArn: aws.String("arn:aws:iam::000000000000:role/BedrockBatchRole"),
			InputDataConfig: &types.ModelInvocationJobInputDataConfigMemberS3InputDataConfig{
				Value: types.ModelInvocationJobS3InputDataConfig{
					S3Uri: aws.String(fmt.Sprintf("s3://%s/stop.jsonl", bucketName)),
				},
			},
			OutputDataConfig: &types.ModelInvocationJobOutputDataConfigMemberS3OutputDataConfig{
				Value: types.ModelInvocationJobS3OutputDataConfig{
					S3Uri: aws.String(fmt.Sprintf("s3://%s/stop-out", bucketName)),
				},
			},
		})
		require.NoError(t, err)

		_, err = svc.StopModelInvocationJob(ctx, &bedrock.StopModelInvocationJobInput{
			JobIdentifier: createOut.JobArn,
		})
		require.NoError(t, err)

		getOut, err := svc.GetModelInvocationJob(ctx, &bedrock.GetModelInvocationJobInput{
			JobIdentifier: createOut.JobArn,
		})
		require.NoError(t, err)
		assert.Contains(t, []string{"Stopping", "Stopped"}, string(getOut.Status))
		assert.NotNil(t, getOut.EndTime)
	})

	t.Run("BatchInferenceExecutionWithS3", func(t *testing.T) {
		inputKey := "prompts.jsonl"
		outputPrefix := fmt.Sprintf("go-batch-out-%d", time.Now().UnixMilli()%100000)
		inputUri := fmt.Sprintf("s3://%s/%s", bucketName, inputKey)
		outputUri := fmt.Sprintf("s3://%s/%s", bucketName, outputPrefix)

		jsonlContent := "{\"recordId\":\"rec-1\",\"modelInput\":{\"inputText\":\"Hello Bedrock\"}}\n{\"recordId\":\"rec-2\",\"modelInput\":{\"inputText\":\"How are you?\"}}"

		_, err := s3Client.PutObject(ctx, &s3.PutObjectInput{
			Bucket: aws.String(bucketName),
			Key:    aws.String(inputKey),
			Body:   bytes.NewReader([]byte(jsonlContent)),
		})
		require.NoError(t, err)

		jobName := fmt.Sprintf("go-s3-exec-%d", time.Now().UnixMilli()%100000)
		createOut, err := svc.CreateModelInvocationJob(ctx, &bedrock.CreateModelInvocationJobInput{
			JobName: aws.String(jobName),
			ModelId: aws.String("amazon.titan-text-express-v1"),
			RoleArn: aws.String("arn:aws:iam::000000000000:role/BedrockBatchRole"),
			InputDataConfig: &types.ModelInvocationJobInputDataConfigMemberS3InputDataConfig{
				Value: types.ModelInvocationJobS3InputDataConfig{
					S3Uri: aws.String(inputUri),
				},
			},
			OutputDataConfig: &types.ModelInvocationJobOutputDataConfigMemberS3OutputDataConfig{
				Value: types.ModelInvocationJobS3OutputDataConfig{
					S3Uri: aws.String(outputUri),
				},
			},
		})
		require.NoError(t, err)

		jobArn := aws.ToString(createOut.JobArn)
		jobId := jobArn[strings.LastIndex(jobArn, "/")+1:]

		getOut, err := svc.GetModelInvocationJob(ctx, &bedrock.GetModelInvocationJobInput{
			JobIdentifier: createOut.JobArn,
		})
		require.NoError(t, err)
		assert.Equal(t, string(types.ModelInvocationJobStatusCompleted), string(getOut.Status))

		// Check output JSONL in S3
		outputKey := fmt.Sprintf("%s/%s/%s.out", outputPrefix, jobId, inputKey)
		getObjOut, err := s3Client.GetObject(ctx, &s3.GetObjectInput{
			Bucket: aws.String(bucketName),
			Key:    aws.String(outputKey),
		})
		require.NoError(t, err)
		outBytes, err := io.ReadAll(getObjOut.Body)
		require.NoError(t, err)
		_ = getObjOut.Body.Close()

		lines := strings.Split(strings.TrimSpace(string(outBytes)), "\n")
		assert.Len(t, lines, 2)

		var rec1 map[string]interface{}
		err = json.Unmarshal([]byte(lines[0]), &rec1)
		require.NoError(t, err)
		assert.Equal(t, "rec-1", rec1["recordId"])
		assert.NotNil(t, rec1["modelOutput"])

		// Check manifest.json.out in S3
		manifestKey := fmt.Sprintf("%s/%s/manifest.json.out", outputPrefix, jobId)
		getManifestOut, err := s3Client.GetObject(ctx, &s3.GetObjectInput{
			Bucket: aws.String(bucketName),
			Key:    aws.String(manifestKey),
		})
		require.NoError(t, err)
		manifestBytes, err := io.ReadAll(getManifestOut.Body)
		require.NoError(t, err)
		_ = getManifestOut.Body.Close()

		var manifest map[string]interface{}
		err = json.Unmarshal(manifestBytes, &manifest)
		require.NoError(t, err)

		assert.Equal(t, float64(2), manifest["totalRecordCount"])
		assert.Equal(t, float64(2), manifest["processedRecordCount"])
		assert.Equal(t, float64(2), manifest["successRecordCount"])
		assert.Equal(t, float64(0), manifest["errorRecordCount"])
	})
}
