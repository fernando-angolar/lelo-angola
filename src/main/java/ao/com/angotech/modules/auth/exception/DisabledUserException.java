package ao.com.angotech.modules.auth.exception;

import ao.com.angotech.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class DisabledUserException extends BusinessException {

    public DisabledUserException() {
        super("ACCOUNT_DISABLED", "Conta desactivada. Contacte o suporte.", HttpStatus.FORBIDDEN);
    }
}
