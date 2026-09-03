package com.guanghe.fs.framework.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分页实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否成功：200 成功
     */
    private int code;

    /**
     * 当前页结果集
     */
    private PageRecord<T> data;


    @Data
    public static class PageRecord<T> {

        /**
         * 当前页结果集
         */
        private List<T> records;

        /**
         * 总数
         */
        private Long total;
    }

    public static <T> PageResult<T> success(List<T> data, Long total) {
        PageResult.PageRecord<T> pageRecord = new PageResult.PageRecord<>();
        pageRecord.setRecords(data);
        pageRecord.setTotal(total);
        return success(pageRecord);
    }

    public static <T> PageResult<T> success(PageRecord<T> data) {
        return PageResult.<T>builder().code(200).data(data).build();
    }
}
