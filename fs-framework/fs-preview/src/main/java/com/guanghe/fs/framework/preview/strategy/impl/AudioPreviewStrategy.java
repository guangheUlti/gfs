package com.guanghe.fs.framework.preview.strategy.impl;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * 音频预览策略
 */
@Slf4j
@Component
public class AudioPreviewStrategy extends AbstractPreviewStrategy {

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.AUDIO;
    }

    @Override
    public String getTemplatePath() {
        return "preview/audio";
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
    }

}

