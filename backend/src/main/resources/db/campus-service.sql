-- CampusRAG 原生服务：指南、常见问题、咨询工单。可重复执行，不改动已有数据。
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
