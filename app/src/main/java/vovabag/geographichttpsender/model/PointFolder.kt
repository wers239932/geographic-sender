package vovabag.geographichttpsender.model

import com.google.gson.annotations.SerializedName

data class PointFolder(
    @SerializedName("id")
    val id: String = java.util.UUID.randomUUID().toString(),
    @SerializedName("name")
    val name: String
)
