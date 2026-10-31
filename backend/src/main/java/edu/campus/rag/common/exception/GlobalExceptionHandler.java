package edu.campus.rag.common.exception;

import static edu.campus.rag.common.Api.*;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<?> business(BusinessException ex, HttpServletRequest req) {
    var builder = ResponseEntity.status(ex.status);
    if (ex.status == 429) builder.header("Retry-After", "60");
    return builder.body(
        response(ex.code, ex.getMessage(), ex.data, (String) req.getAttribute("requestId")));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> large(Exception ex, HttpServletRequest req) {
    return business(fail(413, "D-0002", "文件超过 20 MiB"), req);
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class
  })
  public ResponseEntity<?> input(Exception ex, HttpServletRequest req) {
    return business(fail(400, "C-0001", "请求参数或 JSON 格式无效"), req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> internal(Exception ex, HttpServletRequest req) {
    log.error(
        "requestId={} internalFailure={}",
        req.getAttribute("requestId"),
        ex.getClass().getSimpleName());
    return business(fail(500, "C-0003", "内部存储或处理失败"), req);
  }
}
