package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSNS(t *testing.T) {
	ctx := context.Background()
	svc := testutil.SNSClient()
	sqsSvc := testutil.SQSClient()
	topicName := "go-test-topic"
	queueName := "go-sns-target"
	var topicARN string
	var queueURL string
	var subARN string

	t.Cleanup(func() {
		if subARN != "" {
			svc.Unsubscribe(ctx, &sns.UnsubscribeInput{SubscriptionArn: aws.String(subARN)})
		}
		if topicARN != "" {
			svc.DeleteTopic(ctx, &sns.DeleteTopicInput{TopicArn: aws.String(topicARN)})
		}
		if queueURL != "" {
			sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(queueURL)})
		}
	})

	t.Run("CreateTopic", func(t *testing.T) {
		r, err := svc.CreateTopic(ctx, &sns.CreateTopicInput{Name: aws.String(topicName)})
		require.NoError(t, err)
		topicARN = aws.ToString(r.TopicArn)
		assert.NotEmpty(t, topicARN)
	})

	t.Run("ListTopics", func(t *testing.T) {
		r, err := svc.ListTopics(ctx, &sns.ListTopicsInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Topics)
	})

	t.Run("GetTopicAttributes", func(t *testing.T) {
		r, err := svc.GetTopicAttributes(ctx, &sns.GetTopicAttributesInput{TopicArn: aws.String(topicARN)})
		require.NoError(t, err)
		assert.Equal(t, topicARN, r.Attributes["TopicArn"])
	})

	t.Run("Subscribe", func(t *testing.T) {
		// Create target SQS queue
		qr, err := sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(queueName)})
		require.NoError(t, err)
		queueURL = aws.ToString(qr.QueueUrl)

		// Get queue ARN
		attr, err := sqsSvc.GetQueueAttributes(ctx, &sqs.GetQueueAttributesInput{
			QueueUrl:       aws.String(queueURL),
			AttributeNames: []sqstypes.QueueAttributeName{sqstypes.QueueAttributeNameAll},
		})
		require.NoError(t, err)
		queueARN := attr.Attributes["QueueArn"]

		r, err := svc.Subscribe(ctx, &sns.SubscribeInput{
			TopicArn: aws.String(topicARN),
			Protocol: aws.String("sqs"),
			Endpoint: aws.String(queueARN),
		})
		require.NoError(t, err)
		subARN = aws.ToString(r.SubscriptionArn)
		assert.NotEmpty(t, subARN)
	})

	t.Run("ListSubscriptions", func(t *testing.T) {
		r, err := svc.ListSubscriptions(ctx, &sns.ListSubscriptionsInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Subscriptions)
	})

	t.Run("Publish", func(t *testing.T) {
		r, err := svc.Publish(ctx, &sns.PublishInput{
			TopicArn: aws.String(topicARN),
			Message:  aws.String(`{"event":"go-test"}`),
			Subject:  aws.String("GoTest"),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.MessageId))
	})

	t.Run("GetSubscriptionAttributes", func(t *testing.T) {
		if subARN == "" {
			t.Skip("No subscription created")
		}
		_, err := svc.GetSubscriptionAttributes(ctx, &sns.GetSubscriptionAttributesInput{
			SubscriptionArn: aws.String(subARN),
		})
		require.NoError(t, err)
	})

	t.Run("Unsubscribe", func(t *testing.T) {
		if subARN == "" {
			t.Skip("No subscription created")
		}
		_, err := svc.Unsubscribe(ctx, &sns.UnsubscribeInput{SubscriptionArn: aws.String(subARN)})
		require.NoError(t, err)
		subARN = "" // Prevent double cleanup
	})

	t.Run("DeleteTopic", func(t *testing.T) {
		_, err := svc.DeleteTopic(ctx, &sns.DeleteTopicInput{TopicArn: aws.String(topicARN)})
		require.NoError(t, err)
		topicARN = "" // Prevent double cleanup
	})
}
