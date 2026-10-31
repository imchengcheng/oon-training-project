package edu.campus.rag.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity(name = "Document")
@Table(name = "regulation_document")
public class Document extends BaseEntity {
  @Column(nullable = false, length = 100)
  public String title;

  @Column(nullable = false, length = 32)
  public String category;

  @Column(nullable = false)
  public String filePath;

  public String originalFilename;

  @Column(length = 8)
  public String fileType;

  public long fileSize;
  public String fileHash;

  @Column(nullable = false)
  public Long uploaderId;

  @Column(nullable = false, length = 16)
  public String vectorStatus = "PENDING";

  @Column(nullable = false, length = 16)
  public String lifecycleStatus = "ACTIVE";

  public String activeGeneration;
  public int chunkCount;
  public String taskId, targetGeneration, taskStage;
  public OffsetDateTime taskAcceptedTime, taskStartedTime, taskFinishedTime;
  public String errorCode, errorStage;

  @Column(length = 1000)
  public String errorMessage;

  public String cleanupCode, cleanupStage;

  @Column(length = 1000)
  public String cleanupMessage;
}
