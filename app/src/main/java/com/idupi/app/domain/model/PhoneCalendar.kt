package com.idupi.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Phone calendar wire models (phone-calendar T2/T3).
 *
 * Field names are the contract in `idupi-server/docs/phone.md` ("Snapshot
 * contract" and "Proposals"): the PC reads them verbatim, so they must not be
 * renamed here. Every field has a default so a document from a newer server
 * decodes instead of failing.
 */
@Serializable
data class PhoneCalendarRef(
    val id: String = "",
    val name: String = "",
    val accountType: String = "",
    val accountName: String = "",
    /** `#RRGGBB`, or null when the provider did not expose a color. */
    val color: String? = null,
    val writable: Boolean = false,
)

/**
 * One calendar event as reported to the PC. [start] and [end] are ISO-8601
 * instants; an all-day event uses UTC midnight boundaries, which is what the
 * Android provider stores and what the server's free-slot math expects.
 */
@Serializable
data class PhoneCalendarEvent(
    val id: String = "",
    val calendarId: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val description: String = "",
)

/** `POST /api/v1/phone/calendar` body: the phone's agenda for the next 14 days. */
@Serializable
data class PhoneCalendarSnapshot(
    val capturedAt: String,
    val rangeStart: String,
    val rangeEnd: String,
    val tzOffsetMinutes: Int = 0,
    val calendars: List<PhoneCalendarRef> = emptyList(),
    val events: List<PhoneCalendarEvent> = emptyList(),
)

/** Conflict reference returned by the server for agenda events and proposals. */
@Serializable
data class PhoneCalendarConflictRef(
    val id: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = "",
)

/**
 * One event proposed by a PC agent. The owner decides on the phone; the PC
 * never writes to a calendar. Proposal text is untrusted display data.
 */
@Serializable
data class PhoneCalendarProposal(
    val id: String = "",
    val title: String = "",
    val start: String = "",
    val end: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val notes: String = "",
    val calendarId: String? = null,
    val source: String = "",
    val status: String = "pending",
    val conflicts: List<PhoneCalendarConflictRef> = emptyList(),
    val eventId: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val expiresAt: String = "",
)

/** `GET /api/v1/phone/calendar/proposals?status=pending`. */
@Serializable
data class PhoneCalendarProposalsResponse(
    val proposals: List<PhoneCalendarProposal> = emptyList(),
    val tzOffsetMinutes: Int? = null,
)

/** Proposal lifecycle values the server accepts. */
object PhoneCalendarProposalStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"
}

/**
 * The event the inbox analysis extracted from a message. The agent only fills
 * fields; the owner sees and edits them in the confirm sheet before any write.
 */
@Serializable
data class InboxEvent(
    val title: String = "",
    val start: String = "",
    val end: String = "",
    val allDay: Boolean = false,
    val location: String = "",
)

/** One existing event that overlaps the extracted event (warning only). */
@Serializable
data class InboxEventConflict(
    val title: String = "",
    val start: String = "",
    val end: String = "",
)
