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
