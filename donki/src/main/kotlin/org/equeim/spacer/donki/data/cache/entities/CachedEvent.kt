// SPDX-FileCopyrightText: 2022-2024 Alexey Rochev
//
// SPDX-License-Identifier: MIT

@file:Suppress("FunctionName")

package org.equeim.spacer.donki.data.cache.entities

import androidx.room.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import org.equeim.spacer.donki.data.DonkiJson
import org.equeim.spacer.donki.data.model.Event
import org.equeim.spacer.donki.data.model.EventId
import org.equeim.spacer.donki.data.model.EventSummary
import org.equeim.spacer.donki.data.model.EventType
import java.time.Instant

@Entity(tableName = "events")
internal data class CachedEvent(
    @ColumnInfo(name = "id") @PrimaryKey
    val id: EventId,
    @ColumnInfo(name = "type")
    val type: EventType,
    @ColumnInfo(name = "time", index = true)
    val time: Instant,
    @ColumnInfo(name = "json")
    val json: String
)

internal fun Pair<Event, JsonObject>.toCachedEvent(): CachedEvent {
    val (event, json) = this
    return CachedEvent(
        id = event.id,
        type = event.type,
        time = event.time,
        json = DonkiJson.encodeToString(json)
    )
}

internal data class CachedEventSummary(
    @ColumnInfo(name = "id")
    override val id: EventId,
    @ColumnInfo(name = "type")
    override val type: EventType,
    @ColumnInfo(name = "time")
    override val time: Instant
) : EventSummary

@Dao
internal abstract class CachedEventsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun updateEvent(event: CachedEvent)

    @Query(
        """
            SELECT id, type, time FROM events
            WHERE type = :type AND time >= :startTime AND time < :endTime
            ORDER BY time DESC
        """
    )
    abstract suspend fun getEventSummaries(
        type: EventType,
        startTime: Instant,
        endTime: Instant
    ): List<CachedEventSummary>

    @Query(
        """
            SELECT json FROM events
            WHERE id = :id
        """
    )
    abstract suspend fun getEventJsonById(id: EventId): String?
}
