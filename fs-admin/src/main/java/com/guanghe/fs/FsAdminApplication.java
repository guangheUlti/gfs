package com.guanghe.fs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FsAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(FsAdminApplication.class, args);

        System.out.println("""
                  _____ _    _  _____ _____ ______  _____ _____\s
                 / ____| |  | |/ ____/ ____|  ____|/ ____/ ____|
                | (___ | |  | | |   | |    | |__  | (___| (___ \s
                 \\___ \\| |  | | |   | |    |  __|  \\___ \\\\___ \\\s
                 ____) | |__| | |___| |____| |____ ____) |___) |
                |_____/ \\____/ \\_____\\_____|______|_____/_____/\s
                """);

    }
}
