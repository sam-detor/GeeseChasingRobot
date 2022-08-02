package com.example.stm32usbserial

import android.util.Log

class Driver internal constructor() {

    /*
    private val ROT_P = 0.08//0.5
    private val BASE_ROT_P = 0.0003
    private val ROT_I = 0.0
    private val ROT_D = 0.0
    */
    private val GOOSE_LEN_INCHES = 27
    private val SMALL_THRESHOLD = 3000

    //INSIDE
    //private val FORWARD_P = 0.00001//0.00000005//0.5

    private val grassProfile = DriveProfile(0.001,0.000000001,0.08,0.2f,0.75f)

    private val carpetProfile = DriveProfile(0.0001,0.000000001,0.0075,0.2f,0.1f)
    private val currentProfile = carpetProfile
    private val sizeSetpoint = 100000.0 //135594.0//
    private val centerSetpoint = 0.0//320.0
    private val centerInPixels: Float = 340.0F
    private var frames_empty = 4
    private var frames_full = 0
    private var frames_idle = 0
    private var frames_rotating = 0
    private val forwardPID = MiniPID(currentProfile.forwardKP, currentProfile.forwardKI, 0.0)
    private val rotPID = MiniPID(currentProfile.rotKP, 0.0, 0.0)
    private var prevRot: Float? = 0f
    private var prevForward:Float? = 0f
    private val TAG = "MLKit-ODT"


    fun drive(detectedObjects: List<BoxWithText>): Pair<Float?, Float?> {

        return filterBoxes(detectedObjects)

    }

    private fun chase(goose: BoxWithText): Pair<Float?, Float?> {

        if (frames_empty > 0) {
            frames_empty = 0
            frames_full = 0
        }
        frames_full++

        val center = calculateCenterDist(goose).toDouble()
        val size = calculateSize(goose).toDouble()
        //Log.d(TAG, center.toString())
        prevForward = forwardPID.getOutput(size).toFloat()
        prevRot = rotPID.getOutput(center).toFloat()

        if (prevRot!! <= currentProfile.rotThreshold && prevRot!! >= -currentProfile.rotThreshold)
        {
            Log.d(TAG,prevRot.toString())
            prevRot = 0f
        }
        if (prevForward!! <= currentProfile.forwardThreshold)
        {
            prevForward = 0f
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
    }

    fun filterBoxes(detectionResults: List<BoxWithText>):Pair<Float?, Float?> {
        //for each box we need the: size, h/w ratio, label`
        var labels: MutableList<String> = mutableListOf()
        var size: MutableList<Int> = mutableListOf()
        var hwRatio: MutableList<Float> = mutableListOf()
        var height: MutableList<Int> = mutableListOf()
        var index = 0
        var stateEligibility: Array<Boolean> = arrayOf(false,false,false,false,false,false, false, false)

        var gooseBox: BoxWithText? = null
        var currentBoxSize = 2000000

        var smallBox: BoxWithText? = null
        var currentSmallBoxHW = 2000000f


        for (result in detectionResults){
            labels.add(result.text)
            size.add(result.box.height() * result.box.width())
            hwRatio.add(result.box.height().toFloat() /result.box.width().toFloat())
            height.add(result.box.height())
            if(result.text.startsWith("Goose") && hwRatio[index] < 0.8 && size[index] < sizeSetpoint)
            {
                if (size[index] < currentBoxSize) {
                    gooseBox = result
                    currentBoxSize = size[index]
                }
                stateEligibility[DriveState.CHASE.ordinal] = true
            }
            else if (result.text.startsWith("Goose")){
                stateEligibility[DriveState.GOOSE_BLIND.ordinal] = true
            }
            else if(!result.text.startsWith("Goose") && size[index] < SMALL_THRESHOLD)
            {
                if (hwRatio[index] < currentSmallBoxHW) {
                    smallBox = result
                    currentSmallBoxHW = hwRatio[index]
                }
                stateEligibility[DriveState.SMALL_BOX.ordinal]
            }
        }

        if (stateEligibility[DriveState.CHASE.ordinal])
        {
            return chase(gooseBox!!)
        }
        else if (stateEligibility[DriveState.GOOSE_BLIND.ordinal])
        {
            frames_full = 0
            frames_empty = 10
            return Pair(null, null)
        }
        else if (frames_empty < 10) //MAJOR WORK IN PROGRESS
        {
            frames_empty++
            if (frames_full > 5 && frames_empty < 4)
            {
                return Pair(prevForward, prevRot)
            }
            else {
                return Pair(currentProfile.forwardThreshold + 0.3f, 0f)
            }
        }
        else if(stateEligibility[DriveState.SMALL_BOX.ordinal])
        {
            return explore(smallBox!!)
        }
        else if (frames_idle > 50 && frames_rotating < 10)
        {
            frames_rotating++
            return Pair(0f, currentProfile.rotThreshold + 0.2f)
        }
        if (frames_idle > 50) {
            frames_idle = 0
            frames_rotating = 0
        }
        frames_idle++
        return Pair(0f,0f)
    }

    fun explore(smallBox: BoxWithText): Pair<Float, Float> {

        val center = calculateCenterDist(smallBox)
        val rotVal = rotPID.getOutput(center.toDouble()).toFloat()
        if (rotVal <= currentProfile.rotThreshold && rotVal >= -currentProfile.rotThreshold)
        {
            prevRot = 0f
        }
        return Pair(currentProfile.forwardThreshold + 0.3f, rotVal)

    }

    /*//this method determines what box the robot should try to drive towards
    fun filterBoxes(detectionResults: List<BoxWithText>):List<BoxWithText>  {
        //priority list for which box to choose
        //1. goose with highest label confidence
        //2. potentially other birds
        //3. smallest box (within reason)
        //SMALLEST GOOSE box is usually the most accurate

        var size = 2000000
        var myList: MutableList<BoxWithText> = mutableListOf()
        for (result in detectionResults) {
            if(result.text.startsWith("Goose"))
            {
                var indiv_size = result.box.width() * result.box.height()
                if(indiv_size < size)
                {
                    myList = mutableListOf(result)
                    size = indiv_size
                }

            }
        }
        return myList;
    }*/
}
// data class for storing drive params for different terrain
data class DriveProfile(val forwardKP: Double, val forwardKI: Double, val rotKP: Double, val forwardThreshold: Float, val rotThreshold: Float)
enum class DriveState{
    CHASE, GOOSE_BLIND, INTERPOLATE, EXPLORE_FORWARD, EXPLORE_ROTATE, BLIND,NONE, SMALL_BOX
}
enum class ProcessingState{
    CHASE, GOOSE_BLIND, EXPLORE_FORWARD, EXPLORE_ROTATE, INTERPOLATE,
}