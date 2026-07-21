package com.query.insight.config;

import com.query.insight.common.PublicIdGenerator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true")
public class BootstrapAdminInitializer implements ApplicationRunner {
    private static final List<String> ADMIN_ROLES = List.of("EMPLOYEE", "HR", "SYSTEM_ADMIN", "AUDITOR");
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final String loginId;
    private final String password;
    private final String employeeNo;
    private final String lastName;
    private final String firstName;

    public BootstrapAdminInitializer(JdbcClient jdbc, PasswordEncoder encoder,
            @Value("${app.bootstrap.admin.login-id:}") String loginId,
            @Value("${app.bootstrap.admin.password:}") String password,
            @Value("${app.bootstrap.admin.employee-no:}") String employeeNo,
            @Value("${app.bootstrap.admin.last-name:}") String lastName,
            @Value("${app.bootstrap.admin.first-name:}") String firstName) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.loginId = loginId.strip().toLowerCase(Locale.ROOT);
        this.password = password;
        this.employeeNo = employeeNo.strip();
        this.lastName = lastName.strip();
        this.firstName = firstName.strip();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (count("accounts") > 0) {
            return;
        }
        validateConfiguration();
        if (count("departments") + count("employees") + count("roles") + count("permission_grants") > 0) {
            throw new IllegalStateException("Bootstrap admin requires an empty identity schema");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String departmentPublicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO departments(public_id,code,name,parent_id,status,version,created_at,updated_at)
                VALUES (:publicId,'BOOTSTRAP','初期管理部門',NULL,'ACTIVE',0,:now,:now)
                """).param("publicId", departmentPublicId).param("now", now).update();
        long departmentId = jdbc.sql("SELECT id FROM departments WHERE public_id=:publicId")
                .param("publicId", departmentPublicId).query(Long.class).single();

        String employeePublicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO employees(public_id,employee_no,last_name,first_name,email,department_id,
                  manager_employee_id,position_name,employment_status,hire_date,version,created_at,updated_at)
                VALUES (:publicId,:employeeNo,:lastName,:firstName,:email,:departmentId,
                  NULL,'システム管理者','ACTIVE',NULL,0,:now,:now)
                """).param("publicId", employeePublicId).param("employeeNo", employeeNo)
                .param("lastName", lastName).param("firstName", firstName).param("email", loginId)
                .param("departmentId", departmentId).param("now", now).update();
        long employeeId = jdbc.sql("SELECT id FROM employees WHERE public_id=:publicId")
                .param("publicId", employeePublicId).query(Long.class).single();

        List.of("EMPLOYEE", "MANAGER", "SALES", "HR", "SYSTEM_ADMIN", "AUDITOR").forEach(role ->
                jdbc.sql("INSERT INTO roles(code,name,status) VALUES (:code,:name,'ACTIVE')")
                        .param("code", role).param("name", role).update());

        String accountPublicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO accounts(public_id,employee_id,login_id_normalized,password_hash,status,failed_count,
                  password_changed_at,version) VALUES (:publicId,:employeeId,:loginId,:hash,'ACTIVE',0,:now,0)
                """).param("publicId", accountPublicId).param("employeeId", employeeId)
                .param("loginId", loginId).param("hash", encoder.encode(password)).param("now", now).update();
        long accountId = jdbc.sql("SELECT id FROM accounts WHERE public_id=:publicId")
                .param("publicId", accountPublicId).query(Long.class).single();

        ADMIN_ROLES.forEach(role -> jdbc.sql("""
                INSERT INTO permission_grants(public_id,account_id,role_id,scope_type,valid_from,reason)
                VALUES (:publicId,:accountId,(SELECT id FROM roles WHERE code=:role),'ALL',:now,
                  '初期管理者ブートストラップ')
                """).param("publicId", PublicIdGenerator.next()).param("accountId", accountId)
                .param("role", role).param("now", now).update());
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private void validateConfiguration() {
        if (loginId.isEmpty() || loginId.length() > 254 || !loginId.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_LOGIN_ID must be a valid email address");
        }
        if (password.length() < 15 || password.length() > 128) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must contain 15 to 128 characters");
        }
        if (employeeNo.isEmpty() || employeeNo.length() > 30 || lastName.isEmpty() || lastName.length() > 50
                || firstName.isEmpty() || firstName.length() > 50) {
            throw new IllegalStateException("Bootstrap admin profile values are invalid");
        }
    }
}
