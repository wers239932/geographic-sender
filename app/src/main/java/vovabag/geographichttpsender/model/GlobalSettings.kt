package vovabag.geographichttpsender.model

import com.google.gson.annotations.SerializedName

/**
 * Глобальные настройки приложения, хранимые в DataStore.
 * Все значения таймаутов — в секундах (в коде конвертируются в мс/с).
 */
data class GlobalSettings(
    /** Таймаут подключения HTTP (секунды) */
    @SerializedName("connectTimeoutSec")
    val connectTimeoutSec: Int = 30,
    /** Таймаут чтения HTTP (секунды) */
    @SerializedName("readTimeoutSec")
    val readTimeoutSec: Int = 30,
    /** Таймаут записи HTTP (секунды) */
    @SerializedName("writeTimeoutSec")
    val writeTimeoutSec: Int = 30,
    /** Интервал обновления уведомления (секунды) */
    @SerializedName("notificationUpdateSec")
    val notificationUpdateSec: Int = 5,
    /** Дальность "далёко" — выше этого значения используется FAR_INTERVAL (метры) */
    @SerializedName("farDistanceM")
    val farDistanceM: Int = 1000,
    /** Дальность "средне" — между MID_DISTANCE и FAR_DISTANCE используется MID_INTERVAL (метры) */
    @SerializedName("midDistanceM")
    val midDistanceM: Int = 500,
    /** GPS-интервал "далеко" (секунды) */
    @SerializedName("farIntervalSec")
    val farIntervalSec: Int = 120,
    /** GPS-интервал "средне" (секунды) */
    @SerializedName("midIntervalSec")
    val midIntervalSec: Int = 20,
    /** GPS-интервал "близко" (секунды) */
    @SerializedName("nearIntervalSec")
    val nearIntervalSec: Int = 1,
    /** Актуальность статуса отправки в уведомлении (секунды) */
    @SerializedName("statusFreshnessSec")
    val statusFreshnessSec: Int = 5
) {
    companion object {
        val DEFAULT = GlobalSettings()
    }
}