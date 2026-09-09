package tests

import (
	"context"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/kinesis"
	kinesistypes "github.com/aws/aws-sdk-go-v2/service/kinesis/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestKinesis(t *testing.T) {
	ctx := context.Background()
	svc := testutil.KinesisClient()
	streamName := "go-test-stream"

	t.Cleanup(func() {
		svc.DeleteStream(ctx, &kinesis.DeleteStreamInput{StreamName: aws.String(streamName)})
	})

	t.Run("CreateStream", func(t *testing.T) {
		_, err := svc.CreateStream(ctx, &kinesis.CreateStreamInput{
			StreamName: aws.String(streamName),
			ShardCount: aws.Int32(1),
		})
		require.NoError(t, err)
	})

	t.Run("ListStreams", func(t *testing.T) {
		r, err := svc.ListStreams(ctx, &kinesis.ListStreamsInput{})
		require.NoError(t, err)
		assert.NotEmpty(t, r.StreamNames)
	})

	var shardID string
	var streamARN string

	t.Run("DescribeStream", func(t *testing.T) {
		r, err := svc.DescribeStream(ctx, &kinesis.DescribeStreamInput{StreamName: aws.String(streamName)})
		require.NoError(t, err)
		require.NotNil(t, r.StreamDescription)
		assert.Equal(t, kinesistypes.StreamStatusActive, r.StreamDescription.StreamStatus)
		require.NotEmpty(t, r.StreamDescription.Shards)
		shardID = aws.ToString(r.StreamDescription.Shards[0].ShardId)
		require.NotEmpty(t, shardID)
		streamARN = aws.ToString(r.StreamDescription.StreamARN)
		require.NotEmpty(t, streamARN)
	})

	t.Run("PutRecord", func(t *testing.T) {
		r, err := svc.PutRecord(ctx, &kinesis.PutRecordInput{
			StreamName:   aws.String(streamName),
			Data:         []byte(`{"event":"go-test"}`),
			PartitionKey: aws.String("pk1"),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.ShardId))
	})

	t.Run("GetShardIterator", func(t *testing.T) {
		if shardID == "" {
			t.Skip("No shard ID from DescribeStream")
		}
		r, err := svc.GetShardIterator(ctx, &kinesis.GetShardIteratorInput{
			StreamName:        aws.String(streamName),
			ShardId:           aws.String(shardID),
			ShardIteratorType: kinesistypes.ShardIteratorTypeTrimHorizon,
		})
		require.NoError(t, err)
		assert.NotEmpty(t, aws.ToString(r.ShardIterator))
	})

	t.Run("GetRecords", func(t *testing.T) {
		if shardID == "" {
			t.Skip("No shard ID from DescribeStream")
		}
		// Get fresh iterator
		iter, err := svc.GetShardIterator(ctx, &kinesis.GetShardIteratorInput{
			StreamName:        aws.String(streamName),
			ShardId:           aws.String(shardID),
			ShardIteratorType: kinesistypes.ShardIteratorTypeTrimHorizon,
		})
		require.NoError(t, err)

		r, err := svc.GetRecords(ctx, &kinesis.GetRecordsInput{
			ShardIterator: iter.ShardIterator,
			Limit:         aws.Int32(10),
		})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Records)
	})

	var consumerARN string

	t.Run("RegisterStreamConsumer", func(t *testing.T) {
		if streamARN == "" {
			t.Skip("No stream ARN from DescribeStream")
		}
		r, err := svc.RegisterStreamConsumer(ctx, &kinesis.RegisterStreamConsumerInput{
			StreamARN:    aws.String(streamARN),
			ConsumerName: aws.String("go-test-consumer"),
		})
		require.NoError(t, err)
		require.NotNil(t, r.Consumer)
		consumerARN = aws.ToString(r.Consumer.ConsumerARN)
		assert.NotEmpty(t, consumerARN)
	})

	t.Run("DescribeStreamConsumer", func(t *testing.T) {
		if consumerARN == "" {
			t.Skip("No consumer ARN from RegisterStreamConsumer")
		}
		r, err := svc.DescribeStreamConsumer(ctx, &kinesis.DescribeStreamConsumerInput{
			ConsumerARN: aws.String(consumerARN),
		})
		require.NoError(t, err)
		assert.Equal(t, kinesistypes.ConsumerStatusActive, r.ConsumerDescription.ConsumerStatus)
		assert.Equal(t, "go-test-consumer", aws.ToString(r.ConsumerDescription.ConsumerName))
	})

	t.Run("SubscribeToShard", func(t *testing.T) {
		if consumerARN == "" || shardID == "" {
			t.Skip("No consumerARN or shardID")
		}
		subCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		defer cancel()

		output, err := svc.SubscribeToShard(subCtx, &kinesis.SubscribeToShardInput{
			ConsumerARN: aws.String(consumerARN),
			ShardId:     aws.String(shardID),
			StartingPosition: &kinesistypes.StartingPosition{
				Type: kinesistypes.ShardIteratorTypeTrimHorizon,
			},
		})
		require.NoError(t, err)

		stream := output.GetStream()
		defer stream.Close()

		var gotEvent bool
		for event := range stream.Events() {
			if ev, ok := event.(*kinesistypes.SubscribeToShardEventStreamMemberSubscribeToShardEvent); ok {
				gotEvent = true
				assert.NotNil(t, ev.Value.Records)
			}
		}
		require.NoError(t, stream.Err())
		assert.True(t, gotEvent, "expected at least one SubscribeToShardEvent")
	})

	t.Run("DeleteStream", func(t *testing.T) {
		_, err := svc.DeleteStream(ctx, &kinesis.DeleteStreamInput{StreamName: aws.String(streamName)})
		require.NoError(t, err)
	})
}
