package ao.com.angotech.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6) String password,
        @NotBlank String fullName,
        String phone,
        String role
) {
    public RegisterRequest {
        if (role == null || role.isBlank()) {
            role = "ROLE_BUYER";
        }
    }
}
