package edu.campus.rag.entity;

import jakarta.persistence.*;

@Entity(name = "Feedback")
@Table(
    name = "answer_feedback",
    uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_id"}))
public class Feedback extends BaseEntity {
  @Column(nullable = false)
  public Long messageId;

  @Column(nullable = false)
  public Long userId;

  @Column(length = 8)
  public String rating;

  @Column(length = 500)
  public String remark;
}
