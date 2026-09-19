package com.guanghe.fs.service.service;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.mybatisflex.core.service.IService;

/**
 * 对外文件服务配置业务接口
 */
public interface ServiceSettingService extends IService<ServiceSetting> {

    /** 服务类型：WebDAV */
    String TYPE_WEBDAV = "webdav";

    /** 服务类型：SFTP */
    String TYPE_SFTP = "sftp";

    /** 服务类型：FTP */
    String TYPE_FTP = "ftp";

    /**
     * 校验服务类型是否合法（以 SPI 注册表为准动态校验；静态兜底覆盖内置三协议）
     *
     * @param type 服务类型
     * @return 是否合法
     */
    static boolean isValidType(String type) {
        return TYPE_WEBDAV.equals(type) || TYPE_SFTP.equals(type) || TYPE_FTP.equals(type);
    }
}
