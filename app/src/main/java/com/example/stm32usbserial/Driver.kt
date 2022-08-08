package com.example.stm32usbserial

class Driver internal constructor() {

    //Helper class instances
    private var myPastFrameStatistics = PastFrameStatistics()
    private var myPastSpeeds = PreviousSpeeds()

    //GPS based vars
    private var outOfBounds = false

    //PID
    private val currentProfile = Constants.carpetProfile
    private val forwardPID = MiniPID(currentProfile.forwardKP, currentProfile.forwardKI, 0.0)
    private val rotPID = MiniPID(currentProfile.rotKP, 0.0, 0.0)

    // Misc
    private var numRots = 0

    fun drive(detectionResults: List<BoxWithText>):Pair<Float?, Float?> {

        var gooseBox: BoxWithText? = null
        var currentBoxSize = 2000000

        var smallBox: BoxWithText? = null
        var currentSmallBoxSize = 2000000

        val previousFramesIdle = myPastFrameStatistics.frames_idle

        for (result in detectionResults){
            val currentResultSize = result.box.height() * result.box.width()

            if(result.text.startsWith("Goose")) //if the box is a goose,
            {
                if (currentResultSize < currentBoxSize) { //record the smallest goose box detected
                    gooseBox = result
                    currentBoxSize = currentResultSize
                }
            }
            else if(currentResultSize < Constants.SMALL_BOX_THRESHOLD) //if the box is not a goose and is under the SMALL_BOX_THRESHOLD
            {
                if (currentResultSize < currentSmallBoxSize) { //record the smallest "small box"
                    smallBox = result
                    currentSmallBoxSize = currentResultSize
                }
            }
        }
        //Setting frames idle to be 0, if the frame is actually idle, the previous value will be restored in the else statement below
        myPastFrameStatistics.frames_idle = 0


        if (gooseBox != null) //if a goose was detected, chase it
        {
            return chase(gooseBox)
        }
        else if (myPastFrameStatistics.frames_empty < Constants.MAX_BUFFER_FRAMES) //if it saw a goose less than 7 frames ago
        {
            myPastFrameStatistics.frames_empty++
            return Pair(myPastSpeeds.prevForward, myPastSpeeds.prevRot) //return the previous speeds calculated
        }
        else if(smallBox != null) //if a box under the small box threshold was found, slowly approach it
        {
            return explore(smallBox)
        }
        else if (myPastFrameStatistics.frames_explore_empty < 7) //if it saw a small box less than 7 frames ago
        {
            myPastFrameStatistics.frames_explore_empty++
            return Pair(myPastSpeeds.prevExploreForward, myPastSpeeds.prevExploreRot) //return the previous speeds calculated
        }
        else
        {
            //if there is nothing interesting in the frames, restore the previously set frames_idle value
            myPastFrameStatistics.frames_idle = previousFramesIdle

            //if there has been nothing in the frames for the required threshold, rotate
            if(myPastFrameStatistics.frames_idle > Constants.IDLE_THRESHOLD && numRots < Constants.ROT_THRESHOLD)
            {
                myPastFrameStatistics.frames_idle = 0
                return Pair(0f, currentProfile.rotThreshold + 0.2f)
            }
        }
        //increment the idle frame value and stop the robot
        myPastFrameStatistics.frames_idle++
        return Pair(0f,0f)
    }

    private fun chase(goose: BoxWithText): Pair<Float?, Float?> {

        if (myPastFrameStatistics.frames_empty > 0) {
            myPastFrameStatistics.frames_empty = 0
        }

        val center = calculateCenterDist(goose).toDouble()
        val size = calculateSize(goose).toDouble()
        //Log.d(TAG, center.toString())
        myPastSpeeds.prevForward = forwardPID.getOutput(size).toFloat()
        myPastSpeeds.prevRot = rotPID.getOutput(center).toFloat()

        if (myPastSpeeds.prevRot!! <= currentProfile.rotThreshold && myPastSpeeds.prevRot!! >= -currentProfile.rotThreshold)
        {
            //Log.d(TAG,prevRot.toString())
            myPastSpeeds.prevRot = 0f
        }
        if (myPastSpeeds.prevForward!! <= currentProfile.forwardThreshold)
        {
            myPastSpeeds.prevForward = 0f
        }
        //Log.d(TAG, size.toString())
        //Log.d(TAG, prevRot.toString())
        return Pair(myPastSpeeds.prevForward,myPastSpeeds.prevRot)

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
        var pixels_to_inches: Float = Constants.GOOSE_LEN_INCHES/detectedObject.box.width().toFloat()
        var center = (detectedObject.box.centerX().toFloat() - Constants.CENTER_IN_PIXELS) * pixels_to_inches
        return center
    }

    private fun calculateSize(detectedObject: BoxWithText): Int {
        return detectedObject.box.width() * detectedObject.box.height()
    }

    init {
        rotPID.setSetpoint(Constants.CENTER_SETPOINT)
        forwardPID.setSetpoint(Constants.SIZE_SETPOINT)

        rotPID.setOutputLimits(-1.0,1.0)
        forwardPID.setOutputLimits(0.0,1.0)
    }



    fun explore(smallBox: BoxWithText): Pair<Float, Float> {
        myPastFrameStatistics.frames_explore_empty = 0
        val center = calculateCenterDist(smallBox)
        var prevExploreRot = rotPID.getOutput(center.toDouble()).toFloat()
        if (prevExploreRot <= currentProfile.rotThreshold && prevExploreRot >= -currentProfile.rotThreshold)
        {
            prevExploreRot = 0f
        }
        //prevExploreRot = 0f
        myPastSpeeds.prevExploreForward = currentProfile.forwardThreshold + 0.3f
        myPastSpeeds.prevExploreRot = prevExploreRot
        val pair = Pair( myPastSpeeds.prevExploreForward!!, myPastSpeeds.prevExploreRot!!)
        //Log.d(TAG, pair.toString())
        return pair

    }

    fun stopBot()
    {
        outOfBounds = true
    }

    fun startBot()
    {
        outOfBounds = false
    }

}
// data class for storing drive params for different terrain
data class DriveProfile(val forwardKP: Double, val forwardKI: Double, val rotKP: Double, val forwardThreshold: Float, val rotThreshold: Float)
data class PastFrameStatistics( var frames_empty: Int = 8, var frames_explore_empty: Int = 8, var frames_idle: Int = 0)
data class PreviousSpeeds(var prevRot: Float? = 0f, var prevForward:Float? = 0f, var prevExploreRot: Float? = 0f, var prevExploreForward:Float? = 0f)
enum class DriveState{
    CHASE, GOOSE_BLIND, INTERPOLATE, EXPLORE_FORWARD, EXPLORE_ROTATE, BLIND,NONE, SMALL_BOX
}
enum class ProcessingState{
    CHASE, GOOSE_BLIND, EXPLORE_FORWARD, EXPLORE_ROTATE, INTERPOLATE,
}