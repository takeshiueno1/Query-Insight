package com.query.insight.talent.attachment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.files.scan-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class TalentAttachmentScanScheduler {
    private final TalentAttachmentService service;

    public TalentAttachmentScanScheduler(TalentAttachmentService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.files.scan-retry-fixed-delay:PT1M}",
            initialDelayString = "${app.files.scan-retry-initial-delay:PT1M}")
    public void retry() {
        service.retryPendingScans(10);
    }
}
