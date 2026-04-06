mod common;

use aws_sdk_dynamodb::types::{
    AttributeDefinition, AttributeValue, BillingMode, KeySchemaElement, KeyType,
    ScalarAttributeType,
};

#[tokio::test(flavor = "multi_thread")]
async fn test_dynamodb_create_table() {
    let ddb = common::dynamodb_client().await;
    let table = "rust-test-create-table";

    let _guard = common::CleanupGuard::new({
        let ddb = ddb.clone();
        async move {
            let _ = ddb.delete_table().table_name(table).send().await;
        }
    });

    let result = ddb
        .create_table()
        .table_name(table)
        .billing_mode(BillingMode::PayPerRequest)
        .attribute_definitions(
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        )
        .key_schema(
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
        )
        .send()
        .await;
    assert!(result.is_ok(), "CreateTable failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_dynamodb_describe_table() {
    let ddb = common::dynamodb_client().await;
    let table = "rust-test-describe-table";

    let _guard = common::CleanupGuard::new({
        let ddb = ddb.clone();
        async move {
            let _ = ddb.delete_table().table_name(table).send().await;
        }
    });

    // Setup
    ddb.create_table()
        .table_name(table)
        .billing_mode(BillingMode::PayPerRequest)
        .attribute_definitions(
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        )
        .key_schema(
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
        )
        .send()
        .await
        .expect("setup");

    let result = ddb.describe_table().table_name(table).send().await;
    assert!(result.is_ok(), "DescribeTable failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_dynamodb_list_tables() {
    let ddb = common::dynamodb_client().await;
    let table = "rust-test-list-tables";

    let _guard = common::CleanupGuard::new({
        let ddb = ddb.clone();
        async move {
            let _ = ddb.delete_table().table_name(table).send().await;
        }
    });

    // Setup
    ddb.create_table()
        .table_name(table)
        .billing_mode(BillingMode::PayPerRequest)
        .attribute_definitions(
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        )
        .key_schema(
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
        )
        .send()
        .await
        .expect("setup");

    let result = ddb.list_tables().send().await;
    assert!(result.is_ok(), "ListTables failed: {:?}", result.err());
    assert!(!result.unwrap().table_names().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_dynamodb_put_and_get_item() {
    let ddb = common::dynamodb_client().await;
    let table = "rust-test-put-get";

    let _guard = common::CleanupGuard::new({
        let ddb = ddb.clone();
        async move {
            let _ = ddb.delete_table().table_name(table).send().await;
        }
    });

    // Setup
    ddb.create_table()
        .table_name(table)
        .billing_mode(BillingMode::PayPerRequest)
        .attribute_definitions(
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        )
        .key_schema(
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
        )
        .send()
        .await
        .expect("setup");

    // Put
    let put = ddb
        .put_item()
        .table_name(table)
        .item("pk", AttributeValue::S("user#1".into()))
        .item("name", AttributeValue::S("Alice".into()))
        .send()
        .await;
    assert!(put.is_ok(), "PutItem failed: {:?}", put.err());

    // Get
    let get = ddb
        .get_item()
        .table_name(table)
        .key("pk", AttributeValue::S("user#1".into()))
        .send()
        .await;
    assert!(get.is_ok(), "GetItem failed: {:?}", get.err());

    let item = get.unwrap().item;
    assert!(item.is_some());
    let name = item
        .as_ref()
        .and_then(|i| i.get("name"))
        .and_then(|v| v.as_s().ok());
    assert_eq!(name, Some(&"Alice".to_string()));
}

#[tokio::test(flavor = "multi_thread")]
async fn test_dynamodb_query() {
    let ddb = common::dynamodb_client().await;
    let table = "rust-test-query";

    let _guard = common::CleanupGuard::new({
        let ddb = ddb.clone();
        async move {
            let _ = ddb.delete_table().table_name(table).send().await;
        }
    });

    // Setup
    ddb.create_table()
        .table_name(table)
        .billing_mode(BillingMode::PayPerRequest)
        .attribute_definitions(
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        )
        .key_schema(
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
        )
        .send()
        .await
        .expect("setup");

    ddb.put_item()
        .table_name(table)
        .item("pk", AttributeValue::S("user#1".into()))
        .send()
        .await
        .expect("put");

    let result = ddb
        .query()
        .table_name(table)
        .key_condition_expression("pk = :pk")
        .expression_attribute_values(":pk", AttributeValue::S("user#1".into()))
        .send()
        .await;
    assert!(result.is_ok(), "Query failed: {:?}", result.err());
    assert!(!result.unwrap().items().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_dynamodb_scan() {
    let ddb = common::dynamodb_client().await;
    let table = "rust-test-scan";

    let _guard = common::CleanupGuard::new({
        let ddb = ddb.clone();
        async move {
            let _ = ddb.delete_table().table_name(table).send().await;
        }
    });

    // Setup
    ddb.create_table()
        .table_name(table)
        .billing_mode(BillingMode::PayPerRequest)
        .attribute_definitions(
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        )
        .key_schema(
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
        )
        .send()
        .await
        .expect("setup");

    ddb.put_item()
        .table_name(table)
        .item("pk", AttributeValue::S("item#1".into()))
        .send()
        .await
        .expect("put");

    let result = ddb.scan().table_name(table).send().await;
    assert!(result.is_ok(), "Scan failed: {:?}", result.err());
    assert!(!result.unwrap().items().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_dynamodb_delete_table() {
    let ddb = common::dynamodb_client().await;
    let table = "rust-test-delete-table";

    // No cleanup guard needed - test is about deletion

    // Setup
    ddb.create_table()
        .table_name(table)
        .billing_mode(BillingMode::PayPerRequest)
        .attribute_definitions(
            AttributeDefinition::builder()
                .attribute_name("pk")
                .attribute_type(ScalarAttributeType::S)
                .build()
                .unwrap(),
        )
        .key_schema(
            KeySchemaElement::builder()
                .attribute_name("pk")
                .key_type(KeyType::Hash)
                .build()
                .unwrap(),
        )
        .send()
        .await
        .expect("setup");

    let result = ddb.delete_table().table_name(table).send().await;
    assert!(result.is_ok(), "DeleteTable failed: {:?}", result.err());
}
