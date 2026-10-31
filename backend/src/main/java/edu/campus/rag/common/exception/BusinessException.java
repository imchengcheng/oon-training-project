package edu.campus.rag.common.exception;

public class BusinessException extends RuntimeException {
  public final int status;
  public final String code;
  public Object data;

  public BusinessException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
