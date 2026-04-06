mod common;

#[tokio::test]
async fn test_kms_create_key() {
    let kms = common::kms_client().await;

    let result = kms.create_key().description("rust-test-key").send().await;
    assert!(result.is_ok(), "CreateKey failed: {:?}", result.err());

    let key_id = result
        .unwrap()
        .key_metadata()
        .map(|m| m.key_id().to_string())
        .unwrap_or_default();
    assert!(!key_id.is_empty());
}

#[tokio::test]
async fn test_kms_list_keys() {
    let kms = common::kms_client().await;

    // Setup - create a key first
    kms.create_key()
        .description("rust-test-list")
        .send()
        .await
        .expect("setup");

    let result = kms.list_keys().send().await;
    assert!(result.is_ok(), "ListKeys failed: {:?}", result.err());
    assert!(!result.unwrap().keys().is_empty());
}

#[tokio::test]
async fn test_kms_encrypt_decrypt() {
    let kms = common::kms_client().await;

    // Create key
    let key = kms
        .create_key()
        .description("rust-test-encrypt")
        .send()
        .await
        .expect("create key");
    let key_id = key
        .key_metadata()
        .map(|m| m.key_id().to_string())
        .unwrap_or_default();

    // Encrypt
    let plaintext = b"rust-kms-test";
    let encrypt_result = kms
        .encrypt()
        .key_id(&key_id)
        .plaintext(aws_smithy_types::Blob::new(plaintext.to_vec()))
        .send()
        .await;
    assert!(encrypt_result.is_ok(), "Encrypt failed: {:?}", encrypt_result.err());

    let ciphertext = encrypt_result
        .unwrap()
        .ciphertext_blob()
        .cloned()
        .unwrap_or_else(|| aws_smithy_types::Blob::new(vec![]));
    assert!(!ciphertext.as_ref().is_empty());

    // Decrypt
    let decrypt_result = kms.decrypt().ciphertext_blob(ciphertext).send().await;
    assert!(decrypt_result.is_ok(), "Decrypt failed: {:?}", decrypt_result.err());

    let decrypted = decrypt_result
        .unwrap()
        .plaintext()
        .cloned()
        .unwrap_or_else(|| aws_smithy_types::Blob::new(vec![]));
    assert_eq!(decrypted.as_ref(), plaintext);
}
