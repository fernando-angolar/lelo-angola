package ao.com.angotech.modules.auth.exception;

import ao.com.angotech.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class InvalidTokenException extends BusinessException {

    public InvalidTokenException() {
        super("INVALID_TOKEN", "Token inválido ou expirado", HttpStatus.UNAUTHORIZED);
    }
}
