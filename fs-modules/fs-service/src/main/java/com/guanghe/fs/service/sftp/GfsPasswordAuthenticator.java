package com.guanghe.fs.service.sftp;

import com.guanghe.fs.service.webdav.DavUserAuthenticator;
import com.guanghe.fs.system.domain.SysUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.springframework.stereotype.Component;

/**
 * SFTP 密码认证器：校验逻辑同 WebDAV Basic（现有账号 + BCrypt），
 * 成功后把 userId 存入 session attribute 供 FileSystemFactory 取用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GfsPasswordAuthenticator implements PasswordAuthenticator {

    /** session attribute key：登录成功的用户ID */
    public static final AttributeRepository.AttributeKey<String> USER_ID_KEY =
            new AttributeRepository.AttributeKey<>();

    private final DavUserAuthenticator authenticator;

    @Override
    public boolean authenticate(String username, String password, ServerSession session) {
        SysUser user = authenticator.authenticate(username, password);
        if (user == null) {
            log.info("SFTP 认证失败: username={}", username);
            return false;
        }
        session.setAttribute(USER_ID_KEY, user.getId());
        return true;
    }
}
