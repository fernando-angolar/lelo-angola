package ao.com.angotech.modules.auth.exception;

import ao.com.angotech.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyExistsException extends BusinessException {

    public EmailAlreadyExistsException(String email) {
        super("EMAIL_ALREADY_EXISTS", "Email já está em uso: " + email, HttpStatus.CONFLICT);
    }
}
