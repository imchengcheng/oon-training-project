package edu.campus.rag.entity;

import jakarta.persistence.*;

@Entity(name = "Source")
@Table(
    name = "answer_source",
    uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "rank_no"}))
public class Source extends BaseEntity {
  @Column(nullable = false)
  public Long messageId;

  @Column(nullable = false)
  public Long chunkId;

  @Column(nullable = false)
  public Long docId;

  public String generation;
  public double similarityScore;
  public int rankNo;
  public String docTitleSnapshot, categorySnapshot, sectionLabelSnapshot;
  public Integer pageStart, pageEnd;

  @Lob
  @Column(columnDefinition = "MEDIUMTEXT")
  public String chunkTextSnapshot;
}
