package io.camunda.getstarted.repairShop.controller;

import io.camunda.getstarted.repairShop.service.CalendlyService;
import io.camunda.zeebe.client.ZeebeClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private CalendlyService calendlyService;

    @Autowired
    private ZeebeClient zeebeClient;

    /**
     * Endpoint that receives Calendly webhooks
     */
    @PostMapping("/calendly")
    public ResponseEntity<Map<String, Object>> handleCalendlyWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Calendly-Signature") String signature) {

        logger.info("Received webhook from Calendly");

        // Validate the webhook signature (for enhanced security)
        // In a production app, you would verify this signature with a shared secret

        // Process the webhook payload
        Map<String, Object> bookingInfo = calendlyService.processWebhook(payload);

        // For a real implementation, you would:
        // 1. Find the right process instance that needs this booking confirmation
        // 2. Send a message to that process instance
        // But for our demo, we'll use the correlation key in the webhook

        if (bookingInfo.containsKey("bookingConfirmed") &&
            (Boolean)bookingInfo.get("bookingConfirmed") &&
            bookingInfo.containsKey("customerEmail")) {

            String customerEmail = (String) bookingInfo.get("customerEmail");

            try {
                // Send a message to correlate this booking with the process
                zeebeClient.newPublishMessageCommand()
                    .messageName("BOOKING_CONFIRMED")
                    .correlationKey(customerEmail)
                    .variables(bookingInfo)
                    .send();

                logger.info("Published booking confirmation message for {}", customerEmail);
            } catch (Exception e) {
                logger.error("Failed to publish message: {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(bookingInfo);
    }

    /**
     * Test endpoint to simulate a webhook (for development)
     */
    @GetMapping("/calendly/simulate")
    public ResponseEntity<Map<String, Object>> simulateWebhook(
            @RequestParam(defaultValue = "customer@example.com") String email,
            @RequestParam(defaultValue = "Test Customer") String name) {

        Map<String, Object> booking = calendlyService.simulateBooking(email, name);

        return ResponseEntity.ok(booking);
    }
}
