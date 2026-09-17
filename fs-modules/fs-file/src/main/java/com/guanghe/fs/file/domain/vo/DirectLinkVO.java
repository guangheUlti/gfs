package com.guanghe.fs.file.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 直链 VO
 *
 * @Author: guangheUlti
 */
@Data
public class DirectLinkVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享ID
     */
    private String shareId;

    /**
     * 文件ID
     */
    private String fileId;

    /**
     * 直链相对路径（免登录即可访问下载）
     */
    private String directUrl;
}