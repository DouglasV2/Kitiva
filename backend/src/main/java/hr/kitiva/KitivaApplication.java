package hr.kitiva;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling exists for exactly one job: RateLimitFilter's bucket cleanup. The crons this comment used
// to list (retention, catalog audit, billing reconciliation) belonged to the furniture app and are gone with
// it. Drop the annotation if that last @Scheduled ever goes, rather than leaving a scheduler running for none.
@SpringBootApplication
@EnableScheduling
public class KitivaApplication {
    public static void main(String[] args) {
        SpringApplication.run(KitivaApplication.class, args);
    }
}
