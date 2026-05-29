package ao.com.angotech.modules.auth.exception;

import ao.com.angotech.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends BusinessException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", "Email ou senha inválidos", HttpStatus.UNAUTHORIZED);
    }
}
