package com.reuniware.radarloc

import java.io.Serializable

data class RadarInfoSerializable(
    val numeroRadar: String,
    val latitude: Double,
    val longitude: Double
) : Serializable
