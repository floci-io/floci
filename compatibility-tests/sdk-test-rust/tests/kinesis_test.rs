mod common;

use aws_sdk_kinesis::types::ShardIteratorType;

#[tokio::test(flavor = "multi_thread")]
async fn test_kinesis_create_stream() {
    let kinesis = common::kinesis_client().await;
    let stream_name = "rust-test-create-stream";

    let _guard = common::CleanupGuard::new({
        let kinesis = kinesis.clone();
        async move {
            let _ = kinesis.delete_stream().stream_name(stream_name).send().await;
        }
    });

    let result = kinesis
        .create_stream()
        .stream_name(stream_name)
        .shard_count(1)
        .send()
        .await;
    assert!(result.is_ok(), "CreateStream failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_kinesis_list_streams() {
    let kinesis = common::kinesis_client().await;
    let stream_name = "rust-test-list-streams";

    let _guard = common::CleanupGuard::new({
        let kinesis = kinesis.clone();
        async move {
            let _ = kinesis.delete_stream().stream_name(stream_name).send().await;
        }
    });

    // Setup
    kinesis
        .create_stream()
        .stream_name(stream_name)
        .shard_count(1)
        .send()
        .await
        .expect("setup");

    let result = kinesis.list_streams().send().await;
    assert!(result.is_ok(), "ListStreams failed: {:?}", result.err());
    assert!(!result.unwrap().stream_names().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_kinesis_describe_stream() {
    let kinesis = common::kinesis_client().await;
    let stream_name = "rust-test-describe-stream";

    let _guard = common::CleanupGuard::new({
        let kinesis = kinesis.clone();
        async move {
            let _ = kinesis.delete_stream().stream_name(stream_name).send().await;
        }
    });

    // Setup
    kinesis
        .create_stream()
        .stream_name(stream_name)
        .shard_count(1)
        .send()
        .await
        .expect("setup");

    let result = kinesis.describe_stream().stream_name(stream_name).send().await;
    assert!(result.is_ok(), "DescribeStream failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_kinesis_put_record() {
    let kinesis = common::kinesis_client().await;
    let stream_name = "rust-test-put-record";

    let _guard = common::CleanupGuard::new({
        let kinesis = kinesis.clone();
        async move {
            let _ = kinesis.delete_stream().stream_name(stream_name).send().await;
        }
    });

    // Setup
    kinesis
        .create_stream()
        .stream_name(stream_name)
        .shard_count(1)
        .send()
        .await
        .expect("setup");

    let result = kinesis
        .put_record()
        .stream_name(stream_name)
        .data(aws_smithy_types::Blob::new(b"{\"event\":\"rust-test\"}".to_vec()))
        .partition_key("pk1")
        .send()
        .await;
    assert!(result.is_ok(), "PutRecord failed: {:?}", result.err());
    assert!(!result.unwrap().shard_id().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_kinesis_get_records() {
    let kinesis = common::kinesis_client().await;
    let stream_name = "rust-test-get-records";

    let _guard = common::CleanupGuard::new({
        let kinesis = kinesis.clone();
        async move {
            let _ = kinesis.delete_stream().stream_name(stream_name).send().await;
        }
    });

    // Setup
    kinesis
        .create_stream()
        .stream_name(stream_name)
        .shard_count(1)
        .send()
        .await
        .expect("setup");

    // Put a record
    kinesis
        .put_record()
        .stream_name(stream_name)
        .data(aws_smithy_types::Blob::new(b"test data".to_vec()))
        .partition_key("pk1")
        .send()
        .await
        .expect("put record");

    // Get shard ID
    let desc = kinesis
        .describe_stream()
        .stream_name(stream_name)
        .send()
        .await
        .expect("describe");
    let shard_id = desc
        .stream_description()
        .and_then(|d| d.shards().first())
        .map(|s| s.shard_id())
        .unwrap_or("");

    // Get shard iterator
    let iter = kinesis
        .get_shard_iterator()
        .stream_name(stream_name)
        .shard_id(shard_id)
        .shard_iterator_type(ShardIteratorType::TrimHorizon)
        .send()
        .await
        .expect("get iterator");

    let shard_iterator = iter.shard_iterator().unwrap_or("");

    // Get records
    let result = kinesis
        .get_records()
        .shard_iterator(shard_iterator)
        .limit(10)
        .send()
        .await;
    assert!(result.is_ok(), "GetRecords failed: {:?}", result.err());
    assert!(!result.unwrap().records().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_kinesis_delete_stream() {
    let kinesis = common::kinesis_client().await;
    let stream_name = "rust-test-delete-stream";

    // No cleanup guard needed - test is about deletion

    // Setup
    kinesis
        .create_stream()
        .stream_name(stream_name)
        .shard_count(1)
        .send()
        .await
        .expect("setup");

    let result = kinesis.delete_stream().stream_name(stream_name).send().await;
    assert!(result.is_ok(), "DeleteStream failed: {:?}", result.err());
}
