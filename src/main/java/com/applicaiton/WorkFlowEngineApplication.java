package com.applicaiton;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

import lombok.extern.slf4j.Slf4j;

@ComponentScan({"com"})
@EnableFeignClients
@Slf4j
@SpringBootApplication
public class WorkFlowEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(WorkFlowEngineApplication.class, args);
	}

}
