package com.guanghe.fs.system.auth;

import com.guanghe.fs.framework.common.enums.LoginType;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录策略工厂
 *
 * @Author: guangheUlti
 * @Date: 2026/4/2 09:59
 */
@Service
public class LoginStrategyFactory {

    private final Map<LoginType, LoginStrategy> strategyMap = new ConcurrentHashMap<>();

    public LoginStrategyFactory(List<LoginStrategy> strategies) {
        strategies.forEach(s -> strategyMap.put(s.getLoginType(), s));
    }

    public LoginStrategy getStrategy(LoginType type) {
        LoginStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            throw new BusinessException(I18nUtils.getMessage("auth.login.type.not.supported"));
        }
        return strategy;
    }
}
