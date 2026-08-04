-- Beauty Kit pilot: the two questions asked on a finished nail result.
--
-- Two enum answers, the branch they came from, the kit status they were looking at, the prompt they typed,
-- and a timestamp. No session id, no user id, no IP, no device string — this is a feedback form, not an
-- analytics pipeline, and adding an identifier would give it a consent story the pilot does not have.
--
-- Every answer column is NULLABLE because both questions are optional: a user may answer one and skip the
-- other, and a half answer is still worth more than a dropped one. created_at is the only NOT NULL column.

CREATE TABLE IF NOT EXISTS public.nail_feedback (
    id                  BIGSERIAL PRIMARY KEY,
    matched_expectation VARCHAR(16),
    would_use           VARCHAR(16),
    execution_mode      VARCHAR(16),
    kit_status          VARCHAR(48),
    prompt              VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL
);

-- The only read pattern is "the newest answers" when reviewing the pilot.
CREATE INDEX IF NOT EXISTS idx_nail_feedback_created_at ON public.nail_feedback (created_at DESC);
