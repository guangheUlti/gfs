-- =====================================================================
-- 存量库升级脚本：新增「设置 → 系统 → 安全防护」的 IP 白名单 / 黑名单配置表
--
-- 适用：用旧版初始化脚本（sql/mysql/gfs.sql、release/deploy-package/sql/init.sql）
--        建好的 MySQL 8.x 库
-- 性质：纯新增表 + 一行默认配置，不改动任何既有表、列与数据；可重复执行（幂等）
-- 不需要执行的情况：全新安装（上述种子脚本已包含本表与本行）
--
-- 执行方式（示例）：
--   D:\devTools\mysql\bin\mysql.exe -h <主机> -P <端口> -u <用户> -p \
--     --default-character-set=utf8mb4 <库名> < sql\mysql\upgrade-ip-guard.sql
--
-- 执行后：服务无需重启即可用，但若服务是在建表之前启动的，它会缓存一次「不启用」的
-- 判定快照；在「安全防护」页保存一次配置（或重启服务）即可让新配置立即生效。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `sys_ip_guard` (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '固定主键：全局单行 ip_guard',
  `mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'OFF' COMMENT '防护模式：OFF关闭 WHITE白名单 BLACK黑名单（互斥）',
  `white_list` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '白名单条目 JSON 数组（IP 或 CIDR 网段 + 备注）',
  `black_list` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '黑名单条目 JSON 数组（IP 或 CIDR 网段 + 备注）',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='IP 安全防护配置';

-- 默认配置：不启用、两份名单为空。已有该行时不覆盖（幂等）
INSERT IGNORE INTO `sys_ip_guard`
  (`id`, `mode`, `white_list`, `black_list`, `created_at`, `updated_at`)
VALUES
  ('ip_guard', 'OFF', '[]', '[]', NOW(), NOW());

-- 校验：应返回 1 行，mode = OFF、两份名单为 []
SELECT `id`, `mode`, `white_list`, `black_list` FROM `sys_ip_guard`;
