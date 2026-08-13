package com.query.insight.config;

import com.query.insight.auth.PasswordPolicy;
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
        // Roles may already be provisioned by Flyway; only identity-bearing rows make bootstrap unsafe.
        if (count("departments") + count("employees") + count("permission_grants") > 0) {
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

        List.of("GENERAL", "OFFICER", "ADMIN").forEach(role ->
                jdbc.sql("INSERT INTO roles(code,name,status) SELECT :code,:name,'ACTIVE' "
                                + "WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code=:code)")
                        .param("code", role).param("name", role).update());

        String accountPublicId = PublicIdGenerator.next();
        jdbc.sql("""
                INSERT INTO accounts(public_id,employee_id,login_id_normalized,password_hash,status,failed_count,
                  password_changed_at,version) VALUES (:publicId,:employeeId,:loginId,:hash,'ACTIVE',0,:now,0)
                """).param("publicId", accountPublicId).param("employeeId", employeeId)
                .param("loginId", loginId).param("hash", encoder.encode(password)).param("now", now).update();
        long accountId = jdbc.sql("SELECT id FROM accounts WHERE public_id=:publicId")
                .param("publicId", accountPublicId).query(Long.class).single();

        jdbc.sql("""
                INSERT INTO permission_grants(public_id,account_id,role_id,scope_type,valid_from,reason)
                VALUES (:publicId,:accountId,(SELECT id FROM roles WHERE code='ADMIN'),'ALL',:now,
                  '初期管理者ブートストラップ')
                """).param("publicId", PublicIdGenerator.next()).param("accountId", accountId)
                .param("now", now).update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private void validateConfiguration() {
        if (loginId.isEmpty() || loginId.length() > 254 || !loginId.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_LOGIN_ID must be a valid email address");
        }
        if (!password.matches(PasswordPolicy.REGEX)) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD must contain 8 to 128 ASCII letters and digits, including both");
        }
        if (employeeNo.isEmpty() || employeeNo.length() > 30 || lastName.isEmpty() || lastName.length() > 50
                || firstName.isEmpty() || firstName.length() > 50) {
            throw new IllegalStateException("Bootstrap admin profile values are invalid");
        }
    }
}
