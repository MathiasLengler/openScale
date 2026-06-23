/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.SoehnleLib
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import com.welie.blessed.BluetoothBytesBuilder
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.UUID

/**
 * SoehnleHandler
 * --------------------
 * Modern Kotlin handler for Soehnle smart scales using the custom service
 * 352e3000-28e9-40b8-a361-6db4cca4147c in combination with standard
 * Battery (0x180F), Current Time (0x1805) and User Data (0x181C) services.
 *
 * The device supports per-scale user indices (1..7). We keep a mapping
 * between *app userId* and *scale user index* using [ScaleDeviceHandler]'s
 * built-in DriverSettings helpers (persisted per device address).
 *
 * Flow on connect:
 *  1) Subscribe Battery and read initial level.
 *  2) Write Current Time.
 *  3) Write age, gender, height directly (UDS attribute writes; no consent needed).
 *  4) Subscribe custom measurement notifications (A/B).
 *  5) For each scale slot 1..7: select the user (0x12 idx 0x02) then request its
 *     history (0x09 idx); records arrive as 0x09 frames on CHR_SOEHNLE_A.
 *
 * Note: the UDS User Control Point (0x2A9F) register/consent operations return
 * "Op Code Not Supported" on this firmware (BC-CY-E11) and are not needed — confirmed
 * by an HCI snoop of the official Soehnle app, which downloads history regardless of
 * whether its consent attempt succeeds. The "select user" step (0x12) is the part the
 * scale actually requires before it will return any record.
 */
class SoehnleHandler : ScaleDeviceHandler() {

    override fun supportFor(device: com.health.openscale.core.service.ScannedDeviceInfo): DeviceSupport? {
        val name = device.name
        val supported = name.startsWith("Shape200") || name.startsWith("Shape100") ||
                name.startsWith("Shape50") || name.startsWith("Style100")
        return if (supported) {
            DeviceSupport(
                displayName = "Soehnle Scale",
                capabilities = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.BATTERY_LEVEL
                ),
                implemented = setOf(
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC,
                    DeviceCapability.USER_SYNC,
                    DeviceCapability.HISTORY_READ,
                    DeviceCapability.BATTERY_LEVEL
                ),
                tuningProfile = TuningProfile.Balanced,
                linkMode = LinkMode.CONNECT_GATT
            )
        } else null
    }

    // --- UUIDs ---------------------------------------------------------------

    // Standard services/characteristics
    private val SVC_BATTERY = uuid16(0x180F)
    private val CHR_BATTERY_LEVEL = uuid16(0x2A19)

    private val SVC_CURRENT_TIME = uuid16(0x1805)
    private val CHR_CURRENT_TIME = uuid16(0x2A2B)

    private val SVC_USER_DATA = uuid16(0x181C)
    private val CHR_USER_AGE = uuid16(0x2A80)
    private val CHR_USER_GENDER = uuid16(0x2A8C)
    private val CHR_USER_HEIGHT = uuid16(0x2A8E)

    // Soehnle custom service
    private val SVC_SOEHNLE = UUID.fromString("352e3000-28e9-40b8-a361-6db4cca4147c")
    private val CHR_SOEHNLE_A = UUID.fromString("352e3001-28e9-40b8-a361-6db4cca4147c") // notify
    private val CHR_SOEHNLE_B = UUID.fromString("352e3004-28e9-40b8-a361-6db4cca4147c") // notify
    private val CHR_SOEHNLE_CMD = UUID.fromString("352e3002-28e9-40b8-a361-6db4cca4147c") // write

    // --- Lifecycle -----------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        // (1) Battery: subscribe + read once
        setNotifyOn(SVC_BATTERY, CHR_BATTERY_LEVEL)
        readFrom(SVC_BATTERY, CHR_BATTERY_LEVEL)

        // (2) Write current time using BluetoothBytesParser (CTS) so the scale stamps
        //     stored records with the correct date/time.
        val bleBuilder = BluetoothBytesBuilder()
        val calendar = Calendar.getInstance()
        bleBuilder.addUInt16(calendar.get(Calendar.YEAR))
        bleBuilder.addUInt8((calendar.get(Calendar.MONTH) + 1))
        bleBuilder.addUInt8(calendar.get(Calendar.DAY_OF_MONTH))
        bleBuilder.addUInt8(calendar.get(Calendar.HOUR_OF_DAY))
        bleBuilder.addUInt8(calendar.get(Calendar.MINUTE))
        bleBuilder.addUInt8(calendar.get(Calendar.SECOND))
        bleBuilder.addUInt8(calendar.get(Calendar.DAY_OF_WEEK))
        bleBuilder.addUInt8(0)

        LogManager.d(TAG, "Writing Current Time to $CHR_CURRENT_TIME: ${calendar.time}")

        writeTo(SVC_CURRENT_TIME, CHR_CURRENT_TIME, bleBuilder.build(), withResponse = true)

        // (3) Push profile fields. On the BC-CY-E11 firmware these UDS attribute writes
        //     succeed *directly*; the UDS User Control Point register/consent operations
        //     return "Op Code Not Supported" and are NOT required to read measurements
        //     (confirmed via an HCI snoop of the official Soehnle app), so we skip them.
        writeTo(SVC_USER_DATA, CHR_USER_AGE, byteArrayOf(user.age.toByte()), withResponse = true)
        writeTo(SVC_USER_DATA, CHR_USER_GENDER, byteArrayOf(if (user.gender.isMale()) 0x00 else 0x01), withResponse = true)
        writeTo(SVC_USER_DATA, CHR_USER_HEIGHT, ConverterUtils.toInt16Le(user.bodyHeight.toInt()), withResponse = true)

        // (4) Subscribe to custom measurement/history notifications
        setNotifyOn(SVC_SOEHNLE, CHR_SOEHNLE_A)
        setNotifyOn(SVC_SOEHNLE, CHR_SOEHNLE_B)

        // (5) For each scale slot: SELECT the user, then request its history.
        //     The official app sends 0x12 <index> 0x02 ("select user") *before*
        //     0x09 <index> ("read history"). openScale previously sent only 0x09, so the
        //     scale answered with empty [01] acks on CHR_SOEHNLE_B and never returned a
        //     0x09 measurement frame on CHR_SOEHNLE_A.
        for (i in 1..7) {
            writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, byteArrayOf(0x12, i.toByte(), 0x02), withResponse = true)
            writeTo(SVC_SOEHNLE, CHR_SOEHNLE_CMD, byteArrayOf(0x09, i.toByte()), withResponse = true)
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (data.isEmpty()) return
        when (characteristic) {
            CHR_SOEHNLE_A -> handleSoehnleA(data)
            CHR_BATTERY_LEVEL -> handleBattery(data)
            else -> Unit
        }
    }

    protected fun loadUserIdForScaleIndex(scaleIndex: Int): Int =
        settingsGetInt("userMap/userIdByIndex/$scaleIndex", -1)

    protected fun saveScaleIndexForAppUser(appUserId: Int, scaleIndex: Int) {
        settingsPutInt("userMap/scaleIndexByAppUser/$appUserId", scaleIndex)
        settingsPutInt("userMap/userIdByIndex/$scaleIndex", appUserId)
    }
    // --- Handlers -------------------------------------------------------------

    private fun handleBattery(value: ByteArray) {
        val level = (value.first().toInt() and 0xFF)
        if (level <= 10) {
            userWarn(R.string.bluetooth_scale_warning_low_battery, level)
        }
    }

    private fun handleSoehnleA(value: ByteArray) {
        // Only handle 0x09 frames of length 15
        if (value.size != 15 || value[0] != 0x09.toByte()) return

        val weightKg = ConverterUtils.fromUnsignedInt16Be(value, 9) / 10.0f
        val soehnleUserIndex = (value[1].toInt() and 0xFF)
        val year = ConverterUtils.fromUnsignedInt16Be(value, 2)
        val month = (value[4].toInt() and 0xFF)
        val day = (value[5].toInt() and 0xFF)
        val hour = (value[6].toInt() and 0xFF)
        val minute = (value[7].toInt() and 0xFF)
        val second = (value[8].toInt() and 0xFF)

        val imp5 = ConverterUtils.fromUnsignedInt16Be(value, 11)
        val imp50 = ConverterUtils.fromUnsignedInt16Be(value, 13)

        val cal: Calendar = GregorianCalendar(TimeZone.getDefault()).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

        // We need the user's profile for composition calcs; the current app user is also
        // the fallback owner for a slot we have not mapped yet.
        val u = try { currentAppUser() } catch (_: Throwable) { null }

        var openScaleUserId = loadUserIdForScaleIndex(soehnleUserIndex)
        if (openScaleUserId == -1) {
            // UDS register is unsupported on this firmware, so the scaleIndex→appUser
            // mapping is never created the "official" way. Adopt the currently selected
            // app user for this slot and persist the mapping for next time.
            if (u == null) {
                logE("Unknown Soehnle user index $soehnleUserIndex and no current app user")
                return
            }
            openScaleUserId = u.id
            saveScaleIndexForAppUser(u.id, soehnleUserIndex)
            logD("Adopted Soehnle index $soehnleUserIndex → appUser ${u.id}")
        }

        val activity = mapActivityLevel(u)
        val isMale = u?.gender?.isMale() ?: true
        val age = u?.age ?: 30
        val height = u?.bodyHeight ?: 175f

        val lib = SoehnleLib(isMale, age, height, activity)

        val m = ScaleMeasurement().apply {
            userId = openScaleUserId
            weight = weightKg
            dateTime = cal.time
            water = lib.getWater(weightKg, imp50.toFloat())
            fat = lib.getFat(weightKg, imp50.toFloat())
            muscle = lib.getMuscle(weightKg, imp50.toFloat(), imp5.toFloat())
        }
        publish(m)
    }

    // --- Helpers --------------------------------------------------------------

    private fun mapActivityLevel(user: ScaleUser?): Int = when (user?.activityLevel) {
        ActivityLevel.SEDENTARY -> 0
        ActivityLevel.MILD -> 1
        ActivityLevel.MODERATE -> 2
        ActivityLevel.HEAVY -> 4
        ActivityLevel.EXTREME -> 5
        else -> 0
    }
}
