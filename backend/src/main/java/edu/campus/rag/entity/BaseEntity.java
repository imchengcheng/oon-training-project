package edu.campus.rag.entity;

import static edu.campus.rag.common.TimeUtils.now;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@MappedSuperclass
public abstract class BaseEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false)
  public OffsetDateTime createTime;

  @Column(nullable = false)
  public OffsetDateTime updateTime;

  @PrePersist
  public void created() {
    createTime = now();
    updateTime = createTime;
  }

  @PreUpdate
  public void updated() {
    updateTime = now();
  }
}
