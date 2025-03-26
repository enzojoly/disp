package io.camunda.getstarted.repairShop;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.camunda.getstarted.repairShop.service.CalendlyService;
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

    public static void main(String[] args) {
        SpringApplication.run(Worker.class, args);
    }

    /**
     * Worker to process tow request from the initial form
     */
    @ZeebeWorker(type = "process-tow-request")
    public void processTowRequest(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Extract vehicle information
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Unknown make");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Unknown model");
            String faultDescription = (String) variables.getOrDefault("DescriptionOfFault", "No description provided");

            // Extract towing-specific information
            String breakdownLocation = (String) variables.get("breakdownLocation");
            String towInfoAdditional = (String) variables.getOrDefault("towInfoAdditional", "");

            // Get membership status - default to false unless specifically set to true in the form
            boolean isMember = Boolean.TRUE.equals(variables.get("isMember"));

            logger.info("Processing tow request for {} {}", vehicleMake, vehicleModel);
            logger.info("Vehicle location: {}", breakdownLocation);
            logger.info("Fault description: {}", faultDescription);

            // Prepare combined vehicle details for the tow team
            String vehicleDetails = vehicleMake + " " + vehicleModel + " - " + faultDescription;

            // Calculate priority based on membership
            String priority = isMember ? "High" : "Standard";

            // Prepare output variables - pass all the important data forward
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("towRequestProcessed", true);
            resultVariables.put("vehicleDetails", vehicleDetails);
            resultVariables.put("VehicleMake", vehicleMake);
            resultVariables.put("VehicleModel", vehicleModel);
            resultVariables.put("DescriptionOfFault", faultDescription);
            resultVariables.put("breakdownLocation", breakdownLocation);
            resultVariables.put("towInfoAdditional", towInfoAdditional);
            resultVariables.put("towingPriority", priority);
            resultVariables.put("estimatedTowArrival", "Within 60 minutes");
            resultVariables.put("towRequestTimestamp", System.currentTimeMillis());
            resultVariables.put("isMember", isMember); // Pass membership status along

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
            // Get membership status explicitly - don't set a default
            // Check both isMember (existing membership) and becomeMember (signing up today)
            boolean isMember = Boolean.TRUE.equals(variables.get("isMember"));
            boolean becomeMember = Boolean.TRUE.equals(variables.get("becomeMember"));

            // Set membership status based on either existing or new membership
            boolean membershipStatus = isMember || becomeMember;

            // Simple flat deposit amount
            double deposit = 150.0;

            System.out.println("Calculated deposit: " + deposit);
            System.out.println("Customer is member: " + membershipStatus);

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("depositAmount", deposit);
            resultVariables.put("isMember", membershipStatus); // Pass along final membership status

            // Preserve original form variables to ensure they're available later
            if (variables.containsKey("VehicleMake")) {
                resultVariables.put("VehicleMake", variables.get("VehicleMake"));
            }
            if (variables.containsKey("VehicleModel")) {
                resultVariables.put("VehicleModel", variables.get("VehicleModel"));
            }
            if (variables.containsKey("DescriptionOfFault")) {
                resultVariables.put("DescriptionOfFault", variables.get("DescriptionOfFault"));
            }
            if (variables.containsKey("breakdownLocation")) {
                resultVariables.put("breakdownLocation", variables.get("breakdownLocation"));
            }
            if (variables.containsKey("towInfoAdditional")) {
                resultVariables.put("towInfoAdditional", variables.get("towInfoAdditional"));
            }
            if (variables.containsKey("Breakdown")) {
                resultVariables.put("Breakdown", variables.get("Breakdown"));
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
            // Set default repair cost for demo if not provided
            if (!variables.containsKey("repairCost")) {
                variables.put("repairCost", 500.0); // Default repair cost
            }

            // Get repair cost and membership status
            double repairCost = ((Number) variables.get("repairCost")).doubleValue();
            boolean isMember = Boolean.TRUE.equals(variables.get("isMember"));

            // Apply 10% discount for members
            double discountPercentage = isMember ? 10.0 : 0.0;
            double finalPrice = repairCost * (1 - (discountPercentage / 100.0));

            System.out.println("Original cost: " + repairCost);
            System.out.println("Is member: " + isMember);
            System.out.println("Discount: " + discountPercentage + "%");
            System.out.println("Final price: " + finalPrice);

            // Output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("finalPrice", finalPrice);
            resultVariables.put("discountApplied", isMember);
            resultVariables.put("discountPercentage", discountPercentage);

            // Preserve vehicle information
            if (variables.containsKey("VehicleMake")) {
                resultVariables.put("VehicleMake", variables.get("VehicleMake"));
            }
            if (variables.containsKey("VehicleModel")) {
                resultVariables.put("VehicleModel", variables.get("VehicleModel"));
            }
            if (variables.containsKey("DescriptionOfFault")) {
                resultVariables.put("DescriptionOfFault", variables.get("DescriptionOfFault"));
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
            // Get approval value from the form data
            Boolean approved = Boolean.TRUE.equals(variables.get("Approved"));

            System.out.println("Processing customer approval: " + (approved ? "Approved" : "Denied"));

            // Set the process variable for the gateway condition
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("QuoteApproved", approved);

            // Preserve important vehicle information
            if (variables.containsKey("VehicleMake")) {
                resultVariables.put("VehicleMake", variables.get("VehicleMake"));
            }
            if (variables.containsKey("VehicleModel")) {
                resultVariables.put("VehicleModel", variables.get("VehicleModel"));
            }
            if (variables.containsKey("DescriptionOfFault")) {
                resultVariables.put("DescriptionOfFault", variables.get("DescriptionOfFault"));
            }
            if (variables.containsKey("isMember")) {
                resultVariables.put("isMember", variables.get("isMember"));
            }

            client.newCompleteCommand(job.getKey())
                  .variables(resultVariables)
                  .send()
                  .exceptionally((throwable -> {
                      throw new RuntimeException("Could not process approval", throwable);
                  }));

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

            // Preserve vehicle and customer information
            if (variables.containsKey("VehicleMake")) {
                resultVariables.put("VehicleMake", variables.get("VehicleMake"));
            }
            if (variables.containsKey("VehicleModel")) {
                resultVariables.put("VehicleModel", variables.get("VehicleModel"));
            }
            if (variables.containsKey("customerName")) {
                resultVariables.put("customerName", variables.get("customerName"));
            }
            if (variables.containsKey("customerEmail")) {
                resultVariables.put("customerEmail", variables.get("customerEmail"));
            }
            if (variables.containsKey("isMember")) {
                resultVariables.put("isMember", variables.get("isMember"));
            }

            // Complete the job
            client.newCompleteCommand(job.getKey())
                  .variables(resultVariables)
                  .send()
                  .exceptionally((throwable -> {
                      throw new RuntimeException("Could not process repair completion", throwable);
                  }));

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
            // Get customer information
            String customerEmail = (String) variables.getOrDefault("customerEmail", "customer@example.com");
            String customerName = (String) variables.getOrDefault("customerName", "Customer");
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Vehicle");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Model");

            // Create a booking link
            String bookingLink = calendlyService.createBookingLink(
                customerEmail,
                customerName,
                vehicleMake + " " + vehicleModel,
                "Vehicle collection after repair"
            );

            logger.info("Sending repair completion notification to {} with booking link", customerEmail);
            logger.info("Booking link: {}", bookingLink);

            // In a real implementation, you would send an email here
            // For demo, we just log it

            // Output process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("notificationSent", true);
            resultVariables.put("notificationTimestamp", System.currentTimeMillis());

            // Preserve key information
            if (variables.containsKey("VehicleMake")) {
                resultVariables.put("VehicleMake", variables.get("VehicleMake"));
            }
            if (variables.containsKey("VehicleModel")) {
                resultVariables.put("VehicleModel", variables.get("VehicleModel"));
            }
            if (variables.containsKey("customerName")) {
                resultVariables.put("customerName", variables.get("customerName"));
            }
            if (variables.containsKey("customerEmail")) {
                resultVariables.put("customerEmail", variables.get("customerEmail"));
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
            // Get customer information
            String customerEmail = (String) variables.getOrDefault("customerEmail", "customer@example.com");
            String customerName = (String) variables.getOrDefault("customerName", "Customer");
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Vehicle");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Model");

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
            resultVariables.put("VehicleMake", vehicleMake);
            resultVariables.put("VehicleModel", vehicleModel);
            resultVariables.put("customerName", customerName);
            resultVariables.put("customerEmail", customerEmail);

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
            // Get customer information
            String customerEmail = (String) variables.getOrDefault("customerEmail", "customer@example.com");
            String customerName = (String) variables.getOrDefault("customerName", "Customer");
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Vehicle");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Model");

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
            bookingInfo.put("VehicleMake", vehicleMake);
            bookingInfo.put("VehicleModel", vehicleModel);
            bookingInfo.put("customerName", customerName);
            bookingInfo.put("customerEmail", customerEmail);

            if (variables.containsKey("isMember")) {
                bookingInfo.put("isMember", variables.get("isMember"));
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
     * Worker to process the customer approval form and set CustomerSatisfied variable
     */
    @ZeebeWorker(type = "process-satisfaction")
    public void processApprovalForm(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Default to satisfied (true) if not specified
            boolean satisfied = true;

            logger.info("Setting CustomerSatisfied to: {}", satisfied);

            // Set the CustomerSatisfied variable with capital C and S as requested
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("CustomerSatisfied", satisfied);

            // Preserve vehicle and customer information
            if (variables.containsKey("VehicleMake")) {
                resultVariables.put("VehicleMake", variables.get("VehicleMake"));
            }
            if (variables.containsKey("VehicleModel")) {
                resultVariables.put("VehicleModel", variables.get("VehicleModel"));
            }
            if (variables.containsKey("customerName")) {
                resultVariables.put("customerName", variables.get("customerName"));
            }
            if (variables.containsKey("customerEmail")) {
                resultVariables.put("customerEmail", variables.get("customerEmail"));
            }

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

            // Extract customer info
            String customerEmail = (String) variables.getOrDefault("customerEmail", "customer@example.com");
            String customerName = (String) variables.getOrDefault("customerName", "Customer");
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Vehicle");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Model");

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
            resultVariables.put("VehicleMake", vehicleMake);
            resultVariables.put("VehicleModel", vehicleModel);
            resultVariables.put("customerName", customerName);
            resultVariables.put("customerEmail", customerEmail);

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
            double finalPrice = ((Number) variables.getOrDefault("finalPrice", 0.0)).doubleValue();
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Vehicle");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Model");
            String customerEmail = (String) variables.getOrDefault("customerEmail", "customer@example.com");
            String customerName = (String) variables.getOrDefault("customerName", "Customer");

            // In a real implementation, this would send an actual notification
            System.out.println("Sending final quote to customer: " + customerName);
            System.out.println("Customer email: " + customerEmail);
            System.out.println("Vehicle: " + vehicleMake + " " + vehicleModel);
            System.out.println("Final price: $" + finalPrice);

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("quoteSent", true);
            resultVariables.put("quoteTimestamp", System.currentTimeMillis());
            resultVariables.put("VehicleMake", vehicleMake);
            resultVariables.put("VehicleModel", vehicleModel);
            resultVariables.put("customerName", customerName);
            resultVariables.put("customerEmail", customerEmail);
            resultVariables.put("finalPrice", finalPrice);

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
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Vehicle");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Model");

            // In a real implementation, this would send an actual notification
            System.out.println("Notifying reception that vehicle repairs are complete");
            System.out.println("Vehicle: " + vehicleMake + " " + vehicleModel);

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("workCompleteNotificationSent", true);
            resultVariables.put("completionTimestamp", System.currentTimeMillis());
            resultVariables.put("VehicleMake", vehicleMake);
            resultVariables.put("VehicleModel", vehicleModel);

            // Preserve customer information
            if (variables.containsKey("customerName")) {
                resultVariables.put("customerName", variables.get("customerName"));
            }
            if (variables.containsKey("customerEmail")) {
                resultVariables.put("customerEmail", variables.get("customerEmail"));
            }

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
            String location = (String) variables.getOrDefault("breakdownLocation", "Unknown location");
            String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Unknown make");
            String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Unknown model");
            String faultDescription = (String) variables.getOrDefault("DescriptionOfFault", "No description provided");
            String towInfoAdditional = (String) variables.getOrDefault("towInfoAdditional", "");

            // Combine vehicle information
            String vehicleDetails = vehicleMake + " " + vehicleModel + " - " + faultDescription;

            // In a real implementation, this would send an actual tow request
            System.out.println("Sending tow request to towing service");
            System.out.println("Breakdown location: " + location);
            System.out.println("Vehicle details: " + vehicleDetails);
            if (!towInfoAdditional.isEmpty()) {
                System.out.println("Additional info: " + towInfoAdditional);
            }

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("towRequestSent", true);
            resultVariables.put("estimatedTowArrival", "Within 60 minutes"); // In reality, would come from tow service

            // Preserve all important variables
            resultVariables.put("breakdownLocation", location);
            resultVariables.put("vehicleDetails", vehicleDetails);
            resultVariables.put("VehicleMake", vehicleMake);
            resultVariables.put("VehicleModel", vehicleModel);
            resultVariables.put("DescriptionOfFault", faultDescription);
            resultVariables.put("towInfoAdditional", towInfoAdditional);

            // Preserve customer information
            if (variables.containsKey("customerName")) {
                resultVariables.put("customerName", variables.get("customerName"));
            }
            if (variables.containsKey("customerEmail")) {
                resultVariables.put("customerEmail", variables.get("customerEmail"));
            }
            if (variables.containsKey("isMember")) {
                resultVariables.put("isMember", variables.get("isMember"));
            }

            client.newCompleteCommand(job.getKey())
                  .variables(resultVariables)
                  .send()
                  .exceptionally((throwable -> {
                      throw new RuntimeException("Could not send tow request", throwable);
                  }));

        } catch (Exception e) {
            client.newFailCommand(job.getKey())
                  .retries(job.getRetries() - 1)
                  .errorMessage("Error sending tow request: " + e.getMessage())
                  .send();
        }
    }
}
