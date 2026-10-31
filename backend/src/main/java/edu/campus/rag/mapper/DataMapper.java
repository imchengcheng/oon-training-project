package edu.campus.rag.mapper;

import java.util.List;
import java.util.function.Supplier;

/** JPA data access boundary. Call operations inside tx to retain a shared transaction. */
public interface DataMapper {
  record DatabaseInfo(String product, String catalog) {}

  <T> T tx(Supplier<T> work);

  <T> T get(Class<T> type, Long id);

  <T> T lock(Class<T> type, Long id);

  <T> T save(T value);

  void flush();

  void remove(Object value);

  <T> List<T> list(Class<T> type, String jpql, Object... parameters);

  <T> T one(Class<T> type, String jpql, Object... parameters);

  long count(Class<?> type);

  int executeUpdate(String jpql);

  DatabaseInfo databaseInfo();
}
