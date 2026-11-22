package net.sabafly.dragonraid.finder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.finder.ContentEligibilityResult;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockPolicy;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.finder.DutyEnvironmentChecker;
import net.sabafly.dragonraid.api.finder.EligibilityFailureReason;
import net.sabafly.dragonraid.api.finder.EligibilityIssue;
import net.sabafly.dragonraid.api.finder.ReadyCheckListener;
import net.sabafly.dragonraid.api.finder.ReadyCheckResponse;
import net.sabafly.dragonraid.api.finder.ReadyCheckSession;
import net.sabafly.dragonraid.api.finder.ReadyCheckStatus;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.party.PartyChangeEvent;
import net.sabafly.dragonraid.api.party.PartyChangeListener;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;

/**
 * Thread-safe {@link ContentFinderService} handling duty registration, Content Pack hot-reload,
 * detailed solo/pre-formed party eligibility evaluation, and FF14-style Ready Check state machine.
 */
public final class DefaultContentFinderService implements ContentFinderService, PartyChangeListener {

    public static final long DEFAULT_READY_CHECK_TIMEOUT_MILLIS = 30_000L;

    private static final Logger LOGGER = Logger.getLogger(DefaultContentFinderService.class.getName());
    private static final EnumSet<PartyChangeEvent.Type> PARTY_DISRUPTING_EVENTS = EnumSet.of(
            PartyChangeEvent.Type.JOINED,
            PartyChangeEvent.Type.LEFT,
            PartyChangeEvent.Type.KICKED,
            PartyChangeEvent.Type.LEADER_TRANSFERRED,
            PartyChangeEvent.Type.DISBANDED,
            PartyChangeEvent.Type.DISCONNECTED,
            PartyChangeEvent.Type.RECONNECTED,
            PartyChangeEvent.Type.SEAT_EXPIRED
    );

    private final Object readyCheckLock = new Object();

    private final Map<String, ContentEntrySpec> staticEntries = new ConcurrentHashMap<>();
    private volatile Map<String, ContentEntrySpec> packEntries = Map.of();
    private final ContentUnlockService unlockService;
    private final RaidPartyService partyService;
    private final RaidItemService itemService;
    private final DutyEnvironmentChecker environmentChecker;
    private final LongSupplier clockMillis;
    private final long readyCheckTimeoutMillis;
    private final boolean skipReadyCheckForSolo;

    private final Map<UUID, MutableReadyCheck> activeSessionsById = new LinkedHashMap<>();
    private final Map<UUID, UUID> sessionIdByParticipant = new LinkedHashMap<>();
    private final Map<UUID, UUID> sessionIdByParty = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<ReadyCheckListener> listeners = new CopyOnWriteArrayList<>();

    public DefaultContentFinderService(ContentUnlockService unlockService) {
        this(unlockService, null, null);
    }

    public DefaultContentFinderService(
            ContentUnlockService unlockService,
            RaidPartyService partyService,
            RaidItemService itemService) {
        this(
                unlockService,
                partyService,
                itemService,
                DutyEnvironmentChecker.ALWAYS_READY,
                System::currentTimeMillis,
                DEFAULT_READY_CHECK_TIMEOUT_MILLIS,
                false
        );
    }

    public DefaultContentFinderService(
            ContentUnlockService unlockService,
            RaidPartyService partyService,
            RaidItemService itemService,
            DutyEnvironmentChecker environmentChecker,
            LongSupplier clockMillis,
            long readyCheckTimeoutMillis,
            boolean skipReadyCheckForSolo) {
        this.unlockService = Objects.requireNonNull(unlockService, "unlockService");
        this.partyService = partyService;
        this.itemService = itemService;
        this.environmentChecker = Objects.requireNonNull(environmentChecker, "environmentChecker");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        if (readyCheckTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("readyCheckTimeoutMillis must be > 0");
        }
        this.readyCheckTimeoutMillis = readyCheckTimeoutMillis;
        this.skipReadyCheckForSolo = skipReadyCheckForSolo;
    }

    @Override
    public void registerContent(ContentEntrySpec entry) {
        Objects.requireNonNull(entry, "entry");
        staticEntries.put(entry.contentId(), entry);
    }

    @Override
    public synchronized boolean unregisterContent(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return false;
        }
        boolean removedStatic = staticEntries.remove(contentId) != null;
        Map<String, ContentEntrySpec> currentPack = this.packEntries;
        boolean removedPack = false;
        if (currentPack.containsKey(contentId)) {
            Map<String, ContentEntrySpec> next = new LinkedHashMap<>(currentPack);
            next.remove(contentId);
            this.packEntries = Collections.unmodifiableMap(next);
            removedPack = true;
        }
        return removedStatic || removedPack;
    }

    @Override
    public synchronized void replacePackContents(Collection<ContentEntrySpec> entries) {
        Map<String, ContentEntrySpec> next = new LinkedHashMap<>();
        if (entries != null) {
            for (ContentEntrySpec entry : entries) {
                if (entry != null) {
                    next.put(entry.contentId(), entry);
                }
            }
        }
        this.packEntries = Collections.unmodifiableMap(next);
    }

    @Override
    public Optional<ContentEntrySpec> getContent(String contentId) {
        if (contentId == null) {
            return Optional.empty();
        }
        ContentEntrySpec fromPack = packEntries.get(contentId);
        if (fromPack != null) {
            return Optional.of(fromPack);
        }
        return Optional.ofNullable(staticEntries.get(contentId));
    }

    @Override
    public Collection<ContentEntrySpec> allContents() {
        Map<String, ContentEntrySpec> merged = new LinkedHashMap<>(staticEntries);
        merged.putAll(packEntries);
        return List.copyOf(merged.values());
    }

    @Override
    public boolean requestEntry(UUID initiatorId, String contentId) {
        return evaluateEligibility(initiatorId, contentId).eligible();
    }

    @Override
    public ContentEligibilityResult evaluateEligibility(UUID initiatorId, String contentId) {
        Objects.requireNonNull(initiatorId, "initiatorId");
        return evaluateEligibilityInternal(initiatorId, contentId, null);
    }

    @Override
    public Optional<ReadyCheckSession> startReadyCheck(UUID initiatorId, String contentId) {
        Objects.requireNonNull(initiatorId, "initiatorId");
        ReadyCheckSession createdSession = null;
        boolean commencedImmediately = false;
        List<ReadyCheckSession> timedOut;
        synchronized (readyCheckLock) {
            long now = clockMillis.getAsLong();
            timedOut = expireElapsedLocked(now);
            ContentEligibilityResult eligibility = evaluateEligibilityInternal(initiatorId, contentId, null);
            if (eligibility.eligible() && eligibility.content() != null) {
                ContentEntrySpec spec = eligibility.content();
                List<UUID> participants = eligibility.applicantIds();
                UUID partyId = partyService != null ? partyService.partyOf(initiatorId).map(RaidParty::partyId).orElse(null) : null;
                UUID sessionId = UUID.randomUUID();
                long expiresAt = now + readyCheckTimeoutMillis;

                if (skipReadyCheckForSolo && participants.size() == 1) {
                    Map<UUID, ReadyCheckResponse> acceptedMap = Map.of(initiatorId, ReadyCheckResponse.ACCEPTED);
                    createdSession = new ReadyCheckSession(
                            sessionId,
                            spec.contentId(),
                            partyId,
                            initiatorId,
                            participants,
                            acceptedMap,
                            ReadyCheckStatus.COMMENCED,
                            now,
                            expiresAt
                    );
                    commencedImmediately = true;
                } else {
                    MutableReadyCheck mutable = new MutableReadyCheck(
                            sessionId,
                            spec.contentId(),
                            partyId,
                            initiatorId,
                            participants,
                            now,
                            expiresAt
                    );
                    activeSessionsById.put(sessionId, mutable);
                    for (UUID memberId : participants) {
                        sessionIdByParticipant.put(memberId, sessionId);
                    }
                    if (partyId != null) {
                        sessionIdByParty.put(partyId, sessionId);
                    }
                    createdSession = mutable.snapshot();
                }
            }
        }
        notifyTimedOut(timedOut);
        if (createdSession == null) {
            return Optional.empty();
        }

        if (commencedImmediately) {
            notifyCommenced(createdSession);
        } else {
            notifyStarted(createdSession);
        }
        return Optional.of(createdSession);
    }

    @Override
    public boolean respondReadyCheck(UUID playerId, boolean accepted) {
        Objects.requireNonNull(playerId, "playerId");
        ReadyCheckSession updatedSession = null;
        ReadyCheckSession commencedSession = null;
        ReadyCheckSession endedSession = null;
        String endReason = null;
        List<ReadyCheckSession> timedOut;
        boolean responded = false;

        synchronized (readyCheckLock) {
            long now = clockMillis.getAsLong();
            timedOut = expireElapsedLocked(now);
            UUID sessionId = sessionIdByParticipant.get(playerId);
            MutableReadyCheck session = sessionId == null ? null : activeSessionsById.get(sessionId);
            if (session != null && session.status == ReadyCheckStatus.PENDING) {
                responded = true;
                if (!accepted) {
                    session.responses.put(playerId, ReadyCheckResponse.DECLINED);
                    session.status = ReadyCheckStatus.DECLINED;
                    removeActiveSessionLocked(session);
                    endedSession = session.snapshot();
                    endReason = "declined";
                } else {
                    session.responses.put(playerId, ReadyCheckResponse.ACCEPTED);
                    updatedSession = session.snapshot();
                    if (session.allAccepted()) {
                        // Re-validate eligibility atomically right before launch to close TOCTOU races.
                        ContentEligibilityResult recheck = evaluateEligibilityInternal(
                                session.initiatorId,
                                session.contentId,
                                session.sessionId
                        );
                        if (recheck.eligible() && sameParticipantOrder(recheck.applicantIds(), session.participantIds)) {
                            session.status = ReadyCheckStatus.COMMENCED;
                            removeActiveSessionLocked(session);
                            commencedSession = session.snapshot();
                        } else {
                            session.status = ReadyCheckStatus.CANCELLED;
                            removeActiveSessionLocked(session);
                            endedSession = session.snapshot();
                            endReason = "eligibility_changed";
                        }
                    }
                }
            }
        }

        notifyTimedOut(timedOut);
        if (!responded) {
            return false;
        }
        if (endedSession != null) {
            notifyEnded(endedSession, playerId, endReason);
        } else if (commencedSession != null) {
            notifyResponseUpdated(updatedSession, playerId, ReadyCheckResponse.ACCEPTED);
            notifyCommenced(commencedSession);
        } else if (updatedSession != null) {
            notifyResponseUpdated(updatedSession, playerId, ReadyCheckResponse.ACCEPTED);
        }
        return true;
    }

    @Override
    public boolean cancelReadyCheck(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        ReadyCheckSession ended = null;
        List<ReadyCheckSession> timedOut;
        synchronized (readyCheckLock) {
            timedOut = expireElapsedLocked(clockMillis.getAsLong());
            UUID sessionId = sessionIdByParticipant.get(actorId);
            MutableReadyCheck session = sessionId == null ? null : activeSessionsById.get(sessionId);
            if (session != null && session.status == ReadyCheckStatus.PENDING) {
                boolean isInitiator = actorId.equals(session.initiatorId);
                boolean isCurrentLeader = session.partyId != null && partyService != null
                        && partyService.partyById(session.partyId).map(p -> actorId.equals(p.leaderId())).orElse(false);
                if (isInitiator || isCurrentLeader) {
                    session.status = ReadyCheckStatus.CANCELLED;
                    removeActiveSessionLocked(session);
                    ended = session.snapshot();
                }
            }
        }
        notifyTimedOut(timedOut);
        if (ended == null) {
            return false;
        }
        notifyEnded(ended, actorId, "cancelled");
        return true;
    }

    @Override
    public Optional<ReadyCheckSession> activeReadyCheck(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        List<ReadyCheckSession> timedOut;
        Optional<ReadyCheckSession> result;
        synchronized (readyCheckLock) {
            timedOut = expireElapsedLocked(clockMillis.getAsLong());
            UUID sessionId = sessionIdByParticipant.get(playerId);
            MutableReadyCheck session = sessionId == null ? null : activeSessionsById.get(sessionId);
            result = session == null ? Optional.empty() : Optional.of(session.snapshot());
        }
        notifyTimedOut(timedOut);
        return result;
    }

    @Override
    public void tickReadyChecks() {
        List<ReadyCheckSession> timedOut;
        synchronized (readyCheckLock) {
            timedOut = expireElapsedLocked(clockMillis.getAsLong());
        }
        notifyTimedOut(timedOut);
    }

    @Override
    public void addReadyCheckListener(ReadyCheckListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeReadyCheckListener(ReadyCheckListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void onPartyChanged(PartyChangeEvent event) {
        if (event == null || !PARTY_DISRUPTING_EVENTS.contains(event.type())) {
            return;
        }
        List<ReadyCheckSession> cancelled = new ArrayList<>(2);
        synchronized (readyCheckLock) {
            UUID byParty = sessionIdByParty.get(event.partyId());
            if (byParty != null) {
                MutableReadyCheck s = activeSessionsById.get(byParty);
                if (s != null && s.status == ReadyCheckStatus.PENDING) {
                    s.status = ReadyCheckStatus.CANCELLED;
                    removeActiveSessionLocked(s);
                    cancelled.add(s.snapshot());
                }
            }
            cancelParticipantSessionLocked(event.actorId(), cancelled);
            cancelParticipantSessionLocked(event.targetId(), cancelled);
        }
        for (ReadyCheckSession session : cancelled) {
            notifyEnded(session, event.actorId(), "party_changed:" + event.type().name());
        }
    }

    private void cancelParticipantSessionLocked(UUID playerId, List<ReadyCheckSession> out) {
        if (playerId == null) {
            return;
        }
        UUID sid = sessionIdByParticipant.get(playerId);
        if (sid == null) {
            return;
        }
        MutableReadyCheck session = activeSessionsById.get(sid);
        if (session != null && session.status == ReadyCheckStatus.PENDING) {
            session.status = ReadyCheckStatus.CANCELLED;
            removeActiveSessionLocked(session);
            out.add(session.snapshot());
        }
    }

    private ContentEligibilityResult evaluateEligibilityInternal(
            UUID initiatorId,
            String contentId,
            UUID ignoreReadyCheckSessionId) {
        List<EligibilityIssue> issues = new ArrayList<>();
        ContentEntrySpec spec = getContent(contentId).orElse(null);
        List<UUID> applicants;
        Optional<RaidParty> partyOpt = partyService != null ? partyService.partyOf(initiatorId) : Optional.empty();
        if (partyOpt.isPresent()) {
            RaidParty party = partyOpt.get();
            applicants = party.memberIds();
            if (!party.leaderId().equals(initiatorId)) {
                issues.add(new EligibilityIssue(EligibilityFailureReason.NOT_PARTY_LEADER, initiatorId));
            }
        } else {
            applicants = List.of(initiatorId);
        }

        if (spec == null) {
            issues.add(new EligibilityIssue(EligibilityFailureReason.CONTENT_NOT_FOUND, null, 0, 0, contentId));
            return ContentEligibilityResult.denied(null, applicants, issues);
        }

        if (!environmentChecker.hasBlueprint(spec.blueprintId())) {
            issues.add(new EligibilityIssue(EligibilityFailureReason.BLUEPRINT_MISSING, null, 0, 0, spec.blueprintId()));
        }
        if (environmentChecker.isEncounterSlotBusy()) {
            issues.add(new EligibilityIssue(EligibilityFailureReason.ENCOUNTER_BUSY, null));
        }

        if (applicants.size() < spec.minPlayers()) {
            issues.add(new EligibilityIssue(
                    EligibilityFailureReason.PARTY_TOO_SMALL,
                    applicants.size(),
                    spec.minPlayers()
            ));
        }
        if (applicants.size() > spec.maxPlayers()) {
            issues.add(new EligibilityIssue(
                    EligibilityFailureReason.PARTY_TOO_LARGE,
                    applicants.size(),
                    spec.maxPlayers()
            ));
        }

        for (UUID memberId : applicants) {
            boolean disconnectedInParty = partyService != null && partyService.isDisconnected(memberId);
            if (disconnectedInParty || !environmentChecker.isPlayerOnline(memberId)) {
                issues.add(new EligibilityIssue(EligibilityFailureReason.MEMBER_OFFLINE, memberId));
            }
            if (environmentChecker.isPlayerInEncounter(memberId)) {
                issues.add(new EligibilityIssue(EligibilityFailureReason.MEMBER_IN_ACTIVE_ENCOUNTER, memberId));
            }
            if (hasOtherActiveReadyCheck(memberId, ignoreReadyCheckSessionId)) {
                issues.add(new EligibilityIssue(EligibilityFailureReason.MEMBER_IN_READY_CHECK, memberId));
            }
        }

        String unlockKey = spec.requiredUnlockKey();
        if (!unlockKey.isBlank()) {
            if (spec.unlockPolicy() == ContentUnlockPolicy.LEADER_ONLY) {
                UUID leaderId = partyOpt.map(RaidParty::leaderId).orElse(initiatorId);
                if (!unlockService.isUnlocked(leaderId, unlockKey)) {
                    issues.add(new EligibilityIssue(EligibilityFailureReason.MEMBER_MISSING_UNLOCK, leaderId, 0, 0, unlockKey));
                }
            } else {
                for (UUID memberId : applicants) {
                    if (!unlockService.isUnlocked(memberId, unlockKey)) {
                        issues.add(new EligibilityIssue(EligibilityFailureReason.MEMBER_MISSING_UNLOCK, memberId, 0, 0, unlockKey));
                    }
                }
            }
        }

        if (spec.minAverageItemLevel() > 0) {
            if (itemService == null) {
                for (UUID memberId : applicants) {
                    issues.add(new EligibilityIssue(
                            EligibilityFailureReason.MEMBER_LOW_ITEM_LEVEL,
                            memberId,
                            0,
                            spec.minAverageItemLevel(),
                            null
                    ));
                }
            } else {
                for (UUID memberId : applicants) {
                    int actualIlvl = itemService.averageItemLevel(memberId);
                    if (actualIlvl < spec.minAverageItemLevel()) {
                        issues.add(new EligibilityIssue(
                                EligibilityFailureReason.MEMBER_LOW_ITEM_LEVEL,
                                memberId,
                                actualIlvl,
                                spec.minAverageItemLevel(),
                                null
                        ));
                    }
                }
            }
        }

        return issues.isEmpty()
                ? ContentEligibilityResult.allowed(spec, applicants)
                : ContentEligibilityResult.denied(spec, applicants, issues);
    }

    private boolean hasOtherActiveReadyCheck(UUID memberId, UUID ignoreSessionId) {
        synchronized (readyCheckLock) {
            UUID activeId = sessionIdByParticipant.get(memberId);
            return activeId != null && !activeId.equals(ignoreSessionId);
        }
    }

    private List<ReadyCheckSession> expireElapsedLocked(long nowMillis) {
        if (activeSessionsById.isEmpty()) {
            return List.of();
        }
        List<MutableReadyCheck> expired = new ArrayList<>();
        for (MutableReadyCheck session : activeSessionsById.values()) {
            if (session.status == ReadyCheckStatus.PENDING && nowMillis >= session.expiresAtMillis) {
                expired.add(session);
            }
        }
        if (expired.isEmpty()) {
            return List.of();
        }
        List<ReadyCheckSession> snapshots = new ArrayList<>(expired.size());
        for (MutableReadyCheck session : expired) {
            session.status = ReadyCheckStatus.TIMED_OUT;
            removeActiveSessionLocked(session);
            snapshots.add(session.snapshot());
        }
        return snapshots;
    }

    private void removeActiveSessionLocked(MutableReadyCheck session) {
        activeSessionsById.remove(session.sessionId);
        for (UUID memberId : session.participantIds) {
            sessionIdByParticipant.remove(memberId, session.sessionId);
        }
        if (session.partyId != null) {
            sessionIdByParty.remove(session.partyId, session.sessionId);
        }
    }

    private static boolean sameParticipantOrder(List<UUID> current, List<UUID> expected) {
        return current.equals(expected);
    }

    private void notifyStarted(ReadyCheckSession session) {
        for (ReadyCheckListener listener : listeners) {
            try {
                listener.onSessionStarted(session);
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "ReadyCheckListener.onSessionStarted failed", ex);
            }
        }
    }

    private void notifyResponseUpdated(ReadyCheckSession session, UUID responderId, ReadyCheckResponse response) {
        for (ReadyCheckListener listener : listeners) {
            try {
                listener.onResponseUpdated(session, responderId, response);
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "ReadyCheckListener.onResponseUpdated failed", ex);
            }
        }
    }

    private void notifyCommenced(ReadyCheckSession session) {
        for (ReadyCheckListener listener : listeners) {
            try {
                listener.onSessionCommenced(session);
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "ReadyCheckListener.onSessionCommenced failed", ex);
            }
        }
    }

    private void notifyTimedOut(List<ReadyCheckSession> timedOut) {
        for (ReadyCheckSession session : timedOut) {
            notifyEnded(session, null, "timed_out");
        }
    }

    private void notifyEnded(ReadyCheckSession session, UUID actorId, String reason) {
        for (ReadyCheckListener listener : listeners) {
            try {
                listener.onSessionEnded(session, actorId, reason);
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "ReadyCheckListener.onSessionEnded failed", ex);
            }
        }
    }

    private static final class MutableReadyCheck {
        final UUID sessionId;
        final String contentId;
        final UUID partyId;
        final UUID initiatorId;
        final List<UUID> participantIds;
        final Map<UUID, ReadyCheckResponse> responses = new LinkedHashMap<>();
        ReadyCheckStatus status = ReadyCheckStatus.PENDING;
        final long createdAtMillis;
        final long expiresAtMillis;

        MutableReadyCheck(
                UUID sessionId,
                String contentId,
                UUID partyId,
                UUID initiatorId,
                List<UUID> participantIds,
                long createdAtMillis,
                long expiresAtMillis) {
            this.sessionId = sessionId;
            this.contentId = contentId;
            this.partyId = partyId;
            this.initiatorId = initiatorId;
            this.participantIds = List.copyOf(participantIds);
            this.createdAtMillis = createdAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            for (UUID participantId : participantIds) {
                this.responses.put(participantId, ReadyCheckResponse.PENDING);
            }
        }

        boolean allAccepted() {
            for (UUID participantId : participantIds) {
                if (responses.get(participantId) != ReadyCheckResponse.ACCEPTED) {
                    return false;
                }
            }
            return true;
        }

        ReadyCheckSession snapshot() {
            return new ReadyCheckSession(
                    sessionId,
                    contentId,
                    partyId,
                    initiatorId,
                    participantIds,
                    responses,
                    status,
                    createdAtMillis,
                    expiresAtMillis
            );
        }
    }
}
