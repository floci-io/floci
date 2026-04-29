mod common;

#[tokio::test(flavor = "multi_thread")]
async fn test_cognito_describe_user_pool_returns_all_standard_attributes() {
    let cognito = common::cognito_client().await;
    let pool_name = "rust-test-cognito-standard-attrs";

    let created = cognito
        .create_user_pool()
        .pool_name(pool_name)
        .send()
        .await
        .expect("CreateUserPool failed");

    let pool_id = created
        .user_pool()
        .and_then(|p| p.id())
        .expect("pool id missing")
        .to_string();

    let _guard = common::CleanupGuard::new({
        let cognito = cognito.clone();
        let pool_id = pool_id.clone();
        async move {
            let _ = cognito
                .delete_user_pool()
                .user_pool_id(pool_id)
                .send()
                .await;
        }
    });

    let resp = cognito
        .describe_user_pool()
        .user_pool_id(&pool_id)
        .send()
        .await
        .expect("DescribeUserPool failed");

    let schema = resp
        .user_pool()
        .and_then(|p| Some(p.schema_attributes()))
        .unwrap_or_default();

    assert_eq!(schema.len(), 20, "DescribeUserPool must return all 20 standard Cognito attributes");

    let names: Vec<&str> = schema.iter().filter_map(|a| a.name()).collect();

    let expected = [
        "sub", "name", "given_name", "family_name", "middle_name", "nickname",
        "preferred_username", "profile", "picture", "website", "email",
        "email_verified", "gender", "birthdate", "zoneinfo", "locale",
        "phone_number", "phone_number_verified", "address", "updated_at",
    ];
    for attr in &expected {
        assert!(names.contains(attr), "missing standard attribute: {attr}");
    }

    // spot-check sub
    let sub = schema.iter().find(|a| a.name() == Some("sub")).expect("sub not found");
    assert_eq!(sub.required(), Some(true), "sub must be Required");
    assert_eq!(sub.mutable(), Some(false), "sub must not be Mutable");
}
