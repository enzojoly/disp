@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private CalendlyService calendlyService;

    /**
     * Test endpoint to simulate a webhook (for development and testing)
     */
    @GetMapping("/calendly/simulate")
    public ResponseEntity<Map<String, Object>> simulateWebhook(
            @RequestParam(defaultValue = "customer@example.com") String email,
            @RequestParam(defaultValue = "Test Customer") String name) {

        Map<String, Object> booking = calendlyService.simulateBooking(email, name);
        logger.info("Simulated booking for {}", name);
        return ResponseEntity.ok(booking);
    }
}
