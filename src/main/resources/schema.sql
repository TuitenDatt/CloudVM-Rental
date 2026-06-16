-- ================================================================
-- DATABASE: CloudVmDB
-- Tạo database trước khi chạy script này:
--   CREATE DATABASE CloudVmDB;
--   USE CloudVmDB;
-- ================================================================

-- ================================================================
-- TABLE: Users
-- ================================================================
CREATE TABLE Users (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    username     VARCHAR(50)  NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,
    email        VARCHAR(100) NOT NULL UNIQUE,
    created_at   DATETIME     DEFAULT GETDATE()
);

-- ================================================================
-- TABLE: Packages
-- ================================================================
CREATE TABLE Packages (
    id            INT IDENTITY(1,1) PRIMARY KEY,
    package_name  NVARCHAR(100) NOT NULL,
    duration_days INT           NOT NULL,
    price         DECIMAL(10,2) NOT NULL,
    ami_id        VARCHAR(50)   NOT NULL,
    instance_type VARCHAR(20)   DEFAULT 't2.micro'
);

-- ================================================================
-- TABLE: CloudInstances
-- ================================================================
CREATE TABLE CloudInstances (
    id                INT IDENTITY(1,1) PRIMARY KEY,
    user_id           INT          FOREIGN KEY REFERENCES Users(id),
    package_id        INT          FOREIGN KEY REFERENCES Packages(id),
    aws_instance_id   VARCHAR(50)  NULL,
    public_ip         VARCHAR(50)  NULL,
    status            VARCHAR(20)  NOT NULL, -- PENDING | RUNNING | STOPPED_EXPIRED | TERMINATED
    start_date        DATETIME     DEFAULT GETDATE(),
    expire_date       DATETIME     NOT NULL,
    created_at        DATETIME     DEFAULT GETDATE()
);

-- ================================================================
-- SEED DATA: Packages
-- Thay ami_id bằng AMI Windows Server thực tế tại ap-southeast-1
-- Windows Server 2022 Base tại ap-southeast-1: ami-0c7c4f1e6f1e1e1e1 (ví dụ)
-- Tìm AMI thực tại: EC2 Console -> AMIs -> Windows
-- ================================================================
INSERT INTO Packages (package_name, duration_days, price, ami_id, instance_type)
VALUES
    (N'Gói 7 Ngày - t2.micro',  7,  150000.00, 'ami-xxxxxxxxxxxxxxxxx', 't2.micro'),
    (N'Gói 30 Ngày - t2.micro', 30, 500000.00, 'ami-xxxxxxxxxxxxxxxxx', 't2.micro'),
    (N'Gói 7 Ngày - t3.micro',  7,  180000.00, 'ami-xxxxxxxxxxxxxxxxx', 't3.micro'),
    (N'Gói 30 Ngày - t3.micro', 30, 620000.00, 'ami-xxxxxxxxxxxxxxxxx', 't3.micro');
