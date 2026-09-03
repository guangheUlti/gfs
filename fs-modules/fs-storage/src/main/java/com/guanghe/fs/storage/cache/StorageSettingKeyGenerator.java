package com.guanghe.fs.storage.cache;

import com.guanghe.fs.framework.common.context.WorkspaceContext;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component("storageSettingKeyGenerator")
public class StorageSettingKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        return WorkspaceContext.getWorkspaceId();
    }
}
