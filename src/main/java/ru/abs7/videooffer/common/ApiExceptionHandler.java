package ru.abs7.videooffer.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Проверьте заполнение полей");
        return ResponseEntity.badRequest().body(ApiError.of(message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(ApiError.of(error.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(error.getMessage()));
    }
}
