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

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.DEPOSIT_AMOUNT, deposit);
            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus); // Pass along final membership status

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

    /**
     * Worker to calculate final price with member discount
     */
    @ZeebeWorker(type = "CalculateFinalPrice")
    public void calculateFinalPrice(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Log variables for debugging
            logger.info("CalculateFinalPrice received variables: {}", variables.keySet());

            // Set default repair cost for demo if not provided
            if (!variables.containsKey("repairCost")) {
                variables.put("repairCost", 500.0); // Default repair cost
            }

            // Get repair cost and membership status - check all possible field names
            double repairCost = ((Number) variables.getOrDefault("repairCost", 500.0)).doubleValue();

            // Check multiple fields for membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                                     Boolean.TRUE.equals(variables.get("SignedUp")) ||
                                     Boolean.TRUE.equals(variables.get("SigningUp"));

            // Apply 10% discount for members
            double discountPercentage = membershipStatus ? 10.0 : 0.0;
            double finalPrice = repairCost * (1 - (discountPercentage / 100.0));

            System.out.println("Original cost: " + repairCost);
            System.out.println("Is member: " + membershipStatus);
            System.out.println("Discount: " + discountPercentage + "%");
            System.out.println("Final price: " + finalPrice);

            // Output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);
            resultVariables.put("discountApplied", membershipStatus);
            resultVariables.put("discountPercentage", discountPercentage);
            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus); // Keep consistent membership status

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

            // Get approval value from the form data - try multiple possible field names
            Boolean approved = null;
            if (variables.containsKey("Approved")) {
                approved = Boolean.TRUE.equals(variables.get("Approved"));
            } else if (variables.containsKey("approved")) {
                approved = Boolean.TRUE.equals(variables.get("approved"));
            } else if (variables.containsKey("approval")) {
                approved = Boolean.TRUE.equals(variables.get("approval"));
            } else {
                approved = false; // Default to false if not found
            }

            logger.info("Processing customer approval: {}", approved ? "Approved" : "Denied");

            // Set the process variable for the gateway condition
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.QUOTE_APPROVED, approved);

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

            // Preserve membership status across all possible field names
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                                     Boolean.TRUE.equals(variables.get("SignedUp")) ||
                                     Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            if (variables.containsKey("SignedUp")) {
                resultVariables.put("SignedUp", membershipStatus);
            }
            if (variables.containsKey("SigningUp")) {
                resultVariables.put("SigningUp", membershipStatus);
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

            // Send message to notify of approval (this could be used for future receive tasks)
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());
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

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("repairCompletionProcessed", true);
            resultVariables.put("repairCompletionTimestamp", System.currentTimeMillis());

            // Preserve vehicle and customer information using helper method
            String vehicleMake = getStringValue(variables, ProcessVariables.VEHICLE_MAKE, "vehicleMake", "Make");
            String vehicleModel = getStringValue(variables, ProcessVariables.VEHICLE_MODEL, "vehicleModel", "Model");
            String customerName = getStringValue(variables, ProcessVariables.CUSTOMER_NAME, "CustomerName", "name");
            String customerEmail = getStringValue(variables, ProcessVariables.CUSTOMER_EMAIL, "CustomerEmail", "email");

            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                                     Boolean.TRUE.equals(variables.get("SignedUp")) ||
                                     Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Send message to notify of work completion (this could be used for future receive tasks)
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());
            Map<String, Object> messageVariables = new HashMap<>();
            messageVariables.put("worksCompleted", true);
            messageVariables.put("completionTimestamp", System.currentTimeMillis());

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
                "Vehicle collection after repair"
            );

            logger.info("Sending repair completion notification to {} with booking link", customerEmail);
            logger.info("Booking link: {}", bookingLink);
            logger.info("Vehicle details: {} {}", vehicleMake, vehicleModel);

            // In a real implementation, you would send an email here
            // For demo, we just log it

            // Output process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("notificationSent", true);
            resultVariables.put("notificationTimestamp", System.currentTimeMillis());

            // Preserve key information
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

            // Preserve membership status
            if (variables.containsKey(ProcessVariables.IS_MEMBER)) {
                resultVariables.put(ProcessVariables.IS_MEMBER, variables.get(ProcessVariables.IS_MEMBER));
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
                "Vehicle collection without repair"
            );

            logger.info("Offering collection times to {} via Calendly", customerEmail);
            logger.info("Booking link: {}", bookingLink);
            logger.info("Vehicle: {} {}", vehicleMake, vehicleModel);

            // In a real implementation, you would send an email here

            // Output process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("collectionTimesOffered", true);

            // Preserve vehicle and customer information
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

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
                                   (Boolean)variables.get("bookingConfirmed");

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

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                                     Boolean.TRUE.equals(variables.get("SignedUp")) ||
                                     Boolean.TRUE.equals(variables.get("SigningUp"));

            bookingInfo.put(ProcessVariables.IS_MEMBER, membershipStatus);

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
     * Worker to process the customer approval form and set CustomerSatisfied variable
     */
    @ZeebeWorker(type = "process-satisfaction")
    public void processApprovalForm(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Default to satisfied (true) if not specified
            boolean satisfied = true;

            // Try to get the value from the form if it exists
            if (variables.containsKey("Satisfied")) {
                satisfied = Boolean.TRUE.equals(variables.get("Satisfied"));
            } else if (variables.containsKey("satisfied")) {
                satisfied = Boolean.TRUE.equals(variables.get("satisfied"));
            } else if (variables.containsKey("customerSatisfied")) {
                satisfied = Boolean.TRUE.equals(variables.get("customerSatisfied"));
            }

            logger.info("Setting CustomerSatisfied to: {}", satisfied);

            // Set the CustomerSatisfied variable with capital C and S as requested
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put(ProcessVariables.CUSTOMER_SATISFIED, satisfied);

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

            // Complete the task
            client.newCompleteCommand(job.getKey())
                  .variables(resultVariables)
                  .send()
                  .exceptionally((throwable -> {
                      logger.error("Error processing approval form", throwable);
                      throw new RuntimeException("Failed to process approval form", throwable);
                  }));

        } catch (Exception e) {
            logger.error("Error in approval form processing", e);
            client.newFailCommand(job.getKey())
                  .retries(job.getRetries() - 1)
                  .errorMessage("Error processing approval form: " + e.getMessage())
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

            // Set process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("notificationSent", true);
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                                     Boolean.TRUE.equals(variables.get("SignedUp")) ||
                                     Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            // Complete the job
            client.newCompleteCommand(job.getKey())
                  .variables(resultVariables)
                  .send()
                  .exceptionally((throwable -> {
                      throw new RuntimeException("Could not arrange collection", throwable);
                  }));

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
            double finalPrice = ((Number) variables.getOrDefault(ProcessVariables.FINAL_PRICE, 0.0)).doubleValue();

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
            System.out.println("Sending final quote to customer: " + customerName);
            System.out.println("Customer email: " + customerEmail);
            System.out.println("Vehicle: " + vehicleMake + " " + vehicleModel);
            System.out.println("Final price: $" + finalPrice);

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("quoteSent", true);
            resultVariables.put("quoteTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);
            resultVariables.put(ProcessVariables.CUSTOMER_NAME, customerName);
            resultVariables.put(ProcessVariables.CUSTOMER_EMAIL, customerEmail);
            resultVariables.put(ProcessVariables.FINAL_PRICE, finalPrice);

            // Preserve membership status
            boolean membershipStatus = Boolean.TRUE.equals(variables.get(ProcessVariables.IS_MEMBER)) ||
                                     Boolean.TRUE.equals(variables.get("SignedUp")) ||
                                     Boolean.TRUE.equals(variables.get("SigningUp"));

            resultVariables.put(ProcessVariables.IS_MEMBER, membershipStatus);

            client.newCompleteCommand(job.getKey())
                  .variables(resultVariables)
                  .send()
                  .exceptionally((throwable -> {
                      throw new RuntimeException("Could not send final quote", throwable);
                  }));

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
            System.out.println("Notifying reception that vehicle repairs are complete");
            System.out.println("Vehicle: " + vehicleMake + " " + vehicleModel);

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("workCompleteNotificationSent", true);
            resultVariables.put("completionTimestamp", System.currentTimeMillis());
            resultVariables.put(ProcessVariables.VEHICLE_MAKE, vehicleMake);
            resultVariables.put(ProcessVariables.VEHICLE_MODEL, vehicleModel);

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

            client.newCompleteCommand(job.getKey())
                  .variables(resultVariables)
                  .send()
                  .exceptionally((throwable -> {
                      throw new RuntimeException("Could not notify work completion", throwable);
                  }));

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
            System.out.println("Sending tow request to towing service");
            System.out.println("Breakdown location: " + location);
            System.out.println("Vehicle details: " + vehicleDetails);
            if (towInfoAdditional != null && !towInfoAdditional.isEmpty()) {
                System.out.println("Additional info: " + towInfoAdditional);
            }

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("towRequestSent", true);
            resultVariables.put("estimatedTowArrival", "Within 60 minutes"); // In reality, would come from tow service

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

            // Send message for the tow request (for potential future receive tasks)
            String processInstanceKey = String.valueOf(job.getProcessInstanceKey());
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
