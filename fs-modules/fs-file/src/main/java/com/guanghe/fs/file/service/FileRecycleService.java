package com.guanghe.fs.file.service;

import com.guanghe.fs.file.domain.qry.FileRecycleQry;
import com.guanghe.fs.file.domain.vo.FileRecycleVO;
import com.guanghe.fs.framework.common.domain.PageResult;

import java.util.List;

/**
 * 文件回收站服务接口
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 9:35
 */
public interface FileRecycleService {

    /**
     * 分页查询回收站文件列表
     *
     * @param qry 查询参数
     * @return 分页结果
     */
    PageResult<FileRecycleVO> getRecyclePages(FileRecycleQry qry);

    /**
     * 恢复已删除的文件
     *
     * @param fileIds 文件ID集合
     * @return
     */
    void restoreFiles(List<String> fileIds);

    /**
     * 永久删除当前登录用户的文件
     *
     * @param fileIds 文件ID集合
     */
    void permanentlyDeleteFiles(List<String> fileIds);

    /**
     * 永久删除指定用户的文件。
     * <p>
     * 供定时任务等无登录上下文的场景使用，避开对 Sa-Token 会话的依赖。
     *
     * @param fileIds 文件ID集合
     * @param userId  文件所属用户ID
     */
    void permanentlyDeleteFiles(List<String> fileIds, String userId);

    /**
     * 直接永久删除未进入回收站的文件（不经回收站），文件及其子级立即删除。
     *
     * @param fileIds 文件/文件夹ID集合
     */
    void permanentlyDeleteActiveFiles(List<String> fileIds);

    /**
     * 清空回收站
     */
    void clearRecycles();

    /**
     * 清理指定存储配置下的全部文件（跨用户，含回收站），供删除存储配置时使用。
     * <p>
     * GFS 托管存储（本地/OSS/内存等）：同时删除无引用的物理对象；
     * 直读真实目录的存储（本地挂载）：只清网盘索引，真实文件保持原样。
     * <p>
     * 使用独立事务（REQUIRES_NEW）：必须在存储配置仍存在时提交，
     * 否则事务提交后的物理删除会因存储实例已被移除而无法执行。
     *
     * @param storageSettingId 存储配置 ID
     */
    void purgeStorageFiles(String storageSettingId);

    /**
     * 清理指定用户的全部文件（含回收站），并删除无引用的物理对象。
     * <p>
     * 供管理员删除账号使用；与永久删除一致，挂载式存储会同时清理真实目录。
     *
     * @param userId 用户ID
     * @return 删除的文件/文件夹记录数
     */
    int purgeUserFiles(String userId);
}
