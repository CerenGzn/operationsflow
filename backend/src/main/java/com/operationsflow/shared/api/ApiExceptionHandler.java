package com.operationsflow.shared.api;

import java.util.List;
import com.operationsflow.shared.domain.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ProblemDetail business(BusinessException ex) {
        HttpStatus status = switch (ex.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_QUERY" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, ex.code(), ex.getMessage());
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "IDENTIFIER_CONFLICT", "The identifier is already in use or the change violates a data constraint.");
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail stale(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "STALE_VERSION", "This record changed. Reload it before saving.");
    }
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Correct the highlighted fields.");
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldError(e.getField(), e.getDefaultMessage())).toList();
        detail.setProperty("errors", errors);
        return handleExceptionInternal(ex, detail, headers, status, request);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (body == null || body instanceof ProblemDetail) {
            ProblemDetail detail = body instanceof ProblemDetail p ? p : ProblemDetail.forStatus(status);
            if (detail.getProperties() == null || !detail.getProperties().containsKey("code")) {
                detail.setProperty("code", "INVALID_REQUEST");
                detail.setDetail("The request could not be processed. Check its format and parameters.");
            }
            body = detail;
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }
    private static ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code);
        return detail;
    }
    public record FieldError(String field, String message) {}
}
