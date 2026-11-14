# .agent — 项目内 agent 公用操作目录

本目录取代历史 `.freebuff/`，是所有 agent（Qoder / Claude / Cursor / Trae 等）在本项目内共享的操作目录。

## 目录约定

| 目录 | 用途 | 是否入库 |
|---|---|---|
| `skills/` | 项目技能，一个技能一个子目录，入口固定为 `SKILL.md` | 是 |
| `scripts/` | 跨会话、跨 agent 可复用的脚本 | 是 |
| `notes/` | 交接记录、排查结论、历史发布说明 | 是 |
| `tmp/` | 临时文件、日志、截图、验证产物 | 否（已忽略） |

## 规则

1. 一次性文件一律放 `.agent/tmp/`，禁止在仓库根目录新建 `screenshots/` 等临时目录。
2. 反复用到的操作沉淀为 `scripts/` 脚本，并在对应 skill 中引用，不要每次重写。
3. 不要把口令、token、Cookie、私钥写入本目录；`tmp/` 中产生的凭证用完即删。
4. 长期有效的产品文档和测试报告放 `doc/`，本目录只服务 agent 协作。
5. 新技能使用下面的 frontmatter，便于自动发现：

```markdown
---
name: gfs-xxx
description: 一句话说明适用场景
---
```

## 常用入口

- 本地开发与重启：[skills/gfs-local-dev/SKILL.md](skills/gfs-local-dev/SKILL.md)
- 编译打包与发布：[skills/gfs-build-release/SKILL.md](skills/gfs-build-release/SKILL.md)
- 生产日志与安全排查：[skills/gfs-incident-audit/SKILL.md](skills/gfs-incident-audit/SKILL.md)
- 数据模型与初始化 SQL：[skills/gfs-data-model/SKILL.md](skills/gfs-data-model/SKILL.md)
- 存储插件与协议接入：[skills/gfs-storage-plugin/SKILL.md](skills/gfs-storage-plugin/SKILL.md)
