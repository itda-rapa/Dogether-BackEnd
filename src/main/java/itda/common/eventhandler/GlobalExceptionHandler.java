package itda.common.eventhandler;

import itda.common.constants.ErrorCode;
import itda.common.dto.ApiResponse;
import itda.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.getErrorCode();
        return ResponseEntity
                .status(
                        code.getStatus()
                ).body(
                        ApiResponse.fail(
                                code.name(),
                                exception.getMessage())
                );
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception
    ) {
        return ResponseEntity.
                badRequest().
                body(
                        ApiResponse.fail(
                                ErrorCode.VALIDATION_FAILED.name(),
                                ErrorCode.VALIDATION_FAILED.getDescription())
                );
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(
                ApiResponse.fail(
                        ErrorCode.VALIDATION_FAILED.name(),
                        ErrorCode.VALIDATION_FAILED.getDescription()
                )
        );
    }

    @ExceptionHandler({
            ObjectOptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleConcurrentUpdateConflict(
            RuntimeException exception
    ) {
        ErrorCode code = ErrorCode.CONCURRENT_UPDATE_CONFLICT;
        return ResponseEntity.status(code.getStatus()).body(
                ApiResponse.fail(code.name(), code.getDescription())
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(
            NoResourceFoundException exception
    ) {
        return ResponseEntity
                .status(ErrorCode.RESOURCE_NOT_FOUND.getStatus())
                .body(ApiResponse.fail(
                        ErrorCode.RESOURCE_NOT_FOUND.name(),
                        ErrorCode.RESOURCE_NOT_FOUND.getDescription()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled server error", exception);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(
                        ErrorCode.INTERNAL_ERROR.name(),
                        ErrorCode.INTERNAL_ERROR.getDescription()
                ));
    }
}
