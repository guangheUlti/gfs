package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * 视频预览策略
 */
@Slf4j
@Component
public class VideoPreviewStrategy extends AbstractPreviewStrategy {

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.VIDEO;
    }

    @Override
    public String getTemplatePath() {
        return "preview/video";
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
    }

}

