package edu.campus.rag.common;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class TimeUtils {
  private TimeUtils() {}

  public static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }
}
