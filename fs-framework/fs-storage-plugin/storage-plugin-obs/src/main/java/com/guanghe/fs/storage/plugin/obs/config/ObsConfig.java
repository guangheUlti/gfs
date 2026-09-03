package com.guanghe.fs.storage.plugin.obs.config;

import lombok.Data;

@Data
public class ObsConfig {

    private String endpoint;

    private String accessKey;

    private String secretKey;

    private String bucket;
}
