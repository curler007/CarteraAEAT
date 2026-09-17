package com.raul.bolsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** El planificador lo usa EcbFxRateService para pedir al BCE los días nuevos de cada divisa. */
@SpringBootApplication
@EnableScheduling
public class BolsaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BolsaApplication.class, args);
    }
}
