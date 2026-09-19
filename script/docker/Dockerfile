# 使用 Ubuntu 24.04 作为基础镜像
FROM ubuntu:24.04

RUN sed -i 's@//.*archive.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources && \
    sed -i 's@//security.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources && \
    apt-get update && \
    export DEBIAN_FRONTEND=noninteractive && \
    apt-get install -y --no-install-recommends \
        openjdk-21-jre \
        tzdata \
        locales \
        xfonts-utils \
        fontconfig \
        fonts-wqy-microhei \
        fonts-wqy-zenhei \
        xfonts-wqy && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    locale-gen zh_CN.UTF-8 && \
    apt-get autoremove -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 设置环境变量
ENV LANG=zh_CN.UTF-8 \
    LC_ALL=zh_CN.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 挂载点（大文件流式交付建议 /tmp 有足够空间）
VOLUME /tmp

# 6. 处理自定义字体（防止转换 PDF 时中文字体缺失）
# 确保 script/docker/fonts 文件夹下存有 simsun.ttc, msyh.ttc 等
COPY script/docker/fonts/* /usr/share/fonts/chinese/

RUN if [ -d "/usr/share/fonts/chinese" ]; then \
        cd /usr/share/fonts/chinese && \
        mkfontscale && \
        mkfontdir && \
        fc-cache -fv; \
    fi

# 复制项目 Jar 包
COPY fs-admin/target/fs-admin.jar app.jar

EXPOSE 80

ENTRYPOINT ["java", "-jar", "/app.jar", "--spring.profiles.active=docker"]