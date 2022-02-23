package com.example.stm32usbserial

import java.nio.ByteBuffer

class CommanderPacket(roll: Float, pitch: Float, yaw: Float, thrust: UShort): CrtpPacket(0, CrtpPort.COMMANDER) {
    private var mRoll: Float = roll
    private var mPitch: Float = pitch
    private var mYaw: Float = yaw
    private var mThrust: UShort = thrust

    override fun getDataByteCount(): Int {
        return (3 * 4 + 1 * 2)
    }

    override fun serializeData(b: ByteBuffer) {
        b.putFloat(mRoll)
        b.putFloat(mPitch)
        b.putFloat(mYaw)
        b.putShort(mThrust.toShort())
    }

    override fun toString(): String {
        return "CommanderPacket - R: $mRoll, P: $mPitch, Y: $mYaw, T: $mThrust"
    }
}