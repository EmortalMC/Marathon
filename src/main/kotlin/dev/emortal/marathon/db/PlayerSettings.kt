package dev.emortal.marathon.db

@kotlinx.serialization.Serializable
data class PlayerSettings(val uuid: String, var theme: String = "light")
