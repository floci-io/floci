mod common;

#[tokio::test(flavor = "multi_thread")]
async fn test_neptune_create_cluster() {
    let neptune = common::neptune_client().await;
    let cluster_id = "rust-neptune-create-cluster";

    let result = neptune
        .create_db_cluster()
        .db_cluster_identifier(cluster_id)
        .engine("neptune")
        .send()
        .await;
    assert!(result.is_ok(), "CreateDBCluster failed: {:?}", result.err());

    let cluster = result.unwrap().db_cluster.unwrap();
    assert_eq!(cluster.db_cluster_identifier().unwrap_or(""), cluster_id);
    assert_eq!(cluster.engine().unwrap_or(""), "neptune");
    assert_eq!(cluster.status().unwrap_or(""), "available");
    assert!(cluster.db_cluster_arn().unwrap_or("").starts_with("arn:aws:neptune:"));

    let _guard = common::CleanupGuard::new({
        let neptune = neptune.clone();
        async move {
            let _ = neptune
                .delete_db_cluster()
                .db_cluster_identifier(cluster_id)
                .skip_final_snapshot(true)
                .send()
                .await;
        }
    });
}

#[tokio::test(flavor = "multi_thread")]
async fn test_neptune_describe_cluster() {
    let neptune = common::neptune_client().await;
    let cluster_id = "rust-neptune-describe-cluster";

    neptune
        .create_db_cluster()
        .db_cluster_identifier(cluster_id)
        .engine("neptune")
        .send()
        .await
        .expect("setup: create cluster");

    let _guard = common::CleanupGuard::new({
        let neptune = neptune.clone();
        async move {
            let _ = neptune
                .delete_db_cluster()
                .db_cluster_identifier(cluster_id)
                .skip_final_snapshot(true)
                .send()
                .await;
        }
    });

    let result = neptune
        .describe_db_clusters()
        .db_cluster_identifier(cluster_id)
        .send()
        .await;
    assert!(result.is_ok(), "DescribeDBClusters failed: {:?}", result.err());

    let clusters = result.unwrap().db_clusters;
    assert_eq!(clusters.len(), 1);
    assert_eq!(clusters[0].db_cluster_identifier().unwrap_or(""), cluster_id);
}

#[tokio::test(flavor = "multi_thread")]
async fn test_neptune_modify_cluster() {
    let neptune = common::neptune_client().await;
    let cluster_id = "rust-neptune-modify-cluster";

    neptune
        .create_db_cluster()
        .db_cluster_identifier(cluster_id)
        .engine("neptune")
        .send()
        .await
        .expect("setup: create cluster");

    let _guard = common::CleanupGuard::new({
        let neptune = neptune.clone();
        async move {
            let _ = neptune
                .delete_db_cluster()
                .db_cluster_identifier(cluster_id)
                .skip_final_snapshot(true)
                .send()
                .await;
        }
    });

    let result = neptune
        .modify_db_cluster()
        .db_cluster_identifier(cluster_id)
        .enable_iam_database_authentication(true)
        .send()
        .await;
    assert!(result.is_ok(), "ModifyDBCluster failed: {:?}", result.err());

    let cluster = result.unwrap().db_cluster.unwrap();
    assert!(cluster.iam_database_authentication_enabled());
}

#[tokio::test(flavor = "multi_thread")]
async fn test_neptune_create_and_describe_instance() {
    let neptune = common::neptune_client().await;
    let cluster_id = "rust-neptune-instance-cluster";
    let instance_id = "rust-neptune-instance";

    neptune
        .create_db_cluster()
        .db_cluster_identifier(cluster_id)
        .engine("neptune")
        .send()
        .await
        .expect("setup: create cluster");

    let _cluster_guard = common::CleanupGuard::new({
        let neptune = neptune.clone();
        async move {
            let _ = neptune
                .delete_db_cluster()
                .db_cluster_identifier(cluster_id)
                .skip_final_snapshot(true)
                .send()
                .await;
        }
    });

    let create = neptune
        .create_db_instance()
        .db_instance_identifier(instance_id)
        .db_cluster_identifier(cluster_id)
        .db_instance_class("db.r5.large")
        .engine("neptune")
        .send()
        .await;
    assert!(create.is_ok(), "CreateDBInstance failed: {:?}", create.err());

    let instance = create.unwrap().db_instance.unwrap();
    assert_eq!(instance.db_instance_identifier().unwrap_or(""), instance_id);
    assert_eq!(instance.db_cluster_identifier().unwrap_or(""), cluster_id);
    assert_eq!(instance.db_instance_status().unwrap_or(""), "available");

    let _inst_guard = common::CleanupGuard::new({
        let neptune = neptune.clone();
        async move {
            let _ = neptune
                .delete_db_instance()
                .db_instance_identifier(instance_id)
                .send()
                .await;
        }
    });

    let describe = neptune
        .describe_db_instances()
        .db_instance_identifier(instance_id)
        .send()
        .await;
    assert!(describe.is_ok(), "DescribeDBInstances failed: {:?}", describe.err());
    assert_eq!(describe.unwrap().db_instances.len(), 1);
}

