package com.example.emulator;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class EmulatorApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.configure().filename("prod.env").load();
		dotenv.entries().forEach(entry -> {
			System.setProperty(entry.getKey(), entry.getValue());
		});
		SpringApplication.run(EmulatorApplication.class, args);
	}
}
