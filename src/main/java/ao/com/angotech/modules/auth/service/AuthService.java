package ao.com.angotech.modules.auth.service;

import ao.com.angotech.modules.auth.dto.AuthResponse;
import ao.com.angotech.modules.auth.dto.LoginRequest;
import ao.com.angotech.modules.auth.dto.LogoutRequest;
import ao.com.angotech.modules.auth.dto.RegisterRequest;
import ao.com.angotech.modules.auth.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refreshToken(String refreshToken);

    void logout(String accessToken, LogoutRequest request);

    UserResponse getMe(String email);

    void changePassword(String email, String currentPassword, String newPassword);

    UserResponse toggleUserStatus(UUID userId);

    Page<UserResponse> listUsers(String search, Boolean enabled, Pageable pageable);
}
