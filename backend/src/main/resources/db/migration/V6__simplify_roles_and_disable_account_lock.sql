INSERT INTO roles(code,name,status)
SELECT 'GENERAL','一般','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code='GENERAL');

INSERT INTO roles(code,name,status)
SELECT 'OFFICER','役職者','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code='OFFICER');

INSERT INTO roles(code,name,status)
SELECT 'ADMIN','管理者','ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code='ADMIN');

UPDATE roles
SET name=CASE code
    WHEN 'GENERAL' THEN '一般'
    WHEN 'OFFICER' THEN '役職者'
    WHEN 'ADMIN' THEN '管理者'
  END,
  status='ACTIVE'
WHERE code IN ('GENERAL','OFFICER','ADMIN');

INSERT INTO permission_grants(
  public_id,account_id,role_id,scope_type,valid_from,reason
)
SELECT
  '06' || LPAD(CAST(a.id AS VARCHAR),24,'0'),
  a.id,
  (SELECT r.id FROM roles r WHERE r.code=CASE
      WHEN EXISTS (
        SELECT 1 FROM permission_grants g
        JOIN roles legacy ON legacy.id=g.role_id
        WHERE g.account_id=a.id AND g.revoked_at IS NULL
          AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
          AND legacy.code IN ('HR','SYSTEM_ADMIN','AUDITOR')
      ) THEN 'ADMIN'
      WHEN EXISTS (
        SELECT 1 FROM permission_grants g
        JOIN roles legacy ON legacy.id=g.role_id
        WHERE g.account_id=a.id AND g.revoked_at IS NULL
          AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
          AND legacy.code IN ('MANAGER','EXECUTIVE')
      ) THEN 'OFFICER'
      ELSE 'GENERAL'
    END),
  CASE
    WHEN EXISTS (
      SELECT 1 FROM permission_grants g
      JOIN roles legacy ON legacy.id=g.role_id
      WHERE g.account_id=a.id AND g.revoked_at IS NULL
        AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
        AND legacy.code IN ('HR','SYSTEM_ADMIN','AUDITOR','EXECUTIVE')
    ) THEN 'ALL'
    WHEN EXISTS (
      SELECT 1 FROM permission_grants g
      JOIN roles legacy ON legacy.id=g.role_id
      WHERE g.account_id=a.id AND g.revoked_at IS NULL
        AND (g.valid_to IS NULL OR g.valid_to>CURRENT_TIMESTAMP)
        AND legacy.code='MANAGER'
    ) THEN 'SUBORDINATES'
    ELSE 'SELF'
  END,
  CURRENT_TIMESTAMP,
  '3権限体系への移行'
FROM accounts a
WHERE NOT EXISTS (
  SELECT 1 FROM permission_grants existing_grant
  JOIN roles new_role ON new_role.id=existing_grant.role_id
  WHERE existing_grant.account_id=a.id AND existing_grant.revoked_at IS NULL
    AND new_role.code IN ('GENERAL','OFFICER','ADMIN')
);

UPDATE permission_grants
SET revoked_at=CURRENT_TIMESTAMP
WHERE revoked_at IS NULL
  AND role_id IN (SELECT id FROM roles WHERE code NOT IN ('GENERAL','OFFICER','ADMIN'));

UPDATE roles SET status='INACTIVE' WHERE code NOT IN ('GENERAL','OFFICER','ADMIN');

UPDATE accounts SET failed_count=0, locked_until=NULL;
