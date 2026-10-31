-- CampusRAG 本机 MySQL 8 初始化脚本（UTF-8）。使用 root 连接后执行整个文件。
-- 可重复执行：创建缺失数据库/表/账号，保留已有记录与账号密码。
-- 不创建数据库用户、不执行授权；不包含删除数据库或表的语句。
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS campus_rag CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE campus_rag;

CREATE TABLE IF NOT EXISTS answer_feedback (
  create_time datetime(6) not null,
  id bigint not null auto_increment,
  message_id bigint not null,
  rating varchar(8),
  update_time datetime(6) not null,
  user_id bigint not null,
  remark varchar(500),
  primary key (id),
  constraint UKg286p5kiepfbt6t4f62658sl7 unique (message_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS answer_source (
  page_end integer,
  page_start integer,
  rank_no integer not null,
  similarity_score float(53) not null,
  chunk_id bigint not null,
  create_time datetime(6) not null,
  doc_id bigint not null,
  id bigint not null auto_increment,
  message_id bigint not null,
  update_time datetime(6) not null,
  category_snapshot varchar(255),
  doc_title_snapshot varchar(255),
  generation varchar(255),
  section_label_snapshot varchar(255),
  chunk_text_snapshot MEDIUMTEXT,
  primary key (id),
  constraint UKm2hq089gqof4rp1gkabwt9cx5 unique (message_id, rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS document_chunk (
  chunk_index integer not null,
  embedding_dimension integer not null,
  is_deleted bit not null,
  page_end integer,
  page_start integer,
  create_time datetime(6) not null,
  doc_id bigint not null,
  id bigint not null auto_increment,
  update_time datetime(6) not null,
  embedding_model varchar(255),
  generation varchar(255) not null,
  section_label varchar(255),
  vector_id varchar(255),
  chunk_text MEDIUMTEXT,
  primary key (id),
  index ix_chunk_document (doc_id, generation),
  constraint UKluci02k57dlixs3ss1c4ap8ml unique (doc_id, generation, chunk_index),
  constraint UK1ujsfu54r0xe10ucsqqgo878 unique (vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS qa_message (
  attempt_count integer not null,
  retrieved_count integer not null,
  create_time datetime(6) not null,
  duration_ms bigint not null,
  id bigint not null auto_increment,
  last_attempt_time datetime(6),
  reply_to_message_id bigint,
  session_id bigint not null,
  update_time datetime(6) not null,
  processing_status varchar(16),
  role varchar(16) not null,
  answer_status varchar(32),
  client_request_id varchar(255),
  error_code varchar(255),
  content MEDIUMTEXT,
  rewritten_query TEXT,
  primary key (id),
  index ix_message_session (session_id, id),
  constraint UKdaod0roach9p079wb8vj1k4w0 unique (session_id, client_request_id),
  constraint UKr0r159llwhvnyuukk86d4ql0t unique (reply_to_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS qa_session (
  create_time datetime(6) not null,
  id bigint not null auto_increment,
  last_active_time datetime(6),
  processing_message_id bigint,
  update_time datetime(6) not null,
  user_id bigint not null,
  status varchar(16) not null,
  title varchar(255),
  primary key (id),
  index ix_session_user (user_id, last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS regulation_document (
  chunk_count integer not null,
  create_time datetime(6) not null,
  file_size bigint not null,
  file_type varchar(8),
  id bigint not null auto_increment,
  task_accepted_time datetime(6),
  task_finished_time datetime(6),
  task_started_time datetime(6),
  update_time datetime(6) not null,
  uploader_id bigint not null,
  lifecycle_status varchar(16) not null,
  vector_status varchar(16) not null,
  category varchar(32) not null,
  title varchar(100) not null,
  cleanup_message varchar(1000),
  error_message varchar(1000),
  active_generation varchar(255),
  cleanup_code varchar(255),
  cleanup_stage varchar(255),
  error_code varchar(255),
  error_stage varchar(255),
  file_hash varchar(255),
  file_path varchar(255) not null,
  original_filename varchar(255),
  target_generation varchar(255),
  task_id varchar(255),
  task_stage varchar(255),
  primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_user (
  enabled bit not null,
  create_time datetime(6) not null,
  id bigint not null auto_increment,
  update_time datetime(6) not null,
  role_type varchar(16) not null,
  username varchar(64) not null,
  password_hash varchar(255) not null,
  real_name varchar(255),
  student_no varchar(255),
  primary key (id),
  constraint UK51bvuyvihefoh4kp5syh2jpi4 unique (username),
  constraint UKfvo33gjd4uxacxo53hkxk3ajo unique (student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 初始登录账号；密码只保存 BCrypt 哈希。已有用户名不会被覆盖。
-- admin / Admin@123456；stu2023001、stu2023002 / Student@123456。
INSERT INTO sys_user (username, password_hash, real_name, role_type, student_no, enabled, create_time, update_time)
SELECT 'admin', '$2a$10$al36frrtnx7fqQLWhrdZYe8WZqKenrbam3iYcg/8nDSd6RLuTz.Gy', '校园规章管理员', 'ADMIN', NULL, b'1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

INSERT INTO sys_user (username, password_hash, real_name, role_type, student_no, enabled, create_time, update_time)
SELECT 'stu2023001', '$2a$10$o5oKnOhyY9D3Ua3hiOa1C.tpeFg01/YlIK3BoulzvZQ3Qxas6E9sK', '林同学', 'STUDENT', '2023001', b'1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'stu2023001');

INSERT INTO sys_user (username, password_hash, real_name, role_type, student_no, enabled, create_time, update_time)
SELECT 'stu2023002', '$2a$10$OmvSRYj1NwVgE.Wv1roUmO31ujLNEudWru6YejF/FuErSMyD8jZEG', '陈同学', 'STUDENT', '2023002', b'1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'stu2023002');

-- 规章资料库收藏（学生私有，目标停用后保留收藏快照）。
CREATE TABLE IF NOT EXISTS knowledge_bookmark (
  id bigint not null auto_increment,
  create_time datetime(6) not null,
  update_time datetime(6) not null,
  user_id bigint not null,
  target_type varchar(16) not null,
  target_id bigint not null,
  title_snapshot varchar(200) not null,
  preview_snapshot varchar(2000) not null,
  note varchar(500),
  primary key (id),
  constraint uk_bookmark_owner_target unique (user_id,target_type,target_id),
  index ix_bookmark_user (user_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- CampusRAG 原生服务：指南、常见问题、咨询工单。
CREATE TABLE IF NOT EXISTS campus_service_guide (
  id bigint not null auto_increment,
  create_time datetime(6) not null,
  update_time datetime(6) not null,
  title varchar(140) not null,
  category varchar(32) not null,
  summary varchar(500),
  department varchar(80),
  materials TEXT,
  steps MEDIUMTEXT,
  processing_time varchar(200),
  contact varchar(500),
  source_doc_id bigint,
  source_generation varchar(255),
  published bit not null,
  primary key (id),
  index ix_guide_category (category,published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS campus_faq (
  id bigint not null auto_increment,
  create_time datetime(6) not null,
  update_time datetime(6) not null,
  question varchar(300) not null,
  category varchar(32) not null,
  answer MEDIUMTEXT,
  source_doc_id bigint,
  source_generation varchar(255),
  published bit not null,
  sort_order int not null,
  use_count bigint not null,
  primary key (id),
  index ix_faq_category (category,published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS campus_consultation_ticket (
  id bigint not null auto_increment,
  create_time datetime(6) not null,
  update_time datetime(6) not null,
  student_id bigint not null,
  title varchar(140) not null,
  category varchar(32) not null,
  content MEDIUMTEXT,
  related_message_id bigint,
  status varchar(16) not null,
  reply MEDIUMTEXT,
  handler_id bigint,
  replied_at datetime(6),
  primary key (id),
  index ix_ticket_student (student_id,create_time),
  index ix_ticket_status (status,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
