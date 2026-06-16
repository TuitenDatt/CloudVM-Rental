# ☁️ CloudVM — Cloud VM Rental Platform

Ứng dụng web cho phép người dùng thuê máy chủ ảo Windows Server trên AWS EC2 và truy cập Terminal ngay trên trình duyệt thông qua AWS Systems Manager Session Manager.

## 🛠 Tech Stack

- **Backend**: Java 21, Spring Boot 3.2, Spring Security (JWT), Hibernate / JPA
- **Database**: Microsoft SQL Server
- **Cloud**: AWS EC2, AWS SSM Session Manager
- **Frontend**: Vanilla HTML/CSS/JavaScript (SPA)

## 🚀 Hướng dẫn cài đặt

### 1. Yêu cầu hệ thống
- Java 21+
- Maven 3.8+
- Microsoft SQL Server (local hoặc remote)
- Tài khoản AWS với quyền EC2 và SSM

### 2. Clone dự án
```bash
git clone https://github.com/YOUR_USERNAME/YOUR_REPO.git
cd YOUR_REPO
```

### 3. Cấu hình secrets
```bash
# Copy file mẫu
cp src/main/resources/application-secrets.properties.example \
   src/main/resources/application-secrets.properties

# Mở file và điền thông tin thật của bạn
```

Nội dung cần điền trong `application-secrets.properties`:
```properties
spring.datasource.username=YOUR_SQL_SERVER_USERNAME
spring.datasource.password=YOUR_SQL_SERVER_PASSWORD
jwt.secret=YOUR_BASE64_JWT_SECRET
aws.ec2.securityGroupId=sg-xxxxxxxxxxxxxxxxx
aws.ec2.subnetId=subnet-xxxxxxxxxxxxxxxxx
```

### 4. Cấu hình AWS Credentials
Ứng dụng đọc AWS credentials từ `~/.aws/credentials` (chuẩn AWS CLI):
```bash
aws configure
```
Hoặc đặt biến môi trường:
```bash
export AWS_ACCESS_KEY_ID=your_key
export AWS_SECRET_ACCESS_KEY=your_secret
```

### 5. Chuẩn bị Database
- Tạo database `CloudVmDB` trong SQL Server
- Chạy file `src/main/resources/schema.sql` để tạo bảng

### 6. Chạy ứng dụng
```bash
mvn spring-boot:run
```
Truy cập: http://localhost:8080

## 🔐 Bảo mật

- Tất cả secrets được lưu trong `application-secrets.properties` (đã gitignore)
- AWS credentials **không** được lưu trong code, đọc từ AWS credential chain
- JWT stateless authentication
- CSRF disabled (REST API)

## 📋 AWS Prerequisites

1. Tạo **IAM Instance Profile** tên `EC2-SSM-Role` với policy `AmazonSSMManagedInstanceCore`
2. Tạo **Security Group** cho EC2 instances
3. Chuẩn bị **Public Subnet** trong VPC
4. AMI: Windows Server 2022 Base (kiểm tra AMI ID đúng với region)
