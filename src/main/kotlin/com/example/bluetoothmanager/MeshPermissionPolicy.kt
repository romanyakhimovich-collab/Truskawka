package com.example.bluetoothmanager

import android.Manifest
import android.os.Build

object MeshPermissionPolicy {
    fun requiredPermissions(): List<String> = requiredPermissionsForSdk(Build.VERSION.SDK_INT)

    fun requiredPermissionsForSdk(sdkInt: Int): List<String> = buildList {
        if (requiresLocationPermission(sdkInt)) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingRequiredPermissions(
        sdkInt: Int = Build.VERSION.SDK_INT,
        isGranted: (String) -> Boolean
    ): List<String> = requiredPermissionsForSdk(sdkInt).filterNot(isGranted)

    fun requiresLocationServices(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        requiresLocationPermission(sdkInt)

    fun requiresPostNotificationsPermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    fun voicePermissions(): List<String> = listOf(Manifest.permission.RECORD_AUDIO)

    private fun requiresLocationPermission(sdkInt: Int): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU
}
