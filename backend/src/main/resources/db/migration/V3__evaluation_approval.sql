CREATE TABLE manager_evaluations (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  target_id BIGINT NOT NULL,
  revision_no INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  summary VARCHAR(3000) NULL,
  weighted_score DECIMAL(4,2) NULL,
  grade VARCHAR(10) NULL,
  submitted_at TIMESTAMP(6) NULL,
  finalized_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_manager_evaluation_revision UNIQUE (target_id, revision_no),
  CONSTRAINT fk_manager_evaluation_target FOREIGN KEY (target_id) REFERENCES evaluation_targets(id)
);

CREATE TABLE manager_evaluation_details (
  id BIGSERIAL PRIMARY KEY,
  manager_evaluation_id BIGINT NOT NULL,
  axis_code VARCHAR(30) NOT NULL,
  level SMALLINT NOT NULL,
  comment VARCHAR(1500) NULL,
  CONSTRAINT uq_manager_detail_axis UNIQUE (manager_evaluation_id, axis_code),
  CONSTRAINT chk_manager_level CHECK (level BETWEEN 1 AND 5),
  CONSTRAINT fk_manager_detail_evaluation FOREIGN KEY (manager_evaluation_id) REFERENCES manager_evaluations(id)
);

CREATE TABLE evaluation_workflow_events (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  target_id BIGINT NOT NULL,
  manager_evaluation_id BIGINT NULL,
  actor_account_id BIGINT NOT NULL,
  action VARCHAR(40) NOT NULL,
  from_status VARCHAR(30) NOT NULL,
  to_status VARCHAR(30) NOT NULL,
  reason VARCHAR(1000) NULL,
  comment VARCHAR(2000) NULL,
  late BOOLEAN NOT NULL DEFAULT FALSE,
  occurred_at TIMESTAMP(6) NOT NULL,
  trace_id CHAR(26) NOT NULL,
  CONSTRAINT fk_workflow_target FOREIGN KEY (target_id) REFERENCES evaluation_targets(id),
  CONSTRAINT fk_workflow_manager_evaluation FOREIGN KEY (manager_evaluation_id) REFERENCES manager_evaluations(id),
  CONSTRAINT fk_workflow_actor FOREIGN KEY (actor_account_id) REFERENCES accounts(id)
);

ALTER TABLE evaluation_targets ADD COLUMN current_manager_evaluation_id BIGINT NULL;
ALTER TABLE evaluation_targets ADD COLUMN final_score DECIMAL(4,2) NULL;
ALTER TABLE evaluation_targets ADD COLUMN final_grade VARCHAR(10) NULL;
ALTER TABLE evaluation_targets ADD COLUMN finalized_at TIMESTAMP(6) NULL;
ALTER TABLE evaluation_targets ADD CONSTRAINT fk_target_current_manager_evaluation
  FOREIGN KEY (current_manager_evaluation_id) REFERENCES manager_evaluations(id);

UPDATE evaluation_targets SET status='SELF_RETURNED' WHERE status='RETURNED';
UPDATE evaluation_criteria_versions
SET grade_boundaries_json=CAST('{"S":4.50,"A":4.00,"B":3.00,"C":0.00}' AS JSONB)
WHERE CAST(grade_boundaries_json AS VARCHAR) LIKE '%provisional%';

CREATE INDEX idx_manager_evaluations_target_status ON manager_evaluations(target_id, status, revision_no);
CREATE INDEX idx_workflow_events_target_time ON evaluation_workflow_events(target_id, occurred_at);
CREATE INDEX idx_evaluation_targets_status_evaluator ON evaluation_targets(status, evaluator_employee_id);
