package com.guanghe.fs.storage.plugin.obs.enums;

import lombok.Getter;

@Getter
public enum Regions {

    af_north_1("af-north-1", "非洲-开罗", "obs.af-north-1.myhuaweicloud.com"),
    af_south_2("af-south-1", "非洲-约翰内斯堡", "obs.af-south-1.myhuaweicloud.com"),
    ap_southeast_1("ap-southeast-1", "中国-香港", "obs.ap-southeast-1.myhuaweicloud.com"),
    ap_southeast_2("ap-southeast-2", "亚太-曼谷", "obs.ap-southeast-2.myhuaweicloud.com"),
    ap_southeast_3("ap-southeast-3", "亚太-新加坡", "obs.ap-southeast-3.myhuaweicloud.com"),
    ap_southeast_4("ap-southeast-4", "亚太-雅加达", "obs.ap-southeast-4.myhuaweicloud.com"),
    ap_southeast_5("ap-southeast-5", "亚太-马尼拉", "obs.ap-southeast-5.myhuaweicloud.com"),
    cn_east_2("cn-east-2", "华东-上海二", "obs.cn-east-2.myhuaweicloud.com"),
    cn_east_3("cn-east-3", "华东-上海一", "obs.cn-east-3.myhuaweicloud.com"),
    cn_east_4("cn-east-4", "华东", "obs.cn-east-4.myhuaweicloud.com");

    private final String region;
    private final String regionName;
    private final String endpoint;

    Regions(String region, String regionName, String endpoint) {
        this.region = region;
        this.regionName = regionName;
        this.endpoint = endpoint;
    }
}
