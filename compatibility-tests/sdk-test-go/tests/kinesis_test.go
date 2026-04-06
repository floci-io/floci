package tests

import (
	"context"
	"testing"

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

	t.Run("DescribeStream", func(t *testing.T) {
		r, err := svc.DescribeStream(ctx, &kinesis.DescribeStreamInput{StreamName: aws.String(streamName)})
		require.NoError(t, err)
		assert.Equal(t, kinesistypes.StreamStatusActive, r.StreamDescription.StreamStatus)
		if len(r.StreamDescription.Shards) > 0 {
			shardID = aws.ToString(r.StreamDescription.Shards[0].ShardId)
		}
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

	t.Run("DeleteStream", func(t *testing.T) {
		_, err := svc.DeleteStream(ctx, &kinesis.DeleteStreamInput{StreamName: aws.String(streamName)})
		require.NoError(t, err)
	})
}
