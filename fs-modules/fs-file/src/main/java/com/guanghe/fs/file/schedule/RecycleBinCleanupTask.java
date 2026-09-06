package com.guanghe.fs.file.schedule;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.file.service.FileRecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

/**
 * 回收站清理定时任务
 * 每天凌晨0点05分执行，清理回收站内超过7天的文件
 *
 * @Author: guangheUlti
 * @Date: 2025/11/15 20:21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleBinCleanupTask {

    private final FileInfoService fileInfoService;
    private final FileRecycleService recycleService;

    @Scheduled(cron = "0 5 0 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupRecycleBin() {
        log.info("========== 开始执行回收站清理任务 ==========");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            LocalDateTime expireTime = LocalDateTime.now().minusDays(7);
            List<FileInfo> expiredFiles = findExpiredFiles(expireTime);
            if (CollUtil.isEmpty(expiredFiles)) {
                log.info("回收站无过期文件，任务结束");
                return;
            }

            // 按所属用户分组：定时任务没有登录上下文，需把 userId 显式传给删除逻辑
            Map<String, List<String>> filesByUser = expiredFiles.stream()
                    .filter(file -> StrUtil.isNotBlank(file.getUserId()))
                    .collect(Collectors.groupingBy(
                            FileInfo::getUserId,
                            Collectors.mapping(FileInfo::getId, Collectors.toList())
                    ));

            int totalCleaned = 0;
            for (Map.Entry<String, List<String>> entry : filesByUser.entrySet()) {
                try {
                    recycleService.permanentlyDeleteFiles(entry.getValue(), entry.getKey());
                    totalCleaned += entry.getValue().size();
                } catch (Exception e) {
                    log.error("清理用户 {} 的过期文件失败", entry.getKey(), e);
                }
            }

            stopWatch.stop();
            log.info("回收站清理结束, 总计: {} 个, 耗时: {} ms", totalCleaned, stopWatch.getTotalTimeMillis());
        } catch (Exception e) {
            log.error("回收站清理任务执行异常", e);
            throw e;
        }
    }

    private List<FileInfo> findExpiredFiles(LocalDateTime expireTime) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(FILE_INFO.IS_DELETED.eq(true))
                .and(FILE_INFO.DELETED_TIME.lt(expireTime))
                .orderBy(FILE_INFO.DELETED_TIME.asc());
        return fileInfoService.list(queryWrapper);
    }
}
