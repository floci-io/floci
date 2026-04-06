//! S3 Bucket Notification Configuration Tests
//!
//! Tests S3 notification configurations with SQS and SNS,
//! including filter rules for prefix/suffix patterns.

mod common;

use aws_sdk_s3::types::{
    Event as S3Event, FilterRule as S3FilterRule, FilterRuleName,
    NotificationConfiguration, NotificationConfigurationFilter,
    QueueConfiguration as S3QueueConfig, S3KeyFilter,
    TopicConfiguration as S3TopicConfig,
};

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_notifications_with_filters() {
    let s3 = common::s3_client().await;
    let sqs = common::sqs_client().await;
    let sns = common::sns_client().await;
    let endpoint = sdk_test_rust::endpoint();

    let bucket = "rust-s3-notif-filter-bucket";
    let queue_name = "rust-s3-notif-filter-queue";
    let topic_name = "rust-s3-notif-filter-topic";
    let account_id = "000000000000";
    let queue_arn = format!("arn:aws:sqs:us-east-1:{}:{}", account_id, queue_name);

    // Create topic first to get ARN
    let topic_result = sns.create_topic().name(topic_name).send().await;
    let topic_arn = match topic_result {
        Ok(r) => r.topic_arn.unwrap_or_default(),
        Err(e) => panic!("Failed to create SNS topic: {:?}", e),
    };

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        let sqs = sqs.clone();
        let sns = sns.clone();
        let endpoint = endpoint.clone();
        let topic_arn = topic_arn.clone();
        async move {
            let _ = s3.delete_bucket().bucket(bucket).send().await;
            let queue_url = format!("{}/{}/{}", endpoint, account_id, queue_name);
            let _ = sqs.delete_queue().queue_url(&queue_url).send().await;
            let _ = sns.delete_topic().topic_arn(&topic_arn).send().await;
        }
    });

    // Create SQS queue
    sqs.create_queue()
        .queue_name(queue_name)
        .send()
        .await
        .expect("create queue");

    // Create S3 bucket
    s3.create_bucket()
        .bucket(bucket)
        .send()
        .await
        .expect("create bucket");

    // Configure queue notification with prefix/suffix filter
    let queue_config = S3QueueConfig::builder()
        .id("sqs-filtered")
        .queue_arn(&queue_arn)
        .events(S3Event::S3ObjectCreated)
        .filter(
            NotificationConfigurationFilter::builder()
                .key(
                    S3KeyFilter::builder()
                        .filter_rules(
                            S3FilterRule::builder()
                                .name(FilterRuleName::Prefix)
                                .value("incoming/")
                                .build(),
                        )
                        .filter_rules(
                            S3FilterRule::builder()
                                .name(FilterRuleName::Suffix)
                                .value(".csv")
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
        .build()
        .expect("queue config");

    // Configure topic notification with suffix filter
    let topic_config = S3TopicConfig::builder()
        .id("sns-filtered")
        .topic_arn(&topic_arn)
        .events(S3Event::S3ObjectRemoved)
        .filter(
            NotificationConfigurationFilter::builder()
                .key(
                    S3KeyFilter::builder()
                        .filter_rules(
                            S3FilterRule::builder()
                                .name(FilterRuleName::Prefix)
                                .value("")
                                .build(),
                        )
                        .filter_rules(
                            S3FilterRule::builder()
                                .name(FilterRuleName::Suffix)
                                .value(".txt")
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
        .build()
        .expect("topic config");

    // Put notification configuration
    let put_result = s3
        .put_bucket_notification_configuration()
        .bucket(bucket)
        .notification_configuration(
            NotificationConfiguration::builder()
                .queue_configurations(queue_config)
                .topic_configurations(topic_config)
                .build(),
        )
        .send()
        .await;

    assert!(
        put_result.is_ok(),
        "PutBucketNotificationConfiguration failed: {:?}",
        put_result.err()
    );

    // Get and verify notification configuration
    let get_result = s3
        .get_bucket_notification_configuration()
        .bucket(bucket)
        .send()
        .await;

    assert!(
        get_result.is_ok(),
        "GetBucketNotificationConfiguration failed: {:?}",
        get_result.err()
    );

    let nc = get_result.unwrap();

    // Verify queue configuration
    let queue_configs = nc.queue_configurations();
    assert!(
        !queue_configs.is_empty(),
        "should have queue configurations"
    );
    let has_queue = queue_configs.iter().any(|q| q.queue_arn() == queue_arn);
    assert!(has_queue, "queue ARN should match");

    // Verify queue filter rules
    let queue_filter_ok = queue_configs.iter().any(|q| {
        q.queue_arn() == queue_arn
            && q.filter()
                .and_then(|f| f.key())
                .map(|k| k.filter_rules().len() == 2)
                .unwrap_or(false)
    });
    assert!(
        queue_filter_ok,
        "queue should have 2 filter rules (prefix and suffix)"
    );

    // Verify topic configuration
    let topic_configs = nc.topic_configurations();
    assert!(
        !topic_configs.is_empty(),
        "should have topic configurations"
    );
    let has_topic = topic_configs.iter().any(|t| t.topic_arn() == topic_arn);
    assert!(has_topic, "topic ARN should match");

    // Verify topic filter rules
    let topic_filter_ok = topic_configs.iter().any(|t| {
        t.topic_arn() == topic_arn
            && t.filter()
                .and_then(|f| f.key())
                .map(|k| k.filter_rules().len() == 2)
                .unwrap_or(false)
    });
    assert!(
        topic_filter_ok,
        "topic should have 2 filter rules (prefix and suffix)"
    );
}
