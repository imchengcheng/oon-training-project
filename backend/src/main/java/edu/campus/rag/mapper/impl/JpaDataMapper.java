package edu.campus.rag.mapper.impl;

import edu.campus.rag.mapper.DataMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JpaDataMapper implements DataMapper {
  @PersistenceContext private EntityManager em;
  private final TransactionTemplate transactions;

  public JpaDataMapper(PlatformTransactionManager manager) {
    transactions = new TransactionTemplate(manager);
  }

  public <T> T tx(Supplier<T> work) {
    return transactions.execute(status -> work.get());
  }

  public <T> T get(Class<T> type, Long id) {
    return em.find(type, id);
  }

  public <T> T lock(Class<T> type, Long id) {
    return em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
  }

  public <T> T save(T value) {
    em.persist(value);
    em.flush();
    return value;
  }

  public void flush() {
    em.flush();
  }

  public void remove(Object value) {
    em.remove(value);
  }

  public <T> List<T> list(Class<T> type, String jpql, Object... parameters) {
    var q = em.createQuery(jpql, type);
    for (int i = 0; i < parameters.length; i++) q.setParameter(i + 1, parameters[i]);
    return q.getResultList();
  }

  public <T> T one(Class<T> type, String jpql, Object... parameters) {
    return list(type, jpql, parameters).stream().findFirst().orElse(null);
  }

  @Override
  public long count(Class<?> type) {
    String name = em.getMetamodel().entity(type).getName();
    return em.createQuery("select count(e) from " + name + " e", Long.class).getSingleResult();
  }

  @Override
  public int executeUpdate(String jpql) {
    return em.createQuery(jpql).executeUpdate();
  }

  @Override
  public DatabaseInfo databaseInfo() {
    return em.unwrap(org.hibernate.Session.class)
        .doReturningWork(
            connection ->
                new DatabaseInfo(
                    connection.getMetaData().getDatabaseProductName(), connection.getCatalog()));
  }
}
