CREATE TABLE talent_submissions (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  talent_type VARCHAR(20) NOT NULL,
  logical_public_id CHAR(26) NOT NULL,
  revision_no INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  payload_json JSONB NOT NULL,
  base_record_version BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  submitted_at TIMESTAMP(6) NULL,
  decided_at TIMESTAMP(6) NULL,
  reviewer_account_id BIGINT NULL,
  return_reason VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_talent_submission_revision
    UNIQUE (employee_id, talent_type, logical_public_id, revision_no),
  CONSTRAINT chk_talent_submission_revision CHECK (revision_no >= 1),
  CONSTRAINT chk_talent_submission_type
    CHECK (talent_type IN ('SKILL','KNOWLEDGE','CAREER','CERTIFICATION')),
  CONSTRAINT chk_talent_submission_status
    CHECK (status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','SUPERSEDED')),
  CONSTRAINT chk_talent_submission_return_reason CHECK (
    (status = 'RETURNED' AND return_reason IS NOT NULL AND LENGTH(TRIM(return_reason)) > 0)
    OR (status <> 'RETURNED' AND return_reason IS NULL)
  ),
  CONSTRAINT fk_talent_submission_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT fk_talent_submission_reviewer FOREIGN KEY (reviewer_account_id) REFERENCES accounts(id)
);
CREATE INDEX idx_talent_submission_employee_status
  ON talent_submissions(employee_id, status, updated_at DESC);
CREATE INDEX idx_talent_submission_logical_revision
  ON talent_submissions(employee_id, talent_type, logical_public_id, revision_no DESC);
CREATE INDEX idx_talent_submission_review_queue
  ON talent_submissions(status, submitted_at, employee_id);

CREATE TABLE talent_submission_events (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  submission_id BIGINT NOT NULL,
  actor_account_id BIGINT NULL,
  action VARCHAR(30) NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status VARCHAR(20) NOT NULL,
  reason VARCHAR(1000) NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  trace_id CHAR(26) NOT NULL,
  CONSTRAINT chk_talent_event_action
    CHECK (action IN ('CREATE','SAVE','ATTACH','DETACH','SUBMIT','RETURN','APPROVE','SUPERSEDE')),
  CONSTRAINT chk_talent_event_from_status CHECK (
    from_status IS NULL OR from_status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','SUPERSEDED')
  ),
  CONSTRAINT chk_talent_event_to_status
    CHECK (to_status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','SUPERSEDED')),
  CONSTRAINT fk_talent_event_submission FOREIGN KEY (submission_id) REFERENCES talent_submissions(id),
  CONSTRAINT fk_talent_event_actor FOREIGN KEY (actor_account_id) REFERENCES accounts(id)
);
CREATE INDEX idx_talent_event_submission_occurred
  ON talent_submission_events(submission_id, occurred_at, id);

CREATE TABLE talent_attachments (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  submission_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(50) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  scan_status VARCHAR(20) NOT NULL,
  content BYTEA NULL,
  scan_attempts INTEGER NOT NULL DEFAULT 0,
  last_scan_error_code VARCHAR(60) NULL,
  next_scan_at TIMESTAMP(6) NULL,
  scanned_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_talent_attachment_hash UNIQUE (submission_id, sha256),
  CONSTRAINT chk_talent_attachment_content_type
    CHECK (content_type IN ('application/pdf','image/jpeg','image/png')),
  CONSTRAINT chk_talent_attachment_size CHECK (size_bytes BETWEEN 1 AND 5242880),
  CONSTRAINT chk_talent_attachment_status
    CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','ERROR')),
  CONSTRAINT chk_talent_attachment_attempts CHECK (scan_attempts >= 0),
  CONSTRAINT chk_talent_attachment_content CHECK (
    (scan_status IN ('PENDING','CLEAN','ERROR') AND content IS NOT NULL)
    OR (scan_status = 'INFECTED' AND content IS NULL)
  ),
  CONSTRAINT fk_talent_attachment_submission FOREIGN KEY (submission_id) REFERENCES talent_submissions(id)
);
CREATE INDEX idx_talent_attachment_retry
  ON talent_attachments(scan_status, next_scan_at, id);

CREATE TABLE master_addition_requests (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  requested_by_account_id BIGINT NOT NULL,
  master_type VARCHAR(20) NOT NULL,
  proposed_payload_json JSONB NOT NULL,
  status VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  return_reason VARCHAR(1000) NULL,
  requested_at TIMESTAMP(6) NOT NULL,
  decided_at TIMESTAMP(6) NULL,
  decided_by_account_id BIGINT NULL,
  created_master_public_id CHAR(26) NULL,
  CONSTRAINT chk_master_request_type CHECK (master_type IN ('SKILL','CERTIFICATION')),
  CONSTRAINT chk_master_request_status CHECK (status IN ('SUBMITTED','APPROVED','RETURNED')),
  CONSTRAINT chk_master_request_reason CHECK (
    (status = 'RETURNED' AND return_reason IS NOT NULL AND LENGTH(TRIM(return_reason)) > 0)
    OR (status <> 'RETURNED' AND return_reason IS NULL)
  ),
  CONSTRAINT fk_master_request_requester FOREIGN KEY (requested_by_account_id) REFERENCES accounts(id),
  CONSTRAINT fk_master_request_decider FOREIGN KEY (decided_by_account_id) REFERENCES accounts(id)
);
CREATE INDEX idx_master_request_status_requested
  ON master_addition_requests(status, requested_at, id);
CREATE INDEX idx_master_request_requester
  ON master_addition_requests(requested_by_account_id, requested_at DESC);

INSERT INTO talent_submissions(
  public_id,employee_id,talent_type,logical_public_id,revision_no,status,payload_json,
  base_record_version,version,decided_at,created_at,updated_at)
SELECT es.public_id,es.employee_id,'SKILL',es.public_id,1,'APPROVED',
  JSON_OBJECT(
    'masterPublicId' VALUE sm.public_id,
    'level' VALUE es.proficiency_level,
    'yearsExperience' VALUE es.years_experience,
    'lastUsedOn' VALUE es.last_used_on,
    'evidence' VALUE es.evidence
  ),
  es.version,0,es.updated_at,es.created_at,es.updated_at
FROM employee_skills es
JOIN skill_masters sm ON sm.id=es.skill_id
WHERE NOT EXISTS (
  SELECT 1 FROM talent_submissions ts
  WHERE ts.employee_id=es.employee_id AND ts.talent_type='SKILL'
    AND ts.logical_public_id=es.public_id AND ts.revision_no=1
);

INSERT INTO talent_submissions(
  public_id,employee_id,talent_type,logical_public_id,revision_no,status,payload_json,
  base_record_version,version,decided_at,created_at,updated_at)
SELECT ek.public_id,ek.employee_id,'KNOWLEDGE',ek.public_id,1,'APPROVED',
  JSON_OBJECT(
    'masterPublicId' VALUE km.public_id,
    'level' VALUE ek.proficiency_level,
    'evidence' VALUE ek.evidence
  ),
  ek.version,0,ek.updated_at,ek.created_at,ek.updated_at
FROM employee_knowledge ek
JOIN knowledge_masters km ON km.id=ek.knowledge_id
WHERE NOT EXISTS (
  SELECT 1 FROM talent_submissions ts
  WHERE ts.employee_id=ek.employee_id AND ts.talent_type='KNOWLEDGE'
    AND ts.logical_public_id=ek.public_id AND ts.revision_no=1
);

INSERT INTO talent_submissions(
  public_id,employee_id,talent_type,logical_public_id,revision_no,status,payload_json,
  base_record_version,version,decided_at,created_at,updated_at)
SELECT ch.public_id,ch.employee_id,'CAREER',ch.public_id,1,'APPROVED',
  JSON_OBJECT(
    'projectName' VALUE ch.project_name,
    'industry' VALUE ch.industry,
    'roleName' VALUE ch.role_name,
    'startDate' VALUE ch.start_date,
    'endDate' VALUE ch.end_date,
    'summary' VALUE ch.summary,
    'achievements' VALUE ch.achievements,
    'technologies' VALUE ch.technologies
  ),
  ch.version,0,ch.updated_at,ch.created_at,ch.updated_at
FROM career_histories ch
WHERE NOT EXISTS (
  SELECT 1 FROM talent_submissions ts
  WHERE ts.employee_id=ch.employee_id AND ts.talent_type='CAREER'
    AND ts.logical_public_id=ch.public_id AND ts.revision_no=1
);

INSERT INTO talent_submissions(
  public_id,employee_id,talent_type,logical_public_id,revision_no,status,payload_json,
  base_record_version,version,decided_at,created_at,updated_at)
SELECT ec.public_id,ec.employee_id,'CERTIFICATION',ec.public_id,1,'APPROVED',
  JSON_OBJECT(
    'masterPublicId' VALUE cm.public_id,
    'acquiredOn' VALUE ec.acquired_on,
    'expiresOn' VALUE ec.expires_on,
    'credentialReference' VALUE ec.credential_reference,
    'verificationStatus' VALUE ec.verification_status
  ),
  ec.version,0,ec.updated_at,ec.created_at,ec.updated_at
FROM employee_certifications ec
JOIN certification_masters cm ON cm.id=ec.certification_id
WHERE NOT EXISTS (
  SELECT 1 FROM talent_submissions ts
  WHERE ts.employee_id=ec.employee_id AND ts.talent_type='CERTIFICATION'
    AND ts.logical_public_id=ec.public_id AND ts.revision_no=1
);
