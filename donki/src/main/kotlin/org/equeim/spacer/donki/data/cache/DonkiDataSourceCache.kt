// SPDX-FileCopyrightText: 2022-2023 Alexey Rochev
//
// SPDX-License-Identifier: MIT

package org.equeim.spacer.donki.data.cache

import android.content.Context
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.getSystemService
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import org.equeim.spacer.donki.CoroutineDispatchers
import org.equeim.spacer.donki.data.DonkiJson
import org.equeim.spacer.donki.data.DonkiRepository
import org.equeim.spacer.donki.data.Week
import org.equeim.spacer.donki.data.cache.entities.CachedWeek
import org.equeim.spacer.donki.data.cache.entities.toCachedEvent
import org.equeim.spacer.donki.data.cache.entities.toExtras
import org.equeim.spacer.donki.data.eventSerializer
import org.equeim.spacer.donki.data.model.*
import java.io.Closeable
import java.nio.file.*
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val TAG = "DonkiDataSourceCache"
private val WINDOW_BETWEEN_LAST_DAY_AND_LOAD_TIME_WHEN_REFRESH_IS_NEEDED: Duration =
    Duration.ofDays(7)
private val WINDOW_BETWEEN_LOAD_TIME_AND_CURRENT_TIME_WHEN_REFRESH_IS_NOT_NEEDED: Duration =
    Duration.ofHours(1)

internal class DonkiDataSourceCache(
    private val context: Context,
    db: DonkiDatabase? = null,
    private val coroutineDispatchers: CoroutineDispatchers = CoroutineDispatchers(),
    private val clock: Clock = Clock.systemDefaultZone(),
) : Closeable {
    private val coroutineScope = CoroutineScope(coroutineDispatchers.IO + SupervisorJob())

    @Volatile
    private var db: DonkiDatabase? = null

    @Volatile
    private var dbWatchKey: WatchKey? = null

    @Volatile
    private var dbInitJob: Job? = null

    private val _databaseRecreated = MutableSharedFlow<Unit>()
    val databaseRecreated: Flow<Unit> by ::_databaseRecreated

    init {
        if (db != null) {
            this.db = db
        } else {
            dbInitJob = coroutineScope.launch { initDatabase() }.apply {
                invokeOnCompletion { dbInitJob = null }
            }
        }
    }

    @WorkerThread
    private fun initDatabase() {
        val databaseDirectory = context.cacheDir.toPath().resolve(DonkiDatabase.NAME)
        Files.createDirectories(databaseDirectory)
        val storageManager = checkNotNull(context.getSystemService<StorageManager>())
        storageManager.setCacheBehaviorGroup(databaseDirectory.toFile(), true)
        storageManager.setCacheBehaviorTombstone(databaseDirectory.toFile(), true)
        db = createDatabase(databaseDirectory)
        recreateDatabaseWhenDeleted(databaseDirectory)
    }

    @WorkerThread
    private fun createDatabase(databaseDirectory: Path): DonkiDatabase {
        Log.d(TAG, "createDatabase() called with: databaseDirectory = $databaseDirectory")
        return Room.databaseBuilder(
            context,
            DonkiDatabase::class.java,
            databaseDirectory.resolve(DonkiDatabase.NAME).toString()
        ).build()
    }

    private fun recreateDatabaseWhenDeleted(databaseDirectory: Path) {
        val watchService = FileSystems.getDefault().newWatchService()
        val watchKey =
            databaseDirectory.register(watchService, StandardWatchEventKinds.ENTRY_DELETE)
        watchService.events(coroutineDispatchers).onEach {
            if ((it.context() as? Path)?.toString() == DonkiDatabase.NAME) {
                recreateDatabase(databaseDirectory)
            }
        }.launchIn(coroutineScope)
        dbWatchKey = watchKey
    }

    @WorkerThread
    private suspend fun recreateDatabase(databaseDirectory: Path) {
        Log.d(TAG, "recreateDatabase() called with: databaseDirectory = $databaseDirectory")
        db?.close()
        db = createDatabase(databaseDirectory)
        _databaseRecreated.emit(Unit)
    }

    override fun close() {
        Log.d(TAG, "close() called")
        coroutineScope.cancel()
        dbInitJob?.cancel()
        dbWatchKey?.cancel()
        db?.close()
    }

    private suspend fun awaitDb(): DonkiDatabase {
        dbInitJob?.join()
        return checkNotNull(db)
    }

    suspend fun isWeekCachedAndNeedsRefresh(
        week: Week,
        eventType: EventType,
        refreshIfRecentlyLoaded: Boolean,
    ): Boolean {
        val cacheLoadTime = awaitDb().cachedWeeks()
            .getWeekLoadTime(week.weekBasedYear, week.weekOfWeekBasedYear, eventType)
        return cacheLoadTime != null && week.needsRefresh(cacheLoadTime, refreshIfRecentlyLoaded)
    }

    suspend fun getEventSummariesForWeek(
        week: Week,
        eventType: EventType,
        returnCacheThatNeedsRefreshing: Boolean,
    ): List<EventSummary>? {
        Log.d(
            TAG,
            "getEventSummariesForWeek() called with: week = $week, eventType = $eventType, returnCacheThatNeedsRefreshing = $returnCacheThatNeedsRefreshing"
        )
        val db = awaitDb()
        return try {
            val weekCacheTime = db.cachedWeeks()
                .getWeekLoadTime(week.weekBasedYear, week.weekOfWeekBasedYear, eventType)
            if (weekCacheTime == null) {
                Log.d(
                    TAG,
                    "getEventSummariesForWeek: no cache for week = $week, eventType = $eventType, returning null"
                )
                return null
            }
            if (!returnCacheThatNeedsRefreshing && week.needsRefresh(weekCacheTime)) {
                Log.d(
                    TAG,
                    "getEventSummariesForWeek: cache needs refreshing for week = $week, eventType = $eventType, returning null"
                )
                return null
            }
            val startTime = week.getFirstDayInstant()
            val endTime = week.getInstantAfterLastDay()
            when (eventType) {
                EventType.CoronalMassEjection -> db.coronalMassEjection().getEventSummaries(startTime, endTime)
                EventType.GeomagneticStorm -> db.geomagneticStorm().getEventSummaries(startTime, endTime)
                EventType.InterplanetaryShock -> db.interplanetaryShock().getEventSummaries(startTime, endTime)
                EventType.SolarFlare -> db.solarFlare().getEventSummaries(startTime, endTime)
                else -> db.events().getEventSummaries(eventType, startTime, endTime)
            }.also {
                Log.d(
                    TAG,
                    "getEventSummariesForWeek: returning ${it.size} events for week = $week, eventType = $eventType"
                )
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(
                    TAG,
                    "getEventSummariesForWeek: failed to get events summaries for week = $week, eventType = $eventType",
                    e
                )
            }
            throw e
        }
    }

    suspend fun getEventById(
        id: EventId,
        eventType: EventType,
        week: Week,
    ): DonkiRepository.EventById? {
        Log.d(TAG, "getEventById() called with: id = $id, eventType = $eventType, week = $week")
        val db = awaitDb()
        try {
            val weekLoadTime = db.cachedWeeks()
                .getWeekLoadTime(week.weekBasedYear, week.weekOfWeekBasedYear, eventType)
            if (weekLoadTime == null) {
                Log.d(
                    TAG,
                    "getEventById: no cache for week = $week, eventType = $eventType, returning null"
                )
                return null
            }
            val json = db.events().getEventJsonById(id)
            if (json == null) {
                Log.e(TAG, "getEventById: did not find event $id, returning null")
                return null
            }
            val event = DonkiJson.decodeFromString(eventType.eventSerializer(), json)
            return DonkiRepository.EventById(event, week.needsRefresh(weekLoadTime)).also {
                Log.d(TAG, "getEventById: returning event $id")
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(
                    TAG,
                    "getEventById: failed to get events for id = $id, week = $week, eventType = $eventType",
                    e
                )
            }
            throw e
        }
    }

    private fun Week.needsRefresh(
        cacheLoadTime: Instant,
        refreshIfRecentlyLoaded: Boolean = false,
    ): Boolean {
        return Duration.between(
            getInstantAfterLastDay(),
            cacheLoadTime
        ) < WINDOW_BETWEEN_LAST_DAY_AND_LOAD_TIME_WHEN_REFRESH_IS_NEEDED &&
                (refreshIfRecentlyLoaded || Duration.between(
                    cacheLoadTime,
                    Instant.now(clock)
                ) > WINDOW_BETWEEN_LOAD_TIME_AND_CURRENT_TIME_WHEN_REFRESH_IS_NOT_NEEDED)
    }

    suspend fun cacheWeek(
        week: Week,
        eventType: EventType,
        events: List<Pair<Event, JsonObject>>,
        loadTime: Instant,
    ) {
        Log.d(
            TAG,
            "cacheWeek() called with: week = $week, events count = ${events.size}, loadTime = $loadTime"
        )
        if (events.isEmpty()) {
            Log.d(TAG, "cacheWeek: no events, mark as cached without transaction")
            updateCachedWeek(week, eventType, loadTime)
        } else {
            val db = awaitDb()
            db.withTransaction {
                Log.d(TAG, "cacheWeek: starting transaction for $week")
                updateCachedWeek(week, eventType, loadTime)
                for (event in events) {
                    db.events().updateEvent(event.toCachedEvent())
                    when (eventType) {
                        EventType.CoronalMassEjection ->
                            db.coronalMassEjection()
                                .updateExtras((event.first as CoronalMassEjection).toExtras())

                        EventType.GeomagneticStorm ->
                            db.geomagneticStorm()
                                .updateExtras((event.first as GeomagneticStorm).toExtras())

                        EventType.InterplanetaryShock -> db.interplanetaryShock()
                            .updateExtras((event.first as InterplanetaryShock).toExtras())

                        EventType.SolarFlare -> db.solarFlare().updateExtras((event.first as SolarFlare).toExtras())

                        else -> Unit
                    }
                }
                Log.d(TAG, "cacheWeek: completing transaction for $week")
            }
        }
    }

    private suspend fun updateCachedWeek(week: Week, eventType: EventType, loadTime: Instant) {
        awaitDb().cachedWeeks()
            .updateWeek(
                CachedWeek(
                    week.weekBasedYear,
                    week.weekOfWeekBasedYear,
                    eventType,
                    loadTime
                )
            )
    }

    fun cacheWeekAsync(
        week: Week,
        eventType: EventType,
        events: List<Pair<Event, JsonObject>>,
        loadTime: Instant,
    ) {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch { cacheWeek(week, eventType, events, loadTime) }
    }
}

private fun WatchService.events(coroutineDispatchers: CoroutineDispatchers): Flow<WatchEvent<*>> =
    flow {
        try {
            while (currentCoroutineContext().isActive) {
                val key: WatchKey = runInterruptible { take() }
                try {
                    key.pollEvents().forEach { emit(it) }
                } finally {
                    key.reset()
                }
            }
        } catch (ignore: ClosedWatchServiceException) {
        }
    }.flowOn(coroutineDispatchers.IO)
