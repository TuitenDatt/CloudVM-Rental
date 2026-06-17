package com.cloudvm.controller;

import com.cloudvm.dto.request.ChangePasswordRequest;
import com.cloudvm.dto.request.UpdateProfileRequest;
import com.cloudvm.dto.response.ApiResponse;
import com.cloudvm.dto.response.ProfileResponse;
import com.cloudvm.entity.CloudInstance;
import com.cloudvm.entity.User;
import com.cloudvm.repository.CloudInstanceRepository;
import com.cloudvm.repository.UserRepository;
import com.cloudvm.security.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private static final int ACTIVE_INSTANCE_QUOTA = 2;

    private final UserRepository userRepository;
    private final CloudInstanceRepository cloudInstanceRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(
            @RequestHeader("Authorization") String authHeader
    ) {
        User user = getCurrentUser(authHeader);
        return ResponseEntity.ok(ApiResponse.success("Thong tin ho so", buildProfile(user)));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        try {
            User user = getCurrentUser(authHeader);
            String username = request.getUsername().trim();

            userRepository.findByUsername(username)
                    .filter(existing -> !existing.getId().equals(user.getId()))
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException("Username nay da duoc su dung");
                    });

            user.setUsername(username);
            User savedUser = userRepository.save(user);
            return ResponseEntity.ok(ApiResponse.success("Cap nhat ho so thanh cong", buildProfile(savedUser)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/password")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        User user = getCurrentUser(authHeader);

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Mat khau hien tai khong dung"));
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponse.success("Doi mat khau thanh cong", null));
    }

    private User getCurrentUser(String authHeader) {
        String token = authHeader.substring(7);
        Integer userId = jwtUtil.extractUserId(token);
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User khong ton tai"));
    }

    private ProfileResponse buildProfile(User user) {
        List<CloudInstance> instances = cloudInstanceRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        long activeCount = cloudInstanceRepository.countActiveByUserId(user.getId());
        return ProfileResponse.from(user, instances, activeCount, ACTIVE_INSTANCE_QUOTA);
    }
}
