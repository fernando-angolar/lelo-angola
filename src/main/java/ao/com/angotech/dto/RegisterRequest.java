package ao.com.angotech.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Size(min = 6)
        String password,

        @NotBlank
        String fullName,

        String phone,

        String role

) {

    public RegisterRequest {
        if ( role == null || role.isBlank()  ) {
            role = "ROLE_BUYER";
        }
    }

    /**
    public String roleOrDefault() {
        return (role == null || role.isBlank()) ? "ROLE_BUYER" : role;
    }
     */
}
