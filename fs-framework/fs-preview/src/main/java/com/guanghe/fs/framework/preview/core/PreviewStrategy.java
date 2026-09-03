package com.guanghe.fs.framework.preview.core;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.preview.converter.IConverter;
import org.springframework.ui.Model;

import java.io.InputStream;

public interface PreviewStrategy {

    /**
     * 是否支持该类型
     */
    boolean support(FileTypeEnum type);

    /**
     * 策略模板
     *
     * @return
     */
    String getTemplatePath();

    /**
     * 获取转换工具里
     *
     * @return
     */
    IConverter getConverter();

    /**
     * 是否支持Range请求
     */
    boolean supportRange();

    /**
     * 是否需要转换流
     */
    default boolean needConvert() {
        return getConverter() != null;
    }

    /**
     * 处理文件流
     */
    InputStream processStream(InputStream sourceStream, String extension);

    /**
     * 填充模板数据
     */
    void fillModel(PreviewContext context, Model model);

    /**
     * 获取响应的文件扩展名（可能因转换而改变）
     */
    String getResponseExtension(String originalExtension);
}
