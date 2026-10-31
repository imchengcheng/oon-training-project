package edu.campus.rag.common;

import edu.campus.rag.common.exception.BusinessException;
import edu.campus.rag.dto.ApiResponse;
import java.util.*;

public final class Api {
  private Api() {}

  public static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
    return result;
  }

  public static ApiResponse<Object> response(
      String code, String message, Object data, String requestId) {
    return new ApiResponse<>(code, message, data, requestId);
  }

  public static BusinessException fail(int status, String code, String message) {
    return new BusinessException(status, code, message);
  }

  public static void require(boolean condition, int status, String code, String message) {
    if (!condition) throw fail(status, code, message);
  }

  public static String text(String value, int max, String code) {
    String s = value == null ? "" : value.strip();
    require(!s.isEmpty() && s.codePointCount(0, s.length()) <= max, 400, code, "字段为空或超过长度限制");
    return s;
  }

  public static String cut(String value, int count) {
    return value.substring(
        0, value.offsetByCodePoints(0, Math.min(count, value.codePointCount(0, value.length()))));
  }

  public static final Set<String> CATEGORIES =
      Set.of("STUDENT_STATUS", "DISCIPLINE", "ACADEMIC_AFFAIRS", "DORMITORY");

  public static String category(String value, String code) {
    require(CATEGORIES.contains(value == null ? "" : value), 400, code, "分类无效");
    return value;
  }

  public static void enumeration(String value, Set<String> allowed) {
    if (value != null) require(allowed.contains(value), 400, "C-0001", "枚举值无效");
  }

  public static Long id(String value) {
    try {
      long n = Long.parseLong(value);
      require(n > 0, 400, "C-0001", "ID 无效");
      return n;
    } catch (NumberFormatException ex) {
      throw fail(400, "C-0001", "ID 无效");
    }
  }

  public static <T> Map<String, Object> page(List<T> items, int page, int size) {
    require(page >= 1 && size >= 1 && size <= 100, 400, "C-0001", "分页参数无效");
    long start = (long) (page - 1) * size;
    return map(
        "total",
        items.size(),
        "page",
        page,
        "size",
        size,
        "records",
        items.subList(
            (int) Math.min(start, items.size()), (int) Math.min(start + size, items.size())));
  }
}
