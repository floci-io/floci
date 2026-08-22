# Thiết Kế Kỹ Thuật: AWS Redshift Core Operations (Sub-project 1)

## 1. Tổng quan (Overview)
Bản thiết kế này mô tả chi tiết việc triển khai các tính năng cốt lõi về Quản lý vận hành (Core Operations) cho service Amazon Redshift trong Floci. Trọng tâm của phase này là vòng đời Snapshot (Backup/Restore) và giả lập Parameter Groups.

## 2. API & Routing
- Mở rộng `RedshiftQueryHandler` để phân tích và xử lý các Action mới:
  - **Nhóm Snapshots:**
    - `CreateClusterSnapshot`
    - `DescribeClusterSnapshots`
    - `DeleteClusterSnapshot`
    - `RestoreFromClusterSnapshot`
  - **Nhóm Parameter Groups:**
    - `CreateClusterParameterGroup`
    - `DescribeClusterParameterGroups`
    - `DescribeClusterParameters`
- Toàn bộ kết quả trả về sử dụng `XmlBuilder` để định dạng chuẩn XML AWS Query. Ném `AwsException` nếu Snapshot hoặc Parameter Group không tồn tại (ResourceNotFound).

## 3. Models & Quản lý trạng thái (State Management)
- **Domain Models mới:**
  - `Snapshot`: Chứa metadata như `SnapshotIdentifier`, `ClusterIdentifier`, `Status`, `Port`, `MasterUsername`.
  - `ClusterParameterGroup`: Chứa `ParameterGroupName`, `ParameterGroupFamily`, `Description`.
  - `Parameter`: Chứa `ParameterName`, `ParameterValue`, v.v.
- **Lưu trữ (Storage):**
  - Mở rộng `RedshiftService` để lưu metadata của Snapshot và ParameterGroup vào `StorageFactory` (`AccountAwareStorageBackend`), tương tự như Cluster.
  - Parameter Groups sẽ hoạt động dưới dạng Mock State: Floci chỉ ghi nhận và lưu trữ khai báo của user, không apply xuống cấu hình của container PostgreSQL.

## 4. Snapshot Data Plane (Docker Exec Lifecycle)
Cơ chế sao lưu và khôi phục dữ liệu thực tế:
- **Tạo Snapshot (`CreateClusterSnapshot`):**
  - Mở rộng `RedshiftContainerManager`.
  - Khi có yêu cầu, Floci gọi API `exec` của Docker/Testcontainers để chạy lệnh `pg_dump -U <username> <dbname>` trực tiếp trong container của cluster đích.
  - Luồng dữ liệu (SQL dump) được stream ra và lưu xuống disk/memory của Floci thông qua `StorageFactory`.
  - Trạng thái Snapshot chuyển từ `creating` sang `available`.
- **Khôi phục (`RestoreFromClusterSnapshot`):**
  - Khởi tạo một container PostgreSQL mới tinh (tương tự luồng CreateCluster).
  - Khi container đạt trạng thái ready, Floci load file SQL dump từ Storage.
  - Gọi API `exec` để chạy lệnh `psql -U <username> <dbname>` truyền file dump vào container mới để khôi phục cấu trúc và dữ liệu.
  
## 5. Cấu trúc Testing (Testing Strategy)
- **Unit Testing:** Kiểm tra parsing XML và logic của các method mới trong `RedshiftService`.
- **Integration Testing (SDK v2):** 
  - Gọi `CreateCluster`, tạo bảng, chèn dữ liệu qua JDBC.
  - Gọi `CreateClusterSnapshot` thông qua AWS SDK.
  - Xoá cluster cũ.
  - Gọi `RestoreFromClusterSnapshot` thông qua AWS SDK.
  - Kết nối JDBC vào cluster mới và `SELECT` xác minh dữ liệu cũ vẫn tồn tại.
