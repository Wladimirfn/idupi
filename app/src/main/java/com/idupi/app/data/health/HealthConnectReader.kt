package com.idupi.app.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads the last [HealthDays.HISTORY_DAYS] owner-local days from Health
 * Connect (phone-health T2).
 *
 * Numeric totals use the aggregate API so overlapping writers (Samsung Health
 * and Mi Fitness both writing steps) are de-duplicated by Health Connect
 * itself. Sleep, workouts, weight and SpO2 are read as records because the
 * day rules need their start/end and stages. Each read is guarded on its own:
 * a missing permission, an update-required store or one failing record type
 * leaves the rest of the window intact instead of failing the report.
 *
 * Health values never reach a log line; failures log the exception class only.
 */
class HealthConnectReader(private val context: Context) {

    /** Everything one read produced, already shaped for the day bucketing. */
    data class Read(
        val aggregatesByDay: Map<String, HealthConnectAggregates> = emptyMap(),
        val detailsByDay: Map<String, HealthConnectDayDetails> = emptyMap(),
        val originPackages: Set<String> = emptySet(),
        val grantedPermissions: Set<String> = emptySet(),
    )

    suspend fun read(nowMillis: Long = System.currentTimeMillis()): Read {
        if (healthConnectAvailability(context) != HealthConnectAvailability.AVAILABLE) {
            return Read()
        }
        val granted = HealthConnectPermissions.granted(context)
        if (granted.isEmpty()) return Read(grantedPermissions = granted)

        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
            ?: return Read(grantedPermissions = granted)

        val tzOffsetMinutes = HealthDays.offsetMinutesAt(nowMillis)
        val today = HealthDays.ownerDayKey(nowMillis, tzOffsetMinutes)
        val dayKeys = HealthDays.lastNDays(today, HealthDays.HISTORY_DAYS)

        val aggregates = mutableMapOf<String, HealthConnectAggregates>()
        for (dayKey in dayKeys) {
            val read = readDayAggregates(client, granted, dayKey, tzOffsetMinutes)
            if (read != HealthConnectAggregates()) aggregates[dayKey] = read
        }

        val rawStart = HealthDays.dayStartMillis(dayKeys.first(), tzOffsetMinutes) -
            DAY_MILLIS
        val window = TimeRangeFilter.between(
            Instant.ofEpochMilli(rawStart),
            Instant.ofEpochMilli(nowMillis),
        )

        val readings = mutableListOf<HealthReading>()
        val origins = mutableSetOf<String>()

        if (hasRead(granted, SleepSessionRecord::class)) {
            readRecords<SleepSessionRecord>(client, window, pageSize = SLEEP_PAGE_SIZE) { record ->
                origins.addOrigin(record.metadata.dataOrigin.packageName)
                readings += HealthReading(
                    kind = HealthReadingKind.SLEEP,
                    startMillis = record.startTime.toEpochMilli(),
                    endMillis = record.endTime.toEpochMilli(),
                    originPackage = record.metadata.dataOrigin.packageName,
                    stageSpans = record.stages.map { stage ->
                        HealthStageSpan(
                            type = stage.stage,
                            startMillis = stage.startTime.toEpochMilli(),
                            endMillis = stage.endTime.toEpochMilli(),
                        )
                    },
                )
            }
        }
        if (hasRead(granted, ExerciseSessionRecord::class)) {
            readRecords<ExerciseSessionRecord>(client, window, pageSize = EXERCISE_PAGE_SIZE) { record ->
                origins.addOrigin(record.metadata.dataOrigin.packageName)
                readings += HealthReading(
                    kind = HealthReadingKind.EXERCISE,
                    startMillis = record.startTime.toEpochMilli(),
                    endMillis = record.endTime.toEpochMilli(),
                    originPackage = record.metadata.dataOrigin.packageName,
                    exerciseType = record.exerciseType,
                )
            }
        }
        if (hasRead(granted, WeightRecord::class)) {
            readRecords<WeightRecord>(client, window, pageSize = POINT_PAGE_SIZE, ascending = false) {
                origins.addOrigin(it.metadata.dataOrigin.packageName)
                readings += HealthReading(
                    kind = HealthReadingKind.WEIGHT,
                    startMillis = it.time.toEpochMilli(),
                    value = it.weight.inKilograms,
                    originPackage = it.metadata.dataOrigin.packageName,
                )
            }
        }
        if (hasRead(granted, OxygenSaturationRecord::class)) {
            readRecords<OxygenSaturationRecord>(
                client,
                window,
                pageSize = POINT_PAGE_SIZE,
                ascending = false,
            ) {
                origins.addOrigin(it.metadata.dataOrigin.packageName)
                readings += HealthReading(
                    kind = HealthReadingKind.SPO2,
                    startMillis = it.time.toEpochMilli(),
                    value = it.percentage.value,
                    originPackage = it.metadata.dataOrigin.packageName,
                )
            }
        }
        // Steps are aggregated above; a bounded descending page is read only to
        // learn which apps actually wrote to Health Connect, including apps the
        // catalog does not know.
        if (hasRead(granted, StepsRecord::class)) {
            readRecords<StepsRecord>(
                client,
                window,
                pageSize = ORIGIN_PROBE_SIZE,
                ascending = false,
            ) {
                origins.addOrigin(it.metadata.dataOrigin.packageName)
            }
        }

        val bucket = HealthConnectBucketing.bucket(
            readings = readings,
            originPackages = origins,
            dayKeys = dayKeys,
            tzOffsetMinutes = tzOffsetMinutes,
        )

        return Read(
            aggregatesByDay = aggregates,
            detailsByDay = bucket.detailsByDay,
            originPackages = bucket.originPackages,
            grantedPermissions = granted,
        )
    }

    /**
     * Only the data-origin packages, for the Ajustes source list. Reads a
     * bounded page per record type instead of the whole window.
     */
    suspend fun readOriginPackages(nowMillis: Long = System.currentTimeMillis()): Set<String> {
        if (healthConnectAvailability(context) != HealthConnectAvailability.AVAILABLE) {
            return emptySet()
        }
        val granted = HealthConnectPermissions.granted(context)
        if (granted.isEmpty()) return emptySet()
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
            ?: return emptySet()

        val window = TimeRangeFilter.between(
            Instant.ofEpochMilli(nowMillis - DAY_MILLIS),
            Instant.ofEpochMilli(nowMillis),
        )
        val origins = mutableSetOf<String>()
        if (hasRead(granted, StepsRecord::class)) {
            readRecords<StepsRecord>(client, window, ORIGIN_PROBE_SIZE, ascending = false) {
                origins.addOrigin(it.metadata.dataOrigin.packageName)
            }
        }
        if (hasRead(granted, SleepSessionRecord::class)) {
            readRecords<SleepSessionRecord>(client, window, ORIGIN_PROBE_SIZE, ascending = false) {
                origins.addOrigin(it.metadata.dataOrigin.packageName)
            }
        }
        if (hasRead(granted, ExerciseSessionRecord::class)) {
            readRecords<ExerciseSessionRecord>(client, window, ORIGIN_PROBE_SIZE, ascending = false) {
                origins.addOrigin(it.metadata.dataOrigin.packageName)
            }
        }
        return origins
    }

    private suspend fun <T : Any> aggregate(
        client: HealthConnectClient,
        granted: Set<String>,
        recordType: KClass<out Record>,
        metric: androidx.health.connect.client.aggregate.AggregateMetric<T>,
        range: TimeRangeFilter,
    ): T? {
        if (!hasRead(granted, recordType)) return null
        return runCatching {
            client.aggregate(AggregateRequest(setOf(metric), range))[metric]
        }.onFailure { Log.w(TAG, "aggregate failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private suspend fun readDayAggregates(
        client: HealthConnectClient,
        granted: Set<String>,
        dayKey: String,
        tzOffsetMinutes: Int,
    ): HealthConnectAggregates {
        val range = TimeRangeFilter.between(
            Instant.ofEpochMilli(HealthDays.dayStartMillis(dayKey, tzOffsetMinutes)),
            Instant.ofEpochMilli(HealthDays.dayEndMillis(dayKey, tzOffsetMinutes)),
        )

        val steps = aggregate(client, granted, StepsRecord::class, StepsRecord.COUNT_TOTAL, range)
        val distance =
            aggregate(client, granted, DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL, range)
        val calories = aggregate(
            client,
            granted,
            TotalCaloriesBurnedRecord::class,
            TotalCaloriesBurnedRecord.ENERGY_TOTAL,
            range,
        )
        val activeCalories = aggregate(
            client,
            granted,
            ActiveCaloriesBurnedRecord::class,
            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
            range,
        )
        val heartRateAvg =
            aggregate(client, granted, HeartRateRecord::class, HeartRateRecord.BPM_AVG, range)
        val heartRateMin =
            aggregate(client, granted, HeartRateRecord::class, HeartRateRecord.BPM_MIN, range)
        val heartRateMax =
            aggregate(client, granted, HeartRateRecord::class, HeartRateRecord.BPM_MAX, range)
        val restingHeartRate = aggregate(
            client,
            granted,
            RestingHeartRateRecord::class,
            RestingHeartRateRecord.BPM_AVG,
            range,
        )

        return HealthConnectAggregates(
            steps = steps?.toInt(),
            distanceM = distance?.inMeters,
            caloriesKcal = calories?.inKilocalories,
            activeCaloriesKcal = activeCalories?.inKilocalories,
            heartRateAvg = heartRateAvg?.toInt(),
            heartRateMin = heartRateMin?.toInt(),
            heartRateMax = heartRateMax?.toInt(),
            restingHeartRateBpm = restingHeartRate?.toInt(),
        )
    }

    private suspend inline fun <reified T : Record> readRecords(
        client: HealthConnectClient,
        range: TimeRangeFilter,
        pageSize: Int,
        ascending: Boolean = true,
        onRecord: (T) -> Unit,
    ) {
        runCatching {
            val response = client.readRecords(
                ReadRecordsRequest<T>(
                    timeRangeFilter = range,
                    ascendingOrder = ascending,
                    pageSize = pageSize,
                )
            )
            response.records.forEach(onRecord)
        }.onFailure { Log.w(TAG, "read failed: ${it.javaClass.simpleName}") }
    }

    private fun hasRead(granted: Set<String>, recordType: KClass<out Record>): Boolean =
        HealthPermission.getReadPermission(recordType) in granted

    private fun MutableSet<String>.addOrigin(packageName: String) {
        if (HealthSourceDetection.isValidPackageName(packageName)) add(packageName)
    }

    companion object {
        private const val TAG = "HealthConnectReader"
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val SLEEP_PAGE_SIZE = 100
        private const val EXERCISE_PAGE_SIZE = 200
        private const val POINT_PAGE_SIZE = 20
        private const val ORIGIN_PROBE_SIZE = 50
    }
}
