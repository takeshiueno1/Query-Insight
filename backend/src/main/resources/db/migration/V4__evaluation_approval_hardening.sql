INSERT INTO roles(code,name,status)
SELECT 'EXECUTIVE','EXECUTIVE','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code='EXECUTIVE');

ALTER TABLE evaluation_workflow_events
  ADD COLUMN deadline_type VARCHAR(20) NOT NULL DEFAULT 'MANAGER';

UPDATE evaluation_workflow_events SET deadline_type='SELF' WHERE action='SELF_SUBMIT';
