package edu.campus.rag.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity(name = "ConsultationTicket")
@Table(name = "campus_consultation_ticket", indexes = {
    @Index(name = "ix_ticket_student", columnList = "student_id,create_time"),
    @Index(name = "ix_ticket_status", columnList = "status,update_time")})
public class ConsultationTicket extends BaseEntity {
  @Column(nullable = false) public Long studentId;
  @Column(nullable = false, length = 140) public String title;
  @Column(nullable = false, length = 32) public String category;
  @Lob @Column(columnDefinition = "MEDIUMTEXT") public String content;
  public Long relatedMessageId;
  @Column(nullable = false, length = 16) public String status = "OPEN";
  @Lob @Column(columnDefinition = "MEDIUMTEXT") public String reply;
  public Long handlerId;
  public OffsetDateTime repliedAt;
}
