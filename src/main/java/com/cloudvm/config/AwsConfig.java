package com.cloudvm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Cấu hình AWS SDK clients.
 *
 * DefaultCredentialsProvider tự động đọc credentials theo thứ tự:
 *   1. Env vars: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
 *   2. Java system properties
 *   3. ~/.aws/credentials (AWS CLI)
 *   4. IAM Role gắn vào EC2 instance đang chạy app
 *
 * Không hardcode credentials trong code để đảm bảo bảo mật.
 */
@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String awsRegion;

    /**
     * EC2 Client dùng để: runInstances, stopInstances, terminateInstances,
     * describeInstances (poll trạng thái instance).
     */
    @Bean
    public Ec2Client ec2Client() {
        return Ec2Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * SSM Client dùng để: startSession (tạo WebSocket session vào PowerShell
     * của Windows Server instance mà không cần RDP/SSH).
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
