package com.guanghe.fs.log.service;

import com.mybatisflex.core.service.IService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.log.domain.SysLoginLog;
import com.guanghe.fs.log.domain.dto.LoginLogPageQry;
import com.guanghe.fs.log.domain.vo.SysLoginLogVO;

/**
 * 登录日志表 Service
 *
 * @Author: guanghe
 * @Date: 2025/9/25 14:39
 */
public interface SysLoginLogService extends IService<SysLoginLog> {

    /**
     * 分页查询登录日志
     *
     * @param qry
     * @return
     */
    PageResult<SysLoginLogVO> getPages(LoginLogPageQry qry);
}
