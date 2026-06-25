package com.cubeia.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Entry point for the wallet service.
 */
@SpringBootApplication
public class WalletApplication {

    private static final Logger log = LoggerFactory.getLogger(WalletApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(WalletApplication.class, args);
        Environment env = context.getEnvironment();
        String port = env.getProperty("server.port", "8080");
        log.info("Wallet service started. API secured with OAuth2 JWT bearer tokens.");
        log.info("  Token endpoint : POST http://localhost:{}/oauth/token (client_credentials)", port);
        log.info("  Swagger UI     : http://localhost:{}/swagger-ui.html", port);
    }
}
