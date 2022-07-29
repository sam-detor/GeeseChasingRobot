package com.example.stm32usbserial

import android.util.Log

class Driver internal constructor() {
    private val ROT_P = 0.08//0.5
    private val BASE_ROT_P = 0.0003
    private val ROT_I = 0.0
    private val ROT_D = 0.0

    private val GOOSE_LEN_INCHES = 27

    //INSIDE
    //private val FORWARD_P = 0.00001//0.00000005//0.5

    private val FORWARD_P = 0.001
    private val grassProfile = driveProfile(0.001,0.000000001,0.08,0.2f,0.75f)
    private val currentProfile = grassProfile
    private val sizeSetpoint = 100000.0 //135594.0//
    private val centerSetpoint = 0.0//320.0
    private val centerInPixels: Float = 340.0F
    private var frames_empty = 4
    private val forwardPID = MiniPID(currentProfile.forwardKP, currentProfile.forwardKI, 0.0)
    private val rotPID = MiniPID(currentProfile.forwardKP, 0.0, 0.0)
    private var prevRot: Float? = 0f
    private var prevForward:Float? = 0f
    private val TAG = "MLKit-ODT"


    fun drive(detectedObjects: List<BoxWithText>): Pair<Float?, Float?> {

        if (detectedObjects.isEmpty()) {
            frames_empty++
            if (frames_empty <= 3) {
                return Pair(prevForward, prevRot)
            }
            return Pair(null,null)
        }
        frames_empty = 0
        val center = calculateCenterDist(detectedObjects.get(0)).toDouble()
        val size = calculateSize(detectedObjects.get(0)).toDouble()
        Log.d(TAG, center.toString())
        prevForward = forwardPID.getOutput(size).toFloat()
        prevRot = rotPID.getOutput(center).toFloat()

        if (prevRot!! <= currentProfile.rotThreshold && prevRot!! >= -currentProfile.rotThreshold)
        {
            prevRot = null
        }
        if (prevForward!! <= currentProfile.forwardThreshold)
        {
            prevForward = null
        }

        return Pair(prevForward,prevRot)

    }
    /*
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
     */

    private fun calculateCenterDist(detectedObject: BoxWithText): Float {
        var pixels_to_inches: Float = GOOSE_LEN_INCHES/detectedObject.box.width().toFloat()
        var center = (detectedObject.box.centerX().toFloat() - centerInPixels) * pixels_to_inches
        return center
    }

    private fun calculateSize(detectedObject: BoxWithText): Int {
        return detectedObject.box.width() * detectedObject.box.height()
    }

    init {
        rotPID.setSetpoint(centerSetpoint)
        forwardPID.setSetpoint(sizeSetpoint)

        rotPID.setOutputLimits(-1.0,1.0)
        forwardPID.setOutputLimits(0.0,1.0)
        //sidePID.setDirection(true)
        //sidePID.setOutputFilter(50.0)

    }
}
// data class for storing drive params for different terrinas
data class driveProfile(val forwardKP: Double, val forwardKI: Double, val rotKP: Double, val forwardThreshold: Float, val rotThreshold: Float)