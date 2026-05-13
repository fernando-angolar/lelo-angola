package ao.com.angotech.dto;

public record AuthResponse(

        String token,
        String refreshToken,
        UserResponse user
) {
}
