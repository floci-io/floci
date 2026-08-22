# Thiết Kế Kỹ Thuật: AWS Redshift Service Emulator

## 1. Tổng quan (Overview)
Tính năng này nhằm mục đích bổ sung service Amazon Redshift vào bộ AWS emulator cục bộ (Floci). Service này sẽ mô phỏng lại Control Plane (Management API) bằng AWS Query Protocol và cung cấp Data Plane thực tế thông qua việc cấp phát động các Docker container chạy PostgreSQL.

## 2. Kiến trúc & Định tuyến (Architecture & Routing)
- **Giao thức:** Sử dụng AWS Query Protocol.
- **Controller/Handler:** 
  - Khởi tạo `RedshiftQueryHandler` để phân tích (parse) Request form-encoded và tạo Response XML bằng `XmlBuilder`.
  - Tích hợp vào `AwsQueryController` chung của hệ thống (dựa theo Action hoặc credential scope).
- **API hỗ trợ (Giai đoạn 1):**
  - `CreateCluster`
  - `DescribeClusters`
  - `DeleteCluster`

## 3. Models & Quản lý trạng thái (State Management)
- **Domain Models:** Cần định nghĩa các models tương thích với cấu trúc XML của Redshift SDK:
  - `Cluster`: Thông tin metadata của cluster (ClusterIdentifier, NodeType, MasterUsername, Status...).
  - `Endpoint`: Lưu Address và Port.
- **Storage:** 
  - Trạng thái cluster được quản lý bởi `RedshiftService`.
  - Việc lưu trữ sẽ dùng `StorageFactory` (tương tự các service khác trong Floci), hỗ trợ cả in-memory và persistent storage.

## 4. Data Plane & Docker Lifecycle
- **Công nghệ mô phỏng Data Plane:** Sử dụng `postgres:15-alpine` container làm database engine thực tế cho mỗi Redshift cluster.
- **Quản lý Vòng đời (Lifecycle):**
  - Tạo class `RedshiftContainerManager`.
  - **Khi Create:** Khởi chạy container `postgres`, ánh xạ cổng động hoặc tĩnh, thiết lập user/password từ parameters của request.
  - **Health Check:** Chuyển trạng thái cluster sang `available` chỉ sau khi container sẵn sàng nhận connection (Postgres is ready).
  - **Khi Delete:** Dừng và xóa container, giải phóng tài nguyên.

## 5. Cấu hình (Configuration)
- Khai báo thông số cấu hình vào `EmulatorConfig` và `application.yml` (ví dụ: `floci.services.redshift.default-port`, `floci.services.redshift.image-version`).

## 6. Chiến lược kiểm thử (Testing Strategy)
- **Unit Testing:** 
  - Test việc parse XML và validate logic nghiệp vụ trong `RedshiftService`. Mock các tương tác với Docker.
- **Integration Testing:** 
  - Sử dụng AWS Java SDK v2 (`software.amazon.awssdk.services.redshift.RedshiftClient`) trỏ tới Floci để gọi API.
  - Sau khi `CreateCluster` thành công, dùng JDBC driver của PostgreSQL/Redshift mở TCP connection vào cluster để thực thi câu lệnh SQL cơ bản, đảm bảo Data Plane hoạt động chính xác.
