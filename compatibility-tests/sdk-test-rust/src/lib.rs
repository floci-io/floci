//! Shared test utilities for Floci SDK tests.

use aws_config::BehaviorVersion;
use aws_credential_types::Credentials;

/// Returns the Floci endpoint from environment or default.
pub fn endpoint() -> String {
    std::env::var("FLOCI_ENDPOINT").unwrap_or_else(|_| "http://localhost:4566".into())
}

/// Returns a base AWS SDK config for the Floci endpoint.
pub async fn base_config() -> aws_config::SdkConfig {
    let endpoint = endpoint();
    let creds = Credentials::new("test", "test", None, None, "static");
    aws_config::defaults(BehaviorVersion::latest())
        .region(aws_types::region::Region::new("us-east-1"))
        .credentials_provider(creds)
        .endpoint_url(endpoint)
        .load()
        .await
}

/// Returns an SSM client.
pub async fn ssm_client() -> aws_sdk_ssm::Client {
    aws_sdk_ssm::Client::new(&base_config().await)
}

/// Returns an SQS client.
pub async fn sqs_client() -> aws_sdk_sqs::Client {
    aws_sdk_sqs::Client::new(&base_config().await)
}

/// Returns an SNS client.
pub async fn sns_client() -> aws_sdk_sns::Client {
    aws_sdk_sns::Client::new(&base_config().await)
}

/// Returns an S3 client with path-style addressing.
pub async fn s3_client() -> aws_sdk_s3::Client {
    aws_sdk_s3::Client::from_conf(
        aws_sdk_s3::config::Builder::from(&base_config().await)
            .force_path_style(true)
            .build(),
    )
}

/// Returns a DynamoDB client.
pub async fn dynamodb_client() -> aws_sdk_dynamodb::Client {
    aws_sdk_dynamodb::Client::new(&base_config().await)
}

/// Returns a Lambda client.
pub async fn lambda_client() -> aws_sdk_lambda::Client {
    aws_sdk_lambda::Client::new(&base_config().await)
}

/// Returns an IAM client.
pub async fn iam_client() -> aws_sdk_iam::Client {
    aws_sdk_iam::Client::new(&base_config().await)
}

/// Returns an STS client.
pub async fn sts_client() -> aws_sdk_sts::Client {
    aws_sdk_sts::Client::new(&base_config().await)
}

/// Returns a Secrets Manager client.
pub async fn secretsmanager_client() -> aws_sdk_secretsmanager::Client {
    aws_sdk_secretsmanager::Client::new(&base_config().await)
}

/// Returns a KMS client.
pub async fn kms_client() -> aws_sdk_kms::Client {
    aws_sdk_kms::Client::new(&base_config().await)
}

/// Returns a Kinesis client.
pub async fn kinesis_client() -> aws_sdk_kinesis::Client {
    aws_sdk_kinesis::Client::new(&base_config().await)
}

/// Returns a CloudWatch client.
pub async fn cloudwatch_client() -> aws_sdk_cloudwatch::Client {
    aws_sdk_cloudwatch::Client::new(&base_config().await)
}

/// Returns a Cognito Identity Provider client.
pub async fn cognito_client() -> aws_sdk_cognitoidentityprovider::Client {
    aws_sdk_cognitoidentityprovider::Client::new(&base_config().await)
}

/// Returns a CloudFormation client.
pub async fn cloudformation_client() -> aws_sdk_cloudformation::Client {
    aws_sdk_cloudformation::Client::new(&base_config().await)
}

/// Returns a minimal Lambda deployment zip with a Node.js handler.
pub fn minimal_zip() -> Vec<u8> {
    use std::io::Write;
    let code = r#"exports.handler = async (event) => {
  const name = (event && event.name) ? event.name : "World";
  return { statusCode: 200, body: JSON.stringify({ message: "Hello, " + name + "!" }) };
};"#;
    let mut buf = Vec::new();
    {
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(&mut buf));
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file("index.js", options).unwrap();
        zip.write_all(code.as_bytes()).unwrap();
        zip.finish().unwrap();
    }
    buf
}
