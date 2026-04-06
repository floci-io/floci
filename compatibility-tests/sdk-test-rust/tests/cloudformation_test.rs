mod common;

#[tokio::test(flavor = "multi_thread")]
async fn test_cloudformation_create_stack() {
    let cfn = common::cloudformation_client().await;
    let stack_name = "rust-test-stack";
    let template = r#"{
        "AWSTemplateFormatVersion": "2010-09-09",
        "Description": "Test stack",
        "Resources": {
            "TestQueue": {
                "Type": "AWS::SQS::Queue",
                "Properties": {
                    "QueueName": "cfn-rust-queue"
                }
            }
        }
    }"#;

    let _guard = common::CleanupGuard::new({
        let cfn = cfn.clone();
        async move {
            let _ = cfn.delete_stack().stack_name(stack_name).send().await;
        }
    });

    let result = cfn
        .create_stack()
        .stack_name(stack_name)
        .template_body(template)
        .send()
        .await;
    assert!(result.is_ok(), "CreateStack failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_cloudformation_describe_stacks() {
    let cfn = common::cloudformation_client().await;
    let stack_name = "rust-test-describe-stack";
    let template = r#"{
        "AWSTemplateFormatVersion": "2010-09-09",
        "Description": "Test stack",
        "Resources": {
            "TestQueue": {
                "Type": "AWS::SQS::Queue",
                "Properties": {
                    "QueueName": "cfn-rust-describe-queue"
                }
            }
        }
    }"#;

    let _guard = common::CleanupGuard::new({
        let cfn = cfn.clone();
        async move {
            let _ = cfn.delete_stack().stack_name(stack_name).send().await;
        }
    });

    // Setup
    cfn.create_stack()
        .stack_name(stack_name)
        .template_body(template)
        .send()
        .await
        .expect("setup");

    let result = cfn.describe_stacks().stack_name(stack_name).send().await;
    assert!(result.is_ok(), "DescribeStacks failed: {:?}", result.err());
    assert!(!result.unwrap().stacks().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_cloudformation_list_stacks() {
    let cfn = common::cloudformation_client().await;
    let stack_name = "rust-test-list-stacks";
    let template = r#"{
        "AWSTemplateFormatVersion": "2010-09-09",
        "Description": "Test stack",
        "Resources": {
            "TestQueue": {
                "Type": "AWS::SQS::Queue",
                "Properties": {
                    "QueueName": "cfn-rust-list-queue"
                }
            }
        }
    }"#;

    let _guard = common::CleanupGuard::new({
        let cfn = cfn.clone();
        async move {
            let _ = cfn.delete_stack().stack_name(stack_name).send().await;
        }
    });

    // Setup
    cfn.create_stack()
        .stack_name(stack_name)
        .template_body(template)
        .send()
        .await
        .expect("setup");

    let result = cfn.list_stacks().send().await;
    assert!(result.is_ok(), "ListStacks failed: {:?}", result.err());
    assert!(!result.unwrap().stack_summaries().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_cloudformation_delete_stack() {
    let cfn = common::cloudformation_client().await;
    let stack_name = "rust-test-delete-stack";
    let template = r#"{
        "AWSTemplateFormatVersion": "2010-09-09",
        "Description": "Test stack",
        "Resources": {
            "TestQueue": {
                "Type": "AWS::SQS::Queue",
                "Properties": {
                    "QueueName": "cfn-rust-delete-queue"
                }
            }
        }
    }"#;

    // No cleanup guard needed - test is about deletion

    // Setup
    cfn.create_stack()
        .stack_name(stack_name)
        .template_body(template)
        .send()
        .await
        .expect("setup");

    let result = cfn.delete_stack().stack_name(stack_name).send().await;
    assert!(result.is_ok(), "DeleteStack failed: {:?}", result.err());
}
