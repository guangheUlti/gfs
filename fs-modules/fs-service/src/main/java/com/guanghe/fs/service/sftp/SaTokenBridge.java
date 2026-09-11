package com.guanghe.fs.service.sftp;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import com.guanghe.fs.service.webdav.DavUserAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * SFTP 线程的 Sa-Token 上下文桥接：
 * MINA 的 NIO/会话线程没有 HTTP 请求上下文，用 sa-token-core 1.45.0 自带的
 * {@link SaTokenContextMockUtil} setMockContext → setTokenValue → 执行 → finally 清理。
 *
 * ⚠️ sa-token-core 1.45.0 的 setMockContext 是“覆盖式” mock：嵌套调用会命中
 * outer mock 已建立的上下文并被清空。因此这里用显式 depth 计数保证同一线程上的
 * 嵌套调用不会重复 set/clear，最近的一次 set 生效、最外层 close 后才清空。
 *
 * token 复用 {@link DavUserAuthenticator} 的内存缓存（避免每操作 login 堆积会话）。
 */
@Component
@Slf4j
public class SaTokenBridge {

    private final DavUserAuthenticator authenticator;

    public SaTokenBridge(DavUserAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    /** 线程局部 mock 嵌套深度；配合 ThreadLocal 防止串线程 */
    private static final ThreadLocal<Integer> MOCK_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> TOKEN_SET = ThreadLocal.withInitial(() -> false);

    /**
     * 以指定用户身份执行文件操作（可重入）
     */
    public <T> T runAs(String userId, Supplier<T> action) {
        int depth = MOCK_DEPTH.get();
        boolean alreadySet = TOKEN_SET.get();

        if (depth == 0) {
            // 最外层：建立 mock 上下文（桥接内部还会 setToken 进去）
            return SaTokenContextMockUtil.setMockContext(() -> {
                try {
                    doRunAs(userId, alreadySet);
                    return action.get();
                } finally {
                    MOCK_DEPTH.remove();
                    TOKEN_SET.remove();
                    SaTokenContextMockUtil.clearContext();
                }
            });
        } else {
            // 嵌套调用：mock 已存在，只需把 token 补齐（避免重复 set 把前一层覆盖掉）
            doRunAs(userId, alreadySet);
            return action.get();
        }
    }

    /**
     * 无返回值版本（可重入）
     */
    public void runAs(String userId, Runnable action) {
        int depth = MOCK_DEPTH.get();
        boolean alreadySet = TOKEN_SET.get();

        if (depth == 0) {
            SaTokenContextMockUtil.setMockContext(() -> {
                try {
                    doRunAs(userId, alreadySet);
                    action.run();
                } finally {
                    MOCK_DEPTH.remove();
                    TOKEN_SET.remove();
                    SaTokenContextMockUtil.clearContext();
                }
            });
        } else {
            doRunAs(userId, alreadySet);
            action.run();
        }
    }

    private void doRunAs(String userId, boolean alreadySet) {
        if (alreadySet) {
            return;
        }
        authenticator.bridgeSaToken(userId);
        TOKEN_SET.set(true);
        MOCK_DEPTH.set(MOCK_DEPTH.get() + 1);
    }

    /**
     * 取（或创建）指定用户的 token（不建立上下文，供 FileSystemFactory 传递）
     */
    public String tokenFor(String userId) {
        return authenticator.bridgeSaToken(userId);
    }
}
