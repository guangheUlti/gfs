package com.guanghe.fs.storage.domain.vo;

import com.guanghe.fs.storage.domain.StoragePlatform;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@AutoMapper(target = StoragePlatform.class)
public class StoragePlatformVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String identifier;
    private String configScheme;
    private String icon;
    private String link;
    private String desc;
    private Integer isDefault;
}
