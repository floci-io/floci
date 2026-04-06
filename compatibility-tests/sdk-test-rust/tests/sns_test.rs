mod common;

#[tokio::test(flavor = "multi_thread")]
async fn test_sns_create_topic() {
    let sns = common::sns_client().await;
    let topic_name = "rust-test-topic";

    let result = sns.create_topic().name(topic_name).send().await;
    assert!(result.is_ok(), "CreateTopic failed: {:?}", result.err());

    let arn = result.unwrap().topic_arn().unwrap_or("").to_string();
    assert!(!arn.is_empty());

    let _guard = common::CleanupGuard::new({
        let sns = sns.clone();
        let arn = arn.clone();
        async move {
            let _ = sns.delete_topic().topic_arn(&arn).send().await;
        }
    });
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sns_list_topics() {
    let sns = common::sns_client().await;
    let topic_name = "rust-test-list-topics";

    // Setup
    let create = sns.create_topic().name(topic_name).send().await.expect("setup");
    let arn = create.topic_arn().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let sns = sns.clone();
        let arn = arn.clone();
        async move {
            let _ = sns.delete_topic().topic_arn(&arn).send().await;
        }
    });

    let result = sns.list_topics().send().await;
    assert!(result.is_ok(), "ListTopics failed: {:?}", result.err());
    assert!(!result.unwrap().topics().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sns_get_topic_attributes() {
    let sns = common::sns_client().await;
    let topic_name = "rust-test-get-attrs";

    // Setup
    let create = sns.create_topic().name(topic_name).send().await.expect("setup");
    let arn = create.topic_arn().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let sns = sns.clone();
        let arn = arn.clone();
        async move {
            let _ = sns.delete_topic().topic_arn(&arn).send().await;
        }
    });

    let result = sns.get_topic_attributes().topic_arn(&arn).send().await;
    assert!(result.is_ok(), "GetTopicAttributes failed: {:?}", result.err());

    let attrs = result.unwrap().attributes().cloned().unwrap_or_default();
    assert_eq!(attrs.get("TopicArn").map(|s| s.as_str()).unwrap_or(""), &arn);
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sns_publish() {
    let sns = common::sns_client().await;
    let topic_name = "rust-test-publish";

    // Setup
    let create = sns.create_topic().name(topic_name).send().await.expect("setup");
    let arn = create.topic_arn().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let sns = sns.clone();
        let arn = arn.clone();
        async move {
            let _ = sns.delete_topic().topic_arn(&arn).send().await;
        }
    });

    let result = sns
        .publish()
        .topic_arn(&arn)
        .message(r#"{"event":"rust-test"}"#)
        .subject("RustTest")
        .send()
        .await;
    assert!(result.is_ok(), "Publish failed: {:?}", result.err());
    assert!(result.unwrap().message_id().is_some());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sns_subscribe_and_unsubscribe() {
    let sns = common::sns_client().await;
    let sqs = common::sqs_client().await;
    let topic_name = "rust-test-subscribe";
    let queue_name = "rust-test-sns-target";

    // Setup topic
    let create = sns.create_topic().name(topic_name).send().await.expect("setup topic");
    let topic_arn = create.topic_arn().unwrap_or("").to_string();

    // Setup queue
    let queue = sqs.create_queue().queue_name(queue_name).send().await.expect("setup queue");
    let queue_url = queue.queue_url().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let sns = sns.clone();
        let sqs = sqs.clone();
        let topic_arn = topic_arn.clone();
        let queue_url = queue_url.clone();
        async move {
            let _ = sns.delete_topic().topic_arn(&topic_arn).send().await;
            let _ = sqs.delete_queue().queue_url(&queue_url).send().await;
        }
    });

    // Get queue ARN
    use aws_sdk_sqs::types::QueueAttributeName;
    let attrs = sqs
        .get_queue_attributes()
        .queue_url(&queue_url)
        .attribute_names(QueueAttributeName::QueueArn)
        .send()
        .await
        .expect("get attrs");
    let queue_arn = attrs
        .attributes()
        .and_then(|a| a.get(&QueueAttributeName::QueueArn))
        .map(|s| s.as_str())
        .unwrap_or("");

    // Subscribe
    let sub_result = sns
        .subscribe()
        .topic_arn(&topic_arn)
        .protocol("sqs")
        .endpoint(queue_arn)
        .send()
        .await;
    assert!(sub_result.is_ok(), "Subscribe failed: {:?}", sub_result.err());

    let sub_arn = sub_result.unwrap().subscription_arn().unwrap_or("").to_string();
    assert!(!sub_arn.is_empty());

    // List subscriptions
    let list = sns.list_subscriptions().send().await;
    assert!(list.is_ok(), "ListSubscriptions failed: {:?}", list.err());
    assert!(!list.unwrap().subscriptions().is_empty());

    // Unsubscribe
    let unsub = sns.unsubscribe().subscription_arn(&sub_arn).send().await;
    assert!(unsub.is_ok(), "Unsubscribe failed: {:?}", unsub.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sns_delete_topic() {
    let sns = common::sns_client().await;
    let topic_name = "rust-test-delete-topic";

    // No cleanup guard needed - test is about deletion

    // Setup
    let create = sns.create_topic().name(topic_name).send().await.expect("setup");
    let arn = create.topic_arn().unwrap_or("").to_string();

    let result = sns.delete_topic().topic_arn(&arn).send().await;
    assert!(result.is_ok(), "DeleteTopic failed: {:?}", result.err());
}
