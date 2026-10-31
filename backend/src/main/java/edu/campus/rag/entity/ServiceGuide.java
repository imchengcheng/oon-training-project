package edu.campus.rag.entity;

import jakarta.persistence.*;

@Entity(name = "ServiceGuide")
@Table(name = "campus_service_guide", indexes = @Index(name = "ix_guide_category", columnList = "category,published"))
public class ServiceGuide extends BaseEntity {
  @Column(nullable = false, length = 140) public String title;
  @Column(nullable = false, length = 32) public String category;
  @Column(length = 500) public String summary;
  @Column(length = 80) public String department;
  @Lob @Column(columnDefinition = "TEXT") public String materials;
  @Lob @Column(columnDefinition = "MEDIUMTEXT") public String steps;
  @Column(length = 200) public String processingTime;
  @Column(length = 500) public String contact;
  public Long sourceDocId;
  public String sourceGeneration;
  @Column(nullable = false) public boolean published;
}
