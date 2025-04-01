package io.camunda.getstarted.repairShop;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.getstarted.repairShop.service.CalendlyService;
import io.camunda.getstarted.repairShop.service.MembershipCheckService;
import io.camunda.getstarted.repairShop.service.StripeInvoiceService;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.EnableZeebeClient;
import io.camunda.zeebe.spring.client.annotation.ZeebeWorker;
import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
@EnableZeebeClient
public class Worker {

    private static final Logger logger = LoggerFactory.getLogger(Worker.class);

    @Autowired
    private CalendlyService calendlyService;

    @Autowired
    private StripeInvoiceService stripeInvoiceService;

    @Autowired
    private MembershipCheckService membershipCheckService;

    @Autowired
    private ZeebeClient zeebeClient;

    /**
     * Constants for process variables to ensure consistency
     */
    public static class ProcessVariables {
        // Customer info
        public static final String CUSTOMER_NAME = "customerName";
        public static final String CUSTOMER_EMAIL = "customerEmail";

        // Vehicle info
        public static final String VEHICLE_MAKE = "VehicleMake";
        public static final String VEHICLE_MODEL = "VehicleModel";
        public static final String FAULT_DESCRIPTION = "DescriptionOfFault";
        public static final String BREAKDOWN_LOCATION = "breakdownLocation";

        // Process state
        public static final String IS_MEMBER = "isMember";
        public static final String QUOTE_APPROVED = "QuoteApproved";
        public static final String CUSTOMER_SATISFIED = "CustomerSatisfied";

        // Cost info
        public static final String DEPOSIT_AMOUNT = "depositAmount";
        public static final String FINAL_PRICE = "finalPrice";
        public static final String TOTAL_PRICE = "TotalPrice";
        public static final String INITIAL_COST_RECEIVED = "initialCostReceived";
        public static final String REPAIR_COST = "repairCost";
        public static final String REPAIR_COSTS = "RepairCosts"; // Added to handle form input

        // Process instance key for message correlation
        public static final String PROCESS_INSTANCE_KEY = "processInstanceKey";
    }

    /**
     * Constants for message names - ensure these match the BPMN message names
     * exactly
     */
    public static class MessageNames {
        // Use the exact message names from your BPMN file
        public static final String RECEIVE_INITIAL_COST = "ReceiveInitialCost";
        public static final String TOW_REQUEST = "TowingRequest";
        public static final String APPROVAL = "Approval";
        public static final String WORKS_COMPLETE = "WorksComplete";
        public static final String QUOTE_NOTIFICATION = "QuoteNotification";
        public static final String COLLECTION_ARRANGED = "CollectionArranged";
        public static final String INVOICE_GENERATED = "InvoiceGenerated";
    }

    public static void main(String[] args) {
        // This explicitly loads the DotenvConfig class before Spring Boot starts
        try {
            File dotenvFile = new File(".env");
            if (dotenvFile.exists()) {
                logger.info("Found .env file. Loading environment variables");

                // Load variables from .env file using the dotenv library
                Dotenv dotenv = Dotenv.configure().load();

                // Manually set system properties from .env
                if (dotenv.get("STRIPE_SECRET_KEY") != null) {
                    System.setProperty("STRIPE_SECRET_KEY", dotenv.get("STRIPE_SECRET_KEY"));
                    logger.info("Set STRIPE_SECRET_KEY from .env file");
                }

                if (dotenv.get("STRIPE_PUBLISHABLE_KEY") != null) {
                    System.setProperty("STRIPE_PUBLISHABLE_KEY", dotenv.get("STRIPE_PUBLISHABLE_KEY"));
                    logger.info("Set STRIPE_PUBLISHABLE_KEY from .env file");
                }

                if (dotenv.get("STRIPE_USE_TEST_MODE") != null) {
                    System.setProperty("STRIPE_USE_TEST_MODE", dotenv.get("STRIPE_USE_TEST_MODE"));
                    logger.info("Set STRIPE_USE_TEST_MODE from .env file");
                }

                if (dotenv.get("MEMBERSHIP_FILE_PATH") != null) {
                    System.setProperty("membership.data.file-path", dotenv.get("MEMBERSHIP_FILE_PATH"));
                    logger.info("Set membership.data.file-path from .env file");
                }
            } else {
                logger.warn(".env file not found. Will use system environment variables if available");
            }
        } catch (Exception e) {
            logger.error("Error loading .env file", e);
        }

        // Start the Spring Boot application
        SpringApplication.run(Worker.class, args);
    }

    /**
     * Helper method to send a message to a specific process instance
     *
     * @param messageName    The name of the message
     * @param correlationKey The process instance key to correlate with
     * @param variables      The variables to include in the message
     */
    private void sendMessage(String messageName, String correlationKey, Map<String, Object> variables) {
        try {
            logger.info("Sending message '{}' with correlation key '{}'", messageName, correlationKey);

            zeebeClient.newPublishMessageCommand()
                    .messageName(messageName)
                    .correlationKey(correlationKey)
                    .variables(variables)
                    .send()
                    .join();

            logger.info("Message '{}' sent successfully", messageName);
        } catch (Exception e) {
            logger.error("Failed to send message '{}': {}", messageName, e.getMessage(), e);
            throw new RuntimeException("Failed to send message: " + e.getMessage(), e);
        }
    }

@ZeebeWorker(type = "CheckMembership")
public void checkMembership(final JobClient client, final ActivatedJob job) {
    Map<String, Object> variables = job.getVariablesAsMap();

    try {
        String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");
        boolean isExistingMember = Boolean.TRUE.equals(variables.get("SignedUp"));
        boolean wantsToBeMember = Boolean.TRUE.equals(variables.get("SigningUp"));

        logger.info("Processing membership for customer: {}", customerName);
        logger.info("Is existing member: {}, Wants to be member: {}", isExistingMember, wantsToBeMember);

        // CRITICAL CHANGE: If SigningUp is true, MemberCheck is ALWAYS true
        // This is the ONLY logic that matters in this method
        boolean memberCheckResult = wantsToBeMember ||
                                   (!isExistingMember) ||
                                   (isExistingMember && membershipCheckService.validateMembershipNumber(getStringValue(variables, "MembershipNumber")));

        HashMap<String, Object> resultVariables = new HashMap<>();
        resultVariables.put("MemberCheck", memberCheckResult);

        if (wantsToBeMember) {
            String newMembershipNumber = membershipCheckService.generateMembershipNumber();
            boolean addSuccess = membershipCheckService.addNewMember(newMembershipNumber, customerName);

            resultVariables.put(ProcessVariables.IS_MEMBER, addSuccess);
            resultVariables.put("MembershipNumber", newMembershipNumber);
            resultVariables.put("SignedUp", false);
            resultVariables.put("SigningUp", true);
        } else if (isExistingMember && membershipCheckService.validateMembershipNumber(getStringValue(variables, "MembershipNumber"))) {
            resultVariables.put(ProcessVariables.IS_MEMBER, true);
            resultVariables.put("SignedUp", true);
            resultVariables.put("SigningUp", false);
        } else {
            resultVariables.put(ProcessVariables.IS_MEMBER, false);
            resultVariables.put("SignedUp", false);
            resultVariables.put("SigningUp", false);
        }

        logger.info("MemberCheck result: {}", memberCheckResult);

        preserveCustomerAndVehicleInfo(variables, resultVariables);

        client.newCompleteCommand(job.getKey())
              .variables(resultVariables)
              .send()
              .exceptionally(throwable -> {
                  logger.error("Failed to complete membership check", throwable);
                  throw new RuntimeException("Could not complete membership check", throwable);
              });
    } catch (Exception e) {
        logger.error("Error checking membership", e);
        client.newFailCommand(job.getKey())
              .retries(job.getRetries() - 1)
              .errorMessage("Error checking membership: " + e.getMessage())
              .send();
    }
}

    /**
     * Helper method to preserve customer and vehicle information
     */
    private void preserveCustomerAndVehicleInfo(Map<String, Object> variables, Map<String, Object> resultVariables) {
        // Vehicle information
        String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make",
                "VehicleMake");
        String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model",
                "VehicleModel");
        String faultDescription = getStringValue(variables, ProcessVariables.FAULT_DESCRIPTION, "extraDetails",
                "faultDescription", "description");
        String breakdownLocation = getStringValue(variables, ProcessVariables.BREAKDOWN_LOCATION, "VehicleLocation",
                "location");

        if (vehicleMake != null)
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
        if (vehicleModel != null)
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
        if (faultDescription != null)
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);
        if (breakdownLocation != null)
            resultVariables.put(ProcessVariables.BREAKDOWN_LOCATION, breakdownLocation);

        // Also preserve with original field names
        if (variables.containsKey("vehicleMake"))
            resultVariables.put("vehicleMake", vehicleMake);
        if (variables.containsKey("vehicleModel"))
            resultVariables.put("vehicleModel", vehicleModel);
        if (variables.containsKey("faultDescription"))
            resultVariables.put("faultDescription", faultDescription);
        if (variables.containsKey("VehicleLocation"))
            resultVariables.put("VehicleLocation", breakdownLocation);
        if (variables.containsKey("extraDetails"))
            resultVariables.put("extraDetails", faultDescription);

        // Customer information
        String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");
        String customerEmail = getStringValue(variables, ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email");

        if (customerName != null)
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
        if (customerEmail != null)
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

        // Also preserve with original field names
        if (variables.containsKey("CustomerName"))
            resultVariables.put("CustomerName", customerName);
        if (variables.containsKey("CustomerEmail"))
            resultVariables.put("CustomerEmail", customerEmail);

        // Preserve breakdown status if available
        if (variables.containsKey("Breakdown")) {
            resultVariables.put("Breakdown", variables.get("Breakdown"));
        }

        // Preserve towing information
        String towInfoAdditional = getStringValue(variables, "towInfoAdditional", "extraInfo");
        if (towInfoAdditional != null)
            resultVariables.put("extraInfo", towInfoAdditional);
    }

    /**
     * Worker to generate a Stripe invoice for the final amount
     */
    @ZeebeWorker(type = "stripe-invoice")
    public void generateStripeInvoice(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Log variables for debugging
            logger.info("stripe-invoice worker received variables: {}", variables.keySet());

            // Get the final price from process variables - prioritize different variations
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COST, ProcessVariables.REPAIR_COSTS,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            // Ensure we have a valid positive amount
            if (finalPrice < 0.01 && repairCost > 0) {
                finalPrice = repairCost;
                logger.info("Using repair cost as final price: £{}", String.format("%.2f", finalPrice));
            }

            // Set TotalPrice to be the same as finalPrice
            double totalPrice = finalPrice;

            // Format price with 2 decimal places for display
            String formattedPrice = String.format("%.2f", finalPrice);

            // Get customer and vehicle information
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", "Customer");
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", "customer@example.com");
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");
            String faultDescription = getStringValue(variables,
                    ProcessVariables.FAULT_DESCRIPTION, "extraDetails", "faultDescription", "Repair services");

            // Prepare vehicle details and description for the invoice
            String vehicleDetails = vehicleMake + " " + vehicleModel;
            String serviceDescription = "Auto Repair Services";
            if (faultDescription != null && !faultDescription.isEmpty()) {
                serviceDescription += " - " + faultDescription;
            }

            logger.info("Generating invoice for customer: {}", customerName);
            logger.info("Vehicle: {}", vehicleDetails);
            logger.info("Amount: £{}", formattedPrice);

            // Call the Stripe service to generate the actual invoice
            Map<String, Object> invoiceResult;

            // Use test mode setting from the service (comes from configuration)
            boolean useTestMode = stripeInvoiceService.isUsingTestMode();
            logger.info("Using Stripe in {} mode", useTestMode ? "TEST" : "PRODUCTION");

            if (useTestMode) {
                invoiceResult = stripeInvoiceService.createTestInvoice(
                        customerEmail,
                        customerName,
                        serviceDescription,
                        vehicleDetails,
                        finalPrice);
            } else {
                invoiceResult = stripeInvoiceService.generateInvoice(
                        customerEmail,
                        customerName,
                        serviceDescription,
                        vehicleDetails,
                        finalPrice);
            }

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Prepare result variables
            HashMap<String, Object> resultVariables = new HashMap<>();

            // Add invoice data
            resultVariables.put("invoiceGenerated", true);
            resultVariables.put("invoiceId", invoiceResult.get("invoiceId"));
            resultVariables.put("invoiceUrl", invoiceResult.get("invoiceUrl"));
            resultVariables.put("invoicePdf", invoiceResult.get("invoicePdf"));
            resultVariables.put("invoiceStatus", invoiceResult.get("status"));
            resultVariables.put("invoiceTimestamp", invoiceResult.get("createdAt"));

            // Add price information
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, totalPrice);
            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put("formattedInvoiceAmount", formattedPrice);

            // Preserve important customer and vehicle data
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve membership number if present
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Message variables for potential message subscribers
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put("invoiceId", invoiceResult.get("invoiceId"));
            messageVariables.put("invoiceGenerated", true);
            messageVariables.put("invoiceUrl", invoiceResult.get("invoiceUrl"));
            messageVariables.put("invoiceAmount", formattedPrice);
            messageVariables.put("invoiceTimestamp", invoiceResult.get("createdAt"));

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send a message that the invoice has been generated
            sendMessage(MessageNames.INVOICE_GENERATED, processInstanceKey, messageVariables);

            logger.info("Invoice generation completed successfully. Invoice ID: {}", invoiceResult.get("invoiceId"));

        } catch (Exception e) {
            logger.error("Error generating Stripe invoice", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error generating Stripe invoice: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to inform customer of initial costs
     */
    @ZeebeWorker(type = "inform-customer-init-cost")
    public void informCustomerInitialCost(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Log variables for debugging
            logger.info("inform-customer-init-cost received variables: {}", variables.keySet());

            // Get deposit amount
            double deposit = ((Number) variables.getOrDefault(ProcessVariables.DEPOSIT_AMOUNT, 0.0)).doubleValue();

            // Extract vehicle and customer information using helper method
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");
            String customerEmail = getStringValue(variables, ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email");
            String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");

            logger.info("Informing customer {} of initial cost £{} for vehicle {} {}",
                    customerName, String.format("%.2f", deposit), vehicleMake, vehicleModel);

            // In a real implementation, this would send an actual notification to the
            // customer
            // For demo, we'll just log it

            // Prepare variables that need to be passed forward
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("initialCostNotified", true);
            resultVariables.put("initialCostTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            resultVariables.put(ProcessVariables.DEPOSIT_AMOUNT, deposit);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Keep original membership fields for backward compatibility
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // The process instance key is used as correlation key for the message
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Store the process instance key as a process variable
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Create message variables - these will be available to the receive task
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put(ProcessVariables.DEPOSIT_AMOUNT, deposit);
            messageVariables.put("paymentTimestamp", System.currentTimeMillis());
            messageVariables.put(ProcessVariables.INITIAL_COST_RECEIVED, true);

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send a message to simulate customer payment (for demo purposes)
            // In a real implementation, this would be triggered by an actual payment event
            logger.info("Simulating payment receipt by sending message for process instance: {}", processInstanceKey);
            sendMessage(MessageNames.RECEIVE_INITIAL_COST, processInstanceKey, messageVariables);

        } catch (Exception e) {
            logger.error("Error in inform-customer-init-cost", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error informing customer of initial cost: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to notify receptionist of repair costs
     */
    @ZeebeWorker(type = "notify-reception-costing")
    public void notifyReceptionOfCosts(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Get repair cost - check both possible variable names
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COST, ProcessVariables.REPAIR_COSTS,
                    500.0);

            // Get vehicle information
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");
            String faultDescription = getStringValue(variables,
                    ProcessVariables.FAULT_DESCRIPTION, "extraDetails", "faultDescription");

            logger.info("Notifying reception of repair costs for {} {}: £{}",
                    vehicleMake, vehicleModel, String.format("%.2f", repairCost));
            logger.info("Fault description: {}", faultDescription);

            // Get the process instance key for correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost); // Set both variable names
            resultVariables.put("repairCostingNotified", true);
            resultVariables.put("costingTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve important information
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);

            // Preserve customer information
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email");

            if (customerName != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            }
            if (customerEmail != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            }

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve membership number if present
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Create message variables for potential receivers
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            messageVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            messageVariables.put("notifiedTimestamp", System.currentTimeMillis());
            messageVariables.put("vehicleDetails", vehicleMake + " " + vehicleModel);

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send the message (if there are message-waiting tasks for this)
            // This is optional depending on your BPMN design
            // sendMessage("RepairCostNotification", processInstanceKey, messageVariables);

        } catch (Exception e) {
            logger.error("Error in notify-reception-costing worker", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error notifying reception of costs: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to process tow request from the initial form
     */
    @ZeebeWorker(type = "process-tow-request")
    public void processTowRequest(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Log all received variables to help with debugging
            logger.info("Received variables: {}", variables.keySet());

            // Extract vehicle information - handle all possible field names
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");

            // Get fault description - try all possible variable names
            String faultDescription = getStringValue(variables,
                    ProcessVariables.FAULT_DESCRIPTION, "extraDetails", "faultDescription", "description");

            // Get breakdown location - try all possible variable names
            String breakdownLocation = getStringValue(variables,
                    ProcessVariables.BREAKDOWN_LOCATION, "VehicleLocation", "location");

            // Get additional towing information - try all possible variable names
            String towInfoAdditional = getStringValue(variables,
                    "towInfoAdditional", "extraInfo", "additionalInfo", "towInfo");

            // Get membership status - try all possible variable names
            // Check multiple fields for membership status
            boolean isMember = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp"));
            boolean becomeMember = Boolean.TRUE.equals(variables.get("becomeMember")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            boolean membershipStatus = isMember || becomeMember;

            logger.info("Processing tow request for {} {}", vehicleMake, vehicleModel);
            logger.info("Vehicle location: {}", breakdownLocation);
            logger.info("Fault description: {}", faultDescription);
            logger.info("Additional info: {}", towInfoAdditional);
            logger.info("Membership status - existing: {}, new: {}, final: {}",
                    isMember, becomeMember, membershipStatus);

            // Prepare combined vehicle details for the tow team
            String vehicleDetails = vehicleMake + " " + vehicleModel;
            if (faultDescription != null && !faultDescription.isEmpty()) {
                vehicleDetails += " - " + faultDescription;
            }

            // Calculate priority based on membership
            String priority = membershipStatus ? "High" : "Standard";

            // Prepare output variables - pass all the important data forward
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("towRequestProcessed", true);
            resultVariables.put("vehicleDetails", vehicleDetails);
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);
            resultVariables.put(ProcessVariables.BREAKDOWN_LOCATION, breakdownLocation);
            resultVariables.put("towInfoAdditional", towInfoAdditional);
            resultVariables.put("towingPriority", priority);
            resultVariables.put("estimatedTowArrival", "Within 60 minutes");
            resultVariables.put("towRequestTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus); // Pass final membership status

            // Store process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Also store original field names for backward compatibility
            if (variables.containsKey("VehicleLocation")) {
                resultVariables.put("VehicleLocation", breakdownLocation);
            }
            if (variables.containsKey("extraInfo")) {
                resultVariables.put("extraInfo", towInfoAdditional);
            }
            if (variables.containsKey("extraDetails")) {
                resultVariables.put("extraDetails", faultDescription);
            }
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", isMember);
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", becomeMember);
            }

            // Preserve membership number if present
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            logger.info("Tow request processed with priority: {}", priority);
            logger.info("Vehicle details: {}", vehicleDetails);
            logger.info("Breakdown location: {}", breakdownLocation);

            // Complete the job with result variables
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .exceptionally((throwable -> {
                        logger.error("Failed to complete tow request processing", throwable);
                        throw new RuntimeException("Could not process tow request", throwable);
                    }));

        } catch (Exception e) {
            logger.error("Error processing tow request", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error processing tow request: " + e.getMessage())
                    .send();
        }
    }

/**
 * Worker to calculate initial payment (deposit)
 */
@ZeebeWorker(type = "InitialCostCheck")
public void calculateInitialPayment(final JobClient client, final ActivatedJob job) {
    Map<String, Object> variables = job.getVariablesAsMap();

    try {
        // Log all variables for debugging
        logger.info("InitialCostCheck received variables: {}", variables.keySet());

        // CRITICAL: Look ONLY at the current form submission values, ignore any previous state
        // These values directly reflect what boxes are currently checked
        boolean isExistingMember = Boolean.TRUE.equals(variables.get("SignedUp"));
        boolean wantsToBeMember = Boolean.TRUE.equals(variables.get("SigningUp"));

        // Set default MemberCheck value
        boolean memberCheckResult = true;

        // Get customer name for potential new membership
        String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");

        logger.info("Form state - Existing member box checked: {}, New member box checked: {}",
                    isExistingMember, wantsToBeMember);

        // Handle the three possible cases based on current form state:

        // CASE 1: User checked "I am an existing member"
        if (isExistingMember) {
            // Since this box is checked, we MUST validate the membership number
            String membershipNumber = getStringValue(variables, "MembershipNumber");
            memberCheckResult = membershipNumber != null &&
                               membershipCheckService.validateMembershipNumber(membershipNumber);

            if (memberCheckResult) {
                logger.info("Valid membership number confirmed: {}", membershipNumber);
            } else {
                logger.warn("Invalid membership number: {}", membershipNumber);
            }
        }
        // CASE 2: User checked "I want to become a member"
        else if (wantsToBeMember) {
            // Always allow to proceed when they want to become a member
            memberCheckResult = true;
        }
        // CASE 3: User checked neither box
        else {
            // Always allow non-members to proceed
            memberCheckResult = true;
        }

        // Simple flat deposit amount
        double deposit = 150.0;

        logger.info("Calculated deposit: {}", String.format("%.2f", deposit));
        logger.info("MemberCheck result: {}", memberCheckResult);

        // Create output variables - start with a clean slate
        HashMap<String, Object> resultVariables = new HashMap<>();

        // Set base variables
        resultVariables.put(ProcessVariables.DEPOSIT_AMOUNT, deposit);
        resultVariables.put("MemberCheck", memberCheckResult);
        resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, String.valueOf(job.getProcessInstanceKey()));

        // CRITICAL: Always set membership flags based on CURRENT form state
        resultVariables.put("SignedUp", isExistingMember);
        resultVariables.put("SigningUp", wantsToBeMember);

        // Set overall member status
        boolean finalMemberStatus = false;

        // Determine if they should be treated as a member
        if (isExistingMember && memberCheckResult) {
            // Valid existing member
            finalMemberStatus = true;

            // Preserve their membership number
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }
        }
        else if (wantsToBeMember) {
            // Wants to be a new member - could generate number here if needed
            finalMemberStatus = true;
        }

        resultVariables.put(ProcessVariables.IS_MEMBER, finalMemberStatus);

        // Preserve vehicle and customer information
        preserveCustomerAndVehicleInfo(variables, resultVariables);

        // Complete the job
        client.newCompleteCommand(job.getKey())
              .variables(resultVariables)
              .send()
              .exceptionally((throwable -> {
                  logger.error("Failed to complete deposit calculation", throwable);
                  throw new RuntimeException("Could not complete deposit calculation", throwable);
              }));

    } catch (Exception e) {
        // Handle errors
        logger.error("Error calculating deposit: {}", e.getMessage(), e);
        client.newFailCommand(job.getKey())
              .retries(job.getRetries() - 1)
              .errorMessage("Error calculating deposit: " + e.getMessage())
              .send();
    }
}

    /**
     * Worker to calculate final price with member discount
     */
    @ZeebeWorker(type = "CalculateFinalPrice")
    public void calculateFinalPrice(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Log variables for debugging
            logger.info("CalculateFinalPrice received variables: {}", variables.keySet());

            // Get repair cost and membership status - check all possible field names
            // Look for RepairCosts first (from form), then fallback to repairCost
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    500.0);

            // Check multiple fields for membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            // Apply 10% discount for members
            double discountPercentage = membershipStatus ? 10.0 : 0.0;
            double finalPrice = repairCost * (1 - (discountPercentage / 100.0));

            // Set TotalPrice to be the same as finalPrice
            double totalPrice = finalPrice;

            logger.info("Original cost: {}", String.format("%.2f", repairCost));
            logger.info("Is member: {}", membershipStatus);
            logger.info("Discount: {}%", String.format("%.2f", discountPercentage));
            logger.info("Final price: {}", String.format("%.2f", finalPrice));

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, totalPrice); // Set TotalPrice as requested
            resultVariables.put("formattedFinalPrice", String.format("%.2f", finalPrice)); // Add formatted price
            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost); // Set both variable names
            resultVariables.put("discountApplied", membershipStatus);
            resultVariables.put("discountPercentage", discountPercentage);
            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus); // Keep consistent membership status
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve membership related fields
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Preserve vehicle information - use helper method to try multiple field names
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");
            String faultDescription = getStringValue(variables,
                    ProcessVariables.FAULT_DESCRIPTION, "extraDetails", "faultDescription");

            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);

            // Preserve customer information if available
            String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");
            String customerEmail = getStringValue(variables, ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email");

            if (customerName != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            }
            if (customerEmail != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            }

            // Complete the job
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .exceptionally((throwable -> {
                        logger.error("Failed to complete price calculation", throwable);
                        throw new RuntimeException("Could not complete price calculation", throwable);
                    }));

        } catch (Exception e) {
            // Handle errors
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error calculating price: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to process customer approval or denial
     */
    @ZeebeWorker(type = "process-approval")
    public void processApproval(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Log variables for debugging
            logger.info("process-approval received variables: {}", variables.keySet());

            // Print all variables for debugging
            variables.forEach((key, value) -> {
                logger.info("Variable: {} = {}", key, value);
            });

            // Get approval value from the form data - prioritize QuoteApprovalForm as
            // specified
            Boolean approved = false; // Default to false

            // Check for QuoteApprovalForm first (new approach)
            if (variables.containsKey("QuoteApprovalForm")) {
                Object formValue = variables.get("QuoteApprovalForm");
                logger.info("Found QuoteApprovalForm with value: {}", formValue);

                // If the value is exactly "true" (as string), set approved to true
                if (formValue instanceof String) {
                    approved = "true".equals(formValue);
                } else if (formValue instanceof Boolean) {
                    approved = (Boolean) formValue;
                }
            }
            // Fall back to legacy approaches if needed
            else if (variables.containsKey("Approved")) {
                approved = Boolean.TRUE.equals(variables.get("Approved"));
            } else if (variables.containsKey("approved")) {
                approved = Boolean.TRUE.equals(variables.get("approved"));
            } else if (variables.containsKey("approval")) {
                approved = Boolean.TRUE.equals(variables.get("approval"));
            }

            logger.info("Processing customer approval: {}", approved ? "Approved" : "Denied");

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Set the process variable for the gateway condition
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.QUOTE_APPROVED, approved);
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Also store in original field name if it exists
            if (variables.containsKey("Approved")) {
                resultVariables.put("Approved", approved);
            }

            // Preserve important vehicle information - use helper for multiple field names
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");
            String faultDescription = getStringValue(variables,
                    ProcessVariables.FAULT_DESCRIPTION, "extraDetails", "faultDescription");

            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve formatted values
            if (variables.containsKey("formattedFinalPrice")) {
                resultVariables.put("formattedFinalPrice", variables.get("formattedFinalPrice"));
            } else {
                resultVariables.put("formattedFinalPrice", String.format("%.2f", finalPrice));
            }

            // Preserve membership status across all possible field names
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Preserve customer information if available
            String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");
            String customerEmail = getStringValue(variables, ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email");

            if (customerName != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            }
            if (customerEmail != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            }

            // Send message to notify of approval (this could be used for future receive
            // tasks)
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put(ProcessVariables.QUOTE_APPROVED, approved);
            messageVariables.put("approvalTimestamp", System.currentTimeMillis());

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send the message
            sendMessage(MessageNames.APPROVAL, processInstanceKey, messageVariables);

        } catch (Exception e) {
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error processing approval: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to process repair completion notification
     */
    @ZeebeWorker(type = "repair-complete-notify")
    public void processRepairCompletion(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            logger.info("Processing repair completion notification for the receptionist");
            logger.info("Repair completion details being recorded for customer notification");

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("repairCompletionProcessed", true);
            resultVariables.put("repairCompletionTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve vehicle and customer information using helper method
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");
            String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");
            String customerEmail = getStringValue(variables, ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email");

            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve membership number if present
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Send message to notify of work completion (this could be used for future
            // receive tasks)
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put("worksCompleted", true);
            messageVariables.put("completionTimestamp", System.currentTimeMillis());
            messageVariables.put("vehicleDetails", vehicleMake + " " + vehicleModel);

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send the message
            sendMessage(MessageNames.WORKS_COMPLETE, processInstanceKey, messageVariables);

        } catch (Exception e) {
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error processing repair completion: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to notify customer to book an appointment after repair
     */
    @ZeebeWorker(type = "NotifyBookAppointment")
    public void notifyBookAppointment(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Get customer information using helper method
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", "customer@example.com");
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", "Customer");
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");

            // Create a booking link
            String bookingLink = calendlyService.createBookingLink(
                    customerEmail,
                    customerName,
                    vehicleMake + " " + vehicleModel,
                    "Vehicle collection after repair");

            logger.info("Sending repair completion notification to {} with booking link", customerEmail);
            logger.info("Booking link: {}", bookingLink);
            logger.info("Vehicle details: {} {}", vehicleMake, vehicleModel);

            // In a real implementation, you would send an email here
            // For demo, we just log it

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Output process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("notificationSent", true);
            resultVariables.put("notificationTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve key information
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve membership status
            if (variables.containsKey(ProcessVariables.IS_MEMBER)) {
                resultVariables.put(ProcessVariables.IS_MEMBER, variables.get(ProcessVariables.IS_MEMBER));
            }

            // Preserve membership number if present
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Complete the task
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .exceptionally((throwable -> {
                        logger.error("Error completing NotifyBookAppointment", throwable);
                        throw new RuntimeException("Failed to notify customer", throwable);
                    }));

        } catch (Exception e) {
            logger.error("Error in NotifyBookAppointment worker", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error notifying customer: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to offer collection times when quote is declined
     */
    @ZeebeWorker(type = "OfferCollectionTimes")
    public void offerCollectionTimes(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Get customer information using helper method
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", "customer@example.com");
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", "Customer");
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");

            // Create a booking link for vehicle pickup (no repair)
            String bookingLink = calendlyService.createBookingLink(
                    customerEmail,
                    customerName,
                    vehicleMake + " " + vehicleModel,
                    "Vehicle collection without repair");

            logger.info("Offering collection times to {} via Calendly", customerEmail);
            logger.info("Booking link: {}", bookingLink);
            logger.info("Vehicle: {} {}", vehicleMake, vehicleModel);

            // In a real implementation, you would send an email here

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Output process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("collectionTimesOffered", true);
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve vehicle and customer information
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve membership information
            if (variables.containsKey(ProcessVariables.IS_MEMBER)) {
                resultVariables.put(ProcessVariables.IS_MEMBER, variables.get(ProcessVariables.IS_MEMBER));
            }
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Complete the task
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .exceptionally((throwable -> {
                        logger.error("Error completing OfferCollectionTimes", throwable);
                        throw new RuntimeException("Failed to offer collection times", throwable);
                    }));

        } catch (Exception e) {
            logger.error("Error in OfferCollectionTimes worker", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error offering collection times: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to process booking confirmation
     */
    @ZeebeWorker(type = "process-booking")
    public void processBooking(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Get customer information using helper method
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", "customer@example.com");
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", "Customer");
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");

            // Check if we already have booking information (from a webhook)
            boolean hasBookingInfo = variables.containsKey("bookingConfirmed") &&
                    (Boolean) variables.get("bookingConfirmed");

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            Map<String, Object> bookingInfo;
            if (hasBookingInfo) {
                // Use the existing booking info from webhook
                logger.info("Using existing booking info from webhook");
                bookingInfo = new HashMap<>();
                bookingInfo.put("appointmentTime", variables.get("appointmentTime"));
                bookingInfo.put("bookingConfirmed", variables.get("bookingConfirmed"));
                bookingInfo.put("bookingReference", variables.get("bookingReference"));
            } else {
                // For demo purposes, simulate a booking if webhook hasn't been received
                logger.info("No webhook data found, simulating booking confirmation");
                bookingInfo = calendlyService.simulateBooking(customerEmail, customerName);
            }

            logger.info("Processing booking for {} at {} for vehicle: {} {}",
                    customerName, bookingInfo.get("appointmentTime"), vehicleMake, vehicleModel);

            // Preserve vehicle and customer information
            bookingInfo.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            bookingInfo.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            bookingInfo.put(ProcessVariables.CUSTOMER_NAME, customerName);
            bookingInfo.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            bookingInfo.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            bookingInfo.put(ProcessVariables.REPAIR_COST, repairCost);
            bookingInfo.put(ProcessVariables.REPAIR_COSTS, repairCost);
            bookingInfo.put(ProcessVariables.FINAL_PRICE, finalPrice);
            bookingInfo.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            bookingInfo.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve membership details
            if (variables.containsKey("SignedUp")) {
                bookingInfo.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                bookingInfo.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                bookingInfo.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Complete the task with booking info
            client.newCompleteCommand(job.getKey())
                    .variables(bookingInfo)
                    .send()
                    .exceptionally((throwable -> {
                        logger.error("Error completing ProcessBooking", throwable);
                        throw new RuntimeException("Failed to process booking", throwable);
                    }));

        } catch (Exception e) {
            logger.error("Error in ProcessBooking worker", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error processing booking: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to process the customer satisfaction form with radio buttons
     */
    @ZeebeWorker(type = "process-satisfaction")
    public void processApprovalForm(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Default to not satisfied (false) since it's now a required field
            boolean satisfied = false;

            // Check for the new radio button value
            if (variables.containsKey("CustomerSatisfactionForm")) {
                String satisfactionValue = String.valueOf(variables.get("CustomerSatisfactionForm"));
                logger.info("Found CustomerSatisfactionForm with value: {}", satisfactionValue);

                // If the value is "Satisfied", mark as satisfied
                satisfied = "Satisfied".equals(satisfactionValue);

                logger.info("Customer satisfaction status determined as: {}",
                        satisfied ? "Satisfied" : "Not Satisfied");
            }
            // Fallback to older field names if present
            else if (variables.containsKey("Satisfied")) {
                satisfied = Boolean.TRUE.equals(variables.get("Satisfied"));
            } else if (variables.containsKey("satisfied")) {
                satisfied = Boolean.TRUE.equals(variables.get("satisfied"));
            } else if (variables.containsKey("customerSatisfied")) {
                satisfied = Boolean.TRUE.equals(variables.get("customerSatisfied"));
            }

            logger.info("Setting CustomerSatisfied to: {}", satisfied);

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Set the CustomerSatisfied variable with capital C and S as requested
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.CUSTOMER_SATISFIED, satisfied);
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Also set with alternate capitalization for safety
            resultVariables.put("customerSatisfied", satisfied);

            // Preserve vehicle and customer information
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", "Customer");
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", "customer@example.com");

            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve membership details
            if (variables.containsKey(ProcessVariables.IS_MEMBER)) {
                resultVariables.put(ProcessVariables.IS_MEMBER, variables.get(ProcessVariables.IS_MEMBER));
            }
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Complete the task
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .exceptionally((throwable -> {
                        logger.error("Error processing satisfaction form", throwable);
                        throw new RuntimeException("Failed to process satisfaction form", throwable);
                    }));

        } catch (Exception e) {
            logger.error("Error in satisfaction form processing", e);
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error processing satisfaction form: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to send collection time notification (legacy implementation)
     */
    @ZeebeWorker(type = "ArrangeCollection")
    public void arrangeCollection(final JobClient client, final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();

            // Extract customer info using helper method
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", "customer@example.com");
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", "Customer");
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");

            // Generate a Calendly booking link
            String bookingLink = calendlyService.createBookingLink(
                    customerEmail,
                    customerName,
                    vehicleMake + " " + vehicleModel,
                    "Vehicle collection");

            logger.info("Created Calendly booking link: {}", bookingLink);
            logger.info("In production, an email would be sent to: {}", customerEmail);
            logger.info("Vehicle details: {} {}", vehicleMake, vehicleModel);

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Set process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("notificationSent", true);
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve membership number if present
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Message variables
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put("bookingLink", bookingLink);
            messageVariables.put("collectionArranged", true);
            messageVariables.put("arrangedTimestamp", System.currentTimeMillis());

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send the message
            sendMessage(MessageNames.COLLECTION_ARRANGED, processInstanceKey, messageVariables);

        } catch (Exception e) {
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error arranging collection: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to send final quote notification
     */
    @ZeebeWorker(type = "FinalQuote")
    public void sendFinalQuote(final JobClient client, final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();

            // Get repair cost - check multiple variables
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    500.0);

            // Calculate final price - if not already set
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, 0.0);
            if (finalPrice < 0.01) {
                // Check if there's a discount to apply
                boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                        Boolean.TRUE.equals(variables.get("SignedUp")) ||
                        Boolean.TRUE.equals(variables.get("SigningUp"));
                double discountPercentage = membershipStatus ? 10.0 : 0.0;
                finalPrice = repairCost * (1 - (discountPercentage / 100.0));
            }

            // Also set the TotalPrice variable
            double totalPrice = finalPrice;

            // Format with 2 decimal places for display
            String formattedPrice = String.format("%.2f", finalPrice);

            // Get vehicle and customer information using helper
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", "customer@example.com");
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", "Customer");

            // In a real implementation, this would send an actual notification
            logger.info("Sending final quote to customer: {}", customerName);
            logger.info("Customer email: {}", customerEmail);
            logger.info("Vehicle: {} {}", vehicleMake, vehicleModel);
            logger.info("Final price: £{}", formattedPrice);

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("quoteSent", true);
            resultVariables.put("quoteTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, totalPrice);
            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put("formattedFinalPrice", formattedPrice);
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve membership fields
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Message variables
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            messageVariables.put(ProcessVariables.TOTAL_PRICE, totalPrice);
            messageVariables.put("formattedPrice", formattedPrice);
            messageVariables.put("quoteSent", true);
            messageVariables.put("quoteTimestamp", System.currentTimeMillis());

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send the message
            sendMessage(MessageNames.QUOTE_NOTIFICATION, processInstanceKey, messageVariables);

        } catch (Exception e) {
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error sending quote: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to notify reception of completed works
     */
    @ZeebeWorker(type = "NotifyWorkComplete")
    public void notifyWorkComplete(final JobClient client, final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();

            // Get vehicle information using helper
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Vehicle");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Model");

            // In a real implementation, this would send an actual notification
            logger.info("Notifying reception that vehicle repairs are complete");
            logger.info("Vehicle: {} {}", vehicleMake, vehicleModel);

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("workCompleteNotificationSent", true);
            resultVariables.put("completionTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve customer information
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", null);
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", null);

            if (customerName != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            }
            if (customerEmail != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            }

            // Preserve cost information
            double repairCost = getNumberValue(variables, ProcessVariables.REPAIR_COSTS, ProcessVariables.REPAIR_COST,
                    0.0);
            double finalPrice = getNumberValue(variables, ProcessVariables.FINAL_PRICE, ProcessVariables.TOTAL_PRICE,
                    repairCost);

            resultVariables.put(ProcessVariables.REPAIR_COST, repairCost);
            resultVariables.put(ProcessVariables.REPAIR_COSTS, repairCost);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put(ProcessVariables.TOTAL_PRICE, finalPrice);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve membership fields
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Message variables
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put("workComplete", true);
            messageVariables.put("completionTimestamp", System.currentTimeMillis());
            messageVariables.put("vehicleDetails", vehicleMake + " " + vehicleModel);

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send the message
            sendMessage(MessageNames.WORKS_COMPLETE, processInstanceKey, messageVariables);

        } catch (Exception e) {
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error notifying work completion: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Worker to send tow request
     */
    @ZeebeWorker(type = "TowRequest")
    public void sendTowRequest(final JobClient client, final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();

            // Log variables for debugging
            logger.info("TowRequest received variables: {}", variables.keySet());

            // Get vehicle and location information using helper
            String location = getStringValue(variables,
                    ProcessVariables.BREAKDOWN_LOCATION, "VehicleLocation", "location", "Unknown location");
            String vehicleMake = getStringValue(variables,
                    ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make", "Unknown make");
            String vehicleModel = getStringValue(variables,
                    ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model", "Unknown model");
            String faultDescription = getStringValue(variables,
                    ProcessVariables.FAULT_DESCRIPTION, "extraDetails", "faultDescription", "No description provided");
            String towInfoAdditional = getStringValue(variables,
                    "towInfoAdditional", "extraInfo", "additionalInfo", "");

            // Combine vehicle information
            String vehicleDetails = vehicleMake + " " + vehicleModel;
            if (faultDescription != null && !faultDescription.isEmpty() &&
                    !faultDescription.equals("No description provided")) {
                vehicleDetails += " - " + faultDescription;
            }

            // In a real implementation, this would send an actual tow request
            logger.info("Sending tow request to towing service");
            logger.info("Breakdown location: {}", location);
            logger.info("Vehicle details: {}", vehicleDetails);
            if (towInfoAdditional != null && !towInfoAdditional.isEmpty()) {
                logger.info("Additional info: {}", towInfoAdditional);
            }

            // Get the process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("towRequestSent", true);
            resultVariables.put("estimatedTowArrival", "Within 60 minutes"); // In reality, would come from tow service
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey);

            // Preserve all important variables
            resultVariables.put(ProcessVariables.BREAKDOWN_LOCATION, location);
            resultVariables.put("vehicleDetails", vehicleDetails);
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);
            resultVariables.put("towInfoAdditional", towInfoAdditional);

            // Also preserve with original variable names
            if (variables.containsKey("VehicleLocation")) {
                resultVariables.put("VehicleLocation", location);
            }
            if (variables.containsKey("extraDetails")) {
                resultVariables.put("extraDetails", faultDescription);
            }
            if (variables.containsKey("extraInfo")) {
                resultVariables.put("extraInfo", towInfoAdditional);
            }

            // Preserve customer information
            String customerName = getStringValue(variables,
                    ProcessVariables.CUSTOMER_NAME, "CustomerName", "name", null);
            String customerEmail = getStringValue(variables,
                    ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email", null);

            if (customerName != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            }
            if (customerEmail != null) {
                resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            }

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                    Boolean.TRUE.equals(variables.get("SignedUp")) ||
                    Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Preserve original membership fields
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", variables.get("SignedUp"));
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", variables.get("SigningUp"));
            }
            if (variables.containsKey("MembershipNumber")) {
                resultVariables.put("MembershipNumber", variables.get("MembershipNumber"));
            }

            // Send message for the tow request (for potential future receive tasks)
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put("towRequested", true);
            messageVariables.put("towRequestTimestamp", System.currentTimeMillis());
            messageVariables.put("vehicleDetails", vehicleDetails);
            messageVariables.put("breakdownLocation", location);

            // Complete the job first
            client.newCompleteCommand(job.getKey())
                    .variables(resultVariables)
                    .send()
                    .join();

            // Then send the message
            sendMessage(MessageNames.TOW_REQUEST, processInstanceKey, messageVariables);

        } catch (Exception e) {
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries() - 1)
                    .errorMessage("Error sending tow request: " + e.getMessage())
                    .send();
        }
    }

    /**
     * Helper method to get string value from multiple possible variable names
     * Fixed to avoid null pointer exceptions
     *
     * @param variables The variable map
     * @param keys      Multiple possible keys to try
     * @return The first non-null value found, or null if none found
     */
    private String getStringValue(Map<String, Object> variables, String... keys) {
        if (keys == null || keys.length == 0)
            return null;

        // Try each key in order
        for (String key : keys) {
            if (key != null && variables.containsKey(key) && variables.get(key) != null) {
                Object value = variables.get(key);
                if (value instanceof String) {
                    return (String) value;
                }
                // Convert non-string to string if needed
                return String.valueOf(value);
            }
        }

        // If we get here and there's a default value provided as the last key
        // Check if the last key could be a default value (no dots in it)
        String lastKey = keys[keys.length - 1];
        if (lastKey != null && !lastKey.contains(".")) {
            // The last key might be a default value, not a key to search for
            return lastKey;
        }

        return null;
    }

    /**
     * Helper method to get a numeric value from multiple possible variable names
     *
     * @param variables    The variable map
     * @param keys         Multiple possible keys to try
     * @param defaultValue Default value if none found
     * @return The first non-null numeric value found, or the default value if none
     *         found
     */
    private double getNumberValue(Map<String, Object> variables, String firstKey, String secondKey,
            double defaultValue) {
        // Try first key
        if (firstKey != null && variables.containsKey(firstKey) && variables.get(firstKey) != null) {
            Object value = variables.get(firstKey);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            } else if (value instanceof String) {
                try {
                    return Double.parseDouble((String) value);
                } catch (NumberFormatException e) {
                    // Not a valid number, continue to next key
                }
            }
        }

        // Try second key
        if (secondKey != null && variables.containsKey(secondKey) && variables.get(secondKey) != null) {
            Object value = variables.get(secondKey);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            } else if (value instanceof String) {
                try {
                    return Double.parseDouble((String) value);
                } catch (NumberFormatException e) {
                    // Not a valid number, return default
                }
            }
        }

        return defaultValue;
    }

    /**
     * Overloaded helper to get a numeric value from a single key
     */
    private double getNumberValue(Map<String, Object> variables, String key, double defaultValue) {
        return getNumberValue(variables, key, null, defaultValue);
    }
}
