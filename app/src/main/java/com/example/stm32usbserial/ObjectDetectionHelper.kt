package com.example.stm32usbserial

import android.graphics.*
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op

/**
 * Class that holds helper methods for the object detection process
 */
class ObjectDetectionHelper {

    /**
     * pre-processes the image before it's fed to the object detector. Rotates the image 270°
     */
    fun preProcessInputImage(bitmap: Bitmap): TensorImage? {

        //create the image processor to roate the image by 270°
        val imageProcessor = ImageProcessor.Builder().apply {
            add(Rot90Op(3))
        }.build()

        //create the "TensorImage" from the given bitmpa
        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)

        //process the image
        return imageProcessor.process(tensorImage)
    }

    /**
     * Draw bounding boxes around objects together with the object's name.
     * Method taken from the Build and deploy a custom object detection model with TensorFlow Lite (Android) codelab.
     * Source: https://developers.google.com/codelabs/tflite-object-detection-android#0
     * @author Khanh LeViet
     */
    fun drawDetectionResult(
        bitmap: Bitmap,
        detectionResults: List<BoxWithText>
    ): Bitmap {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val pen = Paint()
        pen.textAlign = Paint.Align.LEFT

        detectionResults.forEach {
            // draw bounding box
            pen.color = Color.RED
            if(it.text.startsWith("Goose")){
                pen.color = Color.BLUE
            }
            pen.strokeWidth = 8F
            pen.style = Paint.Style.STROKE
            val box = it.box
            canvas.drawRect(box, pen)

            val tagSize = Rect(0, 0, 0, 0)

            // calculate the right font size
            pen.style = Paint.Style.FILL_AND_STROKE
            pen.color = Color.YELLOW
            pen.strokeWidth = 2F

            pen.textSize = 96F
            pen.getTextBounds(it.text, 0, it.text.length, tagSize)
            val fontSize: Float = pen.textSize * box.width() / tagSize.width()

            // adjust the font size so texts are inside the bounding box
            if (fontSize < pen.textSize) pen.textSize = fontSize

            var margin = (box.width() - tagSize.width()) / 2.0F
            if (margin < 0F) margin = 0F
            canvas.drawText(
                it.text, box.left + margin,
                box.top + tagSize.height().times(1F), pen
            )
        }
        return outputBitmap
    }

}