package ao.com.angotech.service;

import ao.com.angotech.dto.AuthResponse;
import ao.com.angotech.dto.LoginRequest;
import ao.com.angotech.dto.RegisterRequest;
import ao.com.angotech.dto.UserResponse;
import org.springframework.stereotype.Service;

public interface AuthService {

    UserResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    AuthResponse refreshToken(String refreshToken);
}
