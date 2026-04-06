"""CloudWatch Metrics integration tests."""

import datetime

import pytest


class TestCloudWatchMetrics:
    """Test CloudWatch metrics operations."""

    def test_put_metric_data(self, cloudwatch_client, unique_name):
        """Test PutMetricData writes metric."""
        namespace = f"TestApp/PytestMetrics/{unique_name}"
        now = datetime.datetime.now(datetime.timezone.utc)

        cloudwatch_client.put_metric_data(
            Namespace=namespace,
            MetricData=[
                {
                    "MetricName": "RequestCount",
                    "Value": 42.0,
                    "Unit": "Count",
                    "Timestamp": now,
                }
            ],
        )
        # If no exception, test passes

    def test_put_metric_data_with_dimensions(self, cloudwatch_client, unique_name):
        """Test PutMetricData with dimensions."""
        namespace = f"TestApp/PytestMetrics/{unique_name}"
        now = datetime.datetime.now(datetime.timezone.utc)

        cloudwatch_client.put_metric_data(
            Namespace=namespace,
            MetricData=[
                {
                    "MetricName": "Latency",
                    "Value": 125.5,
                    "Unit": "Milliseconds",
                    "Timestamp": now,
                    "Dimensions": [{"Name": "Host", "Value": "web01"}],
                }
            ],
        )
        # If no exception, test passes

    def test_list_metrics(self, cloudwatch_client, unique_name):
        """Test ListMetrics returns written metrics."""
        namespace = f"TestApp/PytestMetrics/{unique_name}"
        now = datetime.datetime.now(datetime.timezone.utc)

        cloudwatch_client.put_metric_data(
            Namespace=namespace,
            MetricData=[
                {"MetricName": "RequestCount", "Value": 42.0, "Unit": "Count", "Timestamp": now},
                {"MetricName": "Latency", "Value": 125.5, "Unit": "Milliseconds", "Timestamp": now},
            ],
        )

        response = cloudwatch_client.list_metrics(Namespace=namespace)
        has_rc = any(m["MetricName"] == "RequestCount" for m in response["Metrics"])
        has_lat = any(m["MetricName"] == "Latency" for m in response["Metrics"])
        assert has_rc and has_lat

    def test_list_metrics_namespace_isolation(self, cloudwatch_client, unique_name):
        """Test ListMetrics respects namespace isolation."""
        namespace_a = f"TestApp/PytestMetricsA/{unique_name}"
        namespace_b = f"TestApp/PytestMetricsB/{unique_name}"
        now = datetime.datetime.now(datetime.timezone.utc)

        cloudwatch_client.put_metric_data(
            Namespace=namespace_a,
            MetricData=[{"MetricName": "MetricA", "Value": 1.0, "Unit": "Count", "Timestamp": now}],
        )
        cloudwatch_client.put_metric_data(
            Namespace=namespace_b,
            MetricData=[{"MetricName": "MetricB", "Value": 1.0, "Unit": "Count", "Timestamp": now}],
        )

        response_a = cloudwatch_client.list_metrics(Namespace=namespace_a)
        response_b = cloudwatch_client.list_metrics(Namespace=namespace_b)

        no_a_in_b = all(m["Namespace"] != namespace_a for m in response_b["Metrics"])
        no_b_in_a = all(m["Namespace"] != namespace_b for m in response_a["Metrics"])
        assert no_a_in_b and no_b_in_a

    def test_get_metric_statistics(self, cloudwatch_client, unique_name):
        """Test GetMetricStatistics returns aggregated data."""
        namespace = f"TestApp/PytestMetrics/{unique_name}"
        now = datetime.datetime.now(datetime.timezone.utc)

        # Put 5 data points
        data_points = [
            {
                "MetricName": "CPUUtil",
                "Value": v,
                "Unit": "Percent",
                "Timestamp": now - datetime.timedelta(seconds=s),
            }
            for v, s in [(10.0, 250), (20.0, 200), (30.0, 150), (40.0, 100), (50.0, 50)]
        ]
        cloudwatch_client.put_metric_data(Namespace=namespace, MetricData=data_points)

        response = cloudwatch_client.get_metric_statistics(
            Namespace=namespace,
            MetricName="CPUUtil",
            StartTime=now - datetime.timedelta(hours=1),
            EndTime=now + datetime.timedelta(seconds=60),
            Period=3600,
            Statistics=["Sum", "SampleCount"],
        )

        assert len(response["Datapoints"]) > 0
        total_sum = sum(dp["Sum"] for dp in response["Datapoints"])
        total_sc = sum(dp["SampleCount"] for dp in response["Datapoints"])
        assert abs(total_sum - 150.0) < 0.001
        assert abs(total_sc - 5.0) < 0.001

    def test_put_metric_data_with_statistic_values(self, cloudwatch_client, unique_name):
        """Test PutMetricData with pre-calculated StatisticValues."""
        namespace = f"TestApp/StatisticValues/{unique_name}"
        now = datetime.datetime.now(datetime.timezone.utc)

        # Put metric data with pre-calculated statistics
        cloudwatch_client.put_metric_data(
            Namespace=namespace,
            MetricData=[
                {
                    "MetricName": "AggregatedMetric",
                    "StatisticValues": {
                        "SampleCount": 5,
                        "Sum": 150.0,
                        "Minimum": 20.0,
                        "Maximum": 40.0,
                    },
                    "Unit": "Count",
                }
            ],
        )

        # Query back the statistics
        response = cloudwatch_client.get_metric_statistics(
            Namespace=namespace,
            MetricName="AggregatedMetric",
            StartTime=now - datetime.timedelta(hours=1),
            EndTime=now + datetime.timedelta(seconds=60),
            Period=3600,
            Statistics=["Sum", "Average", "Minimum", "Maximum", "SampleCount"],
        )

        assert response["Datapoints"]
        dp = response["Datapoints"][0]
        assert dp["Sum"] == 150.0
        assert dp["SampleCount"] == 5
        assert dp["Minimum"] == 20.0
        assert dp["Maximum"] == 40.0
        assert dp["Average"] == 30.0  # sum / sampleCount


class TestCloudWatchAlarms:
    """Test CloudWatch alarm operations."""

    def test_put_metric_alarm(self, cloudwatch_client, unique_name):
        """Test PutMetricAlarm creates an alarm."""
        alarm_name = f"pytest-alarm-{unique_name}"

        try:
            cloudwatch_client.put_metric_alarm(
                AlarmName=alarm_name,
                MetricName="CPUUtilization",
                Namespace="AWS/EC2",
                Statistic="Average",
                Period=60,
                Threshold=80.0,
                ComparisonOperator="GreaterThanThreshold",
                EvaluationPeriods=1,
                AlarmActions=["arn:aws:sns:us-east-1:000000000000:my-topic"],
            )
            # If no exception, test passes
        finally:
            cloudwatch_client.delete_alarms(AlarmNames=[alarm_name])

    def test_describe_alarms(self, cloudwatch_client, unique_name):
        """Test DescribeAlarms returns alarm details."""
        alarm_name = f"pytest-alarm-{unique_name}"

        cloudwatch_client.put_metric_alarm(
            AlarmName=alarm_name,
            MetricName="CPUUtilization",
            Namespace="AWS/EC2",
            Statistic="Average",
            Period=60,
            Threshold=80.0,
            ComparisonOperator="GreaterThanThreshold",
            EvaluationPeriods=1,
        )

        try:
            response = cloudwatch_client.describe_alarms(AlarmNames=[alarm_name])
            alarms = response["MetricAlarms"]
            assert any(a["AlarmName"] == alarm_name for a in alarms)

            alarm = next(a for a in alarms if a["AlarmName"] == alarm_name)
            assert alarm["StateValue"] == "INSUFFICIENT_DATA"
        finally:
            cloudwatch_client.delete_alarms(AlarmNames=[alarm_name])

    def test_set_alarm_state(self, cloudwatch_client, unique_name):
        """Test SetAlarmState changes alarm state."""
        alarm_name = f"pytest-alarm-{unique_name}"

        cloudwatch_client.put_metric_alarm(
            AlarmName=alarm_name,
            MetricName="CPUUtilization",
            Namespace="AWS/EC2",
            Statistic="Average",
            Period=60,
            Threshold=80.0,
            ComparisonOperator="GreaterThanThreshold",
            EvaluationPeriods=1,
        )

        try:
            cloudwatch_client.set_alarm_state(
                AlarmName=alarm_name,
                StateValue="ALARM",
                StateReason="Threshold breached",
            )

            response = cloudwatch_client.describe_alarms(AlarmNames=[alarm_name])
            assert response["MetricAlarms"][0]["StateValue"] == "ALARM"
        finally:
            cloudwatch_client.delete_alarms(AlarmNames=[alarm_name])

    def test_delete_alarms(self, cloudwatch_client, unique_name):
        """Test DeleteAlarms removes alarms."""
        alarm_name = f"pytest-alarm-{unique_name}"

        cloudwatch_client.put_metric_alarm(
            AlarmName=alarm_name,
            MetricName="CPUUtilization",
            Namespace="AWS/EC2",
            Statistic="Average",
            Period=60,
            Threshold=80.0,
            ComparisonOperator="GreaterThanThreshold",
            EvaluationPeriods=1,
        )

        cloudwatch_client.delete_alarms(AlarmNames=[alarm_name])

        response = cloudwatch_client.describe_alarms(AlarmNames=[alarm_name])
        assert len(response["MetricAlarms"]) == 0
