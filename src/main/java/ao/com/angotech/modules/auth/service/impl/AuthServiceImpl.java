package ao.com.angotech.modules.auth.service.impl;

import ao.com.angotech.modules.auth.domain.User;
import ao.com.angotech.modules.auth.dto.AuthResponse;
import ao.com.angotech.modules.auth.dto.LoginRequest;
import ao.com.angotech.modules.auth.dto.LogoutRequest;
import ao.com.angotech.modules.auth.dto.RegisterRequest;
import ao.com.angotech.modules.auth.dto.UserResponse;
import ao.com.angotech.modules.auth.exception.DisabledUserException;
import ao.com.angotech.modules.auth.exception.EmailAlreadyExistsException;
import ao.com.angotech.modules.auth.exception.InvalidCredentialsException;
import ao.com.angotech.modules.auth.exception.InvalidTokenException;
import ao.com.angotech.modules.auth.exception.UserNotFoundException;
import ao.com.angotech.modules.auth.exception.WrongPasswordException;
import ao.com.angotech.modules.auth.repository.UserRepository;
import ao.com.angotech.modules.auth.security.JwtService;
import ao.com.angotech.modules.auth.service.AuthService;
import ao.com.angotech.shared.exception.BusinessException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private static final Set<String> RESTRICTED_ROLES = Set.of("ADMIN", "ROLE_ADMIN");
    private static final Set<String> ALLOWED_ROLES = Set.of("BUYER", "SELLER", "ROLE_BUYER", "ROLE_SELLER");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String role = request.role() == null || request.role().isBlank() ? "BUYER" : request.role();

        if (RESTRICTED_ROLES.contains(role.toUpperCase().replace("ROLE_", "").trim())
                || role.equalsIgnoreCase("ROLE_ADMIN")
                || role.equalsIgnoreCase("ADMIN")) {
            throw new BusinessException("ROLE_NOT_ALLOWED",
                    "Não é possível criar utilizadores com role ADMIN via registo público",
                    HttpStatus.BAD_REQUEST);
        }

        String normalizedRole = role.startsWith("ROLE_") ? role.toUpperCase() : "ROLE_" + role.toUpperCase();

        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setPhone(request.phone());
        user.setRoles(List.of(normalizedRole));
        user.setEnabled(true);

        User saved = userRepository.save(user);
        return toUserResponse(saved);
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        if (!user.isEnabled()) {
            throw new DisabledUserException();
        }

        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidTokenException();
        }

        if (jwtService.isTokenBlacklisted(refreshToken)) {
            throw new InvalidTokenException();
        }

        String email;
        try {
            email = jwtService.extractUsername(refreshToken);
        } catch (Exception e) {
            throw new InvalidTokenException();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidTokenException::new);

        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new InvalidTokenException();
        }

        jwtService.blacklistToken(refreshToken);

        return buildAuthResponse(user);
    }

    @Override
    @Transactional
    public void logout(String accessToken, LogoutRequest request) {
        jwtService.blacklistToken(accessToken);

        if (request.refreshToken() != null && !request.refreshToken().isBlank()) {
            try {
                jwtService.blacklistToken(request.refreshToken());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public UserResponse getMe(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Utilizador não encontrado", HttpStatus.NOT_FOUND));
        return toUserResponse(user);
    }

    @Override
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new WrongPasswordException();
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public UserResponse toggleUserStatus(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        user.setEnabled(!user.isEnabled());
        User saved = userRepository.save(user);
        return toUserResponse(saved);
    }

    @Override
    public Page<UserResponse> listUsers(String search, Boolean enabled, Pageable pageable) {
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), pattern),
                        cb.like(cb.lower(root.get("fullName")), pattern)
                ));
            }
            if (enabled != null) {
                predicates.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return userRepository.findAll(spec, pageable).map(this::toUserResponse);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        long expiresIn = jwtService.getRemainingTtlMillis(accessToken) / 1000;
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRoles(),
                user.isEnabled(),
                user.getCreatedAt()
        );
    }
}
