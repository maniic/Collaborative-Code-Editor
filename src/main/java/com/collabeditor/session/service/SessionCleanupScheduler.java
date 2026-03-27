package com.collabeditor.session.service;

import com.collabeditor.session.persistence.CodingSessionRepository;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.CodingSessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class SessionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    private final CodingSessionRepository codingSessionRepository;
    private final SessionParticipantRepository participantRepository;

    public SessionCleanupScheduler(CodingSessionRepository codingSessionRepository,
                                    SessionParticipantRepository participantRepository) {
        this.codingSessionRepository = codingSessionRepository;
        this.participantRepository = participantRepository;
    }

    @Scheduled(fixedDelayString = "${app.session.cleanup-fixed-delay:PT5M}")
    @Transactional
    public void cleanupExpiredEmptySessions() {
        Instant now = Instant.now();
        List<CodingSessionEntity> expired = codingSessionRepository.findExpiredEmptySessions(now);

        for (CodingSessionEntity candidate : expired) {
            CodingSessionEntity session = codingSessionRepository.findByIdForUpdate(candidate.getId())
                    .orElse(null);
            if (session == null || session.getCleanupAfter() == null || session.getCleanupAfter().isAfter(now)) {
                continue;
            }

            long activeCount = participantRepository.countBySessionIdAndStatus(session.getId(), "ACTIVE");
            if (activeCount == 0) {
                log.info("Cleaning up expired empty session: {} (invite: {})",
                        session.getId(), session.getInviteCode());
                codingSessionRepository.delete(session);
            } else if (session.getEmptySince() != null || session.getCleanupAfter() != null) {
                // Someone rejoined between cleanup_after being set and now; clear the cleanup window
                log.info("Skipping cleanup for session {} - has {} active participants",
                        session.getId(), activeCount);
                session.setEmptySince(null);
                session.setCleanupAfter(null);
                codingSessionRepository.save(session);
            }
        }
    }
}
