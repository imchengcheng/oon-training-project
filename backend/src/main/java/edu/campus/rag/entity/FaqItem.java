package edu.campus.rag.entity;

import jakarta.persistence.*;

@Entity(name = "FaqItem")
@Table(name = "campus_faq", indexes = @Index(name = "ix_faq_category", columnList = "category,published"))
public class FaqItem extends BaseEntity {
  @Column(nullable = false, length = 300) public String question;
  @Column(nullable = false, length = 32) public String category;
  @Lob @Column(columnDefinition = "MEDIUMTEXT") public String answer;
  public Long sourceDocId;
  public String sourceGeneration;
  @Column(nullable = false) public boolean published;
  @Column(nullable = false) public int sortOrder;
  @Column(nullable = false) public long useCount;
}
