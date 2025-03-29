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
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

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

    @Value("${stripe.currency:gbp}")
    private String currency;

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
        logger.info("Invoice amount: {}{}",
            currency.equalsIgnoreCase("gbp") ? "£" : currency.equalsIgnoreCase("usd") ? "$" : currency + " ",
            String.format("%.2f", amount));
        logger.info("Using Stripe API in {} mode", useTestMode ? "TEST" : "PRODUCTION");

        try {
            // Create or retrieve the customer
            String customerId = createOrRetrieveCustomer(customerEmail, customerName);
            logger.info("Using customer with ID: {}", customerId);

            // Create an invoice item - this is what was missing in your original code!
            // The invoice item needs to exist before creating the invoice
            String invoiceItemId = createInvoiceItem(customerId, description, vehicleDetails, amount);
            logger.info("Created invoice item with ID: {}", invoiceItemId);

            // Now create and finalize the invoice
            Map<String, Object> invoiceData = createAndFinalizeInvoice(customerId);

            String invoiceId = (String) invoiceData.get("id");
            logger.info("Successfully created Stripe invoice with ID: {}", invoiceId);

            // Create a structured response
            Map<String, Object> result = new HashMap<>();
            result.put("invoiceId", invoiceId);
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
            result.put("currency", currency.toLowerCase());

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
            errorResult.put("currency", currency.toLowerCase());

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

        logger.info("Creating/retrieving customer with email: {}, name: {}", email, name);

        // Call Stripe API to create customer
        String responseBody = callStripeApi("/customers", customerData);

        // Debug log the response
        logger.info("Stripe customer response: {}", responseBody);

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

        // Debug log before conversion
        logger.info("Amount before conversion: {}", amount);

        // Convert to cents/pence (Stripe's smallest unit)
        long amountInSmallestUnit = Math.round(amount * 100);

        // Debug log after conversion
        logger.info("Amount after conversion to smallest unit: {}", amountInSmallestUnit);

        itemData.put("amount", amountInSmallestUnit);
        itemData.put("currency", currency.toLowerCase());

        // Debug log the complete item data
        logger.info("Invoice item data being sent to Stripe: {}", itemData.toString());

        // Call Stripe API to create invoice item
        String responseBody = callStripeApi("/invoiceitems", itemData);

        // Debug log the response
        logger.info("Stripe invoice item response: {}", responseBody);

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
        invoiceData.put("collection_method", "send_invoice");
        invoiceData.put("days_until_due", 0);
        invoiceData.put("auto_advance", true);
        invoiceData.put("footer", ""); // Use default branding

        logger.info("Creating invoice for customer: {}", customerId);

        // Call Stripe API to create invoice
        String responseBody = callStripeApi("/invoices", invoiceData);

        logger.info("Stripe invoice response: {}", responseBody);

        // Parse the response
        Map<String, Object> invoice = objectMapper.readValue(responseBody,
                                     new TypeReference<Map<String, Object>>() {});

        // Get the invoice ID
        String invoiceId = (String) invoice.get("id");
        logger.info("Created invoice with ID: {}", invoiceId);

        if (invoiceId != null) {
            // Finalize the invoice explicitly
            ObjectNode finalizeData = objectMapper.createObjectNode();
            logger.info("Finalizing invoice with ID: {}", invoiceId);

            String finalizeResponseBody = callStripeApi("/invoices/" + invoiceId + "/finalize", finalizeData);
            logger.info("Finalize invoice response: {}", finalizeResponseBody);

            invoice = objectMapper.readValue(finalizeResponseBody,
                                        new TypeReference<Map<String, Object>>() {});
            logger.info("Finalized invoice with ID: {}", invoiceId);

            // Now send the invoice via email
            try {
                ObjectNode sendData = objectMapper.createObjectNode();
                logger.info("Sending invoice email to customer for invoice: {}", invoiceId);

                String sendResponseBody = callStripeApi("/invoices/" + invoiceId + "/send", sendData);
                logger.info("Send invoice response: {}", sendResponseBody);

                invoice = objectMapper.readValue(sendResponseBody,
                                            new TypeReference<Map<String, Object>>() {});
                logger.info("Invoice email sent successfully to customer");
            } catch (Exception e) {
                logger.warn("Failed to send invoice email: {}", e.getMessage(), e);
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
            request.setHeader("Content-Type", "application/x-www-form-urlencoded");

            // Convert ObjectNode to form parameters
            List<NameValuePair> params = new ArrayList<>();
            Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = data.fields();
            while (fields.hasNext()) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = fields.next();
                params.add(new BasicNameValuePair(entry.getKey(), entry.getValue().asText()));
            }

            // Set request body as URL-encoded form parameters
            request.setEntity(new UrlEncodedFormEntity(params));

            // Improved detailed logging
            StringBuilder paramsLog = new StringBuilder();
            for (NameValuePair param : params) {
                paramsLog.append(param.getName()).append("=").append(param.getValue()).append(", ");
            }
            logger.info("Calling Stripe API: {} with data: {}", endpoint, paramsLog.toString());

            // Execute request
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                logger.info("Stripe API response status: {}", statusCode);
                logger.info("Stripe API response body: {}", responseBody);

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
        invoiceInfo.put("currency", currency.toLowerCase());

        String currencySymbol = currency.equalsIgnoreCase("gbp") ? "£" :
                               currency.equalsIgnoreCase("usd") ? "$" :
                               currency + " ";

        logger.info("Created test invoice #{} for {} with amount {}{}",
                  invoiceId, customerName, currencySymbol, String.format("%.2f", amount));
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
