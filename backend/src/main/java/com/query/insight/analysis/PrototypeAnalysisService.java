package com.query.insight.analysis;

import com.query.insight.status.ProfileStatusService.ProfileStatusResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PrototypeAnalysisService {
    public static final String MODEL = "ルールベース V1";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern PUBLIC_ID = Pattern.compile("(?i)(?<![A-Z0-9])[A-Z0-9]{26}(?![A-Z0-9])");
    private static final Pattern EMPLOYEE_NUMBER = Pattern.compile("(?i)^[A-Z][A-Z0-9_-]*\\d{3,}$");
    private final Clock clock;

    @Autowired
    public PrototypeAnalysisService() {
        this(Clock.systemUTC());
    }

    PrototypeAnalysisService(Clock clock) {
        this.clock = clock;
    }

    public AiAnalysisService.AnalysisResponse analyze(ProfileStatusResponse status,
            AiAnalysisClient.TalentProfileInput talentProfile) {
        List<Category> categories = categories(status);
        List<AiAnalysisClient.Insight> strengths = categories.stream()
                .filter(category -> category.score().compareTo(ZERO) > 0)
                .sorted(Comparator.comparing(Category::score).reversed().thenComparingInt(Category::order))
                .limit(2).map(category -> new AiAnalysisClient.Insight(category.label(),
                        category.label() + "の登録状況スコアは" + display(category.score()) + "です。"))
                .toList();
        if (strengths.isEmpty()) {
            strengths = List.of(new AiAnalysisClient.Insight("プロフィール整備の開始点",
                    "現在の登録状況を確認できたため、次に登録する情報を具体化できます。"));
        }

        List<Category> growth = categories.stream()
                .sorted(Comparator.comparing(Category::score).thenComparingInt(Category::order))
                .limit(2).toList();
        List<AiAnalysisClient.Insight> growthAreas = growth.stream()
                .map(category -> new AiAnalysisClient.Insight(category.label(),
                        category.score().compareTo(ZERO) == 0
                                ? category.label() + "は未登録です。"
                                : category.label() + "の登録状況スコアは" + display(category.score())
                                        + "で、追加できる情報がないか確認してください。"))
                .toList();

        List<AiAnalysisClient.RecommendedAction> actions = new ArrayList<>();
        for (Category category : growth) {
            String priority = category.score().compareTo(ZERO) == 0 ? "HIGH" : "MEDIUM";
            actions.add(new AiAnalysisClient.RecommendedAction(actionFor(category), priority));
        }
        if (hasTalent(talentProfile)) {
            actions.add(new AiAnalysisClient.RecommendedAction(talentAction(talentProfile), "LOW"));
        } else {
            actions.add(new AiAnalysisClient.RecommendedAction(
                    "まず1件、現在の業務を説明できるタレント情報を申請してください。", "HIGH"));
        }

        long registered = categories.stream().filter(category -> category.score().compareTo(ZERO) > 0).count();
        String summary = registered == 0
                ? "タレント情報は未登録です。小さな実績から登録を始めると、育成計画を具体化できます。"
                : "登録状況に基づく育成支援の試作分析です。強みを活用しながら、登録が少ない領域を補ってください。";
        return new AiAnalysisService.AnalysisResponse(null, null, summary, strengths, growthAreas,
                List.copyOf(actions), MODEL, clock.instant(), "PROTOTYPE");
    }

    private static List<Category> categories(ProfileStatusResponse status) {
        return List.of(new Category("スキル", status.skillScore(), 0),
                new Category("得意分野", status.knowledgeScore(), 1),
                new Category("業務経歴", status.careerScore(), 2),
                new Category("資格", status.certificationScore(), 3));
    }

    private static String actionFor(Category category) {
        return switch (category.label()) {
            case "スキル" -> "業務で使用したスキルと根拠を整理し、上長承認を申請してください。";
            case "得意分野" -> "得意分野と活用実績を整理し、上長承認を申請してください。";
            case "業務経歴" -> "直近の業務経歴に役割と成果を追記し、上長承認を申請してください。";
            case "資格" -> "保有資格または学習中の資格を確認し、取得済みなら上長承認を申請してください。";
            default -> throw new IllegalArgumentException("Unknown profile category");
        };
    }

    private static boolean hasTalent(AiAnalysisClient.TalentProfileInput talent) {
        return talent != null && (!talent.skills().isEmpty() || !talent.knowledge().isEmpty()
                || !talent.experiences().isEmpty() || !talent.certifications().isEmpty());
    }

    private static String talentAction(AiAnalysisClient.TalentProfileInput talent) {
        if (!talent.skills().isEmpty()) {
            return safeLabel(talent.skills().getFirst().name())
                    .map(name -> "承認済みスキル「" + name + "」を次の業務目標に結び付けてください。")
                    .orElse("承認済みスキルを次の業務目標に結び付けてください。");
        }
        if (!talent.knowledge().isEmpty()) {
            return safeLabel(talent.knowledge().getFirst().name())
                    .map(name -> "承認済み得意分野「" + name + "」を次の業務目標に結び付けてください。")
                    .orElse("承認済み得意分野を次の業務目標に結び付けてください。");
        }
        if (!talent.experiences().isEmpty()) {
            AiAnalysisClient.ExperienceInput experience = talent.experiences().getFirst();
            Optional<String> role = safeLabel(experience.role());
            Optional<String> technologies = safeLabel(experience.technologies());
            if (role.isPresent() && technologies.isPresent()) {
                return "承認済み業務経歴の役割「" + role.get() + "」と技術「" + technologies.get()
                        + "」を次の業務目標に結び付けてください。";
            }
            return role.map(value -> "承認済み業務経歴の役割「" + value
                    + "」を次の業務目標に結び付けてください。")
                    .or(() -> technologies.map(value -> "承認済み業務経歴の技術「" + value
                            + "」を次の業務目標に結び付けてください。"))
                    .orElse("承認済み業務経歴を次の業務目標に結び付けてください。");
        }
        return safeLabel(talent.certifications().getFirst().name())
                .map(name -> "承認済み資格「" + name + "」の知識を業務で活用してください。")
                .orElse("承認済み資格の知識を業務で活用してください。");
    }

    private static Optional<String> safeLabel(String value) {
        if (value == null || value.isBlank() || value.length() > 100
                || EMAIL.matcher(value).find() || PUBLIC_ID.matcher(value).find()
                || EMPLOYEE_NUMBER.matcher(value.strip()).matches()) {
            return Optional.empty();
        }
        return Optional.of(value.strip());
    }

    private static String display(BigDecimal score) {
        return score.stripTrailingZeros().toPlainString();
    }

    private record Category(String label, BigDecimal score, int order) {
    }
}
