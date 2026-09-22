package com.repmate.safety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * The one thing [CheckInEscalationAction] needs from the device's location stack, seamed off
 * behind an interface -- same reasoning as `SensorSource`/`SessionRepository` elsewhere in this
 * app -- so escalation logic can be unit-tested against a fake instead of a real
 * [FusedLocationProviderClient], which needs Play Services and a granted permission to do
 * anything.
 */
interface LastLocationProvider {
    /** Null if the permission is missing, no fix has ever been obtained, or the lookup fails --
     * every case degrades the same way (Golden Rule 7): the escalation SMS just goes out without
     * a location. */
    suspend fun lastKnownLocation(): LastLocation?
}

class FusedLastLocationProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fusedLocationClient: FusedLocationProviderClient,
    ) : LastLocationProvider {
        override suspend fun lastKnownLocation(): LastLocation? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            return try {
                fusedLocationClient.lastLocation.await()?.let { LastLocation(it.latitude, it.longitude) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Permission revoked mid-call, location services off, no Play Services, no fix
                // ever obtained -- all degrade the same way (Golden Rule 7): no location in the
                // escalation SMS, not a crashed worker.
                null
            }
        }
    }
