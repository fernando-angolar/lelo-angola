package ao.com.angotech.modules.auth.exception;

import ao.com.angotech.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public class UserNotFoundException extends BusinessException {

    public UserNotFoundException(UUID userId) {
        super("USER_NOT_FOUND", "Utilizador não encontrado: " + userId, HttpStatus.NOT_FOUND);
    }
}
