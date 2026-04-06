mod common;

#[tokio::test(flavor = "multi_thread")]
async fn test_sqs_create_queue() {
    let sqs = common::sqs_client().await;
    let queue_name = "rust-test-create-queue";

    let result = sqs.create_queue().queue_name(queue_name).send().await;
    assert!(result.is_ok(), "CreateQueue failed: {:?}", result.err());

    let url = result.unwrap().queue_url().unwrap_or("").to_string();
    assert!(!url.is_empty());

    let _guard = common::CleanupGuard::new({
        let sqs = sqs.clone();
        let url = url.clone();
        async move {
            let _ = sqs.delete_queue().queue_url(&url).send().await;
        }
    });
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sqs_list_queues() {
    let sqs = common::sqs_client().await;
    let queue_name = "rust-test-list-queue";

    // Setup
    let create = sqs.create_queue().queue_name(queue_name).send().await.expect("setup");
    let url = create.queue_url().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let sqs = sqs.clone();
        let url = url.clone();
        async move {
            let _ = sqs.delete_queue().queue_url(&url).send().await;
        }
    });

    let result = sqs.list_queues().send().await;
    assert!(result.is_ok(), "ListQueues failed: {:?}", result.err());
    assert!(!result.unwrap().queue_urls().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sqs_send_and_receive_message() {
    let sqs = common::sqs_client().await;
    let queue_name = "rust-test-send-recv";

    // Setup
    let create = sqs.create_queue().queue_name(queue_name).send().await.expect("setup");
    let url = create.queue_url().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let sqs = sqs.clone();
        let url = url.clone();
        async move {
            let _ = sqs.delete_queue().queue_url(&url).send().await;
        }
    });

    // Send
    let send_result = sqs
        .send_message()
        .queue_url(&url)
        .message_body("hello from rust")
        .send()
        .await;
    assert!(send_result.is_ok(), "SendMessage failed: {:?}", send_result.err());

    // Receive
    let recv_result = sqs
        .receive_message()
        .queue_url(&url)
        .max_number_of_messages(1)
        .send()
        .await;
    assert!(recv_result.is_ok(), "ReceiveMessage failed: {:?}", recv_result.err());

    let messages = recv_result.unwrap().messages;
    assert!(messages.is_some() && !messages.as_ref().unwrap().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sqs_delete_message() {
    let sqs = common::sqs_client().await;
    let queue_name = "rust-test-delete-msg";

    // Setup
    let create = sqs.create_queue().queue_name(queue_name).send().await.expect("setup");
    let url = create.queue_url().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let sqs = sqs.clone();
        let url = url.clone();
        async move {
            let _ = sqs.delete_queue().queue_url(&url).send().await;
        }
    });

    // Send and receive
    sqs.send_message()
        .queue_url(&url)
        .message_body("to delete")
        .send()
        .await
        .expect("send");

    let recv = sqs
        .receive_message()
        .queue_url(&url)
        .max_number_of_messages(1)
        .send()
        .await
        .expect("receive");

    if let Some(msgs) = recv.messages {
        if let Some(msg) = msgs.first() {
            if let Some(handle) = msg.receipt_handle() {
                let result = sqs
                    .delete_message()
                    .queue_url(&url)
                    .receipt_handle(handle)
                    .send()
                    .await;
                assert!(result.is_ok(), "DeleteMessage failed: {:?}", result.err());
            }
        }
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sqs_delete_queue() {
    let sqs = common::sqs_client().await;
    let queue_name = "rust-test-delete-queue";

    // No cleanup guard needed - test is about deletion

    // Setup
    let create = sqs.create_queue().queue_name(queue_name).send().await.expect("setup");
    let url = create.queue_url().unwrap_or("").to_string();

    let result = sqs.delete_queue().queue_url(&url).send().await;
    assert!(result.is_ok(), "DeleteQueue failed: {:?}", result.err());
}
