package com.guanghe.fs.log.service.impl;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.utils.StringUtils;
import com.guanghe.fs.log.domain.SysLoginLog;
import com.guanghe.fs.log.domain.dto.LoginLogPageQry;
import com.guanghe.fs.log.domain.vo.SysLoginLogVO;
import com.guanghe.fs.log.mapper.SysLoginLogMapper;
import com.guanghe.fs.log.service.SysLoginLogService;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.guanghe.fs.log.domain.table.SysLoginLogTableDef.SYS_LOGIN_LOG;

/**
 * 登录日志表 Service Impl
 *
 * @Author: guanghe
 * @Date: 2025/9/25 14:40
 */
@Service
@RequiredArgsConstructor
public class SysLoginLogServiceImpl extends ServiceImpl<SysLoginLogMapper, SysLoginLog> implements SysLoginLogService {

    private final Converter converter;

    @Override
    public PageResult<SysLoginLogVO> getPages(LoginLogPageQry qry) {
        Page<SysLoginLog> page = new Page<>(qry.getPage(), qry.getPageSize());
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.orderBy(SYS_LOGIN_LOG.LOGIN_TIME.desc());
        //关键字匹配
        if (StringUtils.isNotEmpty(qry.getKeyword())) {
            queryWrapper.where(
                    SYS_LOGIN_LOG.USERNAME.like(qry.getKeyword() + "%")
                            .or(SYS_LOGIN_LOG.LOGIN_IP.like(qry.getKeyword() + "%"))
                            .or(SYS_LOGIN_LOG.LOGIN_ADDRESS.like(qry.getKeyword() + "%"))
                            .or(SYS_LOGIN_LOG.BROWSER.like(qry.getKeyword() + "%"))
                            .or(SYS_LOGIN_LOG.OS.like(qry.getKeyword() + "%"))
                            .or(SYS_LOGIN_LOG.MSG.like(qry.getKeyword() + "%"))

            );
        }
        if (qry.getStatus() != null) {
            queryWrapper.and(SYS_LOGIN_LOG.STATUS.eq(qry.getStatus()));
        }
        this.page(page, queryWrapper);
        Long total = page.getTotalRow();
        List<SysLoginLog> records = page.getRecords();
        List<SysLoginLogVO> vos = converter.convert(records, SysLoginLogVO.class);
        PageResult.PageRecord<SysLoginLogVO> pageRecord = new PageResult.PageRecord<>();
        pageRecord.setRecords(vos);
        pageRecord.setTotal(total);
        return PageResult.<SysLoginLogVO>builder().data(pageRecord).code(200).build();
    }
}
