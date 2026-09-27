package com.wb8wka.bthome2decoder

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceData: ByteArray,
    val lastSeen: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleDevice) return false
        return address == other.address
    }
    override fun hashCode(): Int = address.hashCode()
}
