package com.idupi.app.data.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
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
import kotlin.reflect.KClass

/** Health Connect's install state on this phone. */
enum class HealthConnectAvailability {
    AVAILABLE,
    NEEDS_UPDATE,
    UNAVAILABLE,
}

/** Health Connect package; also the Play Store id for the standalone app. */
const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/** Reads Health Connect's own availability, without touching permissions. */
fun healthConnectAvailability(context: Context): HealthConnectAvailability =
    when (HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            HealthConnectAvailability.NEEDS_UPDATE
        else -> HealthConnectAvailability.UNAVAILABLE
    }

/** Health Connect's settings screen (permissions and connected apps). */
fun healthConnectSettingsIntent(): Intent =
    Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Play Store page of the standalone Health Connect app. */
fun healthConnectInstallIntent(): Intent =
    Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Browser fallback when the phone has no Play Store client. */
fun healthConnectInstallWebIntent(): Intent =
    Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * The health record types IDUPI reads (phone-health T2).
 *
 * This list is the single source for the manifest permissions, the runtime
 * request and the reader: a new record type is added here once. Every entry
 * must also appear as `<uses-permission android:name="android.permission.health.READ_*">`
 * in the manifest, or Health Connect refuses the request.
 */
object HealthConnectPermissions {

    val RECORD_TYPES: List<KClass<out Record>> = listOf(
        StepsRecord::class,
        DistanceRecord::class,
        TotalCaloriesBurnedRecord::class,
        ActiveCaloriesBurnedRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        SleepSessionRecord::class,
        ExerciseSessionRecord::class,
        WeightRecord::class,
        OxygenSaturationRecord::class,
    )

    /** `android.permission.health.READ_*` for every record type above. */
    val READ_PERMISSIONS: Set<String> =
        RECORD_TYPES.mapTo(linkedSetOf()) { HealthPermission.getReadPermission(it) }

    /**
     * Additional access the periodic reporter needs on Android 14+
     * (`READ_HEALTH_DATA_IN_BACKGROUND`). It is requested together with the
     * record permissions but never counted as a record permission: older
     * Health Connect builds cannot grant it.
     */
    val BACKGROUND_PERMISSION: String =
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Everything one dialog asks for. */
    val REQUEST_PERMISSIONS: Set<String> = READ_PERMISSIONS + BACKGROUND_PERMISSION

    /**
     * Granted permissions; empty when Health Connect is missing or the query
     * fails. Partial grants are a normal answer, not an error.
     */
    suspend fun granted(context: Context): Set<String> {
        if (healthConnectAvailability(context) != HealthConnectAvailability.AVAILABLE) {
            return emptySet()
        }
        return runCatching {
            HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
        }.getOrDefault(emptySet())
    }

    /** The contract Ajustes launches to ask for [READ_PERMISSIONS]. */
    fun requestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract(HEALTH_CONNECT_PACKAGE)
}
