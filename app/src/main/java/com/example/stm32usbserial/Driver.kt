package com.example.stm32usbserial

import android.widget.Toast
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import kotlin.math.absoluteValue

class Driver internal constructor() {

    //Helper class instances
    private var myPastFrameStatistics = PastFrameStatistics()
    private var myPastSpeeds = PreviousSpeeds()

    //GPS based vars
    private var outOfBounds = false

    //PID
    private val currentProfile = Constants.grassProfile //can also be carpetProfile for inside testing
    private val forwardPID = MiniPID(currentProfile.forwardKP, currentProfile.forwardKI, 0.0)
    private val rotPID = MiniPID(currentProfile.rotKP, 0.0, 0.0)

    // Misc
    private var numRots = 0


    //constructor
    init {
        //set the setpoints of the PID loops
        rotPID.setSetpoint(Constants.CENTER_SETPOINT)
        forwardPID.setSetpoint(Constants.SIZE_SETPOINT)

        //bound the output of the PID loops so the calculated speeds don't go out of the range
        //of the commands that can be sent to the microcontroller
        rotPID.setOutputLimits(-1.0,1.0)
        forwardPID.setOutputLimits(0.0,1.0)
    }

    /**
     * Takes in the list of BoxWithText objects created from the returned results of the running
     * the object detection model. Returns a pair of speeds to send to the microcontroller dictating
     * how fast the robot should drive forward and turn.
     * Forward speed is bounded [0.0,1.0], Rotation speed is bounded [-1.0,1.0]
     */
    fun drive(detectionResults: List<BoxWithText>):Pair<Float, Float> { //Pair(forward, rotation)
        if (outOfBounds) //If the GPS says we're out of bounds
        {
            return Pair(0f,0f)
        }

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
            else if(currentResultSize < Constants.SMALL_BOX_THRESHOLD && result.box.bottom < Constants.CENTER_IN_PIXELS) //if the box is not a goose and is under the SMALL_BOX_THRESHOLD
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
                //return Pair(0f, 1f), for outside b/c of the chassis, need to rotate at full speed
            }
        }
        //increment the idle frame value and stop the robot
        myPastFrameStatistics.frames_idle++
        return Pair(0f,0f)
    }

    /**
     * Given a BoxWithText object that has been classified as having a goose in it, return
     * a pair with the forward and rotation speeds for the robot using PID loops. The rotation
     * PID loop is based on the box's estimated horizontal distance from the center and the forward PID loop
     * is based on box size.
     *
     *  Forward speed is bounded [0.0,1.0], Rotation speed is bounded [-1.0,1.0]
     */
    private fun chase(goose: BoxWithText): Pair<Float, Float> {

        //updating the frames_empty variable for the state machine in drive
        if (myPastFrameStatistics.frames_empty > 0) {
            myPastFrameStatistics.frames_empty = 0
        }

        //calculating the current values for the forward and rotation PID loops
        val center = calculateCenterDist(goose).toDouble()
        val size = calculateSize(goose).toDouble()

        //Log the speeds for the 7 frame grace period after losing a goose object
        myPastSpeeds.prevForward = forwardPID.getOutput(size).toFloat()
        myPastSpeeds.prevRot = rotPID.getOutput(center).toFloat()

        //if the absolute value of the speeds are smaller than the speed thresholds set for this
        //terrain, then the speed is set to 0
        if (myPastSpeeds.prevRot.absoluteValue <= currentProfile.rotThreshold)
        {
            myPastSpeeds.prevRot = 0f
        }
        if (myPastSpeeds.prevForward <= currentProfile.forwardThreshold)
        {
            myPastSpeeds.prevForward = 0f
        }

        return Pair(myPastSpeeds.prevForward,myPastSpeeds.prevRot)

    }

    /**
     * Uses the estimated length of a goose and the assumption that the width of the box is fitted
     * well to the goose's body, it calculates approximately how far the box is (horizontally)
     * from the center of the image in inches
     */
    private fun calculateCenterDist(detectedObject: BoxWithText): Float {
        //creates a conversion factor from inches to pixels
        val pixelsToInches: Float =
            Constants.GOOSE_LEN_INCHES / detectedObject.box.width().toFloat()

        //converts the horizontal distance from the center from pixels to inches and returns it
        return (detectedObject.box.centerX().toFloat() - Constants.CENTER_IN_PIXELS) * pixelsToInches
    }

    /**
     * calculates the size in pixels of the provided BoxWithText object
     */
    private fun calculateSize(detectedObject: BoxWithText): Int {
        return detectedObject.box.width() * detectedObject.box.height()
    }


    /**
     * Given a BoxWithText object, it will produce a pair of speeds (forward, rotational)
     * that center the object in the image and approach it with a slow constant speed
     */
    private fun explore(smallBox: BoxWithText): Pair<Float, Float> {
        myPastFrameStatistics.frames_explore_empty = 0

        //calculate the rotational speed
        val center = calculateCenterDist(smallBox)
        var prevExploreRot = rotPID.getOutput(center.toDouble()).toFloat()

        //iof the rotation speed is too small, make it 0
        if (prevExploreRot.absoluteValue <= currentProfile.rotThreshold) {
            prevExploreRot = 0f
        }

        //log the speeds for the 7 frame grace period
        myPastSpeeds.prevExploreForward = currentProfile.forwardThreshold + 0.3f //0.9f for outside, chassis need to rotate
        myPastSpeeds.prevExploreRot = prevExploreRot                            //at a high speed so it doesn't get stuck

        return Pair(myPastSpeeds.prevExploreForward, myPastSpeeds.prevExploreRot)

    }

    /**
     * sets the boolean outOfBounds to true, which prevents the drive method from producing non-zero
     * values
     */
    fun stopBot()
    {
        outOfBounds = true
    }

    /**
     * sets the boolean outOfBounds to false, which allows the drive method to produce non-zero
     * values
     */
    fun startBot()
    {
        outOfBounds = false
    }

}

/**
 * data class for storing drive params for different terrain
 */
data class DriveProfile(val forwardKP: Double, val forwardKI: Double, val rotKP: Double, val forwardThreshold: Float, val rotThreshold: Float)

/**
 * data class for storing stats about objects in the previous frames
 */
data class PastFrameStatistics( var frames_empty: Int = 8, var frames_explore_empty: Int = 8, var frames_idle: Int = 0)

/**
 * data class for storing information about the previous speed of the robot
 */
data class PreviousSpeeds(var prevRot: Float = 0f, var prevForward:Float = 0f, var prevExploreRot: Float = 0f, var prevExploreForward:Float = 0f)
