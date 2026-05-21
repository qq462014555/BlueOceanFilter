package com.blueocean.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String BASE_DIR = "file:C:/Users/46201/Documents/无极RPA文件处理/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/rpa-files/**")
                .addResourceLocations(BASE_DIR)
                .setCachePeriod(0)
                .resourceChain(true);
    }
}
