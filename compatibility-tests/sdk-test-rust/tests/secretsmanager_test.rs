mod common;

#[tokio::test(flavor = "multi_thread")]
async fn test_secretsmanager_create_secret() {
    let sm = common::secretsmanager_client().await;
    let name = "rust-test-create-secret";

    let _guard = common::CleanupGuard::new({
        let sm = sm.clone();
        async move {
            let _ = sm
                .delete_secret()
                .secret_id(name)
                .force_delete_without_recovery(true)
                .send()
                .await;
        }
    });

    let result = sm
        .create_secret()
        .name(name)
        .secret_string(r#"{"key":"value"}"#)
        .send()
        .await;
    assert!(result.is_ok(), "CreateSecret failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_secretsmanager_get_secret_value() {
    let sm = common::secretsmanager_client().await;
    let name = "rust-test-get-secret";
    let secret = r#"{"username":"admin"}"#;

    let _guard = common::CleanupGuard::new({
        let sm = sm.clone();
        async move {
            let _ = sm
                .delete_secret()
                .secret_id(name)
                .force_delete_without_recovery(true)
                .send()
                .await;
        }
    });

    // Setup
    sm.create_secret()
        .name(name)
        .secret_string(secret)
        .send()
        .await
        .expect("setup");

    let result = sm.get_secret_value().secret_id(name).send().await;
    assert!(result.is_ok(), "GetSecretValue failed: {:?}", result.err());
    assert_eq!(result.unwrap().secret_string().unwrap_or(""), secret);
}

#[tokio::test(flavor = "multi_thread")]
async fn test_secretsmanager_list_secrets() {
    let sm = common::secretsmanager_client().await;
    let name = "rust-test-list-secret";

    let _guard = common::CleanupGuard::new({
        let sm = sm.clone();
        async move {
            let _ = sm
                .delete_secret()
                .secret_id(name)
                .force_delete_without_recovery(true)
                .send()
                .await;
        }
    });

    // Setup
    sm.create_secret()
        .name(name)
        .secret_string("test")
        .send()
        .await
        .expect("setup");

    let result = sm.list_secrets().send().await;
    assert!(result.is_ok(), "ListSecrets failed: {:?}", result.err());
    assert!(!result.unwrap().secret_list().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_secretsmanager_delete_secret() {
    let sm = common::secretsmanager_client().await;
    let name = "rust-test-delete-secret";

    // No cleanup guard needed - test is about deletion

    // Setup
    sm.create_secret()
        .name(name)
        .secret_string("test")
        .send()
        .await
        .expect("setup");

    let result = sm
        .delete_secret()
        .secret_id(name)
        .force_delete_without_recovery(true)
        .send()
        .await;
    assert!(result.is_ok(), "DeleteSecret failed: {:?}", result.err());
}
