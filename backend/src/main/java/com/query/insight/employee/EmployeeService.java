package com.query.insight.employee;

import com.query.insight.common.ApiException;
import com.query.insight.common.PublicIdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeService {
    private final JdbcClient jdbc;

    public EmployeeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<EmployeeSummary> search(String actorEmployeePublicId, Set<String> roles, Set<String> scopes,
            String keyword, String department, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        boolean all = roles.contains("ADMIN") || roles.contains("OFFICER") && scopes.contains("ALL");
        boolean manager = roles.contains("OFFICER") && scopes.contains("SUBORDINATES");
        if (!all && !manager) {
            throw forbidden();
        }
        String normalizedKeyword = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        String normalizedDepartment = department == null ? "" : department.strip();
        String scopeClause = all ? "" : " AND m.public_id = :actorEmployee ";
        String filters = """
                 AND (:keyword = '' OR LOWER(CONCAT(e.employee_no, ' ', e.last_name, ' ', e.first_name, ' ', e.email)) LIKE :keywordLike)
                 AND (:department = '' OR d.code = :department)
                """;
        JdbcClient.StatementSpec query = jdbc.sql("""
                SELECT e.public_id, e.employee_no, e.last_name, e.first_name, e.email,
                       d.code department_code, d.name department_name, e.position_name,
                       e.employment_status, e.version, e.updated_at
                FROM employees e
                LEFT JOIN departments d ON d.id = e.department_id
                LEFT JOIN employees m ON m.id = e.manager_employee_id
                WHERE e.employment_status <> 'RETIRED'
                """ + scopeClause + filters + " ORDER BY e.employee_no LIMIT :limit OFFSET :offset")
                .param("keyword", normalizedKeyword).param("keywordLike", "%" + normalizedKeyword + "%")
                .param("department", normalizedDepartment).param("limit", safeSize).param("offset", safePage * safeSize);
        JdbcClient.StatementSpec count = jdbc.sql("""
                SELECT COUNT(*) FROM employees e
                LEFT JOIN departments d ON d.id = e.department_id
                LEFT JOIN employees m ON m.id = e.manager_employee_id
                WHERE e.employment_status <> 'RETIRED'
                """ + scopeClause + filters)
                .param("keyword", normalizedKeyword).param("keywordLike", "%" + normalizedKeyword + "%")
                .param("department", normalizedDepartment);
        if (!all) {
            query = query.param("actorEmployee", actorEmployeePublicId);
            count = count.param("actorEmployee", actorEmployeePublicId);
        }
        List<EmployeeSummary> content = query.query((rs, row) -> new EmployeeSummary(rs.getString("public_id"),
                rs.getString("employee_no"), rs.getString("last_name") + " " + rs.getString("first_name"),
                rs.getString("email"), rs.getString("department_code"), rs.getString("department_name"),
                rs.getString("position_name"), rs.getString("employment_status"), rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant())).list();
        long total = count.query(Long.class).single();
        return new PageResponse<>(content, safePage, safeSize, total, (int) Math.ceil((double) total / safeSize));
    }

    public EmployeeDetail findAccessible(String targetPublicId, String actorEmployeePublicId, Set<String> roles,
            Set<String> scopes) {
        EmployeeDetail detail = find(targetPublicId);
        boolean allowed = roles.contains("ADMIN") || roles.contains("OFFICER") && scopes.contains("ALL")
                || targetPublicId.equals(actorEmployeePublicId)
                || roles.contains("OFFICER") && scopes.contains("SUBORDINATES") && jdbc.sql("""
                        SELECT COUNT(*) FROM employees e JOIN employees m ON m.id = e.manager_employee_id
                        WHERE e.public_id = :target AND m.public_id = :actor
                        """).param("target", targetPublicId).param("actor", actorEmployeePublicId)
                        .query(Long.class).single() > 0;
        if (!allowed) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "対象の社員が見つかりません");
        }
        return detail;
    }

    @Transactional
    public EmployeeDetail create(EmployeeRequest request) {
        ensureUnique(request.employeeNo(), request.email(), null);
        Long departmentId = departmentId(request.departmentCode());
        Long managerId = employeeId(request.managerPublicId());
        String publicId = PublicIdGenerator.next();
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO employees(public_id, employee_no, last_name, first_name, email, department_id,
                  manager_employee_id, position_name, employment_status, hire_date, version, created_at, updated_at)
                VALUES (:publicId, :employeeNo, :lastName, :firstName, :email, :departmentId, :managerId,
                  :positionName, :status, :hireDate, 0, :now, :now)
                """).param("publicId", publicId).param("employeeNo", request.employeeNo().strip())
                .param("lastName", request.lastName().strip()).param("firstName", request.firstName().strip())
                .param("email", request.email().strip().toLowerCase(Locale.ROOT)).param("departmentId", departmentId)
                .param("managerId", managerId).param("positionName", request.positionName())
                .param("status", request.employmentStatus()).param("hireDate", request.hireDate())
                .param("now", Timestamp.from(now)).update();
        return find(publicId);
    }

    @Transactional
    public EmployeeDetail update(String publicId, EmployeeRequest request) {
        ensureUnique(request.employeeNo(), request.email(), publicId);
        int updated = jdbc.sql("""
                UPDATE employees SET employee_no=:employeeNo,last_name=:lastName,first_name=:firstName,email=:email,
                  department_id=:departmentId,manager_employee_id=:managerId,position_name=:positionName,
                  employment_status=:status,hire_date=:hireDate,version=version+1,updated_at=:now
                WHERE public_id=:publicId AND version=:version
                """).param("employeeNo", request.employeeNo().strip()).param("lastName", request.lastName().strip())
                .param("firstName", request.firstName().strip())
                .param("email", request.email().strip().toLowerCase(Locale.ROOT))
                .param("departmentId", departmentId(request.departmentCode()))
                .param("managerId", employeeId(request.managerPublicId())).param("positionName", request.positionName())
                .param("status", request.employmentStatus()).param("hireDate", request.hireDate())
                .param("now", Timestamp.from(Instant.now())).param("publicId", publicId)
                .param("version", request.version()).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "他の利用者が更新しました。再読み込みしてください");
        }
        return find(publicId);
    }

    @Transactional
    public void deactivate(String publicId, long version) {
        int updated = jdbc.sql("""
                UPDATE employees SET employment_status='RETIRED', retirement_date=CURRENT_DATE,
                  version=version+1,updated_at=:now WHERE public_id=:publicId AND version=:version
                """).param("now", Timestamp.from(Instant.now())).param("publicId", publicId).param("version", version).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "他の利用者が更新しました。再読み込みしてください");
        }
        jdbc.sql("UPDATE accounts SET status='DISABLED',version=version+1 WHERE employee_id=(SELECT id FROM employees WHERE public_id=:publicId)")
                .param("publicId", publicId).update();
    }

    public EmployeeDetail find(String publicId) {
        return jdbc.sql("""
                SELECT e.public_id,e.employee_no,e.last_name,e.first_name,e.email,d.code department_code,
                  d.name department_name,m.public_id manager_public_id,
                  CONCAT(m.last_name, ' ', m.first_name) manager_name,e.position_name,e.employment_status,
                  e.hire_date,e.retirement_date,e.version,e.updated_at
                FROM employees e LEFT JOIN departments d ON d.id=e.department_id
                LEFT JOIN employees m ON m.id=e.manager_employee_id WHERE e.public_id=:publicId
                """).param("publicId", publicId)
                .query((rs, row) -> new EmployeeDetail(rs.getString("public_id"), rs.getString("employee_no"),
                        rs.getString("last_name"), rs.getString("first_name"), rs.getString("email"),
                        rs.getString("department_code"), rs.getString("department_name"),
                        rs.getString("manager_public_id"), rs.getString("manager_name"), rs.getString("position_name"),
                        rs.getString("employment_status"), rs.getObject("hire_date", LocalDate.class),
                        rs.getObject("retirement_date", LocalDate.class), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND", "対象の社員が見つかりません"));
    }

    private void ensureUnique(String employeeNo, String email, String excludedPublicId) {
        long count = jdbc.sql("""
                SELECT COUNT(*) FROM employees WHERE (employee_no=:employeeNo OR email=:email)
                  AND (:excluded IS NULL OR public_id<>:excluded)
                """).param("employeeNo", employeeNo.strip()).param("email", email.strip().toLowerCase(Locale.ROOT))
                .param("excluded", excludedPublicId).query(Long.class).single();
        if (count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "EMPLOYEE_DUPLICATE", "社員番号またはメールアドレスが既に登録されています");
        }
    }

    private Long departmentId(String code) {
        if (code == null || code.isBlank()) return null;
        return jdbc.sql("SELECT id FROM departments WHERE code=:code AND status='ACTIVE'").param("code", code)
                .query(Long.class).optional().orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "DEPARTMENT_INVALID", "指定された部署は利用できません"));
    }

    private Long employeeId(String publicId) {
        if (publicId == null || publicId.isBlank()) return null;
        return jdbc.sql("SELECT id FROM employees WHERE public_id=:publicId").param("publicId", publicId)
                .query(Long.class).optional().orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "MANAGER_INVALID", "指定された上長が見つかりません"));
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "この操作を行う権限がありません");
    }

    public record EmployeeSummary(String publicId, String employeeNo, String name, String email,
            String departmentCode, String departmentName, String positionName, String employmentStatus,
            long version, Instant updatedAt) {
    }
    public record EmployeeDetail(String publicId, String employeeNo, String lastName, String firstName,
            String email, String departmentCode, String departmentName, String managerPublicId, String managerName,
            String positionName, String employmentStatus, LocalDate hireDate, LocalDate retirementDate,
            long version, Instant updatedAt) {
    }
    public record EmployeeRequest(String employeeNo, String lastName, String firstName, String email,
            String departmentCode, String managerPublicId, String positionName, String employmentStatus,
            LocalDate hireDate, long version) {
    }
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }
}
