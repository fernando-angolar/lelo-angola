package ao.com.angotech.controller;

import ao.com.angotech.dto.AuthResponse;
import ao.com.angotech.dto.LoginRequest;
import ao.com.angotech.dto.RegisterRequest;
import ao.com.angotech.dto.UserResponse;
import ao.com.angotech.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {


    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestHeader("Authorization") String authHeader) {
        String refreshToken = authHeader.replace("Bearer ", "").trim();
        return ResponseEntity.ok(authService.refreshToken(refreshToken));
    }

    @GetMapping("/test")
    public String test() {
        return "Você está autenticado como " + SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
