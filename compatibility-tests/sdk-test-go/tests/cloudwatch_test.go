package tests

import (
	"context"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatch"
	cwtypes "github.com/aws/aws-sdk-go-v2/service/cloudwatch/types"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCloudWatch(t *testing.T) {
	ctx := context.Background()
	svc := testutil.CloudWatchClient()
	namespace := "GoTest"

	t.Run("PutMetricData", func(t *testing.T) {
		_, err := svc.PutMetricData(ctx, &cloudwatch.PutMetricDataInput{
			Namespace: aws.String(namespace),
			MetricData: []cwtypes.MetricDatum{
				{
					MetricName: aws.String("RequestCount"),
					Value:      aws.Float64(42),
					Unit:       cwtypes.StandardUnitCount,
					Timestamp:  aws.Time(time.Now()),
				},
			},
		})
		require.NoError(t, err)
	})

	t.Run("ListMetrics", func(t *testing.T) {
		r, err := svc.ListMetrics(ctx, &cloudwatch.ListMetricsInput{Namespace: aws.String(namespace)})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Metrics)
	})

	t.Run("GetMetricStatistics", func(t *testing.T) {
		now := time.Now()
		r, err := svc.GetMetricStatistics(ctx, &cloudwatch.GetMetricStatisticsInput{
			Namespace:  aws.String(namespace),
			MetricName: aws.String("RequestCount"),
			StartTime:  aws.Time(now.Add(-5 * time.Minute)),
			EndTime:    aws.Time(now.Add(time.Minute)),
			Period:     aws.Int32(60),
			Statistics: []cwtypes.Statistic{cwtypes.StatisticSum},
		})
		require.NoError(t, err)
		assert.NotEmpty(t, r.Datapoints)
	})

	t.Run("PutMetricDataWithStatisticValues", func(t *testing.T) {
		now := time.Now()
		namespace := "GoTestStatisticValues"

		// Put metric data with pre-calculated statistics
		_, err := svc.PutMetricData(ctx, &cloudwatch.PutMetricDataInput{
			Namespace: aws.String(namespace),
			MetricData: []cwtypes.MetricDatum{
				{
					MetricName: aws.String("AggregatedMetric"),
					StatisticValues: &cwtypes.StatisticSet{
						SampleCount: aws.Float64(5.0),
						Sum:         aws.Float64(150.0),
						Minimum:     aws.Float64(20.0),
						Maximum:     aws.Float64(40.0),
					},
					Unit: cwtypes.StandardUnitCount,
				},
			},
		})
		require.NoError(t, err)

		// Query back the statistics
		r, err := svc.GetMetricStatistics(ctx, &cloudwatch.GetMetricStatisticsInput{
			Namespace:  aws.String(namespace),
			MetricName: aws.String("AggregatedMetric"),
			StartTime:  aws.Time(now.Add(-time.Hour)),
			EndTime:    aws.Time(now.Add(time.Minute)),
			Period:     aws.Int32(3600),
			Statistics: []cwtypes.Statistic{
				cwtypes.StatisticSum,
				cwtypes.StatisticAverage,
				cwtypes.StatisticMinimum,
				cwtypes.StatisticMaximum,
				cwtypes.StatisticSampleCount,
			},
		})

		require.NoError(t, err)
		assert.NotEmpty(t, r.Datapoints)

		dp := r.Datapoints[0]
		assert.NotNil(t, dp.Sum)
		assert.Equal(t, 150.0, *dp.Sum)
		assert.NotNil(t, dp.SampleCount)
		assert.Equal(t, float64(5), *dp.SampleCount)
		assert.NotNil(t, dp.Minimum)
		assert.Equal(t, 20.0, *dp.Minimum)
		assert.NotNil(t, dp.Maximum)
		assert.Equal(t, 40.0, *dp.Maximum)
		assert.NotNil(t, dp.Average)
		assert.Equal(t, 30.0, *dp.Average) // sum / sampleCount
	})
}
