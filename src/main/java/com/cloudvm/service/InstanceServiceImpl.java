package com.cloudvm.service;

import com.cloudvm.dto.response.InstanceResponse;
import com.cloudvm.entity.CloudInstance;
import com.cloudvm.entity.Package;
import com.cloudvm.entity.User;
import com.cloudvm.enums.InstanceStatus;
import com.cloudvm.repository.CloudInstanceRepository;
import com.cloudvm.repository.PackageRepository;
import com.cloudvm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Implementation xử lý toàn bộ luồng thuê máy ảo (Luồng 1).
 *
 * Luồng bất đồng bộ:
 * 1. rentInstance()        → Kiểm tra quota, tạo record PENDING, return ngay
 * 2. provisionInstanceAsync() → @Async: gọi AWS API, đợi RUNNING, update DB
 *
 * Quan trọng: @Async phải gọi từ bean khác hoặc qua self-injection vì Spring
 * AOP proxy không intercept method call trong cùng class.
 * Ở đây dùng @Lazy self-injection pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InstanceServiceImpl implements InstanceService {

    private static final int MAX_ACTIVE_INSTANCES = 2;
    private static final int PROVISION_POLL_INTERVAL_MS = 15_000; // 15 giây
    private static final int PROVISION_MAX_ATTEMPTS = 40;          // Tối đa 10 phút

    private final CloudInstanceRepository cloudInstanceRepository;
    private final PackageRepository packageRepository;
    private final UserRepository userRepository;
    private final Ec2Client ec2Client;
    private final InstanceNotificationService instanceNotificationService;

    /**
     * Self-reference để gọi @Async method từ trong cùng class.
     * Dùng setter injection để tránh circular dependency.
     */
    private InstanceServiceImpl self;

    @Value("${aws.iam.instanceProfileName}")
    private String iamInstanceProfileName;

    @Value("${aws.ec2.securityGroupId}")
    private String securityGroupId;

    @Value("${aws.ec2.subnetId}")
    private String subnetId;

    /**
     * Setter injection cho self-reference (giải quyết vấn đề @Async trong cùng class).
     * Spring tự inject bean sau khi khởi tạo.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy InstanceServiceImpl self) {
        this.self = self;
    }

    // ================================================================
    // PUBLIC METHODS — InstanceService interface
    // ================================================================

    /**
     * Luồng 1: Thuê máy ảo.
     *
     * Bước 1-3: Kiểm tra quota, tạo record PENDING, trả về ngay
     * Bước 4:   Gọi @Async provisionInstanceAsync để khởi tạo EC2 ngầm
     */
    @Override
    @Transactional
    public InstanceResponse rentInstance(Integer userId, Integer packageId) {
        // B1: Load User
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User không tồn tại: " + userId));

        // B2: Load Package
        Package pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new NoSuchElementException("Package không tồn tại: " + packageId));

        // B3: Kiểm tra Quota — tối đa MAX_ACTIVE_INSTANCES instance active
        long activeCount = cloudInstanceRepository.countActiveByUserId(userId);
        if (activeCount >= MAX_ACTIVE_INSTANCES) {
            throw new IllegalStateException(
                    "Bạn đã đạt giới hạn " + MAX_ACTIVE_INSTANCES + " máy ảo cùng lúc. " +
                    "Vui lòng chờ các máy hiện tại hết hạn hoặc bị thu hồi."
            );
        }

        // B4: Tính expire_date và tạo record mới với status PENDING
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireDate = now.plusDays(pkg.getDurationDays());

        CloudInstance newInstance = CloudInstance.builder()
                .user(user)
                .pkg(pkg)
                .status(InstanceStatus.PENDING)
                .startDate(now)
                .expireDate(expireDate)
                .build();

        CloudInstance savedInstance = cloudInstanceRepository.save(newInstance);
        log.info("Tạo instance PENDING thành công. DB ID: {}, User: {}, Package: {}",
                savedInstance.getId(), user.getUsername(), pkg.getPackageName());

        // B5: Kích hoạt @Async provision ngầm — trả về response ngay lập tức
        self.provisionInstanceAsync(savedInstance.getId(), pkg);

        return InstanceResponse.from(savedInstance);
    }

    /**
     * Lấy danh sách instance của user, sắp xếp mới nhất trước.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InstanceResponse> getInstancesByUser(Integer userId) {
        return cloudInstanceRepository
                .findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(InstanceResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Lấy chi tiết một instance, validate quyền sở hữu.
     */
    @Override
    @Transactional(readOnly = true)
    public InstanceResponse getInstanceById(Integer instanceId, Integer userId) {
        // Validate instance thuộc về user đang request
        if (!cloudInstanceRepository.existsByIdAndUserId(instanceId, userId)) {
            throw new NoSuchElementException(
                    "Instance không tồn tại hoặc bạn không có quyền truy cập"
            );
        }

        CloudInstance instance = cloudInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new NoSuchElementException("Instance không tồn tại: " + instanceId));

        return InstanceResponse.from(instance);
    }

    @Override
    @Transactional
    public void terminateInstance(Integer instanceId, Integer userId) {
        log.info("User {} request terminate instance {}", userId, instanceId);

        // Validate instance thuộc về user đang request
        CloudInstance instance = cloudInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new NoSuchElementException("Instance không tồn tại: " + instanceId));

        if (!instance.getUser().getId().equals(userId)) {
            throw new NoSuchElementException("Bạn không có quyền truy cập instance này");
        }

        // Nếu máy đang chạy hoặc đang tạo trên AWS, thực hiện lệnh xóa qua AWS SDK
        String awsId = instance.getAwsInstanceId();
        if (awsId != null && !awsId.isEmpty() && instance.getStatus() != InstanceStatus.TERMINATED) {
            try {
                log.info("Gửi request terminate EC2 instance {} lên AWS", awsId);
                TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                        .instanceIds(awsId)
                        .build();
                ec2Client.terminateInstances(terminateRequest);
                log.info("Terminate thành công trên AWS cho instance {}", awsId);
            } catch (Exception e) {
                log.error("Lỗi khi terminate AWS instance {}: {}", awsId, e.getMessage());
                // Vẫn tiếp tục đổi trạng thái trong DB để user không bị kẹt
            }
        }

        // Cập nhật trạng thái trong DB
        instance.setStatus(InstanceStatus.TERMINATED);
        instance.setAwsInstanceId(null); // Clear aws ID để tránh nhầm lẫn hoặc có thể giữ lại để tracking lịch sử
        cloudInstanceRepository.save(instance);
    }

    // ================================================================
    // ASYNC METHODS — Chạy ngầm trong ThreadPool
    // ================================================================

    /**
     * Luồng 1 - Bước 4: Khởi tạo EC2 instance bất đồng bộ.
     *
     * Method này chạy trong thread pool riêng (CloudVM-Async-*) sau khi
     * rentInstance() đã trả về response cho user.
     *
     * Các bước:
     * 1. Gọi ec2.runInstances() để tạo Windows Server instance
     * 2. Poll trạng thái đến khi instance = RUNNING (tối đa 10 phút)
     * 3. Lấy public IP
     * 4. Update DB: status=RUNNING, awsInstanceId, publicIp
     *
     * Nếu có lỗi → log lỗi, instance vẫn PENDING (cần xử lý thủ công hoặc retry)
     *
     * @param dbInstanceId  ID của CloudInstance trong DB
     * @param pkg           Package chứa amiId và instanceType
     */
    @Async("asyncExecutor")
    public void provisionInstanceAsync(Integer dbInstanceId, Package pkg) {
        log.info("[ASYNC] Bắt đầu provision EC2 cho DB instance ID: {}", dbInstanceId);

        try {
            // Bước 1: Gọi AWS EC2 runInstances
            String awsInstanceId = launchEc2Instance(pkg);
            log.info("[ASYNC] AWS EC2 instance đã được tạo: {}", awsInstanceId);

            // Cập nhật awsInstanceId vào DB ngay để tracking
            CloudInstance instance = cloudInstanceRepository.findById(dbInstanceId)
                    .orElseThrow(() -> new NoSuchElementException("DB instance không tồn tại: " + dbInstanceId));
            instance.setAwsInstanceId(awsInstanceId);
            cloudInstanceRepository.save(instance);

            // Bước 2: Poll đến khi instance trạng thái RUNNING
            String publicIp = waitForInstanceRunningAndGetIp(awsInstanceId);
            log.info("[ASYNC] Instance {} đã RUNNING, IP: {}", awsInstanceId, publicIp);

            // Bước 3: Lấy lại bản ghi mới nhất và update DB thành RUNNING với IP
            CloudInstance updatedInstance = cloudInstanceRepository.findById(dbInstanceId)
                    .orElseThrow(() -> new NoSuchElementException("DB instance không tồn tại: " + dbInstanceId));
            updatedInstance.setStatus(InstanceStatus.RUNNING);
            updatedInstance.setPublicIp(publicIp);
            cloudInstanceRepository.save(updatedInstance);

            log.info("[ASYNC] Provision hoàn thành. DB ID: {}, AWS ID: {}, IP: {}",
                    dbInstanceId, awsInstanceId, publicIp);

            cloudInstanceRepository.findWithUserAndPkgById(dbInstanceId)
                    .ifPresent(instanceNotificationService::sendRentalSuccessEmail);

        } catch (Exception e) {
            log.error("[ASYNC] Provision thất bại cho DB instance {}: {}", dbInstanceId, e.getMessage(), e);
            // Instance vẫn ở PENDING, cần monitoring/alerting
        }
    }

    // ================================================================
    // PRIVATE HELPER METHODS
    // ================================================================

    /**
     * Gọi AWS EC2 RunInstances API để khởi tạo Windows Server instance.
     *
     * Cấu hình:
     * - ImageId: AMI Windows Server từ Package
     * - InstanceType: t2.micro hoặc t3.micro
     * - IamInstanceProfile: role có AmazonSSMManagedInstanceCore
     * - MinCount/MaxCount = 1: chỉ tạo 1 instance
     *
     * @param pkg  Package chứa amiId và instanceType
     * @return     AWS Instance ID (i-xxxxxxxxxxxxxxxxx)
     */
    private String launchEc2Instance(Package pkg) {
        IamInstanceProfileSpecification iamProfile = IamInstanceProfileSpecification.builder()
                .name(iamInstanceProfileName)
                .build();

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(pkg.getAmiId())
                .instanceType(InstanceType.fromValue(pkg.getInstanceType()))
                .minCount(1)
                .maxCount(1)
                .iamInstanceProfile(iamProfile)
                .securityGroupIds(securityGroupId)
                .subnetId(subnetId)
                // Tag để dễ identify instance trên AWS Console
                .tagSpecifications(builder -> builder
                        .resourceType("instance")
                        .tags(
                                tag -> tag.key("Name").value("CloudVM-Rental"),
                                tag -> tag.key("ManagedBy").value("CloudVmApp")
                        )
                )
                .build();

        RunInstancesResponse runResponse = ec2Client.runInstances(runRequest);

        return runResponse.instances().get(0).instanceId();
    }

    /**
     * Poll trạng thái EC2 instance đến khi đạt RUNNING.
     * Windows Server thường mất 3-7 phút để boot hoàn toàn.
     *
     * @param awsInstanceId  AWS Instance ID cần theo dõi
     * @return               Public IP address của instance
     * @throws RuntimeException nếu timeout sau PROVISION_MAX_ATTEMPTS lần thử
     */
    private String waitForInstanceRunningAndGetIp(String awsInstanceId) throws InterruptedException {
        DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                .instanceIds(awsInstanceId)
                .build();

        for (int attempt = 1; attempt <= PROVISION_MAX_ATTEMPTS; attempt++) {
            log.debug("[ASYNC] Poll lần {}/{} cho instance {}", attempt, PROVISION_MAX_ATTEMPTS, awsInstanceId);

            DescribeInstancesResponse describeResponse = ec2Client.describeInstances(describeRequest);

            if (!describeResponse.reservations().isEmpty()
                    && !describeResponse.reservations().get(0).instances().isEmpty()) {

                Instance awsInstance = describeResponse.reservations().get(0).instances().get(0);
                InstanceStateName state = awsInstance.state().name();

                log.debug("[ASYNC] Instance {} state: {}", awsInstanceId, state);

                if (InstanceStateName.RUNNING.equals(state)) {
                    String ip = awsInstance.publicIpAddress();
                    if (ip == null || ip.isEmpty()) {
                        ip = awsInstance.privateIpAddress(); // Fallback về private IP
                    }
                    return ip;
                }

                if (InstanceStateName.TERMINATED.equals(state)
                        || InstanceStateName.SHUTTING_DOWN.equals(state)) {
                    throw new RuntimeException(
                            "Instance " + awsInstanceId + " bị terminate bất ngờ, state: " + state
                    );
                }
            }

            Thread.sleep(PROVISION_POLL_INTERVAL_MS);
        }

        throw new RuntimeException(
                "Timeout: Instance " + awsInstanceId + " không đạt RUNNING sau "
                + (PROVISION_MAX_ATTEMPTS * PROVISION_POLL_INTERVAL_MS / 1000) + " giây"
        );
    }
}
