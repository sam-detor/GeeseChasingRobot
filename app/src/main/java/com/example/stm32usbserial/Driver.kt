package com.example.stm32usbserial

import android.util.Log
import com.example.stm32usbserial.MiniPID
import com.example.stm32usbserial.PodUsbSerialService
import com.example.stm32usbserial.BoxWithText
import com.example.stm32usbserial.CommanderPacket

class Driver internal constructor() {
    private val P = 0.01//0.5
    private val I = 0.0
    private val D = 0.0
    private val sizeSetpoint = 200000.0 //135594.0//
    private val centerSetpoint = 340.0
    private var frames_empty = 4
    private val forwardPID = MiniPID(P, I, D)
    private val sidePID = MiniPID(P, I, D)
    private var prevSide: Float = 0f
    private var prevForward:Float = 0f
    private val TAG = "MLKit-ODT"


    fun drive(detectedObjects: List<BoxWithText>): Pair<Float?, Float?> {

        if (detectedObjects.isEmpty()) {
            frames_empty++
            if (frames_empty <= 3) {
                return Pair(prevForward, prevSide)
            }
            return Pair(null,null)
        }
        frames_empty = 0
        val center = calculateCenter(detectedObjects)
        val size = calculateSize(detectedObjects).toDouble()
        Log.d(TAG, size.toString())
        prevSide = sidePID.getOutput(center).toFloat()
        prevForward = forwardPID.getOutput(size).toFloat()
        return Pair(prevForward,prevSide)

    }

    private fun calculateCenter(detectedObjects: List<BoxWithText>): Double {
        var center = 0.0
        for (i in detectedObjects.indices) {
            center += detectedObjects[i].box.centerX()
        }
        center /= detectedObjects.size
        return center
    }

    private fun calculateSize(detectedObjects: List<BoxWithText>): Int {
        var sizes: MutableList<Int> = mutableListOf()
        for (i in detectedObjects.indices) {
            sizes.add(detectedObjects[i].box.width() * detectedObjects[i].box.height())
        }
        return sizes.maxOrNull() ?: 0
    }

    init {
        sidePID.setSetpoint(centerSetpoint)
        forwardPID.setSetpoint(sizeSetpoint)

        sidePID.setOutputLimits(-1.0,1.0)
        forwardPID.setOutputLimits(-1.0,1.0)
        forwardPID.setDirection(true)

    }
}