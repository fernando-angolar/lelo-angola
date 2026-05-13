package ao.com.angotech.service.impl;

import ao.com.angotech.dto.AuthResponse;
import ao.com.angotech.dto.LoginRequest;
import ao.com.angotech.dto.RegisterRequest;
import ao.com.angotech.dto.UserResponse;
import ao.com.angotech.entity.User;
import ao.com.angotech.repository.UserRepository;
import ao.com.angotech.security.JwtService;
import ao.com.angotech.service.AuthService;
import io.jsonwebtoken.Jwt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthServiceImpl implements AuthService {


    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserRepository repository, PasswordEncoder encoder, JwtService jwtService) {
        this.userRepository = repository;
        this.passwordEncoder = encoder;
        this.jwtService = jwtService;
    }

    @Override
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new RuntimeException("Email já está cadastrado");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setPhone(request.phone());
        user.setRoles(List.of(request.role() != null ? request.role() : "ROLE_BUYER"));
        user.setEnabled(true);

        User savedUser = userRepository.save(user);

        return new UserResponse(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getFullName(),
                savedUser.getPhone(),
                savedUser.getRoles()
        );
    }

    @Override
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new RuntimeException("Senha inválida");
        }

        String token = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        UserResponse userResponse = new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRoles()
        );

        return new AuthResponse(token, refreshToken, userResponse);
    }

    @Override
    public AuthResponse refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RuntimeException("Refresh token não fornecido");
        }

        String email = jwtService.extractUsername(refreshToken);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado"));

        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new RuntimeException("Refresh token inválido ou expirado");
        }

        String newToken = jwtService.generateToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user);

        UserResponse userResponse = new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRoles()
        );

        return new AuthResponse(newToken, newRefreshToken, userResponse);
    }
}
