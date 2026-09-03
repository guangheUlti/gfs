package com.guanghe.fs.fsadmin;

import com.guanghe.fs.framework.common.utils.Ip2RegionUtils;
import com.guanghe.fs.framework.common.utils.IpUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FsAdminApplicationTests {

    @Test
    void contextLoads() throws Exception {
        System.out.println(Ip2RegionUtils.search("172.22.166.15"));
    }

}
