package edu.campus.rag.entity;

import jakarta.persistence.*;

@Entity(name = "KnowledgeBookmark")
@Table(name = "knowledge_bookmark",
    uniqueConstraints = @UniqueConstraint(name = "uk_bookmark_owner_target", columnNames = {"user_id", "target_type", "target_id"}),
    indexes = @Index(name = "ix_bookmark_user", columnList = "user_id,create_time"))
public class KnowledgeBookmark extends BaseEntity {
  @Column(nullable = false) public Long userId;
  @Column(nullable = false, length = 16) public String targetType;
  @Column(nullable = false) public Long targetId;
  @Column(nullable = false, length = 200) public String titleSnapshot;
  @Column(nullable = false, length = 2000) public String previewSnapshot;
  @Column(length = 500) public String note = "";
}
