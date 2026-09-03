package com.guanghe.fs.framework.common.utils;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON工具类
 *
 * @Author: guangheUlti
 * @Date: 2023/11/22 15:07
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = SpringUtils.getBean(ObjectMapper.class);

    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    public static String toJsonString(Object object) {
        if (ObjectUtil.isNull(object)) {
            return null;
        }
        return OBJECT_MAPPER.writeValueAsString(object);
    }

    public static <T> T parseObject(String text, Class<T> clazz) {
        if (StringUtils.isEmpty(text)) {
            return null;
        }
        return OBJECT_MAPPER.readValue(text, clazz);
    }

    public static <T> T parseObject(byte[] bytes, Class<T> clazz) {
        if (ArrayUtil.isEmpty(bytes)) {
            return null;
        }
        return OBJECT_MAPPER.readValue(bytes, clazz);
    }

    public static <T> T parseObject(String text, TypeReference<T> typeReference) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        return OBJECT_MAPPER.readValue(text, typeReference);
    }

    public static JsonNode parseTree(String text) {
        if (StringUtils.isEmpty(text)) {
            return null;
        }
        return OBJECT_MAPPER.readTree(text);
    }

    public static Dict parseMap(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(text,
                    OBJECT_MAPPER.getTypeFactory().constructType(Dict.class));
        } catch (MismatchedInputException e) {
            // 类型不匹配说明不是json
            return null;
        }
    }

    public static List<Dict> parseArrayMap(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        return OBJECT_MAPPER.readValue(text,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, Dict.class));
    }

    public static <T> List<T> parseArray(String text, Class<T> clazz) {
        if (StringUtils.isEmpty(text)) {
            return new ArrayList<>();
        }
        return OBJECT_MAPPER.readValue(text,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, clazz));
    }

    /**
     * 标准化JSON字符串（用于比较）
     */
    public static String normalizeJson(String jsonStr) {
        try {
            ObjectMapper mapper = JsonMapper.builder()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .build();
            // 配置：排序key、去除空格
            Object obj = mapper.readValue(jsonStr, Object.class);
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return jsonStr;
        }
    }

}
