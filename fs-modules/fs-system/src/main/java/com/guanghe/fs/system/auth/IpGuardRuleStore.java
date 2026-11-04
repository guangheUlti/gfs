package com.guanghe.fs.system.auth;

import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.system.constant.IpGuardMode;
import com.guanghe.fs.system.domain.SysIpGuard;
import com.guanghe.fs.system.domain.dto.IpGuardRule;
import com.guanghe.fs.system.mapper.SysIpGuardMapper;
import com.guanghe.fs.system.util.IpRuleMatcher;
import com.guanghe.fs.system.util.IpRuleMatcher.IpNet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * IP 防护规则快照：读库 + 编译成字节前缀，结果放 Redis 缓存，保存配置时失效。
 * <p>
 * 判定发生在每个 HTTP 请求和每次协议认证上，所以解析只做一次；缓存用 Redis 而非进程内变量，
 * 多实例部署时改完配置立即全员生效（与功能开关同一套缓存机制）。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpGuardRuleStore {

    public static final String CACHE_NAME = "ipGuard";

    private static final String SNAPSHOT_KEY = "snapshot";

    private final SysIpGuardMapper ipGuardMapper;

    /**
     * 已编译的防护规则快照
     *
     * @param mode      生效模式（名单不可用时已降级为 OFF）
     * @param whiteList 白名单网段
     * @param blackList 黑名单网段
     */
    public record GuardSnapshot(IpGuardMode mode, List<IpNet> whiteList, List<IpNet> blackList) {

        static final GuardSnapshot OFF = new GuardSnapshot(IpGuardMode.OFF, List.of(), List.of());
    }

    @Cacheable(value = CACHE_NAME, key = "'" + SNAPSHOT_KEY + "'")
    public GuardSnapshot snapshot() {
        SysIpGuard config;
        try {
            config = ipGuardMapper.selectOneById(SysIpGuard.CONFIG_ID);
        } catch (Exception e) {
            // 存量库还没执行 sys_ip_guard 建表脚本时，判定必须降级成「不启用」而不是让每个请求 500：
            // 这个过滤器挡在所有入口前面，读不到配置的后果应该是放行+告警，不是把整站关掉
            log.error("读取 IP 防护配置失败，本次按「不启用」处理；请确认数据库已创建 sys_ip_guard 表", e);
            return GuardSnapshot.OFF;
        }
        if (config == null) {
            // 老库还没建这张表的行（或未初始化种子数据）：按不启用处理
            return GuardSnapshot.OFF;
        }
        IpGuardMode mode = IpGuardMode.parse(config.getMode());
        List<IpNet> whiteList = compile(config.getWhiteList(), "白名单");
        List<IpNet> blackList = compile(config.getBlackList(), "黑名单");
        if (mode == IpGuardMode.WHITE && whiteList.isEmpty()) {
            // 名单为空或整份坏数据：当成关闭，而不是拒绝所有人。白名单模式下降级成放行，
            // 代价小于把管理员自己一起锁在外面——后者只能进数据库改，前者最多是少拦几个 IP。
            log.warn("IP 白名单为空或全部无法解析，本次按「不启用」处理，请检查「设置-系统-安全防护」配置");
            return new GuardSnapshot(IpGuardMode.OFF, whiteList, blackList);
        }
        return new GuardSnapshot(mode, whiteList, blackList);
    }

    @CacheEvict(value = CACHE_NAME, key = "'" + SNAPSHOT_KEY + "'")
    public void evict() {
        // 只用于触发缓存失效，方法体为空
    }

    /**
     * 名单 JSON → 已编译网段；无法解析的条目跳过并告警（一条脏数据不该让整份配置失效）
     */
    private List<IpNet> compile(String json, String listName) {
        List<IpGuardRule> rules = parseRules(json);
        if (rules.isEmpty()) {
            return List.of();
        }
        List<IpNet> nets = new ArrayList<>(rules.size());
        for (IpGuardRule rule : rules) {
            IpNet net = IpRuleMatcher.parseRule(rule.getIp());
            if (net == null) {
                log.warn("IP {}条目无法解析，已忽略: {}", listName, rule.getIp());
                continue;
            }
            nets.add(net);
        }
        return nets;
    }

    /**
     * 名单 JSON → 原始条目（管理端回显用，坏数据返回空列表而不是抛异常）
     */
    public List<IpGuardRule> parseRules(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<IpGuardRule> rules = JsonUtils.parseArray(json, IpGuardRule.class);
            return rules == null ? List.of() : rules;
        } catch (Exception e) {
            log.error("IP 防护名单 JSON 解析失败: {}", json, e);
            return List.of();
        }
    }
}
