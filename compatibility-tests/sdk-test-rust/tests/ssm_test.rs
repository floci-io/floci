mod common;

use aws_sdk_ssm::types::ParameterType;

#[tokio::test(flavor = "multi_thread")]
async fn test_ssm_put_parameter() {
    let ssm = common::ssm_client().await;
    let name = "/rust-test/param";
    let value = "rust-test-value";

    let _guard = common::CleanupGuard::new({
        let ssm = ssm.clone();
        async move {
            let _ = ssm.delete_parameter().name(name).send().await;
        }
    });

    let result = ssm
        .put_parameter()
        .name(name)
        .value(value)
        .r#type(ParameterType::String)
        .overwrite(true)
        .send()
        .await;

    assert!(result.is_ok(), "PutParameter failed: {:?}", result.err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_ssm_get_parameter() {
    let ssm = common::ssm_client().await;
    let name = "/rust-test/get-param";
    let value = "rust-get-value";

    let _guard = common::CleanupGuard::new({
        let ssm = ssm.clone();
        async move {
            let _ = ssm.delete_parameter().name(name).send().await;
        }
    });

    // Setup
    ssm.put_parameter()
        .name(name)
        .value(value)
        .r#type(ParameterType::String)
        .overwrite(true)
        .send()
        .await
        .expect("setup put");

    let result = ssm.get_parameter().name(name).send().await;
    assert!(result.is_ok(), "GetParameter failed: {:?}", result.err());

    let param = result.unwrap();
    assert_eq!(
        param.parameter().and_then(|p| p.value()).unwrap_or(""),
        value
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_ssm_get_parameters() {
    let ssm = common::ssm_client().await;
    let name = "/rust-test/get-params";

    let _guard = common::CleanupGuard::new({
        let ssm = ssm.clone();
        async move {
            let _ = ssm.delete_parameter().name(name).send().await;
        }
    });

    // Setup
    ssm.put_parameter()
        .name(name)
        .value("test")
        .r#type(ParameterType::String)
        .overwrite(true)
        .send()
        .await
        .expect("setup");

    let result = ssm.get_parameters().names(name).send().await;
    assert!(result.is_ok(), "GetParameters failed: {:?}", result.err());
    assert_eq!(result.unwrap().parameters().len(), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn test_ssm_describe_parameters() {
    let ssm = common::ssm_client().await;
    let name = "/rust-test/describe";

    let _guard = common::CleanupGuard::new({
        let ssm = ssm.clone();
        async move {
            let _ = ssm.delete_parameter().name(name).send().await;
        }
    });

    // Setup
    ssm.put_parameter()
        .name(name)
        .value("test")
        .r#type(ParameterType::String)
        .overwrite(true)
        .send()
        .await
        .expect("setup");

    let result = ssm.describe_parameters().send().await;
    assert!(result.is_ok(), "DescribeParameters failed: {:?}", result.err());
    assert!(!result.unwrap().parameters().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_ssm_get_parameters_by_path() {
    let ssm = common::ssm_client().await;
    let name = "/rust-test/bypath/param";

    let _guard = common::CleanupGuard::new({
        let ssm = ssm.clone();
        async move {
            let _ = ssm.delete_parameter().name(name).send().await;
        }
    });

    // Setup
    ssm.put_parameter()
        .name(name)
        .value("test")
        .r#type(ParameterType::String)
        .overwrite(true)
        .send()
        .await
        .expect("setup");

    let result = ssm
        .get_parameters_by_path()
        .path("/rust-test/bypath")
        .send()
        .await;
    assert!(result.is_ok(), "GetParametersByPath failed: {:?}", result.err());
    assert!(!result.unwrap().parameters().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_ssm_delete_parameter() {
    let ssm = common::ssm_client().await;
    let name = "/rust-test/delete";

    // No cleanup guard needed - test is about deletion

    // Setup
    ssm.put_parameter()
        .name(name)
        .value("test")
        .r#type(ParameterType::String)
        .overwrite(true)
        .send()
        .await
        .expect("setup");

    let result = ssm.delete_parameter().name(name).send().await;
    assert!(result.is_ok(), "DeleteParameter failed: {:?}", result.err());
}
