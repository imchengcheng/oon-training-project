package edu.campus.rag.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity(name = "Session")
@Table(
    name = "qa_session",
    indexes = @Index(name = "ix_session_user", columnList = "user_id,last_active_time"))
public class Session extends BaseEntity {
  @Column(nullable = false)
  public Long userId;

  public String title = "新会话";

  @Column(nullable = false, length = 16)
  public String status = "ACTIVE";

  public OffsetDateTime lastActiveTime;
  public Long processingMessageId;
}
