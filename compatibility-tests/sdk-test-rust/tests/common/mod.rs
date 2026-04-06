//! Shared test utilities for integration tests.

pub use sdk_test_rust::*;

use std::future::Future;
use std::pin::Pin;

// Allow unused when tests don't use CleanupGuard (sts, kms, cloudwatch)
#[allow(dead_code)]
type BoxFuture = Pin<Box<dyn Future<Output = ()> + Send>>;

/// Guard that runs async cleanup when dropped, even on test panic.
///
/// **Important**: Tests using this guard must use multi-threaded runtime:
/// `#[tokio::test(flavor = "multi_thread")]`
///
/// # Example
/// ```ignore
/// #[tokio::test(flavor = "multi_thread")]
/// async fn test_example() {
///     let s3 = common::s3_client().await;
///     let bucket = "my-test-bucket";
///
///     // Create guard - cleanup runs when guard is dropped
///     let _guard = common::CleanupGuard::new({
///         let s3 = s3.clone();
///         let bucket = bucket.to_string();
///         async move {
///             let _ = s3.delete_bucket().bucket(&bucket).send().await;
///         }
///     });
///
///     // Test code - if this panics, cleanup still runs
///     s3.create_bucket().bucket(bucket).send().await.unwrap();
/// }
/// ```
// Allow unused in tests that don't need cleanup (sts, kms, cloudwatch)
#[allow(dead_code)]
pub struct CleanupGuard {
    cleanup: Option<BoxFuture>,
}

impl CleanupGuard {
    /// Create a new cleanup guard with the given async cleanup function.
    #[allow(dead_code)]
    pub fn new<F>(cleanup: F) -> Self
    where
        F: Future<Output = ()> + Send + 'static,
    {
        Self {
            cleanup: Some(Box::pin(cleanup)),
        }
    }
}

impl Drop for CleanupGuard {
    fn drop(&mut self) {
        if let Some(fut) = self.cleanup.take() {
            // Run async cleanup synchronously using the current tokio runtime
            tokio::task::block_in_place(|| {
                tokio::runtime::Handle::current().block_on(fut);
            });
        }
    }
}
