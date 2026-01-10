package com.project.chefbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ChefbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChefbotApplication.class, args);
    }

}
