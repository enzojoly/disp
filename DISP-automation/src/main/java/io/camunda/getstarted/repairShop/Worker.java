package io.camunda.getstarted.repairShop;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.EnableZeebeClient;
import io.camunda.zeebe.spring.client.annotation.ZeebeWorker;

@SpringBootApplication
@EnableZeebeClient
public class Worker {

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

            if (variables.containsKey("approved")) {
                approved = (Boolean) variables.get("approved");
            } else if (variables.containsKey("approval")) {
                approved = (Boolean) variables.get("approval");
            } else if (variables.containsKey("quoteApproved")) {
                approved = (Boolean) variables.get("quoteApproved");
            } else {
                // Default to false if no approval variable is found
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
     * Worker to send collection time notification
     */
    @ZeebeWorker(type = "ArrangeCollection")
    public void arrangeCollection(final JobClient client, final ActivatedJob job) {
        try {
            Map<String, Object> variables = job.getVariablesAsMap();

            // In a real implementation, this would send an actual notification
            // For now, we just log the action
            System.out.println("Sending collection time notification to customer");
            System.out.println("Vehicle repairs completed, available for collection");

            // Set some collection times (in a real scenario, these would be calculated)
            String[] availableTimes = {"Tomorrow 9am-12pm", "Tomorrow 1pm-5pm", "Day after 9am-5pm"};

            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("collectionTimesOffered", availableTimes);
            resultVariables.put("notificationSent", true);

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
