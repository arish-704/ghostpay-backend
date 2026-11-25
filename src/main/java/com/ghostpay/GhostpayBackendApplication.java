package com.ghostpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GhostpayBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(GhostpayBackendApplication.class, args);
	}

}
