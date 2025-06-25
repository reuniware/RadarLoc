package com.reuniware.radarloc

import java.io.Serializable

data class RadarInfo(
    val numeroRadar: String,
    val typeRadar: String,
    val dateMiseEnService: String,
    val latitude: Double,
    val longitude: Double,
    val vma: Int
) : Serializable {
    fun toSerializable(): RadarInfoSerializable {
        return RadarInfoSerializable(
            numeroRadar = this.numeroRadar,
            latitude = this.latitude,
            longitude = this.longitude
        )
    }
}
