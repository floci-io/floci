mod common;

const ACCOUNT_ID: &str = "000000000000";
const REGION: &str = "us-east-1";
const ROLE_ARN: &str = "arn:aws:iam::000000000000:role/pipe-role";

fn sqs_arn(queue_name: &str) -> String {
    format!("arn:aws:sqs:{}:{}:{}", REGION, ACCOUNT_ID, queue_name)
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_create_pipe() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-create";
    let tgt_queue = "rust-pipe-tgt-create";
    let pipe_name = "rust-pipe-create";

    sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");

    let _guard = common::CleanupGuard::new({
        let pipes = pipes.clone();
        let sqs = sqs.clone();
        async move {
            let _ = pipes.delete_pipe().name(pipe_name).send().await;
            cleanup_queue(&sqs, src_queue).await;
            cleanup_queue(&sqs, tgt_queue).await;
        }
    });

    let result = pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await;
    assert!(result.is_ok(), "CreatePipe failed: {:?}", result.err());

    let output = result.unwrap();
    assert_eq!(
        output.current_state(),
        Some(&aws_sdk_pipes::types::PipeState::Stopped)
    );
    assert!(output.arn().unwrap_or("").contains(pipe_name));
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_describe_pipe() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-describe";
    let tgt_queue = "rust-pipe-tgt-describe";
    let pipe_name = "rust-pipe-describe";

    sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");

    let _guard = common::CleanupGuard::new({
        let pipes = pipes.clone();
        let sqs = sqs.clone();
        async move {
            let _ = pipes.delete_pipe().name(pipe_name).send().await;
            cleanup_queue(&sqs, src_queue).await;
            cleanup_queue(&sqs, tgt_queue).await;
        }
    });

    pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await
        .expect("create pipe");

    let result = pipes.describe_pipe().name(pipe_name).send().await;
    assert!(result.is_ok(), "DescribePipe failed: {:?}", result.err());

    let output = result.unwrap();
    assert_eq!(output.name().unwrap_or(""), pipe_name);
    assert_eq!(output.source().unwrap_or(""), sqs_arn(src_queue));
    assert_eq!(output.target().unwrap_or(""), sqs_arn(tgt_queue));
    assert_eq!(
        output.current_state(),
        Some(&aws_sdk_pipes::types::PipeState::Stopped)
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_list_pipes() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-list";
    let tgt_queue = "rust-pipe-tgt-list";
    let pipe_name = "rust-pipe-list";

    sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");

    let _guard = common::CleanupGuard::new({
        let pipes = pipes.clone();
        let sqs = sqs.clone();
        async move {
            let _ = pipes.delete_pipe().name(pipe_name).send().await;
            cleanup_queue(&sqs, src_queue).await;
            cleanup_queue(&sqs, tgt_queue).await;
        }
    });

    pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await
        .expect("create pipe");

    let result = pipes.list_pipes().send().await;
    assert!(result.is_ok(), "ListPipes failed: {:?}", result.err());

    let found = result
        .unwrap()
        .pipes()
        .iter()
        .any(|p| p.name().unwrap_or("") == pipe_name);
    assert!(found, "pipe should appear in list");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_update_pipe() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-update";
    let tgt_queue = "rust-pipe-tgt-update";
    let pipe_name = "rust-pipe-update";

    sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");

    let _guard = common::CleanupGuard::new({
        let pipes = pipes.clone();
        let sqs = sqs.clone();
        async move {
            let _ = pipes.delete_pipe().name(pipe_name).send().await;
            cleanup_queue(&sqs, src_queue).await;
            cleanup_queue(&sqs, tgt_queue).await;
        }
    });

    pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await
        .expect("create pipe");

    pipes
        .update_pipe()
        .name(pipe_name)
        .role_arn(ROLE_ARN)
        .description("updated via SDK")
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await
        .expect("update pipe");

    let result = pipes.describe_pipe().name(pipe_name).send().await.expect("describe pipe");
    assert_eq!(result.description().unwrap_or(""), "updated via SDK");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_delete_pipe() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-del";
    let tgt_queue = "rust-pipe-tgt-del";
    let pipe_name = "rust-pipe-del";

    sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");

    let _guard = common::CleanupGuard::new({
        let sqs = sqs.clone();
        async move {
            cleanup_queue(&sqs, src_queue).await;
            cleanup_queue(&sqs, tgt_queue).await;
        }
    });

    pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await
        .expect("create pipe");

    let result = pipes.delete_pipe().name(pipe_name).send().await;
    assert!(result.is_ok(), "DeletePipe failed: {:?}", result.err());

    let describe = pipes.describe_pipe().name(pipe_name).send().await;
    assert!(describe.is_err(), "DescribePipe should fail after deletion");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_describe_nonexistent() {
    let pipes = common::pipes_client().await;
    let result = pipes.describe_pipe().name("nonexistent-pipe").send().await;
    assert!(result.is_err());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_start_and_stop() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-startstop";
    let tgt_queue = "rust-pipe-tgt-startstop";
    let pipe_name = "rust-pipe-startstop";

    sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");

    let _guard = common::CleanupGuard::new({
        let pipes = pipes.clone();
        let sqs = sqs.clone();
        async move {
            let _ = pipes.delete_pipe().name(pipe_name).send().await;
            cleanup_queue(&sqs, src_queue).await;
            cleanup_queue(&sqs, tgt_queue).await;
        }
    });

    pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await
        .expect("create pipe");

    let start = pipes.start_pipe().name(pipe_name).send().await;
    assert!(start.is_ok(), "StartPipe failed: {:?}", start.err());
    assert_eq!(
        start.unwrap().current_state(),
        Some(&aws_sdk_pipes::types::PipeState::Running)
    );

    let stop = pipes.stop_pipe().name(pipe_name).send().await;
    assert!(stop.is_ok(), "StopPipe failed: {:?}", stop.err());
    assert_eq!(
        stop.unwrap().current_state(),
        Some(&aws_sdk_pipes::types::PipeState::Stopped)
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_sqs_to_sqs_forwarding() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-fwd";
    let tgt_queue = "rust-pipe-tgt-fwd";
    let pipe_name = "rust-pipe-fwd";

    let src_resp = sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    let src_url = src_resp.queue_url().unwrap_or("").to_string();
    let tgt_resp = sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");
    let tgt_url = tgt_resp.queue_url().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let pipes = pipes.clone();
        let sqs = sqs.clone();
        let src_url = src_url.clone();
        let tgt_url = tgt_url.clone();
        async move {
            let _ = pipes.delete_pipe().name(pipe_name).send().await;
            let _ = sqs.delete_queue().queue_url(&src_url).send().await;
            let _ = sqs.delete_queue().queue_url(&tgt_url).send().await;
        }
    });

    pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Running)
        .send()
        .await
        .expect("create pipe");

    sqs.send_message()
        .queue_url(&src_url)
        .message_body("hello from pipes")
        .send()
        .await
        .expect("send message");

    let mut found = false;
    for _ in 0..15 {
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        let recv = sqs
            .receive_message()
            .queue_url(&tgt_url)
            .max_number_of_messages(1)
            .wait_time_seconds(1)
            .send()
            .await;
        if let Ok(r) = recv {
            if let Some(msgs) = r.messages() {
                if !msgs.is_empty() && msgs[0].body().unwrap_or("").contains("hello from pipes") {
                    found = true;
                    break;
                }
            }
        }
    }
    assert!(found, "target queue should receive forwarded message");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_pipes_stopped_pipe_does_not_forward() {
    let pipes = common::pipes_client().await;
    let sqs = common::sqs_client().await;
    let src_queue = "rust-pipe-src-nofwd";
    let tgt_queue = "rust-pipe-tgt-nofwd";
    let pipe_name = "rust-pipe-nofwd";

    let src_resp = sqs.create_queue().queue_name(src_queue).send().await.expect("create src queue");
    let src_url = src_resp.queue_url().unwrap_or("").to_string();
    let tgt_resp = sqs.create_queue().queue_name(tgt_queue).send().await.expect("create tgt queue");
    let tgt_url = tgt_resp.queue_url().unwrap_or("").to_string();

    let _guard = common::CleanupGuard::new({
        let pipes = pipes.clone();
        let sqs = sqs.clone();
        let src_url = src_url.clone();
        let tgt_url = tgt_url.clone();
        async move {
            let _ = pipes.delete_pipe().name(pipe_name).send().await;
            let _ = sqs.delete_queue().queue_url(&src_url).send().await;
            let _ = sqs.delete_queue().queue_url(&tgt_url).send().await;
        }
    });

    pipes
        .create_pipe()
        .name(pipe_name)
        .source(sqs_arn(src_queue))
        .target(sqs_arn(tgt_queue))
        .role_arn(ROLE_ARN)
        .desired_state(aws_sdk_pipes::types::RequestedPipeState::Stopped)
        .send()
        .await
        .expect("create pipe");

    sqs.send_message()
        .queue_url(&src_url)
        .message_body("should not forward")
        .send()
        .await
        .expect("send message");

    tokio::time::sleep(std::time::Duration::from_secs(3)).await;

    let recv = sqs
        .receive_message()
        .queue_url(&tgt_url)
        .max_number_of_messages(1)
        .wait_time_seconds(1)
        .send()
        .await
        .expect("receive message");
    assert!(
        recv.messages().is_empty(),
        "target queue should be empty"
    );
}

async fn cleanup_queue(sqs: &aws_sdk_sqs::Client, queue_name: &str) {
    if let Ok(r) = sqs.get_queue_url().queue_name(queue_name).send().await {
        if let Some(url) = r.queue_url() {
            let _ = sqs.delete_queue().queue_url(url).send().await;
        }
    }
}
