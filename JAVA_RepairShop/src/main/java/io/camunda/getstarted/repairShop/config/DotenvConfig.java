package io.camunda.getstarted.repairShop.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class DotenvConfig implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger logger = LoggerFactory.getLogger(DotenvConfig.class);

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        // Check if .env file exists
        File dotenvFile = new File(".env");
        if (!dotenvFile.exists()) {
            logger.warn(".env file not found. Skipping environment variable loading from .env");
            return;
        }

        try {
            // Load environment variables from .env file
            Dotenv dotenv = Dotenv.configure().load();

            // Create a map for the environment variables
            Map<String, Object> envMap = new HashMap<>();

            // Add Stripe-related environment variables
            String stripeSecretKey = dotenv.get("STRIPE_SECRET_KEY");
            if (stripeSecretKey != null && !stripeSecretKey.isEmpty()) {
                envMap.put("stripe.api.key", stripeSecretKey);
                logger.info("Loaded Stripe API key from .env file");
            } else {
                logger.warn("STRIPE_SECRET_KEY not found or empty in .env file");
            }

            String stripePublishableKey = dotenv.get("STRIPE_PUBLISHABLE_KEY");
            if (stripePublishableKey != null && !stripePublishableKey.isEmpty()) {
                envMap.put("stripe.api.publishable-key", stripePublishableKey);
                logger.info("Loaded Stripe publishable key from .env file");
            }

            String stripeTestMode = dotenv.get("STRIPE_USE_TEST_MODE");
            if (stripeTestMode != null) {
                envMap.put("stripe.use.test.mode", Boolean.parseBoolean(stripeTestMode));
                logger.info("Loaded Stripe test mode setting from .env file: {}", stripeTestMode);
            }

            // Add the environment variables to the application context
            ConfigurableEnvironment environment = applicationContext.getEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("dotenvProperties", envMap));

            logger.info("Successfully loaded environment variables from .env file");
        } catch (Exception e) {
            logger.error("Error loading .env file", e);
        }
    }
}
