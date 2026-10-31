package edu.campus.rag.common;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RateLimits {
  private record Window(long minute, int count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public void take(String key, int limit) {
    long minute = System.currentTimeMillis() / 60000;
    if (windows.size() > 10000) windows.entrySet().removeIf(e -> e.getValue().minute < minute);
    var w =
        windows.compute(
            key,
            (k, old) ->
                old == null || old.minute != minute
                    ? new Window(minute, 1)
                    : new Window(minute, old.count + 1));
    Api.require(w.count <= limit, 429, "C-0002", "请求过于频繁，请稍后重试");
  }
}
