package com.guanghe.fs.framework.preview.strategy.impl;


import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.converter.IConverter;
import com.guanghe.fs.framework.preview.converter.impl.OfficeToPdfConverter;
import com.guanghe.fs.framework.preview.core.PreviewContext;
import com.guanghe.fs.framework.preview.strategy.AbstractPreviewStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ui.Model;

@Slf4j
@RequiredArgsConstructor
public class OfficePreviewStrategy extends AbstractPreviewStrategy {

    private final OfficeToPdfConverter officeToPdfConverter;

    @Override
    public boolean support(FileTypeEnum fileType) {
        return fileType == FileTypeEnum.WORD ||
                fileType == FileTypeEnum.PPT;
    }

    @Override
    public String getTemplatePath() {
        return "preview/pdf";
    }

    @Override
    public IConverter getConverter() {
        return officeToPdfConverter;
    }

    @Override
    protected void fillSpecificModel(PreviewContext context, Model model) {
    }

}
