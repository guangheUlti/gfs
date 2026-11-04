-- =====================================================================
-- 存量库升级脚本：新增「设置 → 系统 → 安全防护」的 IP 白名单 / 黑名单配置表（PostgreSQL）
--
-- 适用：用旧版 sql/postgresql/gfs_pg.sql 建好的 PostgreSQL 库
-- 性质：纯新增表 + 一行默认配置，不改动任何既有表、列与数据；可重复执行（幂等）
-- 不需要执行的情况：全新安装（gfs_pg.sql 已包含本表与本行）
--
-- 执行方式（示例）：
--   psql -h <主机> -p <端口> -U <用户> -d <库名> -f sql/postgresql/upgrade-ip-guard.sql
--
-- 执行后：服务无需重启即可用，但若服务是在建表之前启动的，它会缓存一次「不启用」的
-- 判定快照；在「安全防护」页保存一次配置（或重启服务）即可让新配置立即生效。
-- =====================================================================

CREATE TABLE IF NOT EXISTS "sys_ip_guard" (
    "id" varchar(64) PRIMARY KEY,
    "mode" varchar(16) NOT NULL DEFAULT 'OFF',
    "white_list" text,
    "black_list" text,
    "created_at" timestamp NOT NULL,
    "updated_at" timestamp NOT NULL
);

COMMENT ON TABLE "sys_ip_guard" IS 'IP 安全防护配置（mode：OFF 关闭 / WHITE 白名单 / BLACK 黑名单，互斥）';
COMMENT ON COLUMN "sys_ip_guard"."id" IS '固定主键：全局单行 ip_guard';
COMMENT ON COLUMN "sys_ip_guard"."mode" IS '防护模式：OFF/WHITE/BLACK';
COMMENT ON COLUMN "sys_ip_guard"."white_list" IS '白名单条目 JSON 数组（IP 或 CIDR 网段 + 备注）';
COMMENT ON COLUMN "sys_ip_guard"."black_list" IS '黑名单条目 JSON 数组（IP 或 CIDR 网段 + 备注）';

-- 默认配置：不启用、两份名单为空。已有该行时不覆盖（幂等）
INSERT INTO "sys_ip_guard" ("id", "mode", "white_list", "black_list", "created_at", "updated_at")
VALUES ('ip_guard', 'OFF', '[]', '[]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT ("id") DO NOTHING;

-- 校验：应返回 1 行，mode = OFF、两份名单为 []
SELECT "id", "mode", "white_list", "black_list" FROM "sys_ip_guard";
