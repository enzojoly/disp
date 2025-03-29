package io.camunda.getstarted.repairShop.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.util.HashMap;
import java.util.Map;

@Service
public class StripeInvoiceService {

    private static final Logger logger = LoggerFactory.getLogger(StripeInvoiceService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stripe.api.key:}")
    private String apiKey;

    @Value("${stripe.api.url:https://api.stripe.com/v1}")
    private String apiUrl;

    @Value("${stripe.use.test.mode:false}")
    private boolean useTestMode;

    /**
     * Creates a Stripe invoice for a customer
     * @param customerEmail Customer email for the invoice
     * @param customerName Customer name for the invoice
     * @param description Description of the service/product
     * @param vehicleDetails Vehicle details to include in the invoice
     * @param amount Total amount to charge
     * @return Map containing invoice information
     */
    public Map<String, Object> generateInvoice(String customerEmail, String customerName,
                                              String description, String vehicleDetails,
                                              double amount) {
        logger.info("Generating Stripe invoice for customer: {}", customerName);
        logger.info("Invoice amount: ${}", String.format("%.2f", amount));
        logger.info("Using Stripe API in {} mode", useTestMode ? "TEST" : "PRODUCTION");

        try {
            // In a real implementation, we would first create or retrieve the customer
            String customerId = createOrRetrieveCustomer(customerEmail, customerName);

            // Then create an invoice item
            String invoiceItemId = createInvoiceItem(customerId, description, vehicleDetails, amount);

            // Finally create and finalize the invoice
            Map<String, Object> invoiceData = createAndFinalizeInvoice(customerId);

            logger.info("Successfully created Stripe invoice with ID: {}", invoiceData.get("id"));

            // Create a structured response
            Map<String, Object> result = new HashMap<>();
            result.put("invoiceId", invoiceData.get("id"));
            result.put("amount", amount);
            result.put("formattedAmount", String.format("%.2f", amount));
            result.put("customerEmail", customerEmail);
            result.put("customerName", customerName);
            result.put("customerId", customerId);
            result.put("description", description);
            result.put("vehicleDetails", vehicleDetails);
            result.put("invoiceUrl", invoiceData.get("hosted_invoice_url"));
            result.put("invoicePdf", invoiceData.get("invoice_pdf"));
            result.put("status", invoiceData.get("status"));
            result.put("createdAt", System.currentTimeMillis());

            return result;
        } catch (Exception e) {
            logger.error("Error creating Stripe invoice", e);

            // Return error information but still provide a usable object
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", true);
            errorResult.put("errorMessage", e.getMessage());
            errorResult.put("customerEmail", customerEmail);
            errorResult.put("customerName", customerName);
            errorResult.put("amount", amount);
            errorResult.put("formattedAmount", String.format("%.2f", amount));

            // Generate a fallback invoice ID so the process can continue
            String fallbackId = "error_inv_" + System.currentTimeMillis();
            errorResult.put("invoiceId", fallbackId);
            errorResult.put("status", "error");
            errorResult.put("createdAt", System.currentTimeMillis());

            return errorResult;
        }
    }

    /**
     * Create or retrieve a Stripe customer
     */
    private String createOrRetrieveCustomer(String email, String name) throws Exception {
        // Prepare customer data
        ObjectNode customerData = objectMapper.createObjectNode();
        customerData.put("email", email);
        customerData.put("name", name);

        // Call Stripe API to create customer
        String responseBody = callStripeApi("/customers", customerData);

        // Use TypeReference to avoid unchecked conversion warning
        Map<String, Object> response = objectMapper.readValue(responseBody,
                                        new TypeReference<Map<String, Object>>() {});

        return (String) response.get("id");
    }

    /**
     * Create an invoice item for the customer
     */
    private String createInvoiceItem(String customerId, String description, String vehicleDetails, double amount) throws Exception {
        // Add vehicle details to description if available
        String fullDescription = description;
        if (vehicleDetails != null && !vehicleDetails.isEmpty()) {
            fullDescription += " - " + vehicleDetails;
        }

        // Prepare invoice item data
        ObjectNode itemData = objectMapper.createObjectNode();
        itemData.put("customer", customerId);
        itemData.put("description", fullDescription);
        itemData.put("amount", Math.round(amount * 100)); // Stripe uses cents
        itemData.put("currency", "usd");

        // Call Stripe API to create invoice item
        String responseBody = callStripeApi("/invoiceitems", itemData);

        // Use TypeReference to avoid unchecked conversion warning
        Map<String, Object> response = objectMapper.readValue(responseBody,
                                        new TypeReference<Map<String, Object>>() {});

        return (String) response.get("id");
    }

    /**
     * Create and finalize an invoice for the customer
     */
    private Map<String, Object> createAndFinalizeInvoice(String customerId) throws Exception {
        // Prepare invoice data
        ObjectNode invoiceData = objectMapper.createObjectNode();
        invoiceData.put("customer", customerId);
        invoiceData.put("auto_advance", true); // Automatically finalize and send the invoice
        invoiceData.put("collection_method", "send_invoice"); // Ensure it's set to send the invoice

        // Call Stripe API to create invoice
        String responseBody = callStripeApi("/invoices", invoiceData);

        // Use TypeReference to avoid unchecked conversion warning
        Map<String, Object> invoice = objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});

        // Explicitly send the invoice (this ensures the email is sent when not in test mode)
        String invoiceId = (String) invoice.get("id");
        if (invoiceId != null) {
            try {
                String sendResponseBody = callStripeApi("/invoices/" + invoiceId + "/send", objectMapper.createObjectNode());
                // Update our invoice object with the latest data
                invoice = objectMapper.readValue(sendResponseBody, new TypeReference<Map<String, Object>>() {});
                logger.info("Invoice email sent successfully to customer");
            } catch (Exception e) {
                // Log but don't fail the process if sending fails
                logger.warn("Failed to explicitly send invoice email: {}", e.getMessage());
            }
        }

        return invoice;
    }

    /**
     * Call the Stripe API
     */
    private String callStripeApi(String endpoint, ObjectNode data) throws Exception {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(apiUrl + endpoint);

            // Set headers
            request.setHeader("Authorization", "Bearer " + apiKey);
            request.setHeader("Content-Type", "application/json");

            // Set request body
            String jsonBody = objectMapper.writeValueAsString(data);
            request.setEntity(new StringEntity(jsonBody));

            logger.debug("Calling Stripe API: {} with data: {}", endpoint, jsonBody);

            // Execute request
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                logger.debug("Stripe API response ({}): {}", statusCode, responseBody);

                if (statusCode >= 200 && statusCode < 300) {
                    return responseBody;
                } else {
                    throw new Exception("Stripe API error (status " + statusCode + "): " + responseBody);
                }
            }
        }
    }

    /**
     * For testing and development purposes when Stripe API is not available
     */
    public Map<String, Object> createTestInvoice(String customerEmail, String customerName,
                                                String description, String vehicleDetails,
                                                double amount) {
        // Create a simulated invoice for testing/development
        Map<String, Object> invoiceInfo = new HashMap<>();

        // Generate realistic-looking test IDs
        String customerId = "cus_" + generateRandomString(14);
        String invoiceId = "in_" + generateRandomString(14);

        invoiceInfo.put("invoiceId", invoiceId);
        invoiceInfo.put("customerId", customerId);
        invoiceInfo.put("customerEmail", customerEmail);
        invoiceInfo.put("customerName", customerName);
        invoiceInfo.put("description", description);
        invoiceInfo.put("vehicleDetails", vehicleDetails);
        invoiceInfo.put("amount", amount);
        invoiceInfo.put("formattedAmount", String.format("%.2f", amount));
        invoiceInfo.put("status", "paid");
        invoiceInfo.put("invoiceUrl", "https://dashboard.stripe.com/test/invoices/" + invoiceId);
        invoiceInfo.put("invoicePdf", "https://dashboard.stripe.com/test/invoices/" + invoiceId + "/pdf");
        invoiceInfo.put("createdAt", System.currentTimeMillis());

        logger.info("Created test invoice #{} for {} with amount ${}",
                    invoiceId, customerName, String.format("%.2f", amount));
        return invoiceInfo;
    }

    /**
     * Generate a random alphanumeric string for test IDs
     */
    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int index = (int) (chars.length() * Math.random());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Check if we are operating in test mode
     * @return true if using test mode, false if production mode
     */
    public boolean isUsingTestMode() {
        return useTestMode;
    }
}
