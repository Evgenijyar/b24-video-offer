package ru.abs7.videooffer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class B24VideoOfferApplication {
    public static void main(String[] args) {
        SpringApplication.run(B24VideoOfferApplication.class, args);
    }
}
