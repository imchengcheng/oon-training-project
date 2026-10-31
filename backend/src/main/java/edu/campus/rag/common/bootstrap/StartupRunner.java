package edu.campus.rag.common.bootstrap;

import edu.campus.rag.common.Api;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.entity.*;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.DocumentService;
import edu.campus.rag.service.QuestionService;
import edu.campus.rag.service.support.ModelGateway;
import edu.campus.rag.service.support.ResponseAssembler;
import edu.campus.rag.service.support.VectorStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner implements ApplicationRunner {
  private final DataMapper db;
  private final AppProperties settings;
  private final PasswordEncoder passwords;
  private final DocumentService documents;
  private final QuestionService questions;
  private final VectorStore vectors;
  private final ModelGateway model;
  private final Environment environment;

  public StartupRunner(
      DataMapper db,
      AppProperties settings,
      PasswordEncoder passwords,
      DocumentService documents,
      QuestionService questions,
      VectorStore vectors,
      ModelGateway model,
      Environment environment) {
    this.db = db;
    this.settings = settings;
    this.passwords = passwords;
    this.documents = documents;
    this.questions = questions;
    this.vectors = vectors;
    this.model = model;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    var log = LoggerFactory.getLogger(getClass());
    String[] profiles = environment.getActiveProfiles();
    if (profiles.length == 0) profiles = environment.getDefaultProfiles();
    var database = db.tx(db::databaseInfo);
    log.info(
        "Database ready: profiles={} product={} catalog={} seedAccounts={}",
        Arrays.toString(profiles),
        database.product(),
        database.catalog(),
        settings.seed);
    if (settings.seed) {
      seed("admin", "校园规章管理员", "ADMIN", null, settings.adminPassword);
      seed("stu2023001", "林同学", "STUDENT", "2023001", settings.studentPassword);
      seed("stu2023002", "陈同学", "STUDENT", "2023002", settings.studentPassword);
    }
    long users = db.tx(() -> db.count(Account.class));
    log.info("Account initialization complete: userCount={}", users);
    if (users == 0)
      log.warn(
          "No accounts exist. Check the initialization script: src/main/resources/db/init.sql.");
    vectors.initialize();
    log.info("Vector storage ready: {}", vectors.description());
    recover();
    // Restore memory indexes and migrate/recover missing Qdrant vectors from valid MySQL chunks.
    for (var document :
        db.tx(() -> db.list(Document.class, "from Document where lifecycleStatus='ACTIVE'"))) {
      if (!ResponseAssembler.searchable(document)) continue;
      var chunks =
          db.tx(
              () ->
                  db.list(
                      Chunk.class,
                      "from Chunk where docId=?1 and generation=?2 and isDeleted=false order by"
                          + " chunkIndex",
                      document.id,
                      document.activeGeneration));
      boolean compatible =
          !chunks.isEmpty()
              && chunks.size() == document.chunkCount
              && chunks.stream()
                  .allMatch(
                      c ->
                          settings.embeddingModel.equals(c.embeddingModel)
                              && settings.embeddingDimension == c.embeddingDimension);
      if (!compatible) {
        log.warn(
            "docId={} vector model changed or chunks incomplete; rebuild this document",
            document.id);
        continue;
      }
      if ("memory".equals(settings.vectorStore)
          || vectors.count(document.id, document.activeGeneration) != chunks.size()) {
        for (var chunk : chunks) vectors.put(chunk, model.embed(chunk.chunkText));
        Api.require(
            vectors.count(document.id, document.activeGeneration) == chunks.size(),
            503,
            "Q-0004",
            "启动时恢复文档向量失败，请重试或重建文档");
        log.info("Vector index restored: docId={} chunkCount={}", document.id, chunks.size());
      }
    }
  }

  public void recover() {
    var pending =
        db.tx(
            () ->
                db.list(
                    Document.class,
                    "from Document where vectorStatus in ('PENDING','PROCESSING')"));
    for (var d : pending)
      documents.failure(d.id, d.taskId, "T-0007", d.taskStage, "服务重启导致任务中断，请手动重建");
    var questionsPending =
        db.tx(
            () ->
                db.list(
                    Message.class,
                    "from Message where role='USER' and processingStatus='PROCESSING'"));
    for (var q : questionsPending) questions.failMessage(q.id, "Q-0009", 0);
    for (var d :
        db.tx(() -> db.list(Document.class, "from Document where lifecycleStatus='DELETING'")))
      documents.enqueueDelete(d.id);
  }

  private void seed(String username, String name, String role, String studentNo, String password) {
    Api.require(
        password != null
            && password.getBytes(StandardCharsets.UTF_8).length >= 10
            && password.getBytes(StandardCharsets.UTF_8).length <= 72,
        500,
        "C-0003",
        "初始化密码必须为 10～72 UTF-8 字节");
    db.tx(
        () -> {
          if (db.one(Account.class, "from Account where username=?1", username) == null) {
            var a = new Account();
            a.username = username;
            a.realName = name;
            a.roleType = role;
            a.studentNo = studentNo;
            a.passwordHash = passwords.encode(password);
            db.save(a);
          }
          return null;
        });
  }
}
