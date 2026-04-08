mod common;

use aws_sdk_acm::primitives::Blob;
use aws_sdk_acm::types::Tag;

fn generate_self_signed_cert() -> (Vec<u8>, Vec<u8>) {
    let cert = rcgen::generate_simple_self_signed(vec!["test.example.com".into()]).unwrap();
    let cert_pem = cert.cert.pem().into_bytes();
    let key_pem = cert.key_pair.serialize_pem().into_bytes();
    (cert_pem, key_pem)
}

// ---------------------------------------------------------------------------
// US1: Lifecycle tests
// ---------------------------------------------------------------------------

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_request_certificate() {
    let acm = common::acm_client().await;
    let domain = "rust-test.example.com";

    let result = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await;
    assert!(result.is_ok(), "RequestCertificate failed: {:?}", result.err());

    let cert_arn = result.unwrap().certificate_arn().unwrap_or("").to_string();
    assert!(!cert_arn.is_empty(), "ARN should not be empty");
    assert!(
        cert_arn.contains("arn:") && cert_arn.contains("acm"),
        "ARN should match expected pattern, got: {}",
        cert_arn
    );

    // Cleanup
    let _ = acm.delete_certificate().certificate_arn(&cert_arn).send().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_describe_certificate() {
    let acm = common::acm_client().await;
    let domain = "rust-describe.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await
        .expect("setup: request cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let result = acm
        .describe_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await;
    assert!(result.is_ok(), "DescribeCertificate failed: {:?}", result.err());

    let detail = result.unwrap().certificate().unwrap().clone();
    assert_eq!(
        detail.domain_name().unwrap_or(""),
        domain,
        "Domain name should match"
    );
    // Status should be present (e.g. ISSUED or PENDING_VALIDATION)
    assert!(detail.status().is_some(), "Status should be present");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_get_certificate() {
    let acm = common::acm_client().await;
    let domain = "rust-get.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await
        .expect("setup: request cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let result = acm
        .get_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await;
    assert!(result.is_ok(), "GetCertificate failed: {:?}", result.err());

    let output = result.unwrap();
    let body = output.certificate().unwrap_or("");
    assert!(
        body.contains("BEGIN CERTIFICATE"),
        "Certificate body should contain PEM header, got: {}",
        body
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_list_certificates() {
    let acm = common::acm_client().await;
    let domain = "rust-list.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await
        .expect("setup: request cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let result = acm.list_certificates().send().await;
    assert!(result.is_ok(), "ListCertificates failed: {:?}", result.err());

    let output = result.unwrap();
    let summaries = output.certificate_summary_list();
    let found = summaries
        .iter()
        .any(|s| s.certificate_arn().unwrap_or("") == cert_arn);
    assert!(found, "Created certificate should appear in list");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_delete_certificate() {
    let acm = common::acm_client().await;
    let domain = "rust-delete.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await
        .expect("setup: request cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let del_result = acm
        .delete_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await;
    assert!(del_result.is_ok(), "DeleteCertificate failed: {:?}", del_result.err());

    // Verify describe now fails
    let describe_result = acm
        .describe_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await;
    assert!(
        describe_result.is_err(),
        "DescribeCertificate should fail after deletion"
    );
}

// ---------------------------------------------------------------------------
// US2: Import / Export tests
// ---------------------------------------------------------------------------

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_import_certificate() {
    let acm = common::acm_client().await;
    let (cert_pem, key_pem) = generate_self_signed_cert();

    let result = acm
        .import_certificate()
        .certificate(Blob::new(cert_pem))
        .private_key(Blob::new(key_pem))
        .send()
        .await;
    assert!(result.is_ok(), "ImportCertificate failed: {:?}", result.err());

    let cert_arn = result.unwrap().certificate_arn().unwrap_or("").to_string();
    assert!(!cert_arn.is_empty(), "Imported cert ARN should not be empty");

    // Cleanup
    let _ = acm.delete_certificate().certificate_arn(&cert_arn).send().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_get_imported_certificate() {
    let acm = common::acm_client().await;
    let (cert_pem, key_pem) = generate_self_signed_cert();

    let cert_arn = acm
        .import_certificate()
        .certificate(Blob::new(cert_pem.clone()))
        .private_key(Blob::new(key_pem))
        .send()
        .await
        .expect("setup: import cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let result = acm
        .get_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await;
    assert!(result.is_ok(), "GetCertificate failed: {:?}", result.err());

    let body = result.unwrap().certificate().unwrap_or("").to_string();
    assert!(
        body.contains("BEGIN CERTIFICATE"),
        "Imported certificate body should contain PEM header"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_export_certificate() {
    let acm = common::acm_client().await;
    let (cert_pem, key_pem) = generate_self_signed_cert();

    let cert_arn = acm
        .import_certificate()
        .certificate(Blob::new(cert_pem))
        .private_key(Blob::new(key_pem))
        .send()
        .await
        .expect("setup: import cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let passphrase = Blob::new(b"test-passphrase".to_vec());
    let result = acm
        .export_certificate()
        .certificate_arn(&cert_arn)
        .passphrase(passphrase)
        .send()
        .await;
    assert!(result.is_ok(), "ExportCertificate failed: {:?}", result.err());

    let output = result.unwrap();
    let exported_cert = output.certificate().unwrap_or("");
    let exported_key = output.private_key().unwrap_or("");
    assert!(
        exported_cert.contains("BEGIN CERTIFICATE"),
        "Exported certificate should contain PEM header"
    );
    assert!(
        !exported_key.is_empty(),
        "Exported private key should not be empty"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_export_requested_fails() {
    let acm = common::acm_client().await;
    let domain = "rust-export-fail.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await
        .expect("setup: request cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let passphrase = Blob::new(b"test-passphrase".to_vec());
    let result = acm
        .export_certificate()
        .certificate_arn(&cert_arn)
        .passphrase(passphrase)
        .send()
        .await;
    assert!(
        result.is_err(),
        "ExportCertificate on a requested (non-imported) cert should fail"
    );
}

// ---------------------------------------------------------------------------
// US3: Tagging tests
// ---------------------------------------------------------------------------

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_add_and_list_tags() {
    let acm = common::acm_client().await;
    let domain = "rust-tags.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await
        .expect("setup: request cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let tag1 = Tag::builder().key("Env").value("test").build().unwrap();
    let tag2 = Tag::builder().key("Team").value("platform").build().unwrap();

    let add_result = acm
        .add_tags_to_certificate()
        .certificate_arn(&cert_arn)
        .tags(tag1)
        .tags(tag2)
        .send()
        .await;
    assert!(add_result.is_ok(), "AddTagsToCertificate failed: {:?}", add_result.err());

    let list_result = acm
        .list_tags_for_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await;
    assert!(list_result.is_ok(), "ListTagsForCertificate failed: {:?}", list_result.err());

    let output = list_result.unwrap();
    let tags = output.tags();
    assert!(tags.len() >= 2, "Should have at least 2 tags, got {}", tags.len());

    let has_env = tags.iter().any(|t| t.key() == "Env" && t.value() == Some("test"));
    let has_team = tags.iter().any(|t| t.key() == "Team" && t.value() == Some("platform"));
    assert!(has_env, "Should have Env=test tag");
    assert!(has_team, "Should have Team=platform tag");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_remove_tags() {
    let acm = common::acm_client().await;
    let domain = "rust-remove-tags.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .send()
        .await
        .expect("setup: request cert")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let tag_env = Tag::builder().key("Env").value("test").build().unwrap();
    let tag_team = Tag::builder().key("Team").value("platform").build().unwrap();

    acm.add_tags_to_certificate()
        .certificate_arn(&cert_arn)
        .tags(tag_env.clone())
        .tags(tag_team)
        .send()
        .await
        .expect("setup: add tags");

    // Remove only the Env tag
    let remove_result = acm
        .remove_tags_from_certificate()
        .certificate_arn(&cert_arn)
        .tags(tag_env)
        .send()
        .await;
    assert!(remove_result.is_ok(), "RemoveTagsFromCertificate failed: {:?}", remove_result.err());

    let list_output = acm
        .list_tags_for_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await
        .expect("list tags after remove");

    let tags = list_output.tags();
    let has_env = tags.iter().any(|t| t.key() == "Env");
    let has_team = tags.iter().any(|t| t.key() == "Team");
    assert!(!has_env, "Env tag should have been removed");
    assert!(has_team, "Team tag should still be present");
}

// ---------------------------------------------------------------------------
// US4: Account Configuration tests
// ---------------------------------------------------------------------------

#[tokio::test]
async fn test_acm_account_configuration() {
    let acm = common::acm_client().await;

    let expiry_config = aws_sdk_acm::types::ExpiryEventsConfiguration::builder()
        .days_before_expiry(45)
        .build();

    let put_result = acm
        .put_account_configuration()
        .expiry_events(expiry_config)
        .idempotency_token("rust-test-token")
        .send()
        .await;
    assert!(put_result.is_ok(), "PutAccountConfiguration failed: {:?}", put_result.err());

    let get_result = acm.get_account_configuration().send().await;
    assert!(get_result.is_ok(), "GetAccountConfiguration failed: {:?}", get_result.err());

    let config = get_result.unwrap().expiry_events().unwrap().clone();
    assert_eq!(
        config.days_before_expiry().unwrap_or(0),
        45,
        "DaysBeforeExpiry should be 45"
    );
}

// ---------------------------------------------------------------------------
// US5: Error handling tests
// ---------------------------------------------------------------------------

#[tokio::test]
async fn test_acm_describe_nonexistent() {
    let acm = common::acm_client().await;
    let fake_arn = "arn:aws:acm:us-east-1:000000000000:certificate/00000000-0000-0000-0000-000000000000";

    let result = acm
        .describe_certificate()
        .certificate_arn(fake_arn)
        .send()
        .await;
    assert!(
        result.is_err(),
        "DescribeCertificate with fake ARN should fail"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_acm_request_with_sans() {
    let acm = common::acm_client().await;
    let domain = "rust-sans.example.com";
    let san1 = "alt1.rust-sans.example.com";
    let san2 = "alt2.rust-sans.example.com";

    let cert_arn = acm
        .request_certificate()
        .domain_name(domain)
        .subject_alternative_names(san1)
        .subject_alternative_names(san2)
        .send()
        .await
        .expect("RequestCertificate with SANs")
        .certificate_arn()
        .unwrap()
        .to_string();

    let _guard = common::CleanupGuard::new({
        let acm = acm.clone();
        let arn = cert_arn.clone();
        async move {
            let _ = acm.delete_certificate().certificate_arn(&arn).send().await;
        }
    });

    let detail = acm
        .describe_certificate()
        .certificate_arn(&cert_arn)
        .send()
        .await
        .expect("DescribeCertificate")
        .certificate()
        .unwrap()
        .clone();

    let sans = detail.subject_alternative_names();
    assert!(
        sans.iter().any(|s| s == san1),
        "SANs should contain {}, got: {:?}",
        san1,
        sans
    );
    assert!(
        sans.iter().any(|s| s == san2),
        "SANs should contain {}, got: {:?}",
        san2,
        sans
    );
}

#[tokio::test]
async fn test_acm_import_invalid_pem() {
    let acm = common::acm_client().await;

    let result = acm
        .import_certificate()
        .certificate(Blob::new(b"not-a-valid-certificate".to_vec()))
        .private_key(Blob::new(b"not-a-valid-key".to_vec()))
        .send()
        .await;
    assert!(
        result.is_err(),
        "ImportCertificate with invalid PEM should fail"
    );
}
