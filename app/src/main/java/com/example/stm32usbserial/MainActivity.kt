package com.example.stm32usbserial

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity(), View.OnClickListener {

    private var previewSV: SurfaceView? = null
    private var cameraSource: CameraSource? = null
    private var psv = PreviewSurfaceView()

    private val job = SupervisorJob()
    private val mCoroutineScope = CoroutineScope(Dispatchers.IO + job)
    private val TAG = "MLKit-ODT"

    private val objectDetectionHelper = ObjectDetectionHelper()
    private val myDriver = Driver()

    //UI Stuff
    private var mTvDevName: TextView? = null
    private var mTvDevVendorId: TextView? = null
    private var mTvDevProductId: TextView? = null
    private var mTvRxMsg: TextView? = null
    private var mEtTxMsg: EditText? = null
    private var mBtnCnt: Button? = null
    private var mBtnSend: Button? = null
    private var mPodUsbSerialService: PodUsbSerialService? = null
    private var mBounded: Boolean = false

    private var mBtnF: Button? = null
    private var mBtnB: Button? = null
    private var mBtnL: Button? = null
    private var mBtnR: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Camera UI
        previewSV = findViewById(R.id.sv_preview)

        //request camera permissions
        requestPermission()

        // UI
        mTvDevName = findViewById(R.id.tv_devName)
        mTvDevVendorId = findViewById(R.id.tv_devVendorId)
        mTvDevProductId = findViewById(R.id.tv_devProductId)
        mTvRxMsg = findViewById(R.id.tv_rxMsg)
        mEtTxMsg = findViewById(R.id.et_txMsg)
        mBtnCnt = findViewById(R.id.btn_cnt)
        mBtnSend = findViewById(R.id.btn_send)

        mBtnF = findViewById(R.id.btn_front)
        mBtnB = findViewById(R.id.btn_back)
        mBtnL = findViewById(R.id.btn_left)
        mBtnR = findViewById(R.id.btn_right)

        // set click listener
        mBtnCnt?.setOnClickListener(this)
        mBtnSend?.setOnClickListener(this)
        mBtnF?.setOnClickListener(this)
        mBtnB?.setOnClickListener(this)
        mBtnL?.setOnClickListener(this)
        mBtnR?.setOnClickListener(this)
    }

    override fun onStart() {
        super.onStart()

        cameraSource = CameraSource(this, object: CameraSource.CameraSourceListener {
            override fun processImage(image: Bitmap) {
                //Log.d(TAG, "hi")
                runObjectDetection(image)
                //psv.setPreviewSurfaceView(image)

            }
            override fun onFPSListener(fps: Int) {}
        })
        mCoroutineScope.launch {
            cameraSource?.initCamera()
        }

        // start and bind service
        val mIntent = Intent(this, PodUsbSerialService::class.java)
        startService(mIntent)
        bindService(mIntent, mConnection, BIND_AUTO_CREATE)
        // set filter for service
        val filter = IntentFilter()
        filter.addAction(PodUsbSerialService.ACTION_USB_MSGRECEIVED)
        filter.addAction(PodUsbSerialService.ACTION_USB_CONNECTED)
        registerReceiver(mBroadcastReceiver, filter)
    }

    // get service instance
    private var mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Toast.makeText(this@MainActivity, "Service is connected", Toast.LENGTH_SHORT).show()
            mBounded = true
            val mUsbBinder: PodUsbSerialService.UsbBinder = service as PodUsbSerialService.UsbBinder
            mPodUsbSerialService = mUsbBinder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Toast.makeText(this@MainActivity, "Service is disconnected", Toast.LENGTH_SHORT).show()
            mBounded = false
            mPodUsbSerialService = null
        }
    }

    // button click
    override fun onClick(p0: View?) {
        when (p0?.id) {
            R.id.btn_cnt -> {
                mPodUsbSerialService?.usbStartConnection()
            }
            R.id.btn_send -> {
                mPodUsbSerialService?.usbSendData(mEtTxMsg?.text.toString())
                mEtTxMsg?.setText("")
            }
            R.id.btn_front -> {
                driveCar(1f,0f, 0f);
               /* val cp: CommanderPacket = CommanderPacket(0F, 1F, 0F, 14000u)
//                val cp: CommanderHoverPacket = CommanderHoverPacket(0.1F, 0F, 0F, 0.23F)
                mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
//                Log.i("CLICK", (cp as CrtpPacket).toByteArray().joinToString(" "){ it.toString(radix = 16).padStart(2, '0')})*/
            }
            R.id.btn_back -> {
                val cp: CommanderPacket = CommanderPacket(0F, -1F, 0F, 14000u)
                mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
            }
            R.id.btn_left -> {
                val cp: CommanderPacket = CommanderPacket(1F, 0F, 0F, 14000u)
                mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
            }
            R.id.btn_right -> {
                val cp: CommanderPacket = CommanderPacket(-1F, 0F, 0F, 14000u)
                mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
            }
        }
    }

    // broadcast receiver to update message and device info
    private val mBroadcastReceiver = object: BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(p0: Context?, p1: Intent?) {
            when (p1?.action) {
                PodUsbSerialService.ACTION_USB_MSGRECEIVED -> {
                    mTvRxMsg?.text = mPodUsbSerialService?.mRxMsg
                }
                PodUsbSerialService.ACTION_USB_CONNECTED -> {
                    mTvDevName?.text = getString(R.string.str_devName) + mPodUsbSerialService?.mDevName
                    mTvDevVendorId?.text = getString(R.string.str_devVendorId) + mPodUsbSerialService?.mDevVendorId.toString()
                    mTvDevProductId?.text = getString(R.string.str_devProductId) + mPodUsbSerialService?.mDevProductId.toString()
                }
            }
        }
    }

    private fun driveCar(forward: Float, side: Float, rot: Float) {
        val cp = CommanderPacket(side, forward, rot, 14000.toShort().toUShort())
        mPodUsbSerialService?.usbSendData(cp.toByteArray())
    }

    inner class PreviewSurfaceView {
        private var left: Int = 0
        private var top: Int = 0
        private var right: Int = 0
        private var bottom: Int = 0
        private var defaultImageWidth: Int = 0
        private var defaultImageHeight: Int = 0
        fun setPreviewSurfaceView(image: Bitmap) {
            val holder = previewSV?.holder
            val surfaceCanvas = holder?.lockCanvas()
            surfaceCanvas?.let { canvas ->
                if (defaultImageWidth != image.width || defaultImageHeight != image.height) {
                    defaultImageWidth = image.width
                    defaultImageHeight = image.height
                    val screenWidth: Int
                    val screenHeight: Int

                    if (canvas.height > canvas.width) {
                        val ratio = image.height.toFloat() / image.width
                        screenWidth = canvas.width
                        left = 0
                        screenHeight = (canvas.width * ratio).toInt()
                        top = (canvas.height - screenHeight) / 2
                    } else {
                        val ratio = image.width.toFloat() / image.height
                        screenHeight = canvas.height
                        top = 0
                        screenWidth = (canvas.height * ratio).toInt()
                        left = (canvas.width - screenWidth) / 2
                    }
                    right = left + screenWidth
                    bottom = top + screenHeight
                }

                canvas.drawBitmap(
                    image, Rect(0, 0, image.width, image.height),
                    Rect(left, top, right, bottom), null)
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    /** request permission */
    private fun requestPermission() {
        /** request camera permission */
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (isGranted) {
                } else {
                    Toast.makeText(this, "Request camera permission failed", Toast.LENGTH_SHORT).show()
                }
            }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }


    private fun runObjectDetection(bitmap: Bitmap) {
        // Get pre-processed img in right form
        val processedImg = objectDetectionHelper.preProcessInputImage(bitmap)
        val image = processedImg?.let { InputImage.fromBitmap(it.bitmap, 0) }

        //set up local model with custom image classification
        val localModel = LocalModel.Builder()
            .setAssetFilePath("lite-model_aiy_vision_classifier_birds_V1_3.tflite")
            .build()

        // Step 2: Initialize the detector object
        val options = CustomObjectDetectorOptions.Builder(localModel)
            .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .setClassificationConfidenceThreshold(0.5f)
            .build()
        val detector = ObjectDetection.getClient(options)

        // Step 3: Feed given image to the detector
        if (image != null) {
            detector.process(image).addOnSuccessListener { results ->

                val detectedObjects: MutableList<BoxWithText> = mutableListOf()

                for (result in results) {
                    if (result.labels.isNotEmpty()) {
                        var name = ""
                        if (result.labels.first().text == "Branta canadensis")
                        {
                            name = "Goose"
                        }
                        else
                        {
                            name = result.labels.first().text
                        }
                        val firstLabel = result.labels.first()
                        val text = "${name}, ${firstLabel.confidence.times(100).toInt()}%"
                        detectedObjects.add(BoxWithText(result.boundingBox, text))
                    }
                }

                // Draw the detection result on the input bitmap
                val visualizedResult = objectDetectionHelper.drawDetectionResult(processedImg.bitmap, detectedObjects)
                val objectsForDriving = objectDetectionHelper.filterBoxes(detectedObjects)

                val pair = myDriver.drive(objectsForDriving)

                val (forward, rot) = pair
                if (forward != null && rot != null && rot != 0f) {
                    driveCar(0f, 0f,rot)
                    Log.d(TAG,pair.toString())
                }


                val rotationMatrix = Matrix()
                rotationMatrix.postRotate(270F)
                val rotatedImage = Bitmap.createBitmap(visualizedResult,0,0,visualizedResult.width, visualizedResult.height, rotationMatrix, true)
                psv.setPreviewSurfaceView(rotatedImage)
            }
        }
    }
}
/**
 * A general-purpose data class to store detection result for visualization
 */
data class BoxWithText(val box: Rect, val text: String)
