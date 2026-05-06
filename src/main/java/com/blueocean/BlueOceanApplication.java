package com.blueocean;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("com.blueocean.mapper")
public class BlueOceanApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlueOceanApplication.class, args);
    }
}
