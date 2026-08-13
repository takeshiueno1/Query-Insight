CREATE TABLE profile_status_snapshots (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  skill_score DECIMAL(5,2) NOT NULL,
  knowledge_score DECIMAL(5,2) NOT NULL,
  career_score DECIMAL(5,2) NOT NULL,
  certification_score DECIMAL(5,2) NOT NULL,
  total_score DECIMAL(5,2) NOT NULL,
  grade VARCHAR(1) NOT NULL,
  formula_version VARCHAR(30) NOT NULL,
  source_fingerprint CHAR(64) NOT NULL,
  calculated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT chk_profile_status_grade CHECK (grade IN ('S','A','B','C','D','F')),
  CONSTRAINT chk_profile_status_scores CHECK (
    skill_score BETWEEN 0 AND 100 AND knowledge_score BETWEEN 0 AND 100
    AND career_score BETWEEN 0 AND 100 AND certification_score BETWEEN 0 AND 100
    AND total_score BETWEEN 0 AND 100),
  CONSTRAINT fk_profile_status_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT uq_profile_status_source UNIQUE (employee_id, formula_version, source_fingerprint)
);
CREATE INDEX idx_profile_status_employee_time ON profile_status_snapshots(employee_id, calculated_at DESC, id DESC);

ALTER TABLE manager_evaluations ADD COLUMN profile_status_snapshot_id BIGINT NULL;
ALTER TABLE manager_evaluations ADD CONSTRAINT fk_manager_profile_status
  FOREIGN KEY (profile_status_snapshot_id) REFERENCES profile_status_snapshots(id);

ALTER TABLE manager_evaluation_details DROP CONSTRAINT chk_manager_level;
ALTER TABLE manager_evaluation_details ADD CONSTRAINT chk_manager_level CHECK (level BETWEEN 0 AND 5);

ALTER TABLE master_addition_requests ADD COLUMN request_type VARCHAR(100) NULL;
ALTER TABLE master_addition_requests ADD COLUMN request_description VARCHAR(1000) NULL;
UPDATE master_addition_requests
SET request_type=master_type,
    request_description=COALESCE(CAST(proposed_payload_json AS VARCHAR),'')
WHERE request_type IS NULL;
ALTER TABLE master_addition_requests ALTER COLUMN request_type SET NOT NULL;
ALTER TABLE master_addition_requests ALTER COLUMN request_description SET NOT NULL;
ALTER TABLE master_addition_requests ALTER COLUMN master_type DROP NOT NULL;
ALTER TABLE master_addition_requests ALTER COLUMN proposed_payload_json DROP NOT NULL;
