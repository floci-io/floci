package tests

import (
	"context"
	"testing"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSQS(t *testing.T) {
	ctx := context.Background()
	svc := testutil.SQSClient()
	qName := "go-test-queue"
	var qURL string

	// Cleanup at end
	t.Cleanup(func() {
		if qURL != "" {
			svc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(qURL)})
		}
	})

	t.Run("CreateQueue", func(t *testing.T) {
		r, err := svc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(qName)})
		require.NoError(t, err)
		qURL = aws.ToString(r.QueueUrl)
		assert.NotEmpty(t, qURL)
	})

	t.Run("ListQueues", func(t *testing.T) {
		r, err := svc.ListQueues(ctx, &sqs.ListQueuesInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.QueueUrls)
	})

	t.Run("GetQueueUrl", func(t *testing.T) {
		r, err := svc.GetQueueUrl(ctx, &sqs.GetQueueUrlInput{QueueName: aws.String(qName)})
		require.NoError(t, err)
		assert.Equal(t, qURL, aws.ToString(r.QueueUrl))
	})

	t.Run("GetQueueAttributes", func(t *testing.T) {
		r, err := svc.GetQueueAttributes(ctx, &sqs.GetQueueAttributesInput{
			QueueUrl:       aws.String(qURL),
			AttributeNames: []sqstypes.QueueAttributeName{sqstypes.QueueAttributeNameAll},
		})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Attributes["QueueArn"])
	})

	t.Run("SendMessage", func(t *testing.T) {
		r, err := svc.SendMessage(ctx, &sqs.SendMessageInput{
			QueueUrl:    aws.String(qURL),
			MessageBody: aws.String(`{"source":"go-test"}`),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.MessageId))
	})

	t.Run("SendMessageBatch", func(t *testing.T) {
		r, err := svc.SendMessageBatch(ctx, &sqs.SendMessageBatchInput{
			QueueUrl: aws.String(qURL),
			Entries: []sqstypes.SendMessageBatchRequestEntry{
				{Id: aws.String("m1"), MessageBody: aws.String("batch-1")},
				{Id: aws.String("m2"), MessageBody: aws.String("batch-2")},
			},
		})
		require.NoError(t, err)
		assert.Len(t, r.Successful, 2)
	})

	var receiptHandle *string
	t.Run("ReceiveMessage", func(t *testing.T) {
		r, err := svc.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:            aws.String(qURL),
			MaxNumberOfMessages: 10,
			WaitTimeSeconds:     1,
		})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Messages)
		if len(r.Messages) > 0 {
			receiptHandle = r.Messages[0].ReceiptHandle
		}
	})

	t.Run("DeleteMessage", func(t *testing.T) {
		require.NotNil(t, receiptHandle, "need a message to delete")
		_, err := svc.DeleteMessage(ctx, &sqs.DeleteMessageInput{
			QueueUrl:      aws.String(qURL),
			ReceiptHandle: receiptHandle,
		})
		require.NoError(t, err)
	})

	t.Run("SetQueueAttributes", func(t *testing.T) {
		_, err := svc.SetQueueAttributes(ctx, &sqs.SetQueueAttributesInput{
			QueueUrl:   aws.String(qURL),
			Attributes: map[string]string{"VisibilityTimeout": "60"},
		})
		require.NoError(t, err)
	})

	t.Run("PurgeQueue", func(t *testing.T) {
		_, err := svc.PurgeQueue(ctx, &sqs.PurgeQueueInput{QueueUrl: aws.String(qURL)})
		require.NoError(t, err)
	})

	t.Run("DeleteQueue", func(t *testing.T) {
		_, err := svc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(qURL)})
		require.NoError(t, err)
		qURL = "" // Prevent double cleanup
	})
}
