package io.camunda.getstarted.repairShop.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

@Service
public class CalendlyService {

    private static final Logger logger = LoggerFactory.getLogger(CalendlyService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${calendly.booking.url:https://calendly.com/your-repair-shop/vehicle-collection}")
    private String bookingUrl;

    /**
     * Creates a Calendly booking link for vehicle collection
     */
    public String createBookingLink(String customerEmail, String customerName, String vehicleInfo, String reason) {
        logger.info("Creating booking link for {} - {}", customerName, reason);

        // Build URL with query parameters for prefilled info
        StringBuilder urlBuilder = new StringBuilder(bookingUrl);
        urlBuilder.append("?email=").append(encode(customerEmail));
        urlBuilder.append("&name=").append(encode(customerName));
        urlBuilder.append("&custom=").append(encode(vehicleInfo));
        urlBuilder.append("&reason=").append(encode(reason));

        String finalUrl = urlBuilder.toString();
        logger.info("Generated booking URL: {}", finalUrl);

        return finalUrl;
    }

    /**
     * Simple URL encoding for parameters
     */
    private String encode(String value) {
        if (value == null) return "";
        return value.replace(" ", "%20");
    }

    /**
     * Process a webhook payload from Calendly
     */
    public Map<String, Object> processWebhook(String payload) {
        try {
            logger.info("Processing Calendly webhook");

            // Parse the webhook payload
            JsonNode root = objectMapper.readTree(payload);

            // Extract the event type
            String eventType = root.path("event").asText();
            logger.info("Webhook event type: {}", eventType);

            // Only process invitee.created events (when someone books an appointment)
            if ("invitee.created".equals(eventType)) {
                Map<String, Object> bookingInfo = new HashMap<>();

                // Extract event data - paths based on Calendly webhook format
                JsonNode payloadNode = root.path("payload");
                JsonNode inviteeNode = payloadNode.path("invitee");
                JsonNode eventNode = payloadNode.path("event");

                // Get customer details
                bookingInfo.put("customerEmail", inviteeNode.path("email").asText());
                bookingInfo.put("customerName", inviteeNode.path("name").asText());

                // Get appointment details
                bookingInfo.put("appointmentTime", eventNode.path("start_time").asText());
                bookingInfo.put("appointmentEndTime", eventNode.path("end_time").asText());
                bookingInfo.put("appointmentLocation", eventNode.path("location").path("location").asText());

                // Confirmation status
                bookingInfo.put("bookingConfirmed", true);
                bookingInfo.put("bookingReference", inviteeNode.path("uri").asText());

                logger.info("Processed booking for {} at {}",
                        bookingInfo.get("customerName"),
                        bookingInfo.get("appointmentTime"));

                return bookingInfo;

            } else {
                logger.warn("Unhandled webhook event type: {}", eventType);
                return Map.of("error", "Unhandled event type: " + eventType);
            }

        } catch (Exception e) {
            logger.error("Error processing webhook payload", e);
            return Map.of("error", "Failed to process webhook: " + e.getMessage());
        }
    }

    /**
     * For testing and cases where no webhook is received yet
     */
    public Map<String, Object> simulateBooking(String customerEmail, String customerName) {
        // This creates a simulated booking confirmation for testing/demo
        Map<String, Object> bookingInfo = new HashMap<>();
        bookingInfo.put("customerEmail", customerEmail);
        bookingInfo.put("customerName", customerName);
        bookingInfo.put("appointmentTime", "2025-03-27T10:00:00Z");
        bookingInfo.put("appointmentEndTime", "2025-03-27T10:30:00Z");
        bookingInfo.put("bookingConfirmed", true);
        bookingInfo.put("bookingReference", "simulated-booking-" + System.currentTimeMillis());

        logger.info("Simulated booking for {} at {}", customerName, bookingInfo.get("appointmentTime"));
        return bookingInfo;
    }
}
