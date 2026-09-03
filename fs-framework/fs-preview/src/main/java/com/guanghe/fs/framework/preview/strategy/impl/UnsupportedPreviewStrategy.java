package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * 不支持预览的策略（兜底）
 */
@Slf4j
@Component
public class UnsupportedPreviewStrategy extends AbstractPreviewStrategy {

    @Override
    public boolean support(FileTypeEnum fileType) {
        return false;
    }

    @Override
    public String getTemplatePath() {
        return "preview/unsupported";
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
        log.warn("不支持预览的文件类型: {}", context.getFileType().getName());
        model.addAttribute("message", "该文件类型暂不支持在线预览");
    }

}
