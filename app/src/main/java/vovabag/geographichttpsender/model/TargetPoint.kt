package vovabag.geographichttpsender.model

import com.google.gson.annotations.SerializedName

data class TargetPoint(
    @SerializedName("id")
    val id: String = java.util.UUID.randomUUID().toString(),
    @SerializedName("name")
    val name: String = "",
    @SerializedName("groupName")
    val groupName: String? = null,
    @SerializedName("latitude")
    val latitude: Double,
    @SerializedName("longitude")
    val longitude: Double,
    @SerializedName("triggerRadius")
    val triggerRadius: Float = 100f, // метры
    @SerializedName("httpConfig")
    val httpConfig: HttpConfig,
    @SerializedName("directionFilter")
    val directionFilter: DirectionFilter? = null,
    @SerializedName("speedThreshold")
    val speedThreshold: Float? = null, // м/с, null = без ограничения
    @SerializedName("isEnabled")
    val isEnabled: Boolean = true
)

data class HttpConfig(
    @SerializedName("url")
    val url: String,
    @SerializedName("method")
    val method: HttpMethod = HttpMethod.GET,
    @SerializedName("headers")
    val headers: Map<String, String> = emptyMap(),
    @SerializedName("body")
    val body: String? = null,
    @SerializedName("intervalSeconds")
    val intervalSeconds: Long = 60
)

data class DirectionFilter(
    @SerializedName("bearing")
    val bearing: Float, // градусы 0-360
    @SerializedName("tolerance")
    val tolerance: Float = 45f // допустимое отклонение в градусах
)

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH
}
