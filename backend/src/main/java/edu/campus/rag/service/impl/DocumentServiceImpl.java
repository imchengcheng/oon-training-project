package edu.campus.rag.service.impl;

import static edu.campus.rag.common.Api.*;
import static edu.campus.rag.common.TimeUtils.now;

import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.common.exception.BusinessException;
import edu.campus.rag.entity.*;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.DocumentService;
import edu.campus.rag.service.support.DocumentParser;
import edu.campus.rag.service.support.ModelGateway;
import edu.campus.rag.service.support.ResponseAssembler;
import edu.campus.rag.service.support.VectorStore;
import jakarta.annotation.PreDestroy;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentServiceImpl implements DocumentService {
  private final DataMapper db;
  private final ResponseAssembler views;
  private final AppProperties s;
  private final DocumentParser parser;
  private final ModelGateway model;
  private final VectorStore vectors;
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(128),
          r -> {
            var t = new Thread(r, "document-worker");
            t.setDaemon(true);
            return t;
          });
  private final Set<Long> deleting = ConcurrentHashMap.newKeySet();

  public DocumentServiceImpl(
      DataMapper db,
      ResponseAssembler views,
      AppProperties s,
      DocumentParser parser,
      ModelGateway model,
      VectorStore vectors) {
    this.db = db;
    this.views = views;
    this.s = s;
    this.parser = parser;
    this.model = model;
    this.vectors = vectors;
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  public Map<String, Object> upload(
      MultipartFile file, String title, String category, Long uploader) {
    title = text(title, 100, "D-0003");
    category = category(category, "D-0003");
    require(file != null && !file.isEmpty(), 400, "D-0003", "请选择文件");
    require(file.getSize() <= 20L * 1024 * 1024, 413, "D-0002", "文件超过 20 MiB");
    String original =
        Optional.ofNullable(file.getOriginalFilename()).orElse("file").replace('\\', '/');
    original = original.substring(original.lastIndexOf('/') + 1);
    require(original.length() <= 240, 400, "D-0003", "文件名过长");
    int dot = original.lastIndexOf('.');
    String type = dot < 0 ? "" : original.substring(dot + 1).toUpperCase(Locale.ROOT);
    require(
        Set.of("PDF", "DOCX", "TXT").contains(type), 415, "D-0001", "仅支持 PDF / DOCX / UTF-8 TXT");
    Path saved = null;
    try {
      Path root = Path.of(s.storage).toAbsolutePath().normalize();
      Files.createDirectories(root);
      saved = root.resolve(UUID.randomUUID() + "." + type.toLowerCase(Locale.ROOT));
      byte[] bytes = file.getBytes();
      validateSignature(bytes, type);
      Files.write(saved, bytes, StandardOpenOption.CREATE_NEW);
      if ("DOCX".equals(type)) {
        try (var zip = new ZipFile(saved.toFile())) {
          require(
              zip.getEntry("word/document.xml") != null
                  && zip.getEntry("[Content_Types].xml") != null,
              415,
              "D-0001",
              "DOCX 内容格式不符");
          require(zip.size() <= 2000, 415, "D-0001", "DOCX 压缩条目超限");
        } catch (BusinessException ex) {
          throw ex;
        } catch (Exception ex) {
          throw fail(415, "D-0001", "DOCX 内容格式不符");
        }
      }
      var d = new Document();
      d.title = title;
      d.category = category;
      d.fileType = type;
      d.fileSize = bytes.length;
      d.originalFilename = original;
      d.filePath = saved.toString();
      d.uploaderId = uploader;
      d.fileHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      initialize(d);
      var accepted =
          db.tx(
              () -> {
                db.save(d);
                return views.document(d, false);
              });
      accepted.put("taskId", d.taskId);
      enqueue(d.id, d.taskId, d.targetGeneration);
      return accepted;
    } catch (Exception ex) {
      if (saved != null)
        try {
          Files.deleteIfExists(saved);
        } catch (Exception ignored) {
          LoggerFactory.getLogger(getClass()).warn("orphanFileCleanupFailed");
        }
      if (ex instanceof BusinessException f) throw f;
      throw fail(500, "C-0003", "文件保存或登记失败");
    }
  }

  private void validateSignature(byte[] bytes, String type) {
    if ("PDF".equals(type))
      require(
          bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"),
          415,
          "D-0001",
          "PDF 内容与扩展名不符");
    if ("DOCX".equals(type))
      require(
          bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K', 415, "D-0001", "DOCX 内容与扩展名不符");
    if ("TXT".equals(type))
      try {
        String v =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        require(
            v.codePoints()
                    .noneMatch(
                        c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                && !v.startsWith("%PDF-")
                && !v.startsWith("PK\u0003\u0004"),
            415,
            "D-0001",
            "TXT 内容格式不符");
      } catch (CharacterCodingException ex) {
        throw fail(415, "D-0001", "TXT 必须为 UTF-8");
      }
  }

  private void initialize(Document d) {
    d.taskId = UUID.randomUUID().toString();
    d.targetGeneration = UUID.randomUUID().toString();
    d.vectorStatus = "PENDING";
    d.taskStage = "QUEUED";
    d.taskAcceptedTime = now();
    d.taskStartedTime = null;
    d.taskFinishedTime = null;
    d.errorCode = null;
    d.errorStage = null;
    d.errorMessage = null;
  }

  public Document existing(Long id) {
    var d = db.get(Document.class, id);
    require(d != null, 404, "D-0004", "文档不存在");
    return d;
  }

  public Map<String, Object> detail(Long id) {
    return db.tx(() -> views.document(existing(id), true));
  }

  public Map<String, Object> rebuild(Long id) {
    String[] task =
        db.tx(
            () -> {
              var d = db.lock(Document.class, id);
              require(d != null, 404, "D-0004", "文档不存在");
              require("ACTIVE".equals(d.lifecycleStatus), 409, "D-0006", "文档正在删除或已删除");
              require(
                  !Set.of("PENDING", "PROCESSING").contains(d.vectorStatus),
                  409,
                  "D-0005",
                  "文档任务已排队或处理中");
              initialize(d);
              return new String[] {d.taskId, d.targetGeneration};
            });
    enqueue(id, task[0], task[1]);
    return db.tx(
        () ->
            map(
                "docId",
                id.toString(),
                "taskId",
                task[0],
                "vectorStatus",
                "PENDING",
                "searchable",
                ResponseAssembler.searchable(existing(id))));
  }

  public Map<String, Object> patch(Long id, Map<String, String> input) {
    require(
        !input.isEmpty() && Set.of("title", "category").containsAll(input.keySet()),
        400,
        "C-0001",
        "仅接受 title/category");
    if (input.containsKey("title")) text(input.get("title"), 100, "D-0003");
    if (input.containsKey("category")) category(input.get("category"), "D-0003");
    return db.tx(
        () -> {
          var d = db.lock(Document.class, id);
          require(d != null, 404, "D-0004", "文档不存在");
          require("ACTIVE".equals(d.lifecycleStatus), 409, "D-0006", "文档正在删除或已删除");
          if (input.containsKey("title")) d.title = input.get("title").strip();
          if (input.containsKey("category")) d.category = input.get("category");
          db.flush();
          return views.document(d, false);
        });
  }

  public Map<String, Object> delete(Long id) {
    String status =
        db.tx(
            () -> {
              var d = db.lock(Document.class, id);
              require(d != null, 404, "D-0004", "文档不存在");
              if (!"DELETED".equals(d.lifecycleStatus)) {
                d.lifecycleStatus = "DELETING";
                d.cleanupCode = null;
                d.cleanupStage = null;
                d.cleanupMessage = null;
              }
              return d.lifecycleStatus;
            });
    if (!"DELETED".equals(status)) enqueueDelete(id);
    return map("docId", id.toString(), "lifecycleStatus", status);
  }

  private void enqueue(Long id, String task, String generation) {
    try {
      worker.execute(() -> process(id, task, generation));
    } catch (RejectedExecutionException ex) {
      failure(id, task, "T-0008", "QUEUED", "任务队列已满，请稍后重建");
    }
  }

  private Document phase(Long id, String task, String stage) {
    return db.tx(
        () -> {
          var d = db.lock(Document.class, id);
          require(
              d != null
                  && "ACTIVE".equals(d.lifecycleStatus)
                  && task.equals(d.taskId)
                  && Set.of("PENDING", "PROCESSING").contains(d.vectorStatus),
              409,
              "D-0006",
              "任务已取消");
          require(
              d.taskAcceptedTime.plusSeconds(s.taskTimeoutSeconds).isAfter(now()),
              504,
              "T-0006",
              "文档处理超时");
          d.vectorStatus = "PROCESSING";
          d.taskStage = stage;
          if (d.taskStartedTime == null) d.taskStartedTime = now();
          return d;
        });
  }

  void process(Long id, String task, String generation) {
    String stage = "PARSING";
    boolean activated = false;
    try {
      var d = phase(id, task, stage);
      var parts = parser.parse(Path.of(d.filePath), d.fileType);
      stage = "CHUNKING";
      phase(id, task, stage);
      var chunks = new ArrayList<Chunk>();
      for (int i = 0; i < parts.size(); i++) {
        var p = parts.get(i);
        var c = new Chunk();
        c.docId = id;
        c.generation = generation;
        c.chunkIndex = i;
        c.chunkText = p.text();
        c.sectionLabel = p.section();
        c.pageStart = p.pageStart();
        c.pageEnd = p.pageEnd();
        c.embeddingModel = s.embeddingModel;
        c.embeddingDimension = s.embeddingDimension;
        db.tx(
            () -> {
              db.save(c);
              c.vectorId = "chunk:" + c.id;
              return null;
            });
        chunks.add(c);
      }
      for (var c : chunks) {
        stage = "EMBEDDING";
        phase(id, task, stage);
        float[] v = model.embed(c.chunkText);
        stage = "INDEXING";
        phase(id, task, stage);
        vectors.put(c, v);
      }
      stage = "FINALIZING";
      phase(id, task, stage);
      require(vectors.count(id, generation) == chunks.size(), 500, "T-0005", "向量数量核对失败");
      String old =
          db.tx(
              () -> {
                var current = db.lock(Document.class, id);
                require(
                    "ACTIVE".equals(current.lifecycleStatus)
                        && task.equals(current.taskId)
                        && "PROCESSING".equals(current.vectorStatus),
                    409,
                    "D-0006",
                    "任务已取消");
                require(
                    current.taskAcceptedTime.plusSeconds(s.taskTimeoutSeconds).isAfter(now()),
                    504,
                    "T-0006",
                    "文档处理超时");
                require(
                    db.list(
                                Chunk.class,
                                "from Chunk where docId=?1 and generation=?2",
                                id,
                                generation)
                            .size()
                        == chunks.size(),
                    500,
                    "T-0008",
                    "分块数量核对失败");
                String previous = current.activeGeneration;
                current.activeGeneration = generation;
                current.chunkCount = chunks.size();
                current.vectorStatus = "COMPLETED";
                current.taskStage = "DONE";
                current.taskFinishedTime = now();
                return previous;
              });
      activated = true;
      if (old != null)
        try {
          vectors.delete(id, old);
          clearCleanup(id);
        } catch (Exception ex) {
          cleanupFailure(id, "T-0009", "INDEXING", "旧代次向量清理失败");
        }
    } catch (Exception ex) {
      if (!activated) {
        String code =
            ex instanceof BusinessException f && f.code.startsWith("T-")
                ? f.code
                : "EMBEDDING".equals(stage)
                    ? "T-0004"
                    : Set.of("INDEXING", "FINALIZING").contains(stage) ? "T-0005" : "T-0008";
        failure(
            id, task, code, stage, ex instanceof BusinessException ? ex.getMessage() : "文档处理失败");
        try {
          vectors.delete(id, generation);
          db.tx(
              () -> {
                for (var c :
                    db.list(
                        Chunk.class, "from Chunk where docId=?1 and generation=?2", id, generation))
                  db.remove(c);
                return null;
              });
        } catch (Exception ignored) {
          cleanupFailure(id, "T-0009", stage, "临时代次清理失败，可重建或删除后重试清理");
        }
      }
    }
    LoggerFactory.getLogger(getClass())
        .info("taskId={} docId={} activated={} model={}", task, id, activated, s.embeddingModel);
  }

  public void failure(Long id, String task, String code, String stage, String message) {
    db.tx(
        () -> {
          var d = db.lock(Document.class, id);
          if (d != null
              && task.equals(d.taskId)
              && Set.of("PENDING", "PROCESSING").contains(d.vectorStatus)) {
            d.vectorStatus = "FAILED";
            d.taskFinishedTime = now();
            d.errorCode = code;
            d.errorStage = stage;
            d.errorMessage = message;
          }
          return null;
        });
    LoggerFactory.getLogger(getClass())
        .warn("taskId={} docId={} stage={} errorCode={}", task, id, stage, code);
  }

  void cleanupFailure(Long id, String code, String stage, String message) {
    db.tx(
        () -> {
          var d = existing(id);
          d.cleanupCode = code;
          d.cleanupStage = stage;
          d.cleanupMessage = message;
          return null;
        });
  }

  void clearCleanup(Long id) {
    db.tx(
        () -> {
          var d = existing(id);
          d.cleanupCode = null;
          d.cleanupStage = null;
          d.cleanupMessage = null;
          return null;
        });
  }

  public void enqueueDelete(Long id) {
    if (!deleting.add(id)) return;
    try {
      worker.execute(
          () -> {
            try {
              vectors.delete(id, null);
              var d = db.tx(() -> existing(id));
              Files.deleteIfExists(Path.of(d.filePath));
              db.tx(
                  () -> {
                    var current = db.lock(Document.class, id);
                    current.lifecycleStatus = "DELETED";
                    if (Set.of("PENDING", "PROCESSING").contains(current.vectorStatus)) {
                      current.vectorStatus = "FAILED";
                      current.errorCode = "T-0008";
                      current.errorStage = current.taskStage;
                      current.errorMessage = "文档已删除，处理取消";
                      current.taskFinishedTime = now();
                    }
                    current.cleanupCode = null;
                    current.cleanupStage = null;
                    current.cleanupMessage = null;
                    for (var c : db.list(Chunk.class, "from Chunk where docId=?1", id))
                      c.isDeleted = true;
                    return null;
                  });
            } catch (Exception ex) {
              cleanupFailure(
                  id,
                  ex instanceof BusinessException ? "T-0009" : "T-0008",
                  "DELETING",
                  "清理失败，请再次删除以重试");
            } finally {
              deleting.remove(id);
            }
          });
    } catch (RejectedExecutionException ex) {
      deleting.remove(id);
      cleanupFailure(id, "T-0008", "DELETING", "清理队列已满，请重试");
    }
  }

  @Scheduled(fixedDelay = 10000)
  void expire() {
    var tasks =
        db.tx(
            () ->
                db.list(
                    Document.class,
                    "from Document where vectorStatus in ('PENDING','PROCESSING')"));
    for (var d : tasks)
      if (d.taskAcceptedTime.plusSeconds(s.taskTimeoutSeconds).isBefore(now()))
        failure(d.id, d.taskId, "T-0006", d.taskStage, "文档处理超过总时限");
  }

  @Scheduled(initialDelay = 60000, fixedDelay = 3600000)
  void maintain() {
    try {
      worker.execute(
          () -> {
            var documents =
                db.tx(
                    () ->
                        db.list(
                            Document.class,
                            "from Document where lifecycleStatus='ACTIVE' and vectorStatus in"
                                + " ('FAILED','COMPLETED')"));
            for (var d : documents) {
              var obsolete =
                  db.tx(
                      () ->
                          db.list(Chunk.class, "from Chunk where docId=?1", d.id).stream()
                              .map(c -> c.generation)
                              .filter(g -> !g.equals(d.activeGeneration))
                              .distinct()
                              .toList());
              try {
                for (String generation : obsolete) vectors.delete(d.id, generation);
                if (!obsolete.isEmpty()) clearCleanup(d.id);
              } catch (Exception ex) {
                cleanupFailure(d.id, "T-0009", "INDEXING", "历史或临时代次清理失败，维护任务将再次补偿");
              }
            }
          });
    } catch (RejectedExecutionException ignored) {
      LoggerFactory.getLogger(getClass()).warn("maintenanceQueueFull");
    }
  }

  public Map<String, Object> list(
      int page,
      int size,
      String vectorStatus,
      String category,
      String lifecycleStatus,
      String keyword) {
    enumeration(vectorStatus, Set.of("PENDING", "PROCESSING", "COMPLETED", "FAILED"));
    enumeration(category, CATEGORIES);
    enumeration(lifecycleStatus, Set.of("ACTIVE", "DELETING", "DELETED"));
    if (keyword != null)
      require(keyword.codePointCount(0, keyword.length()) <= 100, 400, "C-0001", "关键词过长");
    return db.tx(
        () ->
            page(
                db.list(Document.class, "from Document order by createTime desc,id desc").stream()
                    .filter(
                        d ->
                            (vectorStatus == null || vectorStatus.equals(d.vectorStatus))
                                && (category == null || category.equals(d.category))
                                && (lifecycleStatus == null
                                    ? !"DELETED".equals(d.lifecycleStatus)
                                    : lifecycleStatus.equals(d.lifecycleStatus))
                                && (keyword == null || d.title.contains(keyword)))
                    .map(d -> views.document(d, false))
                    .toList(),
                page,
                size));
  }
}
