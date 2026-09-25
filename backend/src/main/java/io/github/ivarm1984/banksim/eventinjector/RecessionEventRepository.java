package io.github.ivarm1984.banksim.eventinjector;

import static io.github.ivarm1984.banksim.jooq.eventinjector.tables.RecessionEvents.RECESSION_EVENTS;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class RecessionEventRepository {

    private final DSLContext dsl;
    /**
     * One-entry cache of the last {@link #findMostRecentOnOrBefore} read -
     * recession state is checked on every repayment and every borrower's
     * daily distress roll, but only changes when the date moves or a row is
     * inserted. Both inserts invalidate it (same pattern as
     * {@code RateShockRepository}).
     */
    private volatile CachedLookup cachedLookup;

    public RecessionEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public RecessionEvent insertStart(LocalDate eventDate, LocalDate plannedEndDate) {
        cachedLookup = null;
        var record = dsl.insertInto(RECESSION_EVENTS)
                .set(RECESSION_EVENTS.EVENT_TYPE, RecessionEventType.START.name())
                .set(RECESSION_EVENTS.EVENT_DATE, eventDate)
                .set(RECESSION_EVENTS.PLANNED_END_DATE, plannedEndDate)
                .returning()
                .fetchOne();
        return toRecessionEvent(record);
    }

    public RecessionEvent insertEnd(LocalDate eventDate) {
        cachedLookup = null;
        var record = dsl.insertInto(RECESSION_EVENTS)
                .set(RECESSION_EVENTS.EVENT_TYPE, RecessionEventType.END.name())
                .set(RECESSION_EVENTS.EVENT_DATE, eventDate)
                .returning()
                .fetchOne();
        return toRecessionEvent(record);
    }

    /** Latest event on or before {@code asOf} - used by {@code isActive}/{@code tick} to derive current recession state. */
    public Optional<RecessionEvent> findMostRecentOnOrBefore(LocalDate asOf) {
        CachedLookup cached = cachedLookup;
        if (cached != null && cached.asOf().equals(asOf)) {
            return cached.event();
        }
        var record = dsl.selectFrom(RECESSION_EVENTS)
                .where(RECESSION_EVENTS.EVENT_DATE.le(asOf))
                .orderBy(RECESSION_EVENTS.EVENT_DATE.desc(), RECESSION_EVENTS.ID.desc())
                .limit(1)
                .fetchOne();
        Optional<RecessionEvent> event = Optional.ofNullable(record).map(RecessionEventRepository::toRecessionEvent);
        cachedLookup = new CachedLookup(asOf, event);
        return event;
    }

    private record CachedLookup(LocalDate asOf, Optional<RecessionEvent> event) {
    }

    public List<RecessionEvent> findAll() {
        return dsl.selectFrom(RECESSION_EVENTS)
                .orderBy(RECESSION_EVENTS.EVENT_DATE, RECESSION_EVENTS.ID)
                .fetch()
                .map(RecessionEventRepository::toRecessionEvent);
    }

    private static RecessionEvent toRecessionEvent(io.github.ivarm1984.banksim.jooq.eventinjector.tables.records.RecessionEventsRecord record) {
        return new RecessionEvent(
                record.getId(),
                RecessionEventType.valueOf(record.getEventType()),
                record.getEventDate(),
                record.getPlannedEndDate(),
                record.getCreatedAt());
    }
}
