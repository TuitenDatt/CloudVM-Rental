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
    auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    email_verified BIT NOT NULL DEFAULT 1,
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
-- TABLE: RefreshTokens
-- ================================================================
CREATE TABLE RefreshTokens (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    user_id      INT          NOT NULL FOREIGN KEY REFERENCES Users(id),
    token        VARCHAR(255) NOT NULL UNIQUE,
    expires_at   DATETIME     NOT NULL,
    revoked      BIT          NOT NULL DEFAULT 0,
    created_at   DATETIME     DEFAULT GETDATE()
);

-- ================================================================
-- TABLE: VerificationTokens
-- ================================================================
CREATE TABLE VerificationTokens (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    user_id      INT          NOT NULL FOREIGN KEY REFERENCES Users(id),
    token        VARCHAR(255) NOT NULL UNIQUE,
    type         VARCHAR(30)  NOT NULL,
    expires_at   DATETIME     NOT NULL,
    used         BIT          NOT NULL DEFAULT 0,
    created_at   DATETIME     DEFAULT GETDATE()
);

-- ================================================================

-- ================================================================
INSERT INTO Packages (package_name, duration_days, price, ami_id, instance_type)
VALUES
    (N'Gói 7 Ngày - t2.micro',  7,  150000.00, 'ami-0ab5e8edee718de14', 't2.micro'),
    (N'Gói 30 Ngày - t2.micro', 30, 500000.00, 'ami-0ab5e8edee718de14', 't2.micro'),
    (N'Gói 7 Ngày - t3.micro',  7,  180000.00, 'ami-0ab5e8edee718de14', 't3.micro'),
    (N'Gói 30 Ngày - t3.micro', 30, 620000.00, 'ami-0ab5e8edee718de14', 't3.micro');
