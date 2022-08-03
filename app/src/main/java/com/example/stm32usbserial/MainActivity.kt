package com.example.stm32usbserial

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), View.OnClickListener {

    private var previewSV: SurfaceView? = null
    private var cameraSource: CameraSource? = null
    private var psv = PreviewSurfaceView()

    private val job = SupervisorJob()
    private val mCoroutineScope = CoroutineScope(Dispatchers.IO + job)
    private val TAG = "MLKit-ODT"

    private val objectDetectionHelper = ObjectDetectionHelper()
    private val myDriver = Driver()
    private var BLIND = false
    private var runObjectDetection = false

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

    //GPS Stuff
    var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private lateinit var locationRequest: LocationRequest
    val DEFAULT_UPDATE_INTERVAL: Long = 30
    val FAST_UPDATE_INTERVAL:Long = 5
    val PERMISSION_LOCATION = 99
    var geofenceButton: Button? = null
    var takeDataButton: Button? = null
    private lateinit var locationCallback: LocationCallback
    private var latCoordinateList: MutableList<Float> = mutableListOf()
    private var longCoordinateList: MutableList<Float> = mutableListOf()
    private var takeData = false
    private var myFence: Geofence? = null
    private var tv_lat: TextView? = null
    private var tv_long: TextView? = null
    private var wasOutOfBounds = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.startscreen)

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

        //deal with buttons



        //GPS stuff
        locationRequest = LocationRequest.create()
        locationRequest?.interval = 1000 * FAST_UPDATE_INTERVAL
        locationRequest?.fastestInterval = 1000 * FAST_UPDATE_INTERVAL
        locationRequest?.priority = Priority.PRIORITY_HIGH_ACCURACY

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                //Log.d("BUTTON", "hi2")
                p0 ?: return
                for (location in p0.locations){
                    // Update UI with location data
                    //Log.d("RESULT", "hi")
                    UpdateUI(location)
                    if (takeData) {
                        latCoordinateList.add(location.latitude.toFloat())
                        longCoordinateList.add(location.longitude.toFloat())
                        takeData = false
                        Toast.makeText(applicationContext, "Data Taken", Toast.LENGTH_SHORT).show()
                    }
                    else {
                        Toast.makeText(applicationContext, "Callback", Toast.LENGTH_SHORT).show()
                    }

                    if(myFence != null) {
                        val infence: Boolean? = myFence?.insideFence(location)
                        if(infence == false){
                            myDriver.stopBot()
                            wasOutOfBounds = true
                            Toast.makeText(applicationContext, "Out of Bounds", Toast.LENGTH_SHORT).show()
                        }
                        else if (wasOutOfBounds){
                            myDriver.startBot()
                            Toast.makeText(applicationContext, "In Bounds", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        startLocationCallbacks()

        takeDataButton = findViewById(R.id.gps_button)
        takeDataButton?.setOnClickListener{
            takeData()
        }

        var clearDataButton: Button = findViewById(R.id.clear_data_button)
        clearDataButton?.setOnClickListener{
            clearData()
        }

        var startButton: Button = findViewById(R.id.chase_geese_button)
        startButton?.setOnClickListener{
            startGeofence()
            setContentView(R.layout.manual_control)
            runObjectDetection = true
        }
    }

    override fun onStart() {
        super.onStart()

        cameraSource = CameraSource(this, object: CameraSource.CameraSourceListener {
            override fun processImage(image: Bitmap) {
                //Log.d(TAG, "hi")
                if(runObjectDetection)
                {
                    runObjectDetection(image)
                }
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
                //val cp: CommanderPacket = CommanderPacket(1F, 0F, 0F, 14000u)
                //mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
                driveCar(0f,0f, -0.7f);
            }
            R.id.btn_right -> {
                //val cp: CommanderPacket = CommanderPacket(-1F, 0F, 0F, 14000u)
                //mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
                driveCar(0f,0f, 1f);
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
        var image = processedImg?.let { InputImage.fromBitmap(it.bitmap, 0) }
        if (BLIND) {


            val my_bitmap = Bitmap.createBitmap(image!!.width, image!!.height, Bitmap.Config.ARGB_8888)
            image = InputImage.fromBitmap(my_bitmap, 0)
            BLIND = false
        }

        //set up local model with custom image classification
        val localModel = LocalModel.Builder()
            .setAssetFilePath("lite-model_aiy_vision_classifier_birds_V1_3.tflite")
            .build()

        // Step 2: Initialize the detector object
        val options = CustomObjectDetectorOptions.Builder(localModel)
            .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .setClassificationConfidenceThreshold(0.6f)
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
                val visualizedResult = objectDetectionHelper.drawDetectionResult(processedImg!!.bitmap, detectedObjects)


                val pair = myDriver.drive(detectedObjects)

                val (forward, rot) = pair
                if(forward == null || rot == null)
                {
                    BLIND = true
                }
                else{
                    Log.d(TAG, pair.toString())
                    if (forward != 0f)
                    {

                        driveCar(forward, 0f,0f)
                    }
                    if (rot != 0f)
                    {
                        driveCar(0f, 0f,rot)
                    }
                }

                    //Log.d(TAG,rot.toString())
                    //Log.d(TAG,pair.toString())


                val rotationMatrix = Matrix()
                rotationMatrix.postRotate(270F)
                val rotatedImage = Bitmap.createBitmap(visualizedResult,0,0,visualizedResult.width, visualizedResult.height, rotationMatrix, true)
                psv.setPreviewSurfaceView(rotatedImage)
            }
        }
    }

    //GPS Methods
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if(grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationCallbacks()
        }
        else {
            Toast.makeText(this, "Permissions not gotten", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    fun updateGPS() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            fusedLocationProviderClient?.lastLocation?.addOnSuccessListener {
                UpdateUI(it)
            }
            /*fusedLocationProviderClient?.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, object : CancellationToken() {
                            override fun onCanceledRequested(p0: OnTokenCanceledListener) = CancellationTokenSource().token

                            override fun isCancellationRequested() = false
                        })
                        ?.addOnSuccessListener { location: Location? ->
                            if (location == null)
                                Toast.makeText(this, "Cannot get location.", Toast.LENGTH_SHORT).show()
                            else {
                                UpdateUI(location)
                            }

                        }*/

        }
        else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val permsArray = arrayOf<String>("${Manifest.permission.ACCESS_FINE_LOCATION}", "${Manifest.permission.ACCESS_COARSE_LOCATION}")
                requestPermissions(permsArray,PERMISSION_LOCATION)
            }
        }
    }

    fun UpdateUI(location: Location) {
        tv_lat?.text = location.latitude.toString()
        tv_long?.text = location.longitude.toString()

    }

    fun startLocationCallbacks() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            //fusedLocationProviderClient?.lastLocation?.addOnSuccessListener {
            //UpdateUI(it)
            //}

            updateGPS()
            fusedLocationProviderClient?.requestLocationUpdates(locationRequest,
                locationCallback,
                Looper.getMainLooper())
        }
        else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val permsArray = arrayOf<String>("${Manifest.permission.ACCESS_FINE_LOCATION}", "${Manifest.permission.ACCESS_COARSE_LOCATION}")
                requestPermissions(permsArray,PERMISSION_LOCATION)
            }
        }
    }

    private fun takeData() {
        takeData = true
    }

    private fun clearData() {
        latCoordinateList = mutableListOf()
        longCoordinateList = mutableListOf()
    }

    private fun startGeofence() {
        val max_lat: Float = latCoordinateList.maxOrNull() ?: 0f
        val min_lat: Float = latCoordinateList.minOrNull() ?: 0f
        val max_long: Float = longCoordinateList.maxOrNull() ?: 0f
        val min_long: Float = longCoordinateList.minOrNull() ?: 0f
        if(max_lat != 0f && min_lat != 0f && max_long != 0f && min_long != 0f) {
            myFence = Geofence(max_lat, min_lat, max_long, min_long)
        }

    }

    class Geofence( val max_lat: Float, val min_lat: Float, val max_long: Float, val min_long: Float) {
        fun insideFence(location: Location): Boolean {
            val lat = location.latitude.toFloat()
            val long = location.longitude.toFloat()
            if (lat > min_lat && lat < max_lat && long > min_long && long < max_long)
            {
                return true
            }
            return false
        }
    }
}
/**
 * A general-purpose data class to store detection result for visualization
 */
data class BoxWithText(val box: Rect, val text: String)
