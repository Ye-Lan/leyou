package com.leyou;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.SpringCloudApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;


@SpringCloudApplication
@EnableFeignClients
public class LYSearchService {
    public static void main(String[] args){
        SpringApplication.run(LYSearchService.class,args);
    }
}
