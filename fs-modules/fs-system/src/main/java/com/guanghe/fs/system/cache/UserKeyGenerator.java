package com.guanghe.fs.system.cache;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component("userKeyGenerator")
public class UserKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        return StpUtil.getLoginIdAsString();
    }
}
