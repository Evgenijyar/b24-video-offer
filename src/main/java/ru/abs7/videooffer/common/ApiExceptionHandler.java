package ru.abs7.videooffer.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import ru.abs7.videooffer.tenant.BackofficeAuthService;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Проверьте заполнение полей");
        log.warn("Request validation failed: error={}", message, error);
        return ResponseEntity.badRequest().body(ApiError.of(message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException error) {
        log.warn("Bad request: error={}", error.getMessage(), error);
        return ResponseEntity.badRequest().body(ApiError.of(error.getMessage()));
    }

    @ExceptionHandler(BackofficeAuthService.BackofficeUnauthorizedException.class)
    public ResponseEntity<ApiError> backofficeUnauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of("Требуется вход в back-office"));
    }

    @ExceptionHandler(BackofficeAuthService.BackofficeCsrfException.class)
    public ResponseEntity<ApiError> backofficeCsrf() {
        return ResponseEntity.status(403).body(ApiError.of("Сессия back-office устарела. Обновите страницу"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException error) {
        log.info("Requested entity not found: error={}", error.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(error.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception error) {
        log.error("Unhandled application error: error={}", rootMessage(error), error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("Внутренняя ошибка приложения: " + rootMessage(error)));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
