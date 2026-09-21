-- ==============================================================
-- Target Server Type    : PostgreSQL
-- Target Server Version : 14/15/16+
-- File Encoding         : 65001
-- Description           : gfs 全量初始化脚本（唯一基线）
--                         运行期数据（文件、传输、分享、日志）不预置；
--                         权限由代码按「系统管理员用户名」判定，不再有角色/权限表。
-- ==============================================================

SET session_replication_role = 'replica';

-- 1. file_info
DROP TABLE IF EXISTS "file_info";
CREATE TABLE "file_info" (
    "id" varchar(128) NOT NULL,
    "object_key" varchar(512) DEFAULT NULL,
    "original_name" varchar(128) NOT NULL,
    "display_name" varchar(128) NOT NULL,
    "suffix" varchar(20) DEFAULT NULL,
    "size" bigint DEFAULT NULL,
    "mime_type" varchar(128) DEFAULT NULL,
    "is_dir" smallint NOT NULL,
    "parent_id" varchar(128) DEFAULT NULL,
    "user_id" varchar(128) NOT NULL,
    "content_md5" text,
    "storage_platform_setting_id" varchar(128) DEFAULT NULL,
    "upload_time" timestamp NOT NULL,
    "update_time" timestamp DEFAULT NULL,
    "last_access_time" timestamp DEFAULT NULL,
    "is_deleted" smallint DEFAULT NULL,
    "deleted_time" timestamp DEFAULT NULL,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "file_info" IS '文件资源表';
CREATE INDEX "idx_user_query" ON "file_info" ("user_id", "is_deleted", "parent_id");
CREATE INDEX "idx_file_content_dedup" ON "file_info" ("storage_platform_setting_id", "content_md5", "size", "is_dir");
CREATE INDEX "idx_file_object_reference" ON "file_info" ("storage_platform_setting_id", "object_key");

-- 2. file_collections
DROP TABLE IF EXISTS "file_collections";
CREATE TABLE "file_collections" (
    "id" varchar(128) NOT NULL,
    "user_id" varchar(128) NOT NULL,
    "target_folder_id" varchar(128) NOT NULL,
    "storage_platform_setting_id" varchar(128) DEFAULT NULL,
    "collection_name" varchar(255) NOT NULL,
    "description" varchar(1000) DEFAULT NULL,
    "access_code_hash" varchar(255) DEFAULT NULL,
    "expire_time" timestamp DEFAULT NULL,
    "max_file_size" bigint NOT NULL DEFAULT 1073741824,
    "allowed_extensions" varchar(1000) DEFAULT NULL,
    "status" varchar(20) NOT NULL DEFAULT 'OPEN',
    "submission_count" int NOT NULL DEFAULT 0,
    "file_count" int NOT NULL DEFAULT 0,
    "total_size" bigint NOT NULL DEFAULT 0,
    "created_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "file_collections" IS '文件收集';
CREATE INDEX "idx_collection_user_status" ON "file_collections" ("user_id", "status", "created_at");
CREATE INDEX "idx_collection_target_folder" ON "file_collections" ("target_folder_id");

-- 3. file_collection_submissions
DROP TABLE IF EXISTS "file_collection_submissions";
CREATE TABLE "file_collection_submissions" (
    "id" varchar(128) NOT NULL,
    "collection_id" varchar(128) NOT NULL,
    "submitter_name" varchar(64) NOT NULL,
    "submitter_ip" varchar(50) DEFAULT NULL,
    "user_agent" varchar(512) DEFAULT NULL,
    "folder_id" varchar(128) NOT NULL,
    "upload_token_hash" varchar(64) NOT NULL,
    "file_count" int NOT NULL DEFAULT 0,
    "total_size" bigint NOT NULL DEFAULT 0,
    "status" varchar(20) NOT NULL DEFAULT 'UPLOADING',
    "completed_at" timestamp DEFAULT NULL,
    "created_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "file_collection_submissions" IS '文件收集提交记录';
CREATE INDEX "idx_submission_collection_time" ON "file_collection_submissions" ("collection_id", "created_at");
CREATE INDEX "idx_submission_folder" ON "file_collection_submissions" ("folder_id");

-- 4. file_share_access_record
DROP TABLE IF EXISTS "file_share_access_record";
CREATE TABLE "file_share_access_record" (
    "id" BIGSERIAL PRIMARY KEY,
    "share_id" varchar(128) NOT NULL,
    "access_ip" varchar(50) DEFAULT NULL,
    "access_address" varchar(255) DEFAULT NULL,
    "browser" varchar(255) DEFAULT NULL,
    "os" varchar(512) DEFAULT NULL,
    "access_time" timestamp NOT NULL
);
COMMENT ON TABLE "file_share_access_record" IS '分享页面访问记录表';

-- 5. file_share_items
DROP TABLE IF EXISTS "file_share_items";
CREATE TABLE "file_share_items" (
    "share_id" varchar(128) NOT NULL,
    "file_id" varchar(128) NOT NULL,
    "created_at" timestamp NOT NULL,
    PRIMARY KEY ("share_id", "file_id")
);
COMMENT ON TABLE "file_share_items" IS '分享文件关联表';

-- 6. file_shares
DROP TABLE IF EXISTS "file_shares";
CREATE TABLE "file_shares" (
    "id" varchar(128) NOT NULL,
    "user_id" varchar(128) NOT NULL,
    "share_name" varchar(255) NOT NULL,
    "share_code" varchar(6) DEFAULT NULL,
    "expire_time" timestamp DEFAULT NULL,
    "scope" varchar(255) NOT NULL,
    "view_count" int DEFAULT 0,
    "max_view_count" int DEFAULT NULL,
    "download_count" int DEFAULT 0,
    "max_download_count" int DEFAULT NULL,
    "created_at" timestamp NOT NULL,
    "updated_at" timestamp NOT NULL,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "file_shares" IS '文件分享表';
CREATE INDEX "idx_share_user" ON "file_shares" ("user_id");

-- 7. file_transfer_task
DROP TABLE IF EXISTS "file_transfer_task";
CREATE TABLE "file_transfer_task" (
    "id" BIGSERIAL PRIMARY KEY,
    "task_id" varchar(64) NOT NULL,
    "upload_id" varchar(255) DEFAULT NULL,
    "parent_id" varchar(128) DEFAULT NULL,
    "user_id" varchar(128) NOT NULL,
    "collection_id" varchar(128) DEFAULT NULL,
    "collection_submission_id" varchar(128) DEFAULT NULL,
    "storage_platform_setting_id" varchar(255) DEFAULT NULL,
    "object_key" varchar(512) NOT NULL,
    "file_id" varchar(128) DEFAULT NULL,
    "file_name" varchar(255) NOT NULL,
    "file_size" bigint NOT NULL,
    "file_md5" varchar(64) DEFAULT NULL,
    "suffix" varchar(50) NOT NULL,
    "mime_type" varchar(255) NOT NULL,
    "total_chunks" int NOT NULL,
    "task_type" varchar(32) DEFAULT NULL,
    "uploaded_chunks" int DEFAULT 0,
    "chunk_size" bigint DEFAULT 5242880,
    "uploaded_size" bigint DEFAULT 0,
    "status" varchar(20) NOT NULL DEFAULT 'uploading',
    "error_msg" varchar(500) DEFAULT NULL,
    "start_time" timestamp NOT NULL,
    "complete_time" timestamp DEFAULT NULL,
    "created_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "uk_task_id" UNIQUE ("task_id")
);
COMMENT ON TABLE "file_transfer_task" IS '传输任务表';
CREATE INDEX "idx_transfer_user" ON "file_transfer_task" ("user_id");
CREATE INDEX "idx_transfer_file_md5" ON "file_transfer_task" ("file_md5");
CREATE INDEX "idx_transfer_status" ON "file_transfer_task" ("status");
CREATE INDEX "idx_transfer_create_time" ON "file_transfer_task" ("created_at");
CREATE INDEX "idx_transfer_collection_submission" ON "file_transfer_task" ("collection_submission_id");
CREATE INDEX "idx_transfer_collection" ON "file_transfer_task" ("collection_id");

-- 8. file_user_favorites
DROP TABLE IF EXISTS "file_user_favorites";
CREATE TABLE "file_user_favorites" (
    "user_id" varchar(128) NOT NULL,
    "file_id" varchar(128) NOT NULL,
    "favorite_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("user_id", "file_id")
);
COMMENT ON TABLE "file_user_favorites" IS '文件收藏表';
CREATE INDEX "idx_favorite_file_time" ON "file_user_favorites" ("file_id", "favorite_time" DESC);

-- 9. storage_platform
DROP TABLE IF EXISTS "storage_platform";
CREATE TABLE "storage_platform" (
    "id" SERIAL PRIMARY KEY,
    "name" varchar(255) NOT NULL,
    "identifier" varchar(128) NOT NULL,
    "config_scheme" json NOT NULL,
    "icon" varchar(128) DEFAULT NULL,
    "link" varchar(255) DEFAULT NULL,
    "is_default" smallint NOT NULL DEFAULT 1,
    "desc" varchar(255) DEFAULT NULL
);
COMMENT ON TABLE "storage_platform" IS '存储平台';

-- 10. storage_settings
DROP TABLE IF EXISTS "storage_settings";
CREATE TABLE "storage_settings" (
    "id" varchar(128) NOT NULL,
    "platform_identifier" varchar(128) NOT NULL,
    "config_data" json NOT NULL,
    "enabled" smallint NOT NULL DEFAULT 0,
    "created_at" timestamp DEFAULT NULL,
    "updated_at" timestamp DEFAULT NULL,
    "remark" varchar(255) DEFAULT NULL,
    "deleted" smallint DEFAULT 0,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "storage_settings" IS '存储平台配置';

-- 10.5 service_settings（对外文件服务配置）
DROP TABLE IF EXISTS "service_settings";
CREATE TABLE "service_settings" (
    "id" varchar(128) NOT NULL,
    "service_type" varchar(32) NOT NULL,
    "enabled" boolean NOT NULL DEFAULT FALSE,
    "port" int DEFAULT NULL,
    "bind_address" varchar(64) DEFAULT NULL,
    "config_data" jsonb DEFAULT NULL,
    "created_at" timestamp DEFAULT NULL,
    "updated_at" timestamp DEFAULT NULL,
    "remark" varchar(255) DEFAULT NULL,
    "deleted" smallint DEFAULT 0,
    PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "uk_service_settings_type" ON "service_settings" ("service_type");
COMMENT ON TABLE "service_settings" IS '对外文件服务配置';
COMMENT ON COLUMN "service_settings"."service_type" IS '服务类型：webdav / sftp';
COMMENT ON COLUMN "service_settings"."enabled" IS '是否启用';
COMMENT ON COLUMN "service_settings"."port" IS '监听端口（webdav 复用 HTTP 80 端口，此列为空）';
COMMENT ON COLUMN "service_settings"."bind_address" IS '监听地址，默认 0.0.0.0';
COMMENT ON COLUMN "service_settings"."config_data" IS '扩展配置（如 sftp 主机密钥路径）';
COMMENT ON COLUMN "service_settings"."deleted" IS '逻辑删除 0未删除 1已删除';

INSERT INTO "service_settings" ("id","service_type","enabled","port","bind_address") VALUES
('svc-webdav','webdav',FALSE,NULL,'0.0.0.0'),
('svc-sftp','sftp',FALSE,9022,'0.0.0.0');

-- 11. sys_login_log
DROP TABLE IF EXISTS "sys_login_log";
CREATE TABLE "sys_login_log" (
    "id" BIGSERIAL PRIMARY KEY,
    "user_id" varchar(100) DEFAULT NULL,
    "username" varchar(50) NOT NULL DEFAULT '',
    "login_ip" varchar(50) NOT NULL,
    "login_address" varchar(255) DEFAULT NULL,
    "browser" varchar(255) DEFAULT NULL,
    "os" varchar(512) NOT NULL,
    "login_type" varchar(32) NOT NULL,
    "status" smallint NOT NULL,
    "msg" varchar(255) NOT NULL,
    "login_time" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE "sys_login_log" IS '系统访问记录';

-- 12. sys_operation_log
DROP TABLE IF EXISTS "sys_operation_log";
CREATE TABLE "sys_operation_log" (
    "id" BIGSERIAL PRIMARY KEY,
    "operator_id" varchar(128) DEFAULT NULL,
    "operator_name" varchar(128) DEFAULT NULL,
    "operation_type" varchar(64) NOT NULL,
    "operation_name" varchar(128) NOT NULL,
    "target_type" varchar(32) DEFAULT NULL,
    "target_id" varchar(128) DEFAULT NULL,
    "target_name" varchar(255) DEFAULT NULL,
    "detail" text DEFAULT NULL,
    "operation_ip" varchar(50) DEFAULT NULL,
    "user_agent" varchar(512) DEFAULT NULL,
    "status" smallint NOT NULL DEFAULT 0,
    "error_message" varchar(512) DEFAULT NULL,
    "operation_time" timestamp NOT NULL
);
COMMENT ON TABLE "sys_operation_log" IS '操作日志';
CREATE INDEX "idx_operation_time" ON "sys_operation_log" ("operation_time");
CREATE INDEX "idx_operation_operator_time" ON "sys_operation_log" ("operator_id", "operation_time");
CREATE INDEX "idx_operation_type_time" ON "sys_operation_log" ("operation_type", "operation_time");

-- 13. sys_user
DROP TABLE IF EXISTS "sys_user";
CREATE TABLE "sys_user" (
    "id" varchar(128) NOT NULL,
    "username" varchar(128) NOT NULL,
    "password" varchar(128) NOT NULL,
    "nickname" varchar(128) NOT NULL,
    "avatar" varchar(255) DEFAULT NULL,
    "status" int NOT NULL DEFAULT 0,
    "created_at" timestamp NOT NULL,
    "updated_at" timestamp NOT NULL,
    "last_login_at" timestamp DEFAULT NULL,
    PRIMARY KEY ("id")
);
COMMENT ON TABLE "sys_user" IS '用户表';

-- 14. sys_user_transfer_setting
DROP TABLE IF EXISTS "sys_user_transfer_setting";
CREATE TABLE "sys_user_transfer_setting" (
    "id" SERIAL PRIMARY KEY,
    "user_id" varchar(128) NOT NULL,
    "download_location" varchar(255) DEFAULT NULL,
    "is_default_download_location" smallint NOT NULL DEFAULT 0,
    "download_speed_limit" int NOT NULL DEFAULT 5,
    "concurrent_upload_quantity" int NOT NULL DEFAULT 1,
    "concurrent_download_quantity" int NOT NULL DEFAULT 1,
    "chunk_size" bigint NOT NULL,
    "created_at" timestamp NOT NULL,
    "updated_at" timestamp NOT NULL,
    CONSTRAINT "uk_user_id" UNIQUE ("user_id")
);
COMMENT ON TABLE "sys_user_transfer_setting" IS '用户传输设置';

-- ==============================================================
-- 15. sys_feature_toggle 功能开关
-- ==============================================================
DROP TABLE IF EXISTS "sys_feature_toggle";
CREATE TABLE "sys_feature_toggle" (
    "id" SERIAL PRIMARY KEY,
    "feature_key" varchar(64) NOT NULL,
    "enabled" smallint NOT NULL DEFAULT 0,
    "created_at" timestamp NOT NULL,
    "updated_at" timestamp NOT NULL,
    CONSTRAINT "uk_feature_key" UNIQUE ("feature_key")
);
COMMENT ON TABLE "sys_feature_toggle" IS '功能开关';

-- ==============================================================
-- 初始化数据
-- ==============================================================

-- 存储平台（与 mysql 基线保持一致）
INSERT INTO "storage_platform" ("name", "identifier", "config_scheme", "icon", "link", "is_default", "desc") VALUES
('阿里云OSS', 'AliyunOSS',
 '[{"label": "Access-Key", "dataType": "string", "identifier": "accessKey", "validation": {"required": true}}, {"label": "Secret-key", "dataType": "string", "identifier": "secretKey", "validation": {"required": true}}, {"label": "服务器端点", "dataType": "string", "identifier": "endpoint", "validation": {"required": true}}, {"label": "存储桶名", "dataType": "string", "identifier": "bucket", "validation": {"required": true}}, {"label": "区域", "dataType": "string", "identifier": "region", "validation": {"required": true}}]',
 'icon-aliyun1', 'https://www.aliyun.com/product/oss', 0,
 '阿里云对象存储 OSS（Object Storage Service）是一款海量、安全、低成本、高可靠的云存储服务'),
('RustFS对象存储', 'RustFS',
 '[{"label": "Access-Key", "dataType": "string", "identifier": "accessKey", "validation": {"required": true}}, {"label": "Secret-key", "dataType": "string", "identifier": "secretKey", "validation": {"required": true}}, {"label": "服务器端点", "dataType": "string", "identifier": "endpoint", "validation": {"required": true}}, {"label": "存储桶名", "dataType": "string", "identifier": "bucket", "validation": {"required": true}}]',
 'icon-bendicunchu1', 'https://github.com/rustfs/rustfs', 0,
 'RustFS 是一个基于 Rust 构建的高性能分布式对象存储系统。Rust 是全球最受开发者喜爱的编程语言之一，RustFS 完美结合了 MinIO 的简洁性与 Rust 的内存安全及高性能优势。它提供完整的 S3 兼容性，完全开源，并专为数据湖、人工智能（AI）和大数据负载进行了优化。');

-- 系统管理员（用户名与 security.super-admin.username 配置一致即拥有存储/日志管理权限）
INSERT INTO "sys_user" ("id", "username", "password", "nickname", "avatar", "status", "created_at", "updated_at", "last_login_at")
VALUES ('01jrvgs943q0f43h0aa5mjde0y', 'admin', '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918', 'admin', NULL, 0, '2026-07-23 14:38:36', '2026-07-23 14:38:36', NULL);

SET session_replication_role = 'origin';
