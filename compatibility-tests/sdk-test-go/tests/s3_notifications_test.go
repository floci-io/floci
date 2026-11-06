package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	s3types "github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestS3Notifications(t *testing.T) {
	ctx := context.Background()
	s3Svc := testutil.S3Client()
	sqsSvc := testutil.SQSClient()
	snsSvc := testutil.SNSClient()

	queueName := "s3-notif-filter-queue"
	topicName := "s3-notif-filter-topic"
	bucketName := "s3-notif-filter-bucket"
	queueArn := "arn:aws:sqs:us-east-1:000000000000:" + queueName
	var topicArn string
	var queueURL string

	t.Cleanup(func() {
		s3Svc.DeleteBucket(ctx, &s3.DeleteBucketInput{Bucket: aws.String(bucketName)})
		if queueURL != "" {
			sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(queueURL)})
		}
		if topicArn != "" {
			snsSvc.DeleteTopic(ctx, &sns.DeleteTopicInput{TopicArn: aws.String(topicArn)})
		}
	})

	t.Run("Setup", func(t *testing.T) {
		// Create SQS queue
		qr, err := sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(queueName)})
		require.NoError(t, err)
		queueURL = aws.ToString(qr.QueueUrl)

		// Create SNS topic
		tr, err := snsSvc.CreateTopic(ctx, &sns.CreateTopicInput{Name: aws.String(topicName)})
		require.NoError(t, err)
		topicArn = aws.ToString(tr.TopicArn)

		// Create S3 bucket
		_, err = s3Svc.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(bucketName)})
		require.NoError(t, err)
	})

	t.Run("PutBucketNotificationConfiguration", func(t *testing.T) {
		_, err := s3Svc.PutBucketNotificationConfiguration(ctx, &s3.PutBucketNotificationConfigurationInput{
			Bucket: aws.String(bucketName),
			NotificationConfiguration: &s3types.NotificationConfiguration{
				QueueConfigurations: []s3types.QueueConfiguration{
					{
						Id:       aws.String("sqs-filtered"),
						QueueArn: aws.String(queueArn),
						Events:   []s3types.Event{s3types.EventS3ObjectCreated},
						Filter: &s3types.NotificationConfigurationFilter{
							Key: &s3types.S3KeyFilter{
								FilterRules: []s3types.FilterRule{
									{Name: s3types.FilterRuleNamePrefix, Value: aws.String("incoming/")},
									{Name: s3types.FilterRuleNameSuffix, Value: aws.String(".csv")},
								},
							},
						},
					},
				},
				TopicConfigurations: []s3types.TopicConfiguration{
					{
						Id:       aws.String("sns-filtered"),
						TopicArn: aws.String(topicArn),
						Events:   []s3types.Event{s3types.EventS3ObjectRemoved},
						Filter: &s3types.NotificationConfigurationFilter{
							Key: &s3types.S3KeyFilter{
								FilterRules: []s3types.FilterRule{
									{Name: s3types.FilterRuleNamePrefix, Value: aws.String("")},
									{Name: s3types.FilterRuleNameSuffix, Value: aws.String(".txt")},
								},
							},
						},
					},
				},
			},
		})
		require.NoError(t, err)
	})

	t.Run("GetBucketNotificationConfiguration", func(t *testing.T) {
		r, err := s3Svc.GetBucketNotificationConfiguration(ctx, &s3.GetBucketNotificationConfigurationInput{
			Bucket: aws.String(bucketName),
		})
		require.NoError(t, err)

		// Assert queue config
		require.NotEmpty(t, r.QueueConfigurations)
		assert.Equal(t, queueArn, aws.ToString(r.QueueConfigurations[0].QueueArn))
		require.NotNil(t, r.QueueConfigurations[0].Filter)
		require.NotNil(t, r.QueueConfigurations[0].Filter.Key)
		assert.Len(t, r.QueueConfigurations[0].Filter.Key.FilterRules, 2)

		// Assert topic config
		require.NotEmpty(t, r.TopicConfigurations)
		assert.Equal(t, topicArn, aws.ToString(r.TopicConfigurations[0].TopicArn))
		require.NotNil(t, r.TopicConfigurations[0].Filter)
		require.NotNil(t, r.TopicConfigurations[0].Filter.Key)
		assert.Len(t, r.TopicConfigurations[0].Filter.Key.FilterRules, 2)
	})
}
