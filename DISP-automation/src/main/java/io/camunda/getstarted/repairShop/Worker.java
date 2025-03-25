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
            // Simple flat deposit amount
            double deposit = 150.0;

            System.out.println("Calculated deposit: " + deposit);

            // Create output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("depositAmount", deposit);

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
            // Get repair cost from previous task
            double repairCost = ((Number) variables.getOrDefault("repairCost", 0.0)).doubleValue();
            boolean isMember = (boolean) variables.getOrDefault("isMember", false);

            // Apply 10% discount for members
            double discountPercentage = isMember ? 10.0 : 0.0;
            double finalPrice = repairCost * (1 - (discountPercentage / 100.0));

            System.out.println("Original cost: " + repairCost);
            System.out.println("Is member: " + isMember);
            System.out.println("Final price: " + finalPrice);

            // Output variables
            HashMap<String, Object> resultVariables = new HashMap<>();
            resultVariables.put("finalPrice", finalPrice);
            resultVariables.put("discountApplied", isMember);

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
}
