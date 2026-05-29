package ao.com.angotech.modules.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * Lançado quando a password actual está incorrecta ao tentar mudar password.
 * Retorna 400 (não 401) porque o utilizador já está autenticado — o erro é de input.
 */
public class WrongPasswordException extends InvalidCredentialsException {

    @Override
    public String getErrorCode() {
        return "WRONG_PASSWORD";
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
