mod common;

use aws_sdk_cloudwatch::types::{MetricDatum, StandardUnit, Statistic};

#[tokio::test]
async fn test_cloudwatch_put_metric_data() {
    let cw = common::cloudwatch_client().await;
    let namespace = "RustTest";

    let result = cw
        .put_metric_data()
        .namespace(namespace)
        .metric_data(
            MetricDatum::builder()
                .metric_name("RequestCount")
                .value(42.0)
                .unit(StandardUnit::Count)
                .build(),
        )
        .send()
        .await;
    assert!(result.is_ok(), "PutMetricData failed: {:?}", result.err());
}

#[tokio::test]
async fn test_cloudwatch_list_metrics() {
    let cw = common::cloudwatch_client().await;
    let namespace = "RustTestList";

    // Setup
    cw.put_metric_data()
        .namespace(namespace)
        .metric_data(
            MetricDatum::builder()
                .metric_name("TestMetric")
                .value(1.0)
                .unit(StandardUnit::Count)
                .build(),
        )
        .send()
        .await
        .expect("setup");

    let result = cw.list_metrics().namespace(namespace).send().await;
    assert!(result.is_ok(), "ListMetrics failed: {:?}", result.err());
    assert!(!result.unwrap().metrics().is_empty());
}

#[tokio::test]
async fn test_cloudwatch_get_metric_statistics() {
    let cw = common::cloudwatch_client().await;
    let namespace = "RustTestStats";

    // Setup
    cw.put_metric_data()
        .namespace(namespace)
        .metric_data(
            MetricDatum::builder()
                .metric_name("StatsMetric")
                .value(100.0)
                .unit(StandardUnit::Count)
                .build(),
        )
        .send()
        .await
        .expect("setup");

    let now = std::time::SystemTime::now();
    let five_mins_ago = now - std::time::Duration::from_secs(300);
    let one_min_future = now + std::time::Duration::from_secs(60);

    let result = cw
        .get_metric_statistics()
        .namespace(namespace)
        .metric_name("StatsMetric")
        .start_time(aws_smithy_types::DateTime::from(five_mins_ago))
        .end_time(aws_smithy_types::DateTime::from(one_min_future))
        .period(60)
        .statistics(Statistic::Sum)
        .send()
        .await;
    assert!(result.is_ok(), "GetMetricStatistics failed: {:?}", result.err());
    assert!(!result.unwrap().datapoints().is_empty());
}

#[tokio::test]
async fn test_cloudwatch_put_statistic_values() {
    let cw = common::cloudwatch_client().await;
    let namespace = "RustTestStatisticValues";

    // Setup: Put metric data with pre-calculated statistics
    let result = cw
        .put_metric_data()
        .namespace(namespace)
        .metric_data(
            MetricDatum::builder()
                .metric_name("AggregatedMetric")
                .statistic_values(
                    aws_sdk_cloudwatch::types::StatisticSet::builder()
                        .sample_count(5.0)
                        .sum(150.0)
                        .minimum(20.0)
                        .maximum(40.0)
                        .build(),
                )
                .unit(StandardUnit::Count)
                .build(),
        )
        .send()
        .await;
    assert!(result.is_ok(), "PutMetricData with StatisticValues failed: {:?}", result.err());

    // Query back the statistics
    let now = std::time::SystemTime::now();
    let five_mins_ago = now - std::time::Duration::from_secs(300);
    let one_min_future = now + std::time::Duration::from_secs(60);

    let result = cw
        .get_metric_statistics()
        .namespace(namespace)
        .metric_name("AggregatedMetric")
        .start_time(aws_smithy_types::DateTime::from(five_mins_ago))
        .end_time(aws_smithy_types::DateTime::from(one_min_future))
        .period(60)
        .statistics(Statistic::Sum)
        .statistics(Statistic::SampleCount)
        .statistics(Statistic::Minimum)
        .statistics(Statistic::Maximum)
        .statistics(Statistic::Average)
        .send()
        .await;
    assert!(result.is_ok(), "GetMetricStatistics failed: {:?}", result.err());
    
    let response = result.unwrap();
    assert!(!response.datapoints().is_empty(), "No datapoints returned");
    
    let dp = &response.datapoints()[0];
    assert_eq!(dp.sample_count(), Some(5.0), "SampleCount mismatch");
    assert_eq!(dp.sum(), Some(150.0), "Sum mismatch");
    assert_eq!(dp.minimum(), Some(20.0), "Minimum mismatch");
    assert_eq!(dp.maximum(), Some(40.0), "Maximum mismatch");
    assert_eq!(dp.average(), Some(30.0), "Average mismatch"); // sum / sampleCount = 150 / 5
}
