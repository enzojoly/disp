package io.camunda.getstarted.repairShop;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
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
    //Worker Type
    @ZeebeWorker(type = "validateTrips")
    public void CheckCelebAge(final JobClient client, final ActivatedJob job) {

        //Getting the process variables

        Map<String, Object> variablesAsMap = job.getVariablesAsMap();
        //dob is the field name of the date field (camunda form)
        String dateOfBirth = (String) variablesAsMap.get("dob");

        try {
            System.out.println("Going to check age for "+ dateOfBirth);
            LocalDate currentDate = LocalDate.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            // Convert string to LocalDate
            LocalDate birthDate = LocalDate.parse(dateOfBirth, formatter);
            // Calculate age
            int age = Period.between(birthDate, currentDate).getYears();


            int yearOfBirth = Integer.parseInt(dateOfBirth.split("-")[0]);
            System.out.println(yearOfBirth);


            if(age > 3) {

               // Setting Process variables
                // Create a HashMap of the Variables
                HashMap<String, Object> variables = new HashMap<>();
                if(age>=18){
                    //Add variables to the HashMap
                    variables.put("age", age);
                    variables.put("isAdult", true);
                } else {
                    variables.put("age", age);
                    variables.put("isAdult", false);
                }

                //Completing the job
                client.newCompleteCommand(job.getKey())
                        //Sending the process variables back to the process
                        .variables(variables)
                        .send()
                        //Handling any exceptions
                        .exceptionally((throwable -> {
                            throw new RuntimeException("Could not complete job", throwable);
                        }));
            } else {
                //Completing the job with trigger the error
                client.newThrowErrorCommand(job.getKey())
                        //Same error code provided in the diagram
                        .errorCode("no_trip")
                        .errorMessage("No Trip Available For Kids Below 3 years")
                        .send()
                        .exceptionally((throwable -> {
                            throw new RuntimeException("Could not throw the BPMN Error Event", throwable);
                        }));
            }
        } catch(Exception e) {
            int retries = job.getRetries() - 1;

            e.printStackTrace();
            client.newFailCommand(job.getKey())
                .retries(retries)
                .send();
        }
    }
}
