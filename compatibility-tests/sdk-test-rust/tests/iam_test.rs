mod common;

#[tokio::test(flavor = "multi_thread")]
async fn test_iam_create_role() {
    let iam = common::iam_client().await;
    let role_name = "rust-test-create-role";
    let assume_policy = r#"{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}"#;

    let _guard = common::CleanupGuard::new({
        let iam = iam.clone();
        async move {
            let _ = iam.delete_role().role_name(role_name).send().await;
        }
    });

    let result = iam
        .create_role()
        .role_name(role_name)
        .assume_role_policy_document(assume_policy)
        .send()
        .await;
    assert!(result.is_ok(), "CreateRole failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_iam_get_role() {
    let iam = common::iam_client().await;
    let role_name = "rust-test-get-role";
    let assume_policy = r#"{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}"#;

    let _guard = common::CleanupGuard::new({
        let iam = iam.clone();
        async move {
            let _ = iam.delete_role().role_name(role_name).send().await;
        }
    });

    // Setup
    iam.create_role()
        .role_name(role_name)
        .assume_role_policy_document(assume_policy)
        .send()
        .await
        .expect("setup");

    let result = iam.get_role().role_name(role_name).send().await;
    assert!(result.is_ok(), "GetRole failed: {:?}", result.err());
    assert_eq!(
        result.unwrap().role().map(|r| r.role_name()).unwrap_or(""),
        role_name
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_iam_list_roles() {
    let iam = common::iam_client().await;
    let role_name = "rust-test-list-roles";
    let assume_policy = r#"{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}"#;

    let _guard = common::CleanupGuard::new({
        let iam = iam.clone();
        async move {
            let _ = iam.delete_role().role_name(role_name).send().await;
        }
    });

    // Setup
    iam.create_role()
        .role_name(role_name)
        .assume_role_policy_document(assume_policy)
        .send()
        .await
        .expect("setup");

    let result = iam.list_roles().send().await;
    assert!(result.is_ok(), "ListRoles failed: {:?}", result.err());
    assert!(!result.unwrap().roles().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_iam_delete_role() {
    let iam = common::iam_client().await;
    let role_name = "rust-test-delete-role";
    let assume_policy = r#"{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}"#;

    // No cleanup guard needed - test is about deletion

    // Setup
    iam.create_role()
        .role_name(role_name)
        .assume_role_policy_document(assume_policy)
        .send()
        .await
        .expect("setup");

    let result = iam.delete_role().role_name(role_name).send().await;
    assert!(result.is_ok(), "DeleteRole failed: {:?}", result.err());
}
