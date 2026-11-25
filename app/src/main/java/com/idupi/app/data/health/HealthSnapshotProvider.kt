package com.idupi.app.data.health

/**
 * Reads every source for the last seven owner-local days and shapes one
 * upload (phone-health T2).
 *
 * A failing Health Connect read must not suppress the digital wellbeing part
 * (and vice versa): each source is guarded, so the PC still receives whatever
 * the phone could read. Detection of installed apps runs on every report so a
 * newly installed health app shows up without restarting the process.
 */
class HealthSnapshotProvider(
    private val reader: HealthConnectReader,
    private val wellbeing: AndroidWellbeingCollector,
    private val sources: AndroidHealthSources,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    suspend fun collect(): HealthSamplesUpload {
        val now = nowMillis()
        val connect = runCatching { reader.read(now) }.getOrElse { HealthConnectReader.Read() }
        val wellbeingDays = runCatching { wellbeing.collect(now) }.getOrNull()

        val tzOffsetMinutes = HealthDays.offsetMinutesAt(now)
        val today = HealthDays.ownerDayKey(now, tzOffsetMinutes)
        val days = HealthDays.lastNDays(today, HealthDays.HISTORY_DAYS).map { dayKey ->
            HealthDayInput(
                date = dayKey,
                connect = connect.aggregatesByDay[dayKey] ?: HealthConnectAggregates(),
                details = connect.detailsByDay[dayKey] ?: HealthConnectDayDetails(),
                wellbeing = wellbeingDays?.firstOrNull { it.date == dayKey },
            )
        }
        val installedApps = runCatching { sources.installedApps(connect.originPackages) }
            .getOrDefault(emptyList())

        return HealthSampleBuilder.build(
            days = days,
            installedApps = installedApps,
            tzOffsetMinutes = tzOffsetMinutes,
            capturedAtMillis = now,
        )
    }
}
