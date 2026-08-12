package com.query.insight.talent;

import com.query.insight.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/talent-masters")
public class TalentMasterController {
    private static final Map<String, String> TABLES = Map.of(
            "SKILL", "skill_masters",
            "KNOWLEDGE", "knowledge_masters",
            "CERTIFICATION", "certification_masters");

    private final JdbcClient jdbc;

    public TalentMasterController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{type}")
    List<Choice> choices(@PathVariable String type) {
        String table = TABLES.get(type.toUpperCase(java.util.Locale.ROOT));
        if (table == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TALENT_TYPE_INVALID", "申請種別が不正です");
        }
        return jdbc.sql("SELECT public_id,code,name FROM " + table
                        + " WHERE status='ACTIVE' ORDER BY code")
                .query((rs, row) -> new Choice(rs.getString("public_id").trim(),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    public record Choice(String publicId, String code, String name) {
    }
}
