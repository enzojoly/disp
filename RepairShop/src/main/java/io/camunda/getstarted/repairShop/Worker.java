/**
 * Worker to process tow request from the initial form
 */
@ZeebeWorker(type = "process-tow-request")
public void processTowRequest(final JobClient client, final ActivatedJob job) {
    Map<String, Object> variables = job.getVariablesAsMap();

    try {
        // Identify and log all variables for debugging
        logger.info("Received variables: {}", variables.keySet());

        // Extract vehicle information
        String vehicleMake = (String) variables.getOrDefault("VehicleMake", "Unknown make");
        String vehicleModel = (String) variables.getOrDefault("VehicleModel", "Unknown model");

        // Get fault description - try alternative variable names
        String faultDescription = null;
        if (variables.containsKey("DescriptionOfFault")) {
            faultDescription = (String) variables.get("DescriptionOfFault");
        } else if (variables.containsKey("faultDescription")) {
            faultDescription = (String) variables.get("faultDescription");
        } else {
            faultDescription = "No description provided";
        }

        // Try to find breakdown location - multiple possible variable names
        String breakdownLocation = null;
        if (variables.containsKey("breakdownLocation")) {
            breakdownLocation = (String) variables.get("breakdownLocation");
        } else if (variables.containsKey("vehicleLocation")) {
            breakdownLocation = (String) variables.get("vehicleLocation");
        } else if (variables.containsKey("location")) {
            breakdownLocation = (String) variables.get("location");
        } else {
            breakdownLocation = "Unknown location";
        }

        // Try to find additional towing information
        String towInfoAdditional = null;
        if (variables.containsKey("towInfoAdditional")) {
            towInfoAdditional = (String) variables.get("towInfoAdditional");
        } else if (variables.containsKey("additionalInfo")) {
            towInfoAdditional = (String) variables.get("additionalInfo");
        } else if (variables.containsKey("towInfo")) {
            towInfoAdditional = (String) variables.get("towInfo");
        } else {
            towInfoAdditional = "";
        }

        // Get membership status - default to false unless specifically set to true in the form
        boolean isMember = Boolean.TRUE.equals(variables.get("isMember"));

        logger.info("Processing tow request for {} {}", vehicleMake, vehicleModel);
        logger.info("Vehicle location: {}", breakdownLocation);
        logger.info("Fault description: {}", faultDescription);
        logger.info("Additional info: {}", towInfoAdditional);

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
