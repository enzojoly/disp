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
     * Worker to calculate initial payment (deposit)
     */
    @ZeebeWorker(type = "InitialCostCheck")
    public void calculateInitialPayment(final JobClient client, final ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();

        try {
            // Set default values for demo purposes if not provided
            // In real scenario, these would come from the process or forms
            if (!variables.containsKey("isMember")) {
                variables.put("isMember", true); // Default customer is a member
            }

            // Simple flat deposit amount
            double deposit = 150.0;

            System.out.println("Calculated deposit: " + deposit);
            System.out.println("Customer is member: " + variables.get("isMember"));

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("depositAmount", deposit);
            resultVariables.put("isMember", variables.get("isMember")); // Pass along membership status

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
            boolean isMember = (boolean) variables.getOrDefault("isMember", false);

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
            // Check multiple possible variable names that might contain approval
            Boolean approved = false;

            // Check for "Approved" with capital A first (this is what your form uses)
            if (variables.containsKey("Approved")) {
                approved = (Boolean) variables.get("Approved");
                System.out.println("Found 'Approved' variable: " + approved);
            } else {
                System.out.println("No approval variable found, defaulting to false");
            }

            System.out.println("Processing customer approval: " + (approved ? "Approved" : "Denied"));

            // Set the process variable for the gateway condition
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("QuoteApproved", approved);

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

            // In a real implementation, you would send an email here

            // Output process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("collectionTimesOffered", true);

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

            logger.info("Processing booking for {} at {}",
                    customerName, bookingInfo.get("appointmentTime"));

            // In a real implementation, you might update an internal system
            // or send further notifications

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

            // Set process variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("bookingLink", bookingLink);
            resultVariables.put("notificationSent", true);

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

            // In a real implementation, this would send an actual notification
            System.out.println("Sending final quote to customer");
            System.out.println("Final price: $" + finalPrice);

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("quoteSent", true);
            resultVariables.put("quoteTimestamp", System.currentTimeMillis());

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
            // In a real implementation, this would send an actual notification
            System.out.println("Notifying reception that vehicle repairs are complete");

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("workCompleteNotificationSent", true);
            resultVariables.put("completionTimestamp", System.currentTimeMillis());

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
            String vehicleDetails = (String) variables.getOrDefault("vehicleDetails", "No details provided");

            // In a real implementation, this would send an actual tow request
            System.out.println("Sending tow request to towing service");
            System.out.println("Breakdown location: " + location);
            System.out.println("Vehicle details: " + vehicleDetails);

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("towRequestSent", true);
            resultVariables.put("estimatedTowArrival", "Within 60 minutes"); // In reality, would come from tow service

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
