package edu.campus.rag.entity;

import jakarta.persistence.*;

@Entity(name = "Chunk")
@Table(
    name = "document_chunk",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"doc_id", "generation", "chunk_index"}),
      @UniqueConstraint(columnNames = "vector_id")
    },
    indexes = @Index(name = "ix_chunk_document", columnList = "doc_id,generation"))
public class Chunk extends BaseEntity {
  @Column(nullable = false)
  public Long docId;

  @Column(nullable = false)
  public String generation;

  @Column(nullable = false)
  public int chunkIndex;

  @Lob
  @Column(columnDefinition = "MEDIUMTEXT")
  public String chunkText;

  public String vectorId, sectionLabel;
  public Integer pageStart, pageEnd;
  public String embeddingModel;
  public int embeddingDimension;
  public boolean isDeleted;
}
