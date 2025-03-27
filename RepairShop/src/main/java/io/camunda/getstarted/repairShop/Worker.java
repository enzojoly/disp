package io.camunda.getstarted.repairShop;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.getstarted.repairShop.service.CalendlyService;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.EnableZeebeClient;
import io.camunda.zeebe.spring.client.annotation.ZeebeWorker;

@SpringBootApplication
@EnableZeebeClient
public class Worker {

    private static final Logger logger = LoggerFactory.getLogger(Worker.class);

    @Autowired
    private CalendlyService calendlyService;

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
        public static final String INITIAL_COST_RECEIVED = "initialCostReceived";

        // NEW: Add process instance key for message correlation
        public static final String PROCESS_INSTANCE_KEY = "processInstanceKey";
    }

    /**
     * Constants for message names
     */
    public static class MessageNames {
        public static final String RECEIVE_INITIAL_COST = "ReceiveInitialCost";
        public static final String TOW_REQUEST = "TowingRequest";
        public static final String APPROVAL = "Approval";
        public static final String WORKS_COMPLETE = "WorksComplete";
    }

    public static void main(String[] args) {
        SpringApplication.run(Worker.class, args);
    }

    /**
     * Helper method to send a message to a specific process instance
     * @param messageName The name of the message
     * @param processInstanceKey The process instance key to correlate with
     * @param variables The variables to include in the message
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

            logger.info("Informing customer {} of initial cost ${} for vehicle {} {}",
                customerName, deposit, vehicleMake, vehicleModel);

            // In a real implementation, this would send an actual notification to the customer
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

            // The process instance key is used as correlation key for the message
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // IMPORTANT: Store the process instance key as a process variable so it can be used for correlation
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

    // Rest of the Worker.java file remains the same...

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

            // Get membership status from multiple possible variables
            boolean existingMember = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                                   Boolean.TRUE.equals(variables.get("SignedUp"));
            boolean newMember = Boolean.TRUE.equals(variables.get("becomeMember")) ||
                              Boolean.TRUE.equals(variables.get("SigningUp"));

            // Set membership status based on either existing or new membership
            boolean membershipStatus = existingMember || newMember;

            // Simple flat deposit amount
            double deposit = 150.0;

            // Apply discount for members if applicable
            // (In this demo, we keep it flat, but you could modify this for members)

            System.out.println("Calculated deposit: " + deposit);
            System.out.println("Customer is member: " + membershipStatus);
            System.out.println("Existing member: " + existingMember + ", New member: " + newMember);

            // Store process instance key for message correlation
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.DEPOSIT_AMOUNT, deposit);
            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus); // Pass along final membership status
            resultVariables.put(ProcessVariables.PROCESS_INSTANCE_KEY, processInstanceKey); // Add process instance key

            // Keep original membership fields for backward compatibility
            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", existingMember);
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", newMember);
            }

            // Preserve vehicle information - try all possible field names
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");
            String faultDescription = getStringValue(variables,
                    ProcessVariables.FAULT_DESCRIPTION, "extraDetails", "faultDescription", "description");
            String breakdownLocation = getStringValue(variables,
                    ProcessVariables.BREAKDOWN_LOCATION, "VehicleLocation", "location");
            String towInfoAdditional = getStringValue(variables,
                    "towInfoAdditional", "extraInfo", "additionalInfo", "towInfo");

            // Store variables with consistent names
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.FAULT_DESCRIPTION, faultDescription);
            resultVariables.put(ProcessVariables.BREAKDOWN_LOCATION, breakdownLocation);
            resultVariables.put("towInfoAdditional", towInfoAdditional);

            // Also preserve with original field names
            if (variables.containsKey("extraDetails")) {
                resultVariables.put("extraDetails", faultDescription);
            }
            if (variables.containsKey("VehicleLocation")) {
                resultVariables.put("VehicleLocation", breakdownLocation);
            }
            if (variables.containsKey("extraInfo")) {
                resultVariables.put("extraInfo", towInfoAdditional);
            }

            // Preserve breakdown status
            if (variables.containsKey("Breakdown")) {
                resultVariables.put("Breakdown", variables.get("Breakdown"));
            }

            // Preserve customer information
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
                      throw new RuntimeException("Could not complete deposit calculation", throwable);
                  }));

        } catch (Exception e) {
            // Handle errors
            client.newFailCommand(job.getKey())
                  .retries(job.getRetries() - 1)
                  .errorMessage("Error calculating deposit: " + e.getMessage())
                  .send();
        }
    }

    // Include the rest of your original Worker.java code here...

    /**
     * Helper method to get string value from multiple possible variable names
     * Fixed to avoid null pointer exceptions
     * @param variables The variable map
     * @param keys Multiple possible keys to try
     * @return The first non-null value found, or null if none found
     */
    private String getStringValue(Map<String, Object> variables, String... keys) {
        if (keys == null || keys.length == 0) return null;

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
        String lastKey = keys[keys.length-1];
        if (lastKey != null && !lastKey.contains(".")) {
            // The last key might be a default value, not a key to search for
            return lastKey;
        }

        return null;
    }
}
