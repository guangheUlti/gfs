package com.guanghe.fs.file.preview;

import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.constant.RedisKey;
import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.preview.config.FilePreviewConfig;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.core.PreviewStrategy;
import com.guanghe.fs.framework.preview.factory.PreviewStrategyManager;
import com.guanghe.fs.framework.redis.repository.RedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 预览服务
 */
@Service
@RequiredArgsConstructor
public class PreviewService {
    private static final String BROWSER_PREVIEW_STREAM_PATH = "/api/file/stream/preview";

    private final FileInfoService fileInfoService;
    private final PreviewStrategyManager strategyManager;
    private final FilePreviewConfig previewConfig;
    private final RedisRepository redisRepository;

    public String preview(String fileId, Model model) {
        if (fileId == null || fileId.trim().isEmpty()) {
            return buildErrorPage(model, I18nUtils.getMessage("file.id.invalid"), I18nUtils.getMessage("file.id.empty"));
        }
        FileInfo fileInfo = fileInfoService.getById(fileId);
        if (fileInfo == null) {
            return buildErrorPage(model, I18nUtils.getMessage("file.not.found"), I18nUtils.getMessage("file.not.exist.or.deleted"));
        }

        Long maxFileSize = previewConfig.getMaxFileSize();
        if (maxFileSize != null && maxFileSize > 0 && fileInfo.getSize() > maxFileSize) {
            return buildErrorPage(model, I18nUtils.getMessage("file.too.large"),
                    I18nUtils.getMessage("file.size.limit.exceeded", 
                            new Object[]{maxFileSize / 1024 / 1024}));
        }

        FileTypeEnum fileType = FileTypeEnum.fromFileName(fileInfo.getDisplayName());
        PreviewStrategy strategy = strategyManager.getStrategy(fileType);
        if (strategy == null) {
            return buildErrorPage(model, I18nUtils.getMessage("preview.file.type.not.supported"), 
                    I18nUtils.getMessage("preview.file.type.not.supported.detail"));
        }

        // 内嵌页媒体走流接口，补签短时 previewToken（流接口已纳入防盗链拦截）
        String streamToken = UUID.randomUUID().toString().replace("-", "");
        redisRepository.setExpire(RedisKey.getPreviewTokenKey(streamToken), fileId, RedisKey.PREVIEW_TOKEN_EXPIRE);

        PreviewContext context = PreviewContext.builder()
                .fileId(fileId)
                .fileName(fileInfo.getDisplayName())
                .streamUrl(BROWSER_PREVIEW_STREAM_PATH + "/" + fileId + "?previewToken=" + streamToken)
                .fileSize(fileInfo.getSize())
                .extension(fileInfo.getSuffix())
                .fileType(fileType)
                .build();
        strategy.fillModel(context, model);

        //修改文件访问记录
        fileInfo.setLastAccessTime(LocalDateTime.now());
        fileInfoService.updateById(fileInfo);
        return strategy.getTemplatePath();
    }

    /**
     * 构建错误页面
     */
    private String buildErrorPage(Model model, String errorMessage, String errorDetail) {
        model.addAttribute("errorMessage", errorMessage);
        model.addAttribute("errorDetail", errorDetail);
        return "preview/error";
    }
}
