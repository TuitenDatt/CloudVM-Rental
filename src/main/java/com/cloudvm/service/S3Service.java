package com.cloudvm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {

    private final S3Client s3Client;

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.s3.bucketName}")
    private String bucketName;

    /**
     * Upload an avatar image file to S3 bucket.
     * Generates a unique key and returns the public S3 URL of the uploaded image.
     */
    public String uploadAvatar(Integer userId, MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        // Generate unique key: avatars/user-ID-UUID.extension
        String objectKey = String.format("avatars/user-%d-%s%s", userId, UUID.randomUUID().toString(), extension);

        log.info("Uploading avatar to S3 bucket: {}, key: {}", bucketName, objectKey);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .acl(ObjectCannedACL.PUBLIC_READ) // Request public read access
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            String publicUrl = String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, awsRegion, objectKey);
            log.info("Avatar uploaded successfully. Public URL: {}", publicUrl);
            return publicUrl;
        } catch (Exception e) {
            log.error("Failed to upload avatar to S3", e);
            throw new IOException("Failed to upload avatar to S3: " + e.getMessage(), e);
        }
    }

    /**
     * Delete an object from S3 using its public URL.
     */
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }

        // URL format: https://bucketName.s3.region.amazonaws.com/key
        String prefix = String.format("https://%s.s3.%s.amazonaws.com/", bucketName, awsRegion);
        if (!fileUrl.startsWith(prefix)) {
            log.warn("File URL does not match bucket prefix, skipping deletion: {}", fileUrl);
            return;
        }

        String objectKey = fileUrl.substring(prefix.length());
        log.info("Deleting S3 object: {}", objectKey);

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("Deleted S3 object successfully");
        } catch (Exception e) {
            log.error("Failed to delete S3 object: " + objectKey, e);
        }
    }
}
