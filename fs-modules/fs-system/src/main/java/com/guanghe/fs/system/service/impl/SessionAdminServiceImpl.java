package com.guanghe.fs.system.service.impl;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.session.SaTerminalInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.log.constant.OperationType;
import com.guanghe.fs.log.service.SysOperationLogService;
import com.guanghe.fs.system.constant.TerminalExtraKey;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.vo.OnlineTerminalVO;
import com.guanghe.fs.system.domain.vo.OnlineUserVO;
import com.guanghe.fs.system.service.SessionAdminService;
import com.guanghe.fs.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录管理实现：在线终端全部以 Sa-Token 服务端存储为准
 * （已启用 sa-token-redis-template，会话在 Redis，因此多实例下看到的列表与踢人效果一致）
 *
 * @Author: guangheUlti
 * @Date: 2026/9/5
 */
@Service
@RequiredArgsConstructor
public class SessionAdminServiceImpl implements SessionAdminService {

    /** token 指纹长度：只回传末 6 位，够前端确认踢的是同一个会话，又不泄露可用凭证 */
    private static final int TOKEN_TAIL_LENGTH = 6;

    private final SysUserService sysUserService;

    private final SysOperationLogService operationLogService;

    @Override
    public List<OnlineUserVO> listOnlineSessions(String keyword) {
        sysUserService.assertSuperAdmin();

        Map<String, List<String>> tokensByLoginId = collectLiveTokens();
        if (tokensByLoginId.isEmpty()) {
            return List.of();
        }

        String currentToken = StpUtil.getTokenValue();
        Map<String, SysUser> userMap = loadUsers(tokensByLoginId.keySet());
        List<OnlineUserVO> list = new ArrayList<>(tokensByLoginId.size());
        for (Map.Entry<String, List<String>> entry : tokensByLoginId.entrySet()) {
            String loginId = entry.getKey();
            SysUser user = userMap.get(loginId);
            OnlineUserVO vo = new OnlineUserVO();
            vo.setLoginId(loginId);
            if (user != null) {
                vo.setUsername(user.getUsername());
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                vo.setStatus(user.getStatus());
            }
            vo.setTerminals(buildTerminals(loginId, entry.getValue(), currentToken));
            list.add(vo);
        }

        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (!kw.isEmpty()) {
            list = list.stream().filter(vo -> matches(vo, kw)).collect(Collectors.toList());
        }
        list.sort(Comparator.comparing(
                vo -> vo.getUsername() == null ? vo.getLoginId() : vo.getUsername(),
                String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    @Override
    public int kickoutUser(String loginId) {
        sysUserService.assertSuperAdmin();

        List<String> tokens = liveTokensOf(loginId);
        if (tokens.isEmpty()) {
            throw new BusinessException(404, I18nUtils.getMessage("admin.session.not.found"));
        }
        // 用 kickout 而非 logout：被强制下线的终端下次请求会收到「已被踢下线」，
        // 区别于自己退出，便于用户知道是管理员操作（见 GlobalExceptionHandler 对 NotLoginException 的分支）
        // 审计必须在踢之前落库：整账号下线可能连着管理员自己的当前会话，先踢会导致记录不到操作者
        recordKickout(loginId, "强制用户下线", "下线会话数=" + tokens.size());
        tokens.forEach(StpUtil::kickoutByTokenValue);
        return tokens.size();
    }

    @Override
    public void kickoutTerminal(String loginId, int index, String tokenTail) {
        sysUserService.assertSuperAdmin();

        List<String> ordered = liveTokensOf(loginId);
        if (index < 0 || index >= ordered.size()) {
            throw new BusinessException(404, I18nUtils.getMessage("admin.session.changed"));
        }
        String token = ordered.get(index);
        // 列表展示与踢人之间可能有变化（期间有新登录或有端退出，序号会整体错位），
        // 用 token 末 6 位确认还是同一个会话，宁可让管理员刷新重来也不踢错
        if (tokenTail != null && !tokenTail.isEmpty() && !tokenTail.equalsIgnoreCase(tokenTail(token))) {
            throw new BusinessException(404, I18nUtils.getMessage("admin.session.changed"));
        }
        recordKickout(loginId, "强制终端下线", "终端序号=" + index + "，Token末位=" + tokenTail(token));
        StpUtil.kickoutByTokenValue(token);
    }

    /** 记录踢人审计，目标用户名一并落库，便于事后追溯 */
    private void recordKickout(String loginId, String operationName, String detail) {
        SysUser target = sysUserService.getById(loginId);
        operationLogService.recordSuccess(
                OperationType.KICKOUT_SESSION,
                operationName,
                "USER",
                loginId,
                target == null ? null : target.getUsername(),
                detail
        );
    }

    /**
     * 枚举服务端仍然有效的 token，并按 loginId 分组。
     * 被踢/被顶/已过期/活跃冻结的 token 其映射会被 Sa-Token 判定为无效（getLoginIdByToken 返回 null），
     * 因此不会出现在列表里。
     */
    private Map<String, List<String>> collectLiveTokens() {
        String keyPrefix = StpUtil.getStpLogic().splicingKeyTokenValue("");
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String key : StpUtil.searchTokenValue("", 0, -1, false)) {
            String token = stripKeyPrefix(key, keyPrefix);
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                continue;
            }
            grouped.computeIfAbsent(String.valueOf(loginId), k -> new ArrayList<>()).add(token);
        }
        grouped.values().forEach(Collections::sort);
        return grouped;
    }

    /** 某账号当前有效的 token，顺序与 {@link #listOnlineSessions} 完全一致，序号才可复用 */
    private List<String> liveTokensOf(String loginId) {
        List<String> tokens = collectLiveTokens().get(loginId);
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        return orderTokens(StpUtil.getSessionByLoginId(loginId, false), tokens);
    }

    /**
     * 终端的稳定排序：先按账号会话里终端记录的顺序（即登录先后），没有终端记录的 token 兜底按字典序追加，
     * 保证同一份数据在「查列表」和「踢人」两次请求里算出的序号一致。
     */
    private List<String> orderTokens(SaSession session, Collection<String> tokens) {
        Set<String> pool = new LinkedHashSet<>(tokens);
        List<String> ordered = new ArrayList<>(pool.size());
        if (session != null && session.getTerminalList() != null) {
            for (SaTerminalInfo info : session.getTerminalList()) {
                String token = info == null ? null : info.getTokenValue();
                if (token != null && pool.remove(token)) {
                    ordered.add(token);
                }
            }
        }
        List<String> rest = new ArrayList<>(pool);
        Collections.sort(rest);
        ordered.addAll(rest);
        return ordered;
    }

    private List<OnlineTerminalVO> buildTerminals(String loginId, Collection<String> tokens, String currentToken) {
        // 传 false：查看在线列表不该顺手把不存在的账号会话创建出来
        SaSession session = StpUtil.getSessionByLoginId(loginId, false);
        List<String> ordered = orderTokens(session, tokens);
        List<OnlineTerminalVO> terminals = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            String token = ordered.get(i);
            SaTerminalInfo info = session == null ? null : session.getTerminal(token);
            OnlineTerminalVO vo = new OnlineTerminalVO();
            vo.setIndex(i);
            vo.setTokenTail(tokenTail(token));
            vo.setDeviceType(info == null ? null : info.getDeviceType());
            vo.setIp(terminalExtra(info, TerminalExtraKey.IP));
            vo.setBrowser(terminalExtra(info, TerminalExtraKey.BROWSER));
            vo.setOs(terminalExtra(info, TerminalExtraKey.OS));
            if (info != null && info.getCreateTime() > 0) {
                vo.setLoginTime(info.getCreateTime());
            }
            vo.setLastActiveTime(lastActiveTime(token));
            vo.setCurrent(token.equals(currentToken));
            terminals.add(vo);
        }
        return terminals;
    }

    private Map<String, SysUser> loadUsers(Collection<String> loginIds) {
        return sysUserService.listByIds(new ArrayList<>(loginIds)).stream()
                .filter(user -> user != null && user.getId() != null)
                .collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a));
    }

    private boolean matches(OnlineUserVO vo, String keyword) {
        if (contains(vo.getUsername(), keyword) || contains(vo.getNickname(), keyword)
                || contains(vo.getLoginId(), keyword)) {
            return true;
        }
        return vo.getTerminals() != null && vo.getTerminals().stream().anyMatch(terminal ->
                contains(terminal.getIp(), keyword) || contains(terminal.getBrowser(), keyword)
                        || contains(terminal.getOs(), keyword));
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /** 最后活跃时间：Sa-Token 为支撑 active-timeout 单独存的毫秒戳 */
    private Long lastActiveTime(String token) {
        String value = SaManager.getSaTokenDao()
                .get(StpUtil.getStpLogic().splicingKeyLastActiveTime(token));
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String terminalExtra(SaTerminalInfo info, String key) {
        if (info == null || !info.haveExtraData()) {
            return null;
        }
        Object value = info.getExtra(key);
        return value == null ? null : String.valueOf(value);
    }

    private String stripKeyPrefix(String key, String prefix) {
        // Sa-Token 的 searchTokenValue 返回的是完整的存储键（Authorization:login:token:xxx）
        return key != null && key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    private String tokenTail(String token) {
        if (token == null) {
            return "";
        }
        return token.length() <= TOKEN_TAIL_LENGTH ? token : token.substring(token.length() - TOKEN_TAIL_LENGTH);
    }
}
