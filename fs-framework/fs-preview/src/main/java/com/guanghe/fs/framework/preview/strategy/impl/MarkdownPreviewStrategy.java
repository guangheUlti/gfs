package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Markdown 预览策略
 * 支持 .md 和 .markdown 文件的在线预览
 */
@Slf4j
@Component
public class MarkdownPreviewStrategy extends AbstractPreviewStrategy {

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.MARKDOWN;
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
    }

    @Override
    public String getTemplatePath() {
        return "preview/markdown";
    }

}
