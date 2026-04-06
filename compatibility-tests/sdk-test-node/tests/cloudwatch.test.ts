/**
 * CloudWatch Metrics integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  CloudWatchClient,
  PutMetricDataCommand,
  GetMetricStatisticsCommand,
  ListMetricsCommand,
  PutMetricAlarmCommand,
  DescribeAlarmsCommand,
  DeleteAlarmsCommand,
} from '@aws-sdk/client-cloudwatch';
import { makeClient, uniqueName } from './setup';

describe('CloudWatch Metrics', () => {
  let cw: CloudWatchClient;
  let namespace: string;
  let alarmName: string;

  beforeAll(() => {
    cw = makeClient(CloudWatchClient);
    namespace = `Floci/Test/${uniqueName()}`;
    alarmName = `test-alarm-${uniqueName()}`;
  });

  afterAll(async () => {
    try {
      await cw.send(new DeleteAlarmsCommand({ AlarmNames: [alarmName] }));
    } catch {
      // ignore
    }
  });

  it('should put metric data', async () => {
    await cw.send(
      new PutMetricDataCommand({
        Namespace: namespace,
        MetricData: [{ MetricName: 'TestMetric', Value: 42.0, Unit: 'Count' }],
      })
    );
  });

  it('should put metric data batch', async () => {
    await cw.send(
      new PutMetricDataCommand({
        Namespace: namespace,
        MetricData: [
          { MetricName: 'Latency', Value: 100, Unit: 'Milliseconds' },
          { MetricName: 'Errors', Value: 0, Unit: 'Count' },
        ],
      })
    );
  });

  it('should list metrics', async () => {
    const response = await cw.send(new ListMetricsCommand({ Namespace: namespace }));
    expect(response.Metrics?.length).toBeGreaterThan(0);
  });

  it('should get metric statistics', async () => {
    const now = new Date();
    const start = new Date(now.getTime() - 3600000);

    const response = await cw.send(
      new GetMetricStatisticsCommand({
        Namespace: namespace,
        MetricName: 'TestMetric',
        StartTime: start,
        EndTime: now,
        Period: 3600,
        Statistics: ['Sum', 'Average', 'Maximum'],
      })
    );
    expect(response.Datapoints).toBeDefined();
  });

  it('should put metric data with statistic values', async () => {
    const now = new Date();
    const start = new Date(now.getTime() - 3600000);

    // Put metric data with pre-calculated statistics
    await cw.send(
      new PutMetricDataCommand({
        Namespace: namespace,
        MetricData: [
          {
            MetricName: 'AggregatedMetric',
            StatisticValues: {
              SampleCount: 5,
              Sum: 150,
              Minimum: 20,
              Maximum: 40,
            },
            Unit: 'Count',
          },
        ],
      })
    );

    // Query back the statistics
    const response = await cw.send(
      new GetMetricStatisticsCommand({
        Namespace: namespace,
        MetricName: 'AggregatedMetric',
        StartTime: start,
        EndTime: now,
        Period: 3600,
        Statistics: ['Sum', 'Average', 'Minimum', 'Maximum', 'SampleCount'],
      })
    );
    
    expect(response.Datapoints).toBeDefined();
    expect(response.Datapoints?.length).toBeGreaterThan(0);
    
    const dp = response.Datapoints?.[0];
    expect(dp?.Sum).toBe(150);
    expect(dp?.SampleCount).toBe(5);
    expect(dp?.Minimum).toBe(20);
    expect(dp?.Maximum).toBe(40);
    expect(dp?.Average).toBe(30); // sum / sampleCount
  });

  it('should put metric alarm', async () => {
    await cw.send(
      new PutMetricAlarmCommand({
        AlarmName: alarmName,
        MetricName: 'TestMetric',
        Namespace: namespace,
        Statistic: 'Average',
        Period: 60,
        EvaluationPeriods: 1,
        Threshold: 100,
        ComparisonOperator: 'GreaterThanThreshold',
      })
    );
  });

  it('should describe alarms', async () => {
    const response = await cw.send(
      new DescribeAlarmsCommand({ AlarmNames: [alarmName] })
    );
    expect(response.MetricAlarms?.some((a) => a.AlarmName === alarmName)).toBe(true);
  });

  it('should delete alarms', async () => {
    await cw.send(new DeleteAlarmsCommand({ AlarmNames: [alarmName] }));
    alarmName = '';
  });
});
