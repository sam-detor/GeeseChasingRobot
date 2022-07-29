package com.example.stm32usbserial

import android.graphics.*
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op

class ObjectDetectionHelper {

    fun preProcessInputImage(bitmap: Bitmap): TensorImage? {
        val width: Int = bitmap.width
        val height: Int = bitmap.height

        val size = if (height > width) width else height
        val imageProcessor = ImageProcessor.Builder().apply {
            add(Rot90Op(3))
            //add(ResizeWithCropOrPadOp(size, size))
            //add(ResizeOp(width, height, ResizeOp.ResizeMethod.BILINEAR))
        }.build()
        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)
        return imageProcessor.process(tensorImage)
    }

    /**
     * Draw bounding boxes around objects together with the object's name.
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

    //this method determines what box the robot should try to drive towards
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
    }
}