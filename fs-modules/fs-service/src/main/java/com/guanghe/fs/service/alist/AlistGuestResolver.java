package com.guanghe.fs.service.alist;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.system.constant.UserStatus;
import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.mapper.SysUserMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.guanghe.fs.service.domain.table.ServiceSettingTableDef.SERVICE_SETTING;

/**
 * AList 游客身份解析。
 * <p>
 * 文件接口一律按「当前登录用户」过滤（{@code StpUtil.getLoginIdAsString()}），
 * 匿名请求没有身份就查不了任何文件；因此游客只读必须绑定一个公开账号，
 * 匿名请求在过滤器里桥接成该账号后再进业务层。
 * <p>
 * 绑定账号被禁用/删除时按「游客关闭」处理，避免匿名请求带着一个无效身份进入业务层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlistGuestResolver {

    private final ServiceSettingService serviceSettingService;
    private final SysUserMapper userMapper;

    /** 当前 AList 配置；读取失败返回 null */
    public ServiceSetting current() {
        try {
            return serviceSettingService.getOne(
                    QueryWrapper.create().where(SERVICE_SETTING.SERVICE_TYPE.eq(ServiceSettingService.TYPE_ALIST)));
        } catch (Exception e) {
            log.warn("读取 AList 服务配置失败，按停用处理", e);
            return null;
        }
    }

    /**
     * 解析可用于匿名访问的账号 ID。
     *
     * @param setting AList 配置
     * @return 账号 ID；开关关闭、未绑定或账号不可用时返回 null
     */
    public String resolve(ServiceSetting setting) {
        if (setting == null || !ServiceSettingService.isGuestAllowed(setting)) {
            return null;
        }
        String userId = ServiceSettingService.guestUserId(setting);
        if (StrUtil.isBlank(userId)) {
            return null;
        }
        SysUser user = userMapper.selectOneById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != UserStatus.NORMAL) {
            return null;
        }
        return user.getId();
    }

    /** 游客只读是否真正可用（开关 + 账号都有效） */
    public boolean isAvailable(ServiceSetting setting) {
        return resolve(setting) != null;
    }
}
