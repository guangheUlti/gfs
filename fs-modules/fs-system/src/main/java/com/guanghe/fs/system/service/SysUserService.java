package com.guanghe.fs.system.service;

import com.guanghe.fs.system.domain.SysUser;
import com.guanghe.fs.system.domain.dto.*;
import com.guanghe.fs.system.domain.vo.LockedUserVO;
import com.guanghe.fs.system.domain.vo.PendingUserVO;
import com.guanghe.fs.system.domain.vo.SysUserVO;
import com.mybatisflex.core.service.IService;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 用户服务接口
 *
 * @Author: guangheUlti
 * @Date: 2024/6/7 11:08
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 根据用户名获取用户信息
     *
     * @param username
     * @return
     */
    SysUser getByUsername(String username);

    /**
     * 获取用户信息
     *
     * @return
     */
    SysUserVO getDetail();

    /**
     * 注册用户
     *
     * @param cmd
     */
    void register(UserRegisterCmd cmd);

    /**
     * 编辑用户个人信息
     *
     * @param cmd
     * @return
     */
    void editUserInfo(UserEditInfoCmd cmd);

    /**
     * 上传头像
     *
     * @param file
     * @return
     */
    void uploadAvatar(MultipartFile file);

    /**
     * 修改密码
     *
     * @param cmd
     * @return
     */
    void updatePassword(PasswordEditCmd cmd);

    /**
     * 设置密码
     *
     * @param cmd
     * @return
     */
    void setPassword(PasswordAddCmd cmd);

    /**
     * 管理员手动创建用户（免审核，创建即可登录）
     *
     * @param cmd 创建参数
     */
    void createUserByAdmin(AdminUserCreateCmd cmd);

    /**
     * 查询待审核用户列表（新注册需管理员审核）
     *
     * @return 待审核用户列表
     */
    List<PendingUserVO> listPendingUsers();

    /**
     * 审核通过（待审核 -> 正常）
     *
     * @param userId 用户id
     */
    void approveUser(String userId);

    /**
     * 拒绝注册申请（待审核 -> 已拒绝）
     *
     * @param userId 用户id
     */
    void rejectUser(String userId);

    /**
     * 查询被防暴力锁定的账号列表（连续登录失败触发，锁定数据在 Redis）
     *
     * @return 锁定账号列表
     */
    List<LockedUserVO> listLockedUsers();

    /**
     * 解除账号锁定（清除该账号在所有 IP 上的锁定与失败计数）
     *
     * @param username 登录账号
     */
    void unlockUser(String username);

    /**
     * 断言当前登录用户是系统管理员，否则抛出异常
     */
    void assertSuperAdmin();
}
