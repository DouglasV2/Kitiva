package ai.budgetspace.beauty.nail;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Pilot feedback rows. Write-only from the app's point of view — nothing in the product reads these back, so
 * there is no endpoint that could leak them. Review is a SQL query against {@code nail_feedback}; see
 * {@code docs/beauty-kit-test-baseline.md}.
 */
public interface NailFeedbackRepository extends JpaRepository<NailFeedback, Long> {
}
