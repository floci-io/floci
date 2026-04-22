package tests

import (
	"context"
	"strings"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/pipes"
	pipestypes "github.com/aws/aws-sdk-go-v2/service/pipes/types"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	pipesAccountID = "000000000000"
	pipesRegion    = "us-east-1"
	pipesRoleArn   = "arn:aws:iam::000000000000:role/pipe-role"
)

func sqsArn(queueName string) string {
	return "arn:aws:sqs:" + pipesRegion + ":" + pipesAccountID + ":" + queueName
}

func TestPipes(t *testing.T) {
	ctx := context.Background()
	pipesSvc := testutil.PipesClient()
	sqsSvc := testutil.SQSClient()

	t.Run("CreatePipe", func(t *testing.T) {
		pipeName := "go-test-create-pipe"
		srcQueue := "go-pipe-src-create"
		tgtQueue := "go-pipe-tgt-create"

		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})

		t.Cleanup(func() {
			pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
			cleanupQueue(ctx, sqsSvc, srcQueue)
			cleanupQueue(ctx, sqsSvc, tgtQueue)
		})

		r, err := pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})
		require.NoError(t, err)
		assert.Equal(t, pipestypes.PipeStateStopped, r.CurrentState)
		assert.True(t, strings.Contains(aws.ToString(r.Arn), pipeName))
	})

	t.Run("DescribePipe", func(t *testing.T) {
		pipeName := "go-test-describe-pipe"
		srcQueue := "go-pipe-src-describe"
		tgtQueue := "go-pipe-tgt-describe"

		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})

		t.Cleanup(func() {
			pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
			cleanupQueue(ctx, sqsSvc, srcQueue)
			cleanupQueue(ctx, sqsSvc, tgtQueue)
		})

		pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})

		r, err := pipesSvc.DescribePipe(ctx, &pipes.DescribePipeInput{Name: aws.String(pipeName)})
		require.NoError(t, err)
		assert.Equal(t, pipeName, aws.ToString(r.Name))
		assert.Equal(t, sqsArn(srcQueue), aws.ToString(r.Source))
		assert.Equal(t, sqsArn(tgtQueue), aws.ToString(r.Target))
		assert.Equal(t, pipestypes.PipeStateStopped, r.CurrentState)
	})

	t.Run("ListPipes", func(t *testing.T) {
		pipeName := "go-test-list-pipe"
		srcQueue := "go-pipe-src-list"
		tgtQueue := "go-pipe-tgt-list"

		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})

		t.Cleanup(func() {
			pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
			cleanupQueue(ctx, sqsSvc, srcQueue)
			cleanupQueue(ctx, sqsSvc, tgtQueue)
		})

		pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})

		r, err := pipesSvc.ListPipes(ctx, &pipes.ListPipesInput{})
		require.NoError(t, err)

		found := false
		for _, p := range r.Pipes {
			if aws.ToString(p.Name) == pipeName {
				found = true
				break
			}
		}
		assert.True(t, found, "pipe should appear in list")
	})

	t.Run("UpdatePipe", func(t *testing.T) {
		pipeName := "go-test-update-pipe"
		srcQueue := "go-pipe-src-update"
		tgtQueue := "go-pipe-tgt-update"

		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})

		t.Cleanup(func() {
			pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
			cleanupQueue(ctx, sqsSvc, srcQueue)
			cleanupQueue(ctx, sqsSvc, tgtQueue)
		})

		pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})

		_, err := pipesSvc.UpdatePipe(ctx, &pipes.UpdatePipeInput{
			Name:         aws.String(pipeName),
			RoleArn:      aws.String(pipesRoleArn),
			Description:  aws.String("updated via SDK"),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})
		require.NoError(t, err)

		r, err := pipesSvc.DescribePipe(ctx, &pipes.DescribePipeInput{Name: aws.String(pipeName)})
		require.NoError(t, err)
		assert.Equal(t, "updated via SDK", aws.ToString(r.Description))
	})

	t.Run("DeletePipe", func(t *testing.T) {
		pipeName := "go-test-delete-pipe"
		srcQueue := "go-pipe-src-delete"
		tgtQueue := "go-pipe-tgt-delete"

		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})

		t.Cleanup(func() {
			cleanupQueue(ctx, sqsSvc, srcQueue)
			cleanupQueue(ctx, sqsSvc, tgtQueue)
		})

		pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})

		_, err := pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
		require.NoError(t, err)

		_, err = pipesSvc.DescribePipe(ctx, &pipes.DescribePipeInput{Name: aws.String(pipeName)})
		require.Error(t, err)
	})

	t.Run("DescribeNonExistentPipe", func(t *testing.T) {
		_, err := pipesSvc.DescribePipe(ctx, &pipes.DescribePipeInput{Name: aws.String("nonexistent-pipe")})
		require.Error(t, err)
	})

	t.Run("StartAndStopPipe", func(t *testing.T) {
		pipeName := "go-test-startstop-pipe"
		srcQueue := "go-pipe-src-startstop"
		tgtQueue := "go-pipe-tgt-startstop"

		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})

		t.Cleanup(func() {
			pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
			cleanupQueue(ctx, sqsSvc, srcQueue)
			cleanupQueue(ctx, sqsSvc, tgtQueue)
		})

		pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})

		startR, err := pipesSvc.StartPipe(ctx, &pipes.StartPipeInput{Name: aws.String(pipeName)})
		require.NoError(t, err)
		assert.Equal(t, pipestypes.PipeStateRunning, startR.CurrentState)

		stopR, err := pipesSvc.StopPipe(ctx, &pipes.StopPipeInput{Name: aws.String(pipeName)})
		require.NoError(t, err)
		assert.Equal(t, pipestypes.PipeStateStopped, stopR.CurrentState)
	})

	t.Run("SQSToSQSForwarding", func(t *testing.T) {
		pipeName := "go-test-fwd-pipe"
		srcQueue := "go-pipe-src-fwd"
		tgtQueue := "go-pipe-tgt-fwd"

		srcResp, _ := sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		srcURL := aws.ToString(srcResp.QueueUrl)
		tgtResp, _ := sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})
		tgtURL := aws.ToString(tgtResp.QueueUrl)

		t.Cleanup(func() {
			pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
			sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(srcURL)})
			sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(tgtURL)})
		})

		pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateRunning,
		})

		sqsSvc.SendMessage(ctx, &sqs.SendMessageInput{
			QueueUrl:    aws.String(srcURL),
			MessageBody: aws.String("hello from pipes"),
		})

		found := false
		for i := 0; i < 15; i++ {
			time.Sleep(1 * time.Second)
			r, err := sqsSvc.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
				QueueUrl:            aws.String(tgtURL),
				MaxNumberOfMessages: 1,
				WaitTimeSeconds:     1,
			})
			if err == nil && len(r.Messages) > 0 && strings.Contains(aws.ToString(r.Messages[0].Body), "hello from pipes") {
				found = true
				break
			}
		}
		assert.True(t, found, "target queue should receive forwarded message")
	})

	t.Run("StoppedPipeDoesNotForward", func(t *testing.T) {
		pipeName := "go-test-nofwd-pipe"
		srcQueue := "go-pipe-src-nofwd"
		tgtQueue := "go-pipe-tgt-nofwd"

		srcResp, _ := sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(srcQueue)})
		srcURL := aws.ToString(srcResp.QueueUrl)
		tgtResp, _ := sqsSvc.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(tgtQueue)})
		tgtURL := aws.ToString(tgtResp.QueueUrl)

		t.Cleanup(func() {
			pipesSvc.DeletePipe(ctx, &pipes.DeletePipeInput{Name: aws.String(pipeName)})
			sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(srcURL)})
			sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: aws.String(tgtURL)})
		})

		pipesSvc.CreatePipe(ctx, &pipes.CreatePipeInput{
			Name:         aws.String(pipeName),
			Source:       aws.String(sqsArn(srcQueue)),
			Target:       aws.String(sqsArn(tgtQueue)),
			RoleArn:      aws.String(pipesRoleArn),
			DesiredState: pipestypes.RequestedPipeStateStopped,
		})

		sqsSvc.SendMessage(ctx, &sqs.SendMessageInput{
			QueueUrl:    aws.String(srcURL),
			MessageBody: aws.String("should not forward"),
		})

		time.Sleep(3 * time.Second)

		r, _ := sqsSvc.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:            aws.String(tgtURL),
			MaxNumberOfMessages: 1,
			WaitTimeSeconds:     1,
		})
		assert.Empty(t, r.Messages, "target queue should be empty")
	})
}

func cleanupQueue(ctx context.Context, sqsSvc *sqs.Client, queueName string) {
	r, err := sqsSvc.GetQueueUrl(ctx, &sqs.GetQueueUrlInput{QueueName: aws.String(queueName)})
	if err == nil {
		sqsSvc.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: r.QueueUrl})
	}
}
