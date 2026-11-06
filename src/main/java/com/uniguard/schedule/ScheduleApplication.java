package com.uniguard.schedule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
@EnableScheduling
public class ScheduleApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScheduleApplication.class, args);
		System.out.println("ScheduleApplication started successfully.");
	}

}