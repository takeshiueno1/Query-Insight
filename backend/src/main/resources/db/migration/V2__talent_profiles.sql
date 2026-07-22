CREATE TABLE skill_masters (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  category VARCHAR(40) NOT NULL,
  description VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL
);

CREATE TABLE employee_skills (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  proficiency_level SMALLINT NOT NULL,
  years_experience DECIMAL(4,1) NOT NULL,
  last_used_on DATE NOT NULL,
  evidence VARCHAR(1000) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_employee_skill UNIQUE (employee_id, skill_id),
  CONSTRAINT chk_skill_level CHECK (proficiency_level BETWEEN 1 AND 5),
  CONSTRAINT chk_skill_years CHECK (years_experience BETWEEN 0 AND 60),
  CONSTRAINT fk_employee_skill_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT fk_employee_skill_master FOREIGN KEY (skill_id) REFERENCES skill_masters(id)
);
CREATE INDEX idx_employee_skills_employee ON employee_skills(employee_id, proficiency_level DESC);

CREATE TABLE knowledge_masters (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  category VARCHAR(40) NOT NULL,
  description VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL
);

CREATE TABLE employee_knowledge (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  knowledge_id BIGINT NOT NULL,
  proficiency_level SMALLINT NOT NULL,
  evidence VARCHAR(1000) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_employee_knowledge UNIQUE (employee_id, knowledge_id),
  CONSTRAINT chk_knowledge_level CHECK (proficiency_level BETWEEN 1 AND 5),
  CONSTRAINT fk_employee_knowledge_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT fk_employee_knowledge_master FOREIGN KEY (knowledge_id) REFERENCES knowledge_masters(id)
);
CREATE INDEX idx_employee_knowledge_employee ON employee_knowledge(employee_id, proficiency_level DESC);

CREATE TABLE career_histories (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  project_name VARCHAR(150) NOT NULL,
  industry VARCHAR(100) NOT NULL,
  role_name VARCHAR(100) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NULL,
  summary VARCHAR(1000) NOT NULL,
  achievements VARCHAR(1500) NOT NULL,
  technologies VARCHAR(1000) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_career_project_start UNIQUE (employee_id, project_name, start_date),
  CONSTRAINT chk_career_dates CHECK (end_date IS NULL OR end_date >= start_date),
  CONSTRAINT fk_career_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
);
CREATE INDEX idx_career_employee_start ON career_histories(employee_id, start_date DESC);

CREATE TABLE certification_masters (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  code VARCHAR(50) NOT NULL UNIQUE,
  name VARCHAR(150) NOT NULL,
  issuer VARCHAR(150) NOT NULL,
  status VARCHAR(20) NOT NULL
);

CREATE TABLE employee_certifications (
  id BIGSERIAL PRIMARY KEY,
  public_id CHAR(26) NOT NULL UNIQUE,
  employee_id BIGINT NOT NULL,
  certification_id BIGINT NOT NULL,
  acquired_on DATE NOT NULL,
  expires_on DATE NULL,
  credential_reference VARCHAR(100) NULL,
  verification_status VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_employee_certification UNIQUE (employee_id, certification_id),
  CONSTRAINT chk_certification_dates CHECK (expires_on IS NULL OR expires_on >= acquired_on),
  CONSTRAINT fk_employee_cert_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
  CONSTRAINT fk_employee_cert_master FOREIGN KEY (certification_id) REFERENCES certification_masters(id)
);
CREATE INDEX idx_employee_certifications_employee ON employee_certifications(employee_id, acquired_on DESC);
