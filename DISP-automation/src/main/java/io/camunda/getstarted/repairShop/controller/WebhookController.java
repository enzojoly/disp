package io.camunda.getstarted.repairShop.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

import io.camunda.getstarted.repairShop.service.CalendlyService;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private CalendlyService calendlyService;

    /**
     * Test endpoint to simulate a webhook (for development and testing)
     * Since we can't use real webhooks without premium Calendly, this provides
     * a simple way to emulate webhook callbacks for testing the workflow
     */
    @GetMapping("/calendly/simulate")
    public ResponseEntity<Map<String, Object>> simulateWebhook(
            @RequestParam(defaultValue = "customer@example.com") String email,
            @RequestParam(defaultValue = "Test Customer") String name) {

        Map<String, Object> booking = calendlyService.simulateBooking(email, name);
        logger.info("Simulated booking for {}", name);
        return ResponseEntity.ok(booking);
    }
}
