CREATE TABLE departments (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  code VARCHAR(30) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  parent_id BIGINT NULL,
  status VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_departments_parent FOREIGN KEY (parent_id) REFERENCES departments(id)
);

CREATE TABLE employees (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_no VARCHAR(30) NOT NULL UNIQUE,
  last_name VARCHAR(50) NOT NULL,
  first_name VARCHAR(50) NOT NULL,
  email VARCHAR(254) NOT NULL UNIQUE,
  department_id BIGINT NULL,
  manager_employee_id BIGINT NULL,
  position_name VARCHAR(100) NULL,
  employment_status VARCHAR(20) NOT NULL,
  hire_date DATE NULL,
  retirement_date DATE NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_employees_department FOREIGN KEY (department_id) REFERENCES departments(id),
  CONSTRAINT fk_employees_manager FOREIGN KEY (manager_employee_id) REFERENCES employees(id)
);
CREATE INDEX idx_employees_department_status ON employees(department_id, employment_status);
CREATE INDEX idx_employees_manager_status ON employees(manager_employee_id, employment_status);

CREATE TABLE accounts (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL UNIQUE,
  login_id_normalized VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,
  failed_count INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP(6) NULL,
  password_changed_at TIMESTAMP(6) NOT NULL,
  last_login_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_accounts_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
);

CREATE TABLE roles (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL
);

CREATE TABLE permission_grants (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  account_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  scope_type VARCHAR(30) NOT NULL,
  scope_target_public_id CHAR(26) NULL,
  valid_from TIMESTAMP(6) NOT NULL,
  valid_to TIMESTAMP(6) NULL,
  reason VARCHAR(500) NOT NULL,
  granted_by BIGINT NULL,
  revoked_at TIMESTAMP(6) NULL,
  CONSTRAINT fk_grants_account FOREIGN KEY (account_id) REFERENCES accounts(id),
  CONSTRAINT fk_grants_role FOREIGN KEY (role_id) REFERENCES roles(id),
  CONSTRAINT fk_grants_actor FOREIGN KEY (granted_by) REFERENCES accounts(id)
);
CREATE INDEX idx_grants_account_validity ON permission_grants(account_id, revoked_at, valid_to);

CREATE TABLE refresh_tokens (
  id BIGSERIAL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  family_id CHAR(26) NOT NULL,
  issued_at TIMESTAMP(6) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  used_at TIMESTAMP(6) NULL,
  revoked_at TIMESTAMP(6) NULL,
  replaced_by_id BIGINT NULL,
  client_fingerprint VARCHAR(128) NULL,
  CONSTRAINT fk_refresh_account FOREIGN KEY (account_id) REFERENCES accounts(id),
  CONSTRAINT fk_refresh_replacement FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens(id)
);
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_expiry ON refresh_tokens(expires_at);

CREATE TABLE evaluation_criteria_versions (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  version_name VARCHAR(60) NOT NULL UNIQUE,
  effective_from DATE NOT NULL,
  status VARCHAR(20) NOT NULL,
  grade_boundaries_json JSONB NOT NULL,
  published_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE evaluation_criteria (
  id BIGSERIAL PRIMARY KEY,
  criteria_version_id BIGINT NOT NULL,
  axis_code VARCHAR(30) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  weight DECIMAL(5,2) NOT NULL,
  sort_order INT NOT NULL,
  CONSTRAINT uq_criteria_axis UNIQUE (criteria_version_id, axis_code),
  CONSTRAINT fk_criteria_version FOREIGN KEY (criteria_version_id) REFERENCES evaluation_criteria_versions(id)
);

CREATE TABLE evaluation_periods (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  self_deadline TIMESTAMP(6) NOT NULL,
  manager_deadline TIMESTAMP(6) NOT NULL,
  criteria_version_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_period_criteria_version FOREIGN KEY (criteria_version_id) REFERENCES evaluation_criteria_versions(id)
);

CREATE TABLE evaluation_targets (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  period_id BIGINT NOT NULL,
  employee_id BIGINT NOT NULL,
  evaluator_employee_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  provisional_score DECIMAL(4,2) NULL,
  provisional_grade VARCHAR(10) NULL,
  submitted_at TIMESTAMP(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_target_period_employee UNIQUE (period_id, employee_id),
  CONSTRAINT fk_target_period FOREIGN KEY (period_id) REFERENCES evaluation_periods(id),
  CONSTRAINT fk_target_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT fk_target_evaluator FOREIGN KEY (evaluator_employee_id) REFERENCES employees(id)
);

CREATE TABLE self_evaluation_details (
  id BIGSERIAL PRIMARY KEY,
  target_id BIGINT NOT NULL,
  axis_code VARCHAR(30) NOT NULL,
  level SMALLINT NOT NULL,
  evidence VARCHAR(1500) NOT NULL,
  CONSTRAINT uq_self_detail_axis UNIQUE (target_id, axis_code),
  CONSTRAINT chk_self_level CHECK (level BETWEEN 1 AND 5),
  CONSTRAINT fk_self_target FOREIGN KEY (target_id) REFERENCES evaluation_targets(id)
);

CREATE TABLE notifications (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  recipient_account_id BIGINT NOT NULL,
  type VARCHAR(40) NOT NULL,
  title VARCHAR(150) NOT NULL,
  body VARCHAR(1000) NOT NULL,
  link_path VARCHAR(500) NULL,
  read_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  dedupe_key VARCHAR(150) NOT NULL UNIQUE,
  CONSTRAINT fk_notification_account FOREIGN KEY (recipient_account_id) REFERENCES accounts(id)
);
CREATE INDEX idx_notifications_recipient ON notifications(recipient_account_id, read_at, created_at);

CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  occurred_at TIMESTAMP(6) NOT NULL,
  actor_account_id BIGINT NULL,
  action VARCHAR(60) NOT NULL,
  target_type VARCHAR(50) NOT NULL,
  target_public_id CHAR(26) NULL,
  result VARCHAR(20) NOT NULL,
  data_scope VARCHAR(30) NULL,
  diff_json JSONB NULL,
  ip_hash CHAR(64) NULL,
  user_agent_hash CHAR(64) NULL,
  trace_id CHAR(26) NOT NULL,
  prev_hash CHAR(64) NOT NULL,
  record_hash CHAR(64) NOT NULL,
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_account_id) REFERENCES accounts(id)
);
CREATE INDEX idx_audit_occurred ON audit_logs(occurred_at);
CREATE INDEX idx_audit_action_target ON audit_logs(action, target_type, target_public_id);

CREATE TABLE ai_analysis_results (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  evaluation_target_id BIGINT NOT NULL,
  provider VARCHAR(30) NOT NULL,
  model VARCHAR(100) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  response_json JSONB NOT NULL,
  generated_at TIMESTAMP(6) NOT NULL,
  generated_by_account_id BIGINT NOT NULL,
  CONSTRAINT fk_ai_result_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT fk_ai_result_target FOREIGN KEY (evaluation_target_id) REFERENCES evaluation_targets(id),
  CONSTRAINT fk_ai_result_actor FOREIGN KEY (generated_by_account_id) REFERENCES accounts(id)
);
CREATE INDEX idx_ai_result_employee_generated ON ai_analysis_results(employee_id, generated_at DESC);
