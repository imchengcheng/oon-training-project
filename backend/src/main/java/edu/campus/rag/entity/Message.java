package edu.campus.rag.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity(name = "Message")
@Table(
    name = "qa_message",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"session_id", "client_request_id"}),
      @UniqueConstraint(columnNames = "reply_to_message_id")
    },
    indexes = @Index(name = "ix_message_session", columnList = "session_id,id"))
public class Message extends BaseEntity {
  @Column(nullable = false)
  public Long sessionId;

  @Column(nullable = false, length = 16)
  public String role;

  @Lob
  @Column(columnDefinition = "MEDIUMTEXT")
  public String content;

  public Long replyToMessageId;
  public String clientRequestId;

  @Column(length = 16)
  public String processingStatus;

  @Lob
  @Column(columnDefinition = "TEXT")
  public String rewrittenQuery;

  @Column(length = 32)
  public String answerStatus;

  public int retrievedCount;
  public String errorCode;
  public int attemptCount;
  public OffsetDateTime lastAttemptTime;
  public long durationMs;
}
