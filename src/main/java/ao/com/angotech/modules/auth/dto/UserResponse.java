package ao.com.angotech.modules.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String phone,
        List<String> roles,
        boolean enabled,
        Instant createdAt
) {}
