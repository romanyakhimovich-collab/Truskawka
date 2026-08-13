package com.example.bluetoothmanager

import android.Manifest
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshPermissionPolicyTest {
    @Test
    fun `android 11 uses location permissions for discovery`() {
        val permissions = MeshPermissionPolicy.requiredPermissionsForSdk(Build.VERSION_CODES.R)

        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            permissions
        )
        assertTrue(MeshPermissionPolicy.requiresLocationServices(Build.VERSION_CODES.R))
    }

    @Test
    fun `android 12 adds bluetooth runtime permissions and still needs location services`() {
        val permissions = MeshPermissionPolicy.requiredPermissionsForSdk(Build.VERSION_CODES.S)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertTrue(MeshPermissionPolicy.requiresLocationServices(Build.VERSION_CODES.S))
    }

    @Test
    fun `android 13 uses nearby permissions without requiring location services`() {
        val permissions = MeshPermissionPolicy.requiredPermissionsForSdk(Build.VERSION_CODES.TIRAMISU)

        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in permissions)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertFalse(MeshPermissionPolicy.requiresLocationServices(Build.VERSION_CODES.TIRAMISU))
        assertTrue(MeshPermissionPolicy.requiresPostNotificationsPermission(Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun `missing permissions returns only denied entries`() {
        val missing = MeshPermissionPolicy.missingRequiredPermissions(Build.VERSION_CODES.S) { permission ->
            permission == Manifest.permission.ACCESS_COARSE_LOCATION
        }

        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            missing
        )
    }
}
