mod common;

use aws_sdk_s3::types::{BucketLocationConstraint, CreateBucketConfiguration, Delete, ObjectIdentifier};

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_create_bucket() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-create-bucket";

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    let result = s3.create_bucket().bucket(bucket).send().await;
    assert!(result.is_ok(), "CreateBucket failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_create_bucket_with_location_constraint() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-eu-bucket";

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    let result = s3
        .create_bucket()
        .bucket(bucket)
        .create_bucket_configuration(
            CreateBucketConfiguration::builder()
                .location_constraint(BucketLocationConstraint::EuCentral1)
                .build(),
        )
        .send()
        .await;
    assert!(result.is_ok(), "CreateBucket with location failed: {:?}", result.err());

    // Verify location
    let loc = s3.get_bucket_location().bucket(bucket).send().await;
    assert!(loc.is_ok());
    assert_eq!(
        loc.unwrap()
            .location_constraint()
            .map(|c| c.as_str())
            .unwrap_or(""),
        "eu-central-1"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_list_buckets() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-list-buckets";

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("setup");

    let result = s3.list_buckets().send().await;
    assert!(result.is_ok(), "ListBuckets failed: {:?}", result.err());
    assert!(!result.unwrap().buckets().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_put_and_get_object() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-put-get";
    let key = "test.json";
    let content = r#"{"source":"rust-test"}"#;

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3.delete_object().bucket(bucket).key(key).send().await;
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("setup");

    // Put
    let put_result = s3
        .put_object()
        .bucket(bucket)
        .key(key)
        .body(bytes::Bytes::from(content).into())
        .content_type("application/json")
        .send()
        .await;
    assert!(put_result.is_ok(), "PutObject failed: {:?}", put_result.err());

    // Get
    let get_result = s3.get_object().bucket(bucket).key(key).send().await;
    assert!(get_result.is_ok(), "GetObject failed: {:?}", get_result.err());

    let body = get_result
        .unwrap()
        .body
        .collect()
        .await
        .map(|b| b.into_bytes());
    assert!(body.is_ok());
    assert_eq!(body.unwrap().as_ref(), content.as_bytes());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_head_object() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-head";
    let key = "head-test.txt";

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3.delete_object().bucket(bucket).key(key).send().await;
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("setup");
    s3.put_object()
        .bucket(bucket)
        .key(key)
        .body(bytes::Bytes::from("test").into())
        .send()
        .await
        .expect("put");

    let result = s3.head_object().bucket(bucket).key(key).send().await;
    assert!(result.is_ok(), "HeadObject failed: {:?}", result.err());

    let head = result.unwrap();
    assert!(head.last_modified().is_some());
    // Check second precision
    assert_eq!(head.last_modified().unwrap().subsec_nanos(), 0);
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_list_objects_v2() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-list-objects";
    let key = "list-test.txt";

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3.delete_object().bucket(bucket).key(key).send().await;
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("setup");
    s3.put_object()
        .bucket(bucket)
        .key(key)
        .body(bytes::Bytes::from("test").into())
        .send()
        .await
        .expect("put");

    let result = s3.list_objects_v2().bucket(bucket).send().await;
    assert!(result.is_ok(), "ListObjectsV2 failed: {:?}", result.err());
    assert!(!result.unwrap().contents().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_copy_object() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-copy";
    let src_key = "source.txt";
    let dst_key = "copy.txt";

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3
                .delete_objects()
                .bucket(bucket)
                .delete(
                    Delete::builder()
                        .objects(ObjectIdentifier::builder().key(src_key).build().unwrap())
                        .objects(ObjectIdentifier::builder().key(dst_key).build().unwrap())
                        .build()
                        .unwrap(),
                )
                .send()
                .await;
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("setup");
    s3.put_object()
        .bucket(bucket)
        .key(src_key)
        .body(bytes::Bytes::from("source").into())
        .send()
        .await
        .expect("put");

    let copy_source = format!("{}/{}", bucket, src_key);
    let result = s3
        .copy_object()
        .bucket(bucket)
        .copy_source(&copy_source)
        .key(dst_key)
        .send()
        .await;
    assert!(result.is_ok(), "CopyObject failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_delete_bucket() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-delete-bucket";

    // No cleanup guard needed - test is about deletion

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("setup");

    let result = s3.delete_bucket().bucket(bucket).send().await;
    assert!(result.is_ok(), "DeleteBucket failed: {:?}", result.err());
}

/// Percent-encodes non-ASCII bytes in an S3 key, preserving / as a path separator.
/// The Rust SDK does not URL-encode CopySource headers, so this must be done manually.
fn s3_encode_key(key: &str) -> String {
    let mut out = String::with_capacity(key.len());
    for b in key.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' | b'/' => {
                out.push(b as char);
            }
            _ => {
                out.push_str(&format!("%{:02X}", b));
            }
        }
    }
    out
}

/// Regression test for issue #93: CopyObject with non-ASCII (multibyte) key.
/// The Rust SDK does not URL-encode CopySource headers; encode the key manually.
#[tokio::test(flavor = "multi_thread")]
async fn test_s3_copy_object_non_ascii_key() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-non-ascii";
    let src_key = "src/テスト画像.png";
    let dst_key = "dst/テスト画像.png";

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3
                .delete_objects()
                .bucket(bucket)
                .delete(
                    Delete::builder()
                        .objects(ObjectIdentifier::builder().key(src_key).build().unwrap())
                        .objects(ObjectIdentifier::builder().key(dst_key).build().unwrap())
                        .build()
                        .unwrap(),
                )
                .send()
                .await;
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("create bucket");
    s3.put_object()
        .bucket(bucket)
        .key(src_key)
        .body(bytes::Bytes::from("non-ascii content").into())
        .send()
        .await
        .expect("put source object");

    // Copy with URL-encoded non-ASCII key
    let copy_source = format!("{}/{}", bucket, s3_encode_key(src_key));
    let result = s3
        .copy_object()
        .bucket(bucket)
        .copy_source(&copy_source)
        .key(dst_key)
        .send()
        .await;

    assert!(
        result.is_ok(),
        "CopyObject with non-ASCII key failed: {:?}",
        result.err()
    );

    // Verify the copy exists
    let head = s3.head_object().bucket(bucket).key(dst_key).send().await;
    assert!(head.is_ok(), "copied object should exist");
}

/// Test large object upload (25 MB) - validates upload size limit handling.
#[tokio::test(flavor = "multi_thread")]
async fn test_s3_put_object_25mb() {
    let s3 = common::s3_client().await;
    let bucket = "rust-test-large-upload";
    let key = "large-object-25mb.bin";
    let size: i64 = 25 * 1024 * 1024; // 25 MB

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        async move {
            let _ = s3.delete_object().bucket(bucket).key(key).send().await;
            let _ = s3.delete_bucket().bucket(bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(bucket).send().await.expect("create bucket");

    // Create 25 MB payload
    let payload = vec![0u8; size as usize];

    // Upload
    let put_result = s3
        .put_object()
        .bucket(bucket)
        .key(key)
        .body(bytes::Bytes::from(payload).into())
        .content_type("application/octet-stream")
        .content_length(size)
        .send()
        .await;

    assert!(
        put_result.is_ok(),
        "PutObject 25 MB failed: {:?}",
        put_result.err()
    );

    // Verify content-length via HeadObject
    let head_result = s3.head_object().bucket(bucket).key(key).send().await;
    assert!(
        head_result.is_ok(),
        "HeadObject failed: {:?}",
        head_result.err()
    );

    let head = head_result.unwrap();
    assert_eq!(
        head.content_length(),
        Some(size),
        "content-length should be {} bytes",
        size
    );
}
