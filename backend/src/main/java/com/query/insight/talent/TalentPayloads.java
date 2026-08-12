package com.query.insight.talent;

import com.query.insight.common.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class TalentPayloads {
    private static final Pattern PUBLIC_ID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

    private TalentPayloads() {
    }

    public sealed interface Payload permits SkillPayload, KnowledgePayload, CareerPayload, CertificationPayload {
        void validate(LocalDate today);
    }

    public record SkillPayload(String masterPublicId, Integer level, BigDecimal yearsExperience,
            LocalDate lastUsedOn, String evidence) implements Payload {
        @Override
        public void validate(LocalDate today) {
            requirePublicId(masterPublicId);
            requireLevel(level);
            if (yearsExperience == null || yearsExperience.compareTo(BigDecimal.ZERO) < 0
                    || yearsExperience.compareTo(new BigDecimal("60.0")) > 0
                    || yearsExperience.stripTrailingZeros().scale() > 1) {
                throw invalid("経験年数は0～60年の範囲で小数第1位まで指定してください");
            }
            requireNotFuture(lastUsedOn, today, "最終利用日");
            requireText(evidence, 1000, "根拠");
        }
    }

    public record KnowledgePayload(String masterPublicId, Integer level, String evidence) implements Payload {
        @Override
        public void validate(LocalDate today) {
            requirePublicId(masterPublicId);
            requireLevel(level);
            requireText(evidence, 1000, "根拠");
        }
    }

    public record CareerPayload(String projectName, String industry, String roleName, LocalDate startDate,
            LocalDate endDate, String summary, String achievements, String technologies) implements Payload {
        @Override
        public void validate(LocalDate today) {
            requireText(projectName, 150, "案件名");
            requireText(industry, 100, "業界");
            requireText(roleName, 100, "役割");
            if (startDate == null) {
                throw invalid("開始日を入力してください");
            }
            if (endDate != null && endDate.isBefore(startDate)) {
                throw invalid("終了日は開始日以降にしてください");
            }
            requireText(summary, 1000, "概要");
            requireText(achievements, 1500, "成果");
            requireText(technologies, 1000, "利用技術");
        }
    }

    public record CertificationPayload(String masterPublicId, LocalDate acquiredOn, LocalDate expiresOn,
            String credentialReference) implements Payload {
        @Override
        public void validate(LocalDate today) {
            requirePublicId(masterPublicId);
            requireNotFuture(acquiredOn, today, "取得日");
            if (expiresOn != null && expiresOn.isBefore(acquiredOn)) {
                throw invalid("有効期限は取得日以降にしてください");
            }
            requireOptionalText(credentialReference, 100, "資格番号");
        }
    }

    private static void requirePublicId(String value) {
        if (value == null || !PUBLIC_ID.matcher(value).matches()) {
            throw invalid("マスタIDが不正です");
        }
    }

    private static void requireLevel(Integer level) {
        if (level == null || level < 1 || level > 5) {
            throw invalid("習熟度は1～5で指定してください");
        }
    }

    private static void requireNotFuture(LocalDate value, LocalDate today, String label) {
        if (value == null) {
            throw invalid(label + "を入力してください");
        }
        if (today == null || value.isAfter(today)) {
            throw invalid(label + "は未来日を指定できません");
        }
    }

    private static void requireText(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + "を入力してください");
        }
        if (value.length() > maxLength) {
            throw invalid(label + "は" + maxLength + "文字以内で入力してください");
        }
    }

    private static void requireOptionalText(String value, int maxLength, String label) {
        if (value != null && value.length() > maxLength) {
            throw invalid(label + "は" + maxLength + "文字以内で入力してください");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "TALENT_PAYLOAD_INVALID", message);
    }
}
