package com.guanghe.fs.system.service.impl;

import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.IpUtils;
import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.system.auth.IpGuardRuleStore;
import com.guanghe.fs.system.auth.IpGuardRuleStore.GuardSnapshot;
import com.guanghe.fs.system.constant.IpGuardMode;
import com.guanghe.fs.system.domain.SysIpGuard;
import com.guanghe.fs.system.domain.dto.IpGuardEditCmd;
import com.guanghe.fs.system.domain.dto.IpGuardRule;
import com.guanghe.fs.system.domain.vo.IpGuardConfigVO;
import com.guanghe.fs.system.mapper.SysIpGuardMapper;
import com.guanghe.fs.system.service.IpGuardService;
import com.guanghe.fs.system.service.SysUserService;
import com.guanghe.fs.system.util.IpRuleMatcher;
import com.guanghe.fs.system.util.IpRuleMatcher.IpNet;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IP 安全防护业务实现
 *
 * @Author: guangheUlti
 * @Date: 2026/11/4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpGuardServiceImpl extends ServiceImpl<SysIpGuardMapper, SysIpGuard> implements IpGuardService {

    /** 单份名单条数上限：判定是逐条前缀比较，不设上限会让一个请求付出解析几千条的代价 */
    private static final int MAX_RULES_PER_LIST = 200;

    private static final int MAX_REMARK_LENGTH = 100;

    private final SysUserService sysUserService;
    private final IpGuardRuleStore ruleStore;

    @Override
    public IpGuardConfigVO getConfig() {
        sysUserService.assertSuperAdmin();
        SysIpGuard config = this.getById(SysIpGuard.CONFIG_ID);
        IpGuardConfigVO vo = new IpGuardConfigVO();
        vo.setMode(config == null ? IpGuardMode.OFF.name() : IpGuardMode.parse(config.getMode()).name());
        // 回显用原始条目：连写错的条目也要让管理员看到并改掉，不能只在界面上"消失"
        vo.setWhiteList(config == null
                ? List.of() : ruleStore.parseRules(config.getWhiteList()));
        vo.setBlackList(config == null
                ? List.of() : ruleStore.parseRules(config.getBlackList()));
        vo.setCurrentIp(currentRequestIp());
        return vo;
    }

    @Override
    public void saveConfig(IpGuardEditCmd cmd) {
        sysUserService.assertSuperAdmin();
        IpGuardMode mode = parseMode(cmd.getMode());
        List<IpGuardRule> whiteList = normalize(cmd.getWhiteList(), "白名单");
        List<IpGuardRule> blackList = normalize(cmd.getBlackList(), "黑名单");

        String currentIp = currentRequestIp();
        assertOperatorNotLockedOut(mode, whiteList, blackList, currentIp);

        SysIpGuard config = this.getById(SysIpGuard.CONFIG_ID);
        boolean creating = config == null;
        if (creating) {
            config = new SysIpGuard();
            config.setId(SysIpGuard.CONFIG_ID);
        }
        config.setMode(mode.name());
        config.setWhiteList(JsonUtils.toJsonString(whiteList));
        config.setBlackList(JsonUtils.toJsonString(blackList));
        if (creating) {
            this.save(config);
        } else {
            this.updateById(config);
        }
        ruleStore.evict();
        log.info("IP 安全防护配置已更新: mode={}, white={}, black={}, operatorIp={}",
                mode, whiteList.size(), blackList.size(), currentIp);
    }

    @Override
    public boolean isAllowed(String clientIp) {
        GuardSnapshot snapshot = ruleStore.snapshot();
        return switch (snapshot.mode()) {
            case OFF -> true;
            case WHITE -> matchesAny(clientIp, snapshot.whiteList());
            case BLACK -> !matchesAny(clientIp, snapshot.blackList());
        };
    }

    /**
     * 名单是互斥的，但两份名单都留在库里（切换模式不必重新录入），因此拒绝原因按生效模式给。
     */
    @Override
    public void assertAllowed(String clientIp) {
        if (isAllowed(clientIp)) {
            return;
        }
        IpGuardMode mode = ruleStore.snapshot().mode();
        String key = mode == IpGuardMode.BLACK ? "ipGuard.denied.black" : "ipGuard.denied.white";
        throw new BusinessException(403, I18nUtils.getMessage(key, new Object[]{clientIp == null ? "" : clientIp}));
    }

    @Override
    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        // 与防爆破、注册限流、操作日志共用 IpUtils 的口径，避免「防护之间互相看不到同一个 IP」
        return IpUtils.getIpAddr(request);
    }

    /**
     * 当前 HTTP 请求的来源 IP；没有请求上下文（定时任务等）时返回 null
     */
    private String currentRequestIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return resolveClientIp(servletAttributes.getRequest());
        }
        return null;
    }

    /**
     * 保存前自检：新配置不能把当前操作者挡在门外。
     * <p>
     * 只信任本次请求的来源 IP（就是管理员此刻正在用的地址），不信任他填写的备注或历史配置。
     * 取不到 IP（异常环境）时按「不在白名单内」处理：宁可拒绝保存，也不要保存完才发现整个系统没人进得来。
     */
    private void assertOperatorNotLockedOut(IpGuardMode mode, List<IpGuardRule> whiteList,
                                           List<IpGuardRule> blackList, String currentIp) {
        if (mode == IpGuardMode.OFF) {
            return;
        }
        if (mode == IpGuardMode.WHITE && !matchesAny(currentIp, toNets(whiteList))) {
            throw new BusinessException(I18nUtils.getMessage("ipGuard.lockout.white",
                    new Object[]{currentIp == null ? "" : currentIp}));
        }
        if (mode == IpGuardMode.BLACK && matchesAny(currentIp, toNets(blackList))) {
            throw new BusinessException(I18nUtils.getMessage("ipGuard.lockout.black",
                    new Object[]{currentIp == null ? "" : currentIp}));
        }
    }

    /**
     * 条目列表 → 网段列表；保存路径已校验过格式，这里的脏数据来自库里的手改，跳过即可
     */
    private List<IpNet> toNets(List<IpGuardRule> rules) {
        List<IpNet> nets = new ArrayList<>(rules.size());
        for (IpGuardRule rule : rules) {
            IpNet net = IpRuleMatcher.parseRule(rule.getIp());
            if (net != null) {
                nets.add(net);
            }
        }
        return nets;
    }

    /**
     * 校验并整理名单：去空白、忽略未填的空行、按 IP 去重，格式非法直接拒绝保存
     */
    private List<IpGuardRule> normalize(List<IpGuardRule> rules, String listName) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        if (rules.size() > MAX_RULES_PER_LIST) {
            throw new BusinessException(I18nUtils.getMessage("ipGuard.rule.tooMany",
                    new Object[]{MAX_RULES_PER_LIST}));
        }
        Map<String, IpGuardRule> unique = new LinkedHashMap<>();
        for (IpGuardRule rule : rules) {
            String ip = rule == null || rule.getIp() == null ? "" : rule.getIp().trim();
            if (ip.isEmpty()) {
                continue;
            }
            if (IpRuleMatcher.parseRule(ip) == null) {
                throw new BusinessException(I18nUtils.getMessage("ipGuard.rule.invalid", new Object[]{ip}));
            }
            String remark = rule.getRemark() == null ? "" : rule.getRemark().trim();
            if (remark.length() > MAX_REMARK_LENGTH) {
                throw new BusinessException(I18nUtils.getMessage("ipGuard.rule.remarkTooLong",
                        new Object[]{listName, MAX_REMARK_LENGTH}));
            }
            IpGuardRule cleaned = new IpGuardRule();
            cleaned.setIp(ip);
            cleaned.setRemark(remark);
            unique.putIfAbsent(ip.toLowerCase(), cleaned);
        }
        return new ArrayList<>(unique.values());
    }

    private IpGuardMode parseMode(String value) {
        if (value != null) {
            String normalized = value.trim().toUpperCase();
            for (IpGuardMode mode : IpGuardMode.values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }
        }
        throw new BusinessException(I18nUtils.getMessage("ipGuard.mode.invalid"));
    }

    /**
     * 客户端地址是否命中任一网段；地址解析不出来时返回 false（白名单模式下即「不允许」）
     */
    private boolean matchesAny(String clientIp, List<IpNet> nets) {
        byte[] client = IpRuleMatcher.parseAddress(clientIp);
        if (client == null || nets.isEmpty()) {
            return false;
        }
        for (IpNet net : nets) {
            if (IpRuleMatcher.matches(client, net)) {
                return true;
            }
        }
        return false;
    }
}
