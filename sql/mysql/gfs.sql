-- MySQL dump 10.13  Distrib 8.0.42, for Linux (x86_64)
--
-- Host: localhost    Database: gfs_clean_export
-- ------------------------------------------------------
-- Clean initialization baseline: runtime file, transfer, share, login, and
-- operation-log records are intentionally omitted. Permissions are resolved in
-- code from the system administrator username, so no role/permission seed is needed.
-- Server version	8.0.42

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `file_collection_submissions`
--

DROP TABLE IF EXISTS `file_collection_submissions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_collection_submissions` (
  `id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '提交记录ID',
  `collection_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收集ID',
  `submitter_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '提交人姓名',
  `submitter_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '提交人IP',
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'User-Agent',
  `folder_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '自动创建的提交文件夹ID',
  `upload_token_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '一次性上传令牌SHA-256',
  `file_count` int NOT NULL DEFAULT '0' COMMENT '成功上传文件数',
  `total_size` bigint NOT NULL DEFAULT '0' COMMENT '成功上传总字节数',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'UPLOADING' COMMENT 'UPLOADING/COMPLETED',
  `completed_at` datetime DEFAULT NULL COMMENT '完成提交时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_submission_collection_time` (`collection_id`,`created_at`),
  KEY `idx_submission_folder` (`folder_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文件收集提交记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_collection_submissions`
--

LOCK TABLES `file_collection_submissions` WRITE;
/*!40000 ALTER TABLE `file_collection_submissions` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_collection_submissions` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_collections`
--

DROP TABLE IF EXISTS `file_collections`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_collections` (
  `id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收集ID',
  `user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '创建人ID',
  `target_folder_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收集目标文件夹ID',
  `storage_platform_setting_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '存储平台配置ID，空表示本地存储',
  `collection_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收集名称',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '收集说明',
  `access_code_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '访问码哈希',
  `expire_time` datetime DEFAULT NULL COMMENT '截止时间，空表示永久',
  `max_file_size` bigint NOT NULL DEFAULT '1073741824' COMMENT '单文件大小限制（字节）',
  `allowed_extensions` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '允许扩展名，逗号分隔',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
  `submission_count` int NOT NULL DEFAULT '0' COMMENT '提交会话数',
  `file_count` int NOT NULL DEFAULT '0' COMMENT '成功上传文件数',
  `total_size` bigint NOT NULL DEFAULT '0' COMMENT '成功上传总字节数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_collection_user_status` (`user_id`,`status`,`created_at`),
  KEY `idx_collection_target_folder` (`target_folder_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文件收集';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_collections`
--

LOCK TABLES `file_collections` WRITE;
/*!40000 ALTER TABLE `file_collections` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_collections` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_info`
--

DROP TABLE IF EXISTS `file_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_info` (
  `id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `object_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '资源名称',
  `original_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '资源原始名称',
  `display_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '资源别名',
  `suffix` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '后缀名',
  `size` bigint DEFAULT NULL COMMENT '大小',
  `mime_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '存储标准MIME类型',
  `is_dir` tinyint(1) NOT NULL COMMENT '是否目录',
  `parent_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '父节点ID',
  `user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户id',
  `content_md5` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用于秒传和文件校验',
  `storage_platform_setting_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '存储平台标识符',
  `upload_time` datetime NOT NULL COMMENT '上传时间',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  `last_access_time` datetime DEFAULT NULL COMMENT '最后访问时间',
  `is_deleted` tinyint(1) DEFAULT NULL COMMENT '软删除标记，回收站标识0：未删除 1：已删除',
  `deleted_time` datetime DEFAULT NULL COMMENT '删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_query` (`user_id`,`is_deleted`,`parent_id`) USING BTREE,
  KEY `idx_file_content_dedup` (`storage_platform_setting_id`,`content_md5`,`size`,`is_dir`),
  KEY `idx_file_object_reference` (`storage_platform_setting_id`,`object_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='文件资源表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_info`
--

LOCK TABLES `file_info` WRITE;
/*!40000 ALTER TABLE `file_info` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_share_access_record`
--

DROP TABLE IF EXISTS `file_share_access_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_share_access_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `share_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分享ID',
  `access_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '访问IP',
  `access_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '访问地址',
  `browser` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '浏览器类型',
  `os` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作系统',
  `access_time` datetime NOT NULL COMMENT '访问时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='分享页面访问记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_share_access_record`
--

LOCK TABLES `file_share_access_record` WRITE;
/*!40000 ALTER TABLE `file_share_access_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_share_access_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_share_items`
--

DROP TABLE IF EXISTS `file_share_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_share_items` (
  `share_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分享ID',
  `file_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件/文件夹ID',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`share_id`,`file_id` DESC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='分享文件关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_share_items`
--

LOCK TABLES `file_share_items` WRITE;
/*!40000 ALTER TABLE `file_share_items` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_share_items` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_shares`
--

DROP TABLE IF EXISTS `file_shares`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_shares` (
  `id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分享ID',
  `user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分享人ID',
  `share_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分享名称',
  `share_code` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '提取码（可为空）',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间（null表示永久有效）',
  `scope` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '权限范围: preview,download  (逗号分隔)',
  `view_count` int DEFAULT '0' COMMENT '查看次数统计',
  `max_view_count` int DEFAULT NULL COMMENT '最大查看次数（NULL表示无限制）',
  `download_count` int DEFAULT '0' COMMENT '下载次数统计',
  `max_download_count` int DEFAULT NULL COMMENT '最大下载次数（NULL表示无限制）',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='文件分享表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_shares`
--

LOCK TABLES `file_shares` WRITE;
/*!40000 ALTER TABLE `file_shares` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_shares` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_transfer_task`
--

DROP TABLE IF EXISTS `file_transfer_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_transfer_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '任务ID(UUID)',
  `upload_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '上传唯一ID',
  `parent_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '父ID',
  `user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户ID',
  `collection_id` varchar(128) DEFAULT NULL COMMENT '文件收集ID（普通上传为空）',
  `collection_submission_id` varchar(128) DEFAULT NULL COMMENT '文件收集提交记录ID（普通上传为空）',
  `storage_platform_setting_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '存储平台配置ID',
  `object_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '对象key',
  `file_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '下载时关联的文件ID',
  `file_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件名',
  `file_size` bigint NOT NULL COMMENT '文件大小(字节)',
  `file_md5` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '文件MD5值',
  `suffix` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '文件类型(扩展名)',
  `mime_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '存储标准MIME类型',
  `total_chunks` int NOT NULL COMMENT '总分片数',
  `task_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '任务类型',
  `uploaded_chunks` int DEFAULT '0' COMMENT '已上传分片数',
  `chunk_size` bigint DEFAULT '5242880' COMMENT '分片大小(默认5MB)',
  `uploaded_size` bigint DEFAULT '0' COMMENT '已上传大小(字节)',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'uploading' COMMENT '状态',
  `error_msg` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '错误信息',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_task_id` (`task_id`) USING BTREE,
  KEY `idx_user_id` (`user_id`) USING BTREE,
  KEY `idx_file_md5` (`file_md5`) USING BTREE,
  KEY `idx_status` (`status`) USING BTREE,
  KEY `idx_create_time` (`created_at`) USING BTREE,
  KEY `idx_collection_submission` (`collection_submission_id`),
  KEY `idx_collection_id` (`collection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='传输任务表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_transfer_task`
--

LOCK TABLES `file_transfer_task` WRITE;
/*!40000 ALTER TABLE `file_transfer_task` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_transfer_task` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `file_user_favorites`
--

DROP TABLE IF EXISTS `file_user_favorites`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `file_user_favorites` (
  `user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `file_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '文件ID',
  `favorite_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (`user_id`,`file_id`) USING BTREE,
  KEY `idx_file_time` (`file_id`,`favorite_time` DESC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='文件收藏表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `file_user_favorites`
--

LOCK TABLES `file_user_favorites` WRITE;
/*!40000 ALTER TABLE `file_user_favorites` DISABLE KEYS */;
/*!40000 ALTER TABLE `file_user_favorites` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `storage_platform`
--

DROP TABLE IF EXISTS `storage_platform`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `storage_platform` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '存储平台',
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '存储平台名称',
  `identifier` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '存储平台标识符',
  `config_scheme` json NOT NULL COMMENT '存储平台配置描述schema',
  `icon` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '存储平台图标',
  `link` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '存储平台链接',
  `is_default` tinyint NOT NULL DEFAULT '1' COMMENT '是否默认存储平台 0-否 1-是',
  `desc` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '存储平台描述',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='存储平台';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `storage_platform`
--

LOCK TABLES `storage_platform` WRITE;
/*!40000 ALTER TABLE `storage_platform` DISABLE KEYS */;
INSERT INTO `storage_platform` VALUES (28,'阿里云OSS','AliyunOSS','[{\"label\": \"Access-Key\", \"dataType\": \"string\", \"identifier\": \"accessKey\", \"validation\": {\"required\": true}}, {\"label\": \"Secret-key\", \"dataType\": \"string\", \"identifier\": \"secretKey\", \"validation\": {\"required\": true}}, {\"label\": \"服务器端点\", \"dataType\": \"string\", \"identifier\": \"endpoint\", \"validation\": {\"required\": true}}, {\"label\": \"存储桶名\", \"dataType\": \"string\", \"identifier\": \"bucket\", \"validation\": {\"required\": true}}, {\"label\": \"区域\", \"dataType\": \"string\", \"identifier\": \"region\", \"validation\": {\"required\": true}}]','icon-aliyun1','https://www.aliyun.com/product/oss',0,'阿里云对象存储 OSS（Object Storage Service）是一款海量、安全、低成本、高可靠的云存储服务'),(30,'RustFS对象存储','RustFS','[{\"label\": \"Access-Key\", \"dataType\": \"string\", \"identifier\": \"accessKey\", \"validation\": {\"required\": true}}, {\"label\": \"Secret-key\", \"dataType\": \"string\", \"identifier\": \"secretKey\", \"validation\": {\"required\": true}}, {\"label\": \"服务器端点\", \"dataType\": \"string\", \"identifier\": \"endpoint\", \"validation\": {\"required\": true}}, {\"label\": \"存储桶名\", \"dataType\": \"string\", \"identifier\": \"bucket\", \"validation\": {\"required\": true}}]','icon-bendicunchu1','https://github.com/rustfs/rustfs',0,'RustFS 是一个基于 Rust 构建的高性能分布式对象存储系统。Rust 是全球最受开发者喜爱的编程语言之一，RustFS 完美结合了 MinIO 的简洁性与 Rust 的内存安全及高性能优势。它提供完整的 S3 兼容性，完全开源，并专为数据湖、人工智能（AI）和大数据负载进行了优化。');
/*!40000 ALTER TABLE `storage_platform` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `storage_settings`
--

DROP TABLE IF EXISTS `storage_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `storage_settings` (
  `id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'id',
  `platform_identifier` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '存储平台标识符',
  `config_data` json NOT NULL COMMENT '存储配置',
  `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用 0：否 1：是',
  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除 0未删除 1已删除',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='存储平台配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `storage_settings`
--

LOCK TABLES `storage_settings` WRITE;
/*!40000 ALTER TABLE `storage_settings` DISABLE KEYS */;
/*!40000 ALTER TABLE `storage_settings` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `service_settings`
--

DROP TABLE IF EXISTS `service_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `service_settings` (
  `id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'id',
  `service_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '服务类型：webdav / sftp',
  `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用 0：否 1：是',
  `port` int DEFAULT NULL COMMENT '监听端口（webdav 复用 HTTP 80 端口，此列为空）',
  `bind_address` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '监听地址，默认 0.0.0.0',
  `config_data` json DEFAULT NULL COMMENT '扩展配置（如 sftp 主机密钥路径）',
  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除 0未删除 1已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_service_type` (`service_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='对外文件服务配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `service_settings`
--

LOCK TABLES `service_settings` WRITE;
/*!40000 ALTER TABLE `service_settings` DISABLE KEYS */;
INSERT INTO `service_settings` (`id`,`service_type`,`enabled`,`port`,`bind_address`) VALUES
('svc-webdav','webdav',0,NULL,'0.0.0.0'),
('svc-sftp','sftp',0,9022,'0.0.0.0');
/*!40000 ALTER TABLE `service_settings` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_login_log`
--

DROP TABLE IF EXISTS `sys_login_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_login_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
  `user_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户编号',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '用户账号',
  `login_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录IP',
  `login_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '登录地址',
  `browser` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '浏览器类型',
  `os` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作系统',
  `login_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录方式',
  `status` tinyint NOT NULL COMMENT '登录状态（0成功 1失败）',
  `msg` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '提示消息',
  `login_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='系统访问记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_login_log`
--

LOCK TABLES `sys_login_log` WRITE;
/*!40000 ALTER TABLE `sys_login_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_login_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_operation_log`
--

DROP TABLE IF EXISTS `sys_operation_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `operator_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作人名称',
  `operation_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作类型',
  `operation_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '操作名称',
  `target_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '目标类型',
  `target_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '目标ID',
  `target_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '目标名称',
  `detail` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '操作详情',
  `operation_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户端IP',
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'User-Agent',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0成功 1失败',
  `error_message` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '失败原因',
  `operation_time` datetime NOT NULL COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_operation_time` (`operation_time`),
  KEY `idx_operation_operator_time` (`operator_id`,`operation_time`),
  KEY `idx_operation_type_time` (`operation_type`,`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_operation_log`
--

LOCK TABLES `sys_operation_log` WRITE;
/*!40000 ALTER TABLE `sys_operation_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_operation_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `username` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户名',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '密码',
  `nickname` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '昵称',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '头像',
  `status` int NOT NULL DEFAULT '0' COMMENT '用户状态 0正常 1禁用',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  `last_login_at` datetime DEFAULT NULL COMMENT '最后登录时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user`
--

LOCK TABLES `sys_user` WRITE;
/*!40000 ALTER TABLE `sys_user` DISABLE KEYS */;
INSERT INTO `sys_user` VALUES ('01jrvgs943q0f43h0aa5mjde0y','admin','8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918','Administrator',NULL,0,'2026-07-23 14:38:36','2026-07-23 14:38:36',NULL);
/*!40000 ALTER TABLE `sys_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user_transfer_setting`
--

DROP TABLE IF EXISTS `sys_user_transfer_setting`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_transfer_setting` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '用户ID',
  `download_speed_limit` int NOT NULL DEFAULT '5' COMMENT '下载速率限制 单位：MB/S',
  `concurrent_upload_quantity` int NOT NULL DEFAULT '1' COMMENT '并发上传数量',
  `concurrent_download_quantity` int NOT NULL DEFAULT '1' COMMENT '并发下载数量',
  `chunk_size` bigint NOT NULL COMMENT '分片大小',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`) USING BTREE COMMENT '用户ID唯一索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户传输设置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user_transfer_setting`
--

LOCK TABLES `sys_user_transfer_setting` WRITE;
/*!40000 ALTER TABLE `sys_user_transfer_setting` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_user_transfer_setting` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_feature_toggle`
--

DROP TABLE IF EXISTS `sys_feature_toggle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_feature_toggle` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `feature_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '功能标识',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '是否开启：0关 1开',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_feature_key` (`feature_key`) USING BTREE COMMENT '功能标识唯一索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='功能开关';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping events for database 'gfs_clean_export'
--

--
-- Dumping routines for database 'gfs_clean_export'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-07-23 14:39:12
