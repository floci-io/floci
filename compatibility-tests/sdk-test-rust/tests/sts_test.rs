mod common;

#[tokio::test]
async fn test_sts_get_caller_identity() {
    let sts = common::sts_client().await;

    let result = sts.get_caller_identity().send().await;
    assert!(result.is_ok(), "GetCallerIdentity failed: {:?}", result.err());

    let identity = result.unwrap();
    assert!(identity.account().is_some());
}
