package ru.indeece.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Запускает приложение Checkout и регистрацию его компонентов Spring.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CheckoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckoutApplication.class, args);
    }
}
