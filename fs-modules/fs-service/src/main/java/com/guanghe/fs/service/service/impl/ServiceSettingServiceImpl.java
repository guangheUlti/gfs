package com.guanghe.fs.service.service.impl;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.mapper.ServiceSettingMapper;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 对外文件服务配置业务实现
 */
@Service
public class ServiceSettingServiceImpl extends ServiceImpl<ServiceSettingMapper, ServiceSetting>
        implements ServiceSettingService {
}
