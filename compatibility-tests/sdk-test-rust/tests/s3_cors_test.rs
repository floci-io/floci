//! S3 CORS Enforcement Tests
//!
//! Tests CORS preflight and actual requests with various configurations:
//! - Wildcard origin (*)
//! - Specific origin
//! - Subdomain wildcard patterns (http://*.example.com)

mod common;

use aws_sdk_s3::types::{CorsConfiguration, CorsRule};

/// Helper to send raw HTTP requests and get (status, headers).
async fn raw_request(
    client: &reqwest::Client,
    method: &str,
    url: &str,
    headers: &[(&str, &str)],
) -> Result<(u16, reqwest::header::HeaderMap), String> {
    let method = match method {
        "GET" => reqwest::Method::GET,
        "PUT" => reqwest::Method::PUT,
        "POST" => reqwest::Method::POST,
        "DELETE" => reqwest::Method::DELETE,
        "OPTIONS" => reqwest::Method::OPTIONS,
        "HEAD" => reqwest::Method::HEAD,
        other => return Err(format!("unsupported method: {}", other)),
    };
    let mut builder = client.request(method, url);
    for (k, v) in headers {
        builder = builder.header(*k, *v);
    }
    let resp = builder.send().await.map_err(|e| e.to_string())?;
    let status = resp.status().as_u16();
    let hdrs = resp.headers().clone();
    Ok((status, hdrs))
}

/// Get header value as string, or empty string if absent.
fn hdr(map: &reqwest::header::HeaderMap, name: &str) -> String {
    map.get(name)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string()
}

/// Check if Vary header contains "Origin".
fn vary_has_origin(vary: &str) -> bool {
    vary.split(',')
        .any(|t| t.trim().eq_ignore_ascii_case("origin"))
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_cors_preflight_without_config_returns_403() {
    let s3 = common::s3_client().await;
    let endpoint = sdk_test_rust::endpoint();
    let bucket = format!(
        "rust-cors-noconfig-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let object_key = "cors-test.txt";
    let object_url = format!("{}/{}/{}", endpoint, bucket, object_key);

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        let bucket = bucket.clone();
        async move {
            let _ = s3.delete_object().bucket(&bucket).key(object_key).send().await;
            let _ = s3.delete_bucket().bucket(&bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(&bucket).send().await.expect("create bucket");
    s3.put_object()
        .bucket(&bucket)
        .key(object_key)
        .body(bytes::Bytes::from("hello cors").into())
        .content_type("text/plain")
        .send()
        .await
        .expect("put object");

    // No CORS config: OPTIONS preflight -> 403
    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("reqwest client");

    let (status, _) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "http://localhost:3000"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight request");

    assert_eq!(status, 403, "preflight without CORS config should return 403");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_cors_wildcard_origin() {
    let s3 = common::s3_client().await;
    let endpoint = sdk_test_rust::endpoint();
    let bucket = format!(
        "rust-cors-wildcard-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let object_key = "cors-test.txt";
    let object_url = format!("{}/{}/{}", endpoint, bucket, object_key);

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        let bucket = bucket.clone();
        async move {
            let _ = s3.delete_bucket_cors().bucket(&bucket).send().await;
            let _ = s3.delete_object().bucket(&bucket).key(object_key).send().await;
            let _ = s3.delete_bucket().bucket(&bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(&bucket).send().await.expect("create bucket");
    s3.put_object()
        .bucket(&bucket)
        .key(object_key)
        .body(bytes::Bytes::from("hello cors").into())
        .content_type("text/plain")
        .send()
        .await
        .expect("put object");

    // Configure wildcard CORS
    let wildcard_cors = CorsConfiguration::builder()
        .cors_rules(
            CorsRule::builder()
                .allowed_origins("*")
                .allowed_methods("GET")
                .allowed_methods("PUT")
                .allowed_methods("POST")
                .allowed_methods("DELETE")
                .allowed_methods("HEAD")
                .allowed_headers("*")
                .expose_headers("ETag")
                .max_age_seconds(3000)
                .build()
                .expect("valid CorsRule"),
        )
        .build()
        .expect("valid CorsConfiguration");

    s3.put_bucket_cors()
        .bucket(&bucket)
        .cors_configuration(wildcard_cors)
        .send()
        .await
        .expect("put bucket cors");

    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("reqwest client");

    // Test: wildcard preflight -> 200
    let (status, hdrs) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "http://localhost:3000"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight request");

    assert_eq!(status, 200, "wildcard preflight should return 200");
    assert_eq!(
        hdr(&hdrs, "access-control-allow-origin"),
        "*",
        "should have Allow-Origin: *"
    );
    assert_eq!(
        hdr(&hdrs, "access-control-max-age"),
        "3000",
        "should have Max-Age: 3000"
    );
    assert!(
        hdr(&hdrs, "access-control-allow-methods")
            .to_uppercase()
            .contains("GET"),
        "Allow-Methods should contain GET"
    );

    // Test: actual GET with Origin -> CORS headers
    let (status, hdrs) = raw_request(
        &http,
        "GET",
        &object_url,
        &[("Origin", "http://localhost:3000")],
    )
    .await
    .expect("get request");

    assert_eq!(status, 200, "GET with Origin should return 200");
    assert_eq!(
        hdr(&hdrs, "access-control-allow-origin"),
        "*",
        "GET should have Allow-Origin: *"
    );
    assert!(
        vary_has_origin(&hdr(&hdrs, "vary")),
        "should have Vary: Origin"
    );
    assert!(
        hdr(&hdrs, "access-control-expose-headers").contains("ETag"),
        "Expose-Headers should contain ETag"
    );

    // Test: GET without Origin -> no CORS headers
    let (_, hdrs) = raw_request(&http, "GET", &object_url, &[])
        .await
        .expect("get request without origin");

    assert!(
        hdr(&hdrs, "access-control-allow-origin").is_empty(),
        "GET without Origin should have no Allow-Origin header"
    );

    // Test: OPTIONS without Origin -> no CORS headers
    let (_, hdrs) = raw_request(&http, "OPTIONS", &object_url, &[])
        .await
        .expect("options without origin");

    assert!(
        hdr(&hdrs, "access-control-allow-origin").is_empty(),
        "OPTIONS without Origin should have no Allow-Origin header"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_cors_specific_origin() {
    let s3 = common::s3_client().await;
    let endpoint = sdk_test_rust::endpoint();
    let bucket = format!(
        "rust-cors-specific-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let object_key = "cors-test.txt";
    let object_url = format!("{}/{}/{}", endpoint, bucket, object_key);

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        let bucket = bucket.clone();
        async move {
            let _ = s3.delete_bucket_cors().bucket(&bucket).send().await;
            let _ = s3.delete_object().bucket(&bucket).key(object_key).send().await;
            let _ = s3.delete_bucket().bucket(&bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(&bucket).send().await.expect("create bucket");
    s3.put_object()
        .bucket(&bucket)
        .key(object_key)
        .body(bytes::Bytes::from("hello cors").into())
        .content_type("text/plain")
        .send()
        .await
        .expect("put object");

    // Configure specific origin CORS
    let specific_cors = CorsConfiguration::builder()
        .cors_rules(
            CorsRule::builder()
                .allowed_origins("https://example.com")
                .allowed_methods("GET")
                .allowed_methods("PUT")
                .allowed_headers("Content-Type")
                .allowed_headers("Authorization")
                .expose_headers("ETag")
                .expose_headers("x-amz-request-id")
                .max_age_seconds(600)
                .build()
                .expect("valid CorsRule"),
        )
        .build()
        .expect("valid CorsConfiguration");

    s3.put_bucket_cors()
        .bucket(&bucket)
        .cors_configuration(specific_cors)
        .send()
        .await
        .expect("put bucket cors");

    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("reqwest client");

    // Test: matching origin preflight -> 200, echoes origin
    let (status, hdrs) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "https://example.com"),
            ("Access-Control-Request-Method", "GET"),
            ("Access-Control-Request-Headers", "Content-Type"),
        ],
    )
    .await
    .expect("preflight request");

    assert_eq!(status, 200, "matching origin preflight should return 200");
    assert_eq!(
        hdr(&hdrs, "access-control-allow-origin"),
        "https://example.com",
        "should echo the specific origin"
    );
    assert_eq!(
        hdr(&hdrs, "access-control-max-age"),
        "600",
        "should have Max-Age: 600"
    );

    // Test: non-matching origin -> 403
    let (status, _) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "https://attacker.evil.com"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight with wrong origin");

    assert_eq!(status, 403, "non-matching origin should return 403");

    // Test: non-matching method -> 403
    let (status, _) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "https://example.com"),
            ("Access-Control-Request-Method", "DELETE"),
        ],
    )
    .await
    .expect("preflight with wrong method");

    assert_eq!(status, 403, "non-matching method should return 403");

    // Test: actual GET with matching origin -> echoes origin
    let (_, hdrs) = raw_request(
        &http,
        "GET",
        &object_url,
        &[("Origin", "https://example.com")],
    )
    .await
    .expect("get with matching origin");

    assert_eq!(
        hdr(&hdrs, "access-control-allow-origin"),
        "https://example.com",
        "GET should echo matching origin"
    );

    // Test: actual GET with non-matching origin -> no CORS headers
    let (_, hdrs) = raw_request(
        &http,
        "GET",
        &object_url,
        &[("Origin", "https://not-allowed.com")],
    )
    .await
    .expect("get with non-matching origin");

    assert!(
        hdr(&hdrs, "access-control-allow-origin").is_empty(),
        "GET with non-matching origin should have no Allow-Origin"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_cors_delete_bucket_cors() {
    let s3 = common::s3_client().await;
    let endpoint = sdk_test_rust::endpoint();
    let bucket = format!(
        "rust-cors-delete-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let object_key = "cors-test.txt";
    let object_url = format!("{}/{}/{}", endpoint, bucket, object_key);

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        let bucket = bucket.clone();
        async move {
            let _ = s3.delete_bucket_cors().bucket(&bucket).send().await;
            let _ = s3.delete_object().bucket(&bucket).key(object_key).send().await;
            let _ = s3.delete_bucket().bucket(&bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(&bucket).send().await.expect("create bucket");
    s3.put_object()
        .bucket(&bucket)
        .key(object_key)
        .body(bytes::Bytes::from("hello cors").into())
        .content_type("text/plain")
        .send()
        .await
        .expect("put object");

    // Configure CORS
    let cors = CorsConfiguration::builder()
        .cors_rules(
            CorsRule::builder()
                .allowed_origins("*")
                .allowed_methods("GET")
                .build()
                .expect("valid CorsRule"),
        )
        .build()
        .expect("valid CorsConfiguration");

    s3.put_bucket_cors()
        .bucket(&bucket)
        .cors_configuration(cors)
        .send()
        .await
        .expect("put bucket cors");

    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("reqwest client");

    // Verify CORS works
    let (status, _) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "http://localhost:3000"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight");

    assert_eq!(status, 200, "preflight should work before delete");

    // Delete CORS config
    s3.delete_bucket_cors()
        .bucket(&bucket)
        .send()
        .await
        .expect("delete bucket cors");

    // Preflight should now return 403
    let (status, _) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "http://localhost:3000"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight after delete");

    assert_eq!(status, 403, "preflight should return 403 after CORS delete");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_s3_cors_subdomain_wildcard() {
    let s3 = common::s3_client().await;
    let endpoint = sdk_test_rust::endpoint();
    let bucket = format!(
        "rust-cors-subdomain-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let object_key = "cors-test.txt";
    let object_url = format!("{}/{}/{}", endpoint, bucket, object_key);

    let _guard = common::CleanupGuard::new({
        let s3 = s3.clone();
        let bucket = bucket.clone();
        async move {
            let _ = s3.delete_bucket_cors().bucket(&bucket).send().await;
            let _ = s3.delete_object().bucket(&bucket).key(object_key).send().await;
            let _ = s3.delete_bucket().bucket(&bucket).send().await;
        }
    });

    // Setup
    s3.create_bucket().bucket(&bucket).send().await.expect("create bucket");
    s3.put_object()
        .bucket(&bucket)
        .key(object_key)
        .body(bytes::Bytes::from("hello cors").into())
        .content_type("text/plain")
        .send()
        .await
        .expect("put object");

    // Configure subdomain wildcard CORS
    let subdomain_cors = CorsConfiguration::builder()
        .cors_rules(
            CorsRule::builder()
                .allowed_origins("http://*.example.com")
                .allowed_methods("GET")
                .allowed_headers("*")
                .max_age_seconds(120)
                .build()
                .expect("valid CorsRule"),
        )
        .build()
        .expect("valid CorsConfiguration");

    s3.put_bucket_cors()
        .bucket(&bucket)
        .cors_configuration(subdomain_cors)
        .send()
        .await
        .expect("put bucket cors");

    let http = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("reqwest client");

    // Test: matching subdomain -> 200, echoes origin
    let (status, hdrs) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "http://app.example.com"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight with matching subdomain");

    assert_eq!(status, 200, "matching subdomain should return 200");
    assert_eq!(
        hdr(&hdrs, "access-control-allow-origin"),
        "http://app.example.com",
        "should echo the matched subdomain origin"
    );

    // Test: wrong scheme (https instead of http) -> 403
    let (status, _) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "https://app.example.com"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight with wrong scheme");

    assert_eq!(status, 403, "wrong scheme should return 403");

    // Test: different domain -> 403
    let (status, _) = raw_request(
        &http,
        "OPTIONS",
        &object_url,
        &[
            ("Origin", "http://app.other.com"),
            ("Access-Control-Request-Method", "GET"),
        ],
    )
    .await
    .expect("preflight with different domain");

    assert_eq!(status, 403, "different domain should return 403");
}
