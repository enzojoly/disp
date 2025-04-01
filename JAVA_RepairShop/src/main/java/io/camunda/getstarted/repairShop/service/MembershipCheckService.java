package io.camunda.getstarted.repairShop.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class MembershipCheckService {

    private static final Logger logger = LoggerFactory.getLogger(MembershipCheckService.class);

    // File path for the membership CSV file - configurable via application.properties or .env
    @Value("${membership.data.file-path:src/main/resources/data/members.csv}")
    private String membershipFilePath;

    // Map to cache existing membership numbers for faster lookups (optional)
    private Map<String, String> membershipCache;

    /**
     * Initializes the membership service and ensures the CSV file exists
     */
    public MembershipCheckService() {
        membershipCache = new HashMap<>();
    }

    /**
     * Validates if a membership number exists in the CSV file
     * @param membershipNumber The 6-digit membership number to validate
     * @return true if the membership number is valid, false otherwise
     */
    public boolean validateMembershipNumber(String membershipNumber) {
        // Validate format first (should be 6 digits)
        if (membershipNumber == null || !membershipNumber.matches("\\d{6}")) {
            logger.warn("Invalid membership number format: {}", membershipNumber);
            return false;
        }

        // Check cache first for performance
        if (membershipCache.containsKey(membershipNumber)) {
            logger.info("Membership number found in cache: {}", membershipNumber);
            return true;
        }

        // Otherwise, read from CSV file
        try {
            // Ensure file exists
            File membershipFile = new File(membershipFilePath);
            if (!membershipFile.exists()) {
                logger.info("Membership file does not exist, creating new file at: {}", membershipFilePath);
                createMembershipFile();
                return false;
            }

            // Read and check CSV file
            try (BufferedReader reader = new BufferedReader(new FileReader(membershipFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length >= 2 && parts[0].trim().equals(membershipNumber)) {
                        // Add to cache for future lookups
                        membershipCache.put(membershipNumber, parts[1].trim());
                        logger.info("Membership number validated: {}", membershipNumber);
                        return true;
                    }
                }
            }

            logger.warn("Membership number not found: {}", membershipNumber);
            return false;

        } catch (IOException e) {
            logger.error("Error validating membership number: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Generates a new unique 6-digit membership number
     * @return A new membership number that doesn't exist in the CSV file
     */
    public String generateMembershipNumber() {
        Set<String> existingNumbers = getAllMembershipNumbers();
        String newNumber;

        // Generate a random 6-digit number that doesn't already exist
        do {
            newNumber = String.format("%06d", ThreadLocalRandom.current().nextInt(1, 1000000));
        } while (existingNumbers.contains(newNumber));

        logger.info("Generated new membership number: {}", newNumber);
        return newNumber;
    }

    /**
     * Adds a new member to the CSV file
     * @param membershipNumber The 6-digit membership number
     * @param customerName The name of the customer
     * @return true if the member was added successfully, false otherwise
     */
    public boolean addNewMember(String membershipNumber, String customerName) {
        // Validate format
        if (membershipNumber == null || !membershipNumber.matches("\\d{6}")) {
            logger.warn("Invalid membership number format for new member: {}", membershipNumber);
            return false;
        }

        // Ensure customer name doesn't contain commas to avoid CSV format issues
        String safeName = customerName.replace(",", "");

        try {
            // Ensure file and parent directories exist
            File membershipFile = new File(membershipFilePath);
            if (!membershipFile.exists()) {
                createMembershipFile();
            }

            // Append the new member to the CSV file
            Path path = Paths.get(membershipFilePath);
            String lineToAdd = membershipNumber + "," + safeName + "\n";
            Files.write(path, lineToAdd.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);

            // Add to cache
            membershipCache.put(membershipNumber, safeName);

            logger.info("Added new member: {} with number: {}", safeName, membershipNumber);
            return true;

        } catch (IOException e) {
            logger.error("Error adding new member: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Gets all existing membership numbers from the CSV file
     * @return A set of all membership numbers
     */
    private Set<String> getAllMembershipNumbers() {
        Set<String> numbers = new HashSet<>();

        try {
            // Ensure file exists
            File membershipFile = new File(membershipFilePath);
            if (!membershipFile.exists()) {
                logger.info("Membership file does not exist, creating new file at: {}", membershipFilePath);
                createMembershipFile();
                return numbers;
            }

            // Read all membership numbers
            try (BufferedReader reader = new BufferedReader(new FileReader(membershipFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length >= 2) {
                        numbers.add(parts[0].trim());
                        // Update cache
                        membershipCache.put(parts[0].trim(), parts[1].trim());
                    }
                }
            }

        } catch (IOException e) {
            logger.error("Error reading membership numbers: {}", e.getMessage(), e);
        }

        return numbers;
    }

    /**
     * Creates the membership CSV file if it doesn't exist
     */
    private void createMembershipFile() throws IOException {
        File file = new File(membershipFilePath);

        // Create parent directories if needed
        File parentDir = file.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Create file with header
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("MembershipNumber,CustomerName\n");
        }

        logger.info("Created new membership file at: {}", membershipFilePath);
    }
}
