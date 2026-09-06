package com.guanghe.fs.framework.notify.mail.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Mail {

    /**
     * 收件人
     */
    private String recipient;
    /**
     * 主题
     */
    private String subject;
    /**
     * 内容
     */
    private String content;

    /**
     * 模板
     */
    private String htmlTemplate;

    /**
     * 模板参数
     */
    private Map<String, Object> params;

    /**
     * 附件
     */
    private String attachment;
}
