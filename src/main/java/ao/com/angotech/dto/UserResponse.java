package ao.com.angotech.dto;

import jakarta.persistence.*;

import java.util.List;

public record UserResponse(
        String id,
        String email,
        String fullName,
        String phone,
        List<String> roles
) {}
