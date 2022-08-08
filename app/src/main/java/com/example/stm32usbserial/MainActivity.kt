/**
 * Main activity for Geese Chasing Robot App.
 * To use the app, first take GPS data at the 4 corners of the area you want the robot to stay in. Then hit "chase geese"
 * The robot will start obj detection, it will investigate identified objects that it thinks could be geese,
 * and then once it recoginizes a goose, it will chase it. If it can't find a goose in its line of sight after 200 frames, it will rotate
 * If the robot exits the GPS dictated bounds of the property, it will stop.
 *
 * This code heavily draws from Guojun Chen's STM32UsbSerial and Andriod-Camera repos:
 * @see https://github.com/Leonana69/STM32UsbSerial
 * @see https://github.com/Leonana69/Android-Camera
 */

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
import android.widget.*
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

    //Camera feed related variables
    private var previewSV: SurfaceView? = null
    private var cameraSource: CameraSource? = null
    private var psv = PreviewSurfaceView()

    private val job = SupervisorJob()
    private val mCoroutineScope = CoroutineScope(Dispatchers.IO + job)

    //Debugging constants
    private val TAG = "MLKit-ODT"

    //Helper class instances
    private val objectDetectionHelper = ObjectDetectionHelper()
    private val myDriver = Driver()
    private var myFence: Geofence? = null

    // State variables
    private var runObjectDetection = false

    //STM32 connection variables
    private var mPodUsbSerialService: PodUsbSerialService? = null
    private var mBounded: Boolean = false

    //UI
    private var mBtnCnt: Button? = null
    var takeDataButton: Button? = null

    //GPS variables
    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var latCoordinateList: MutableList<Float> = mutableListOf()
    private var longCoordinateList: MutableList<Float> = mutableListOf()
    private var takeData = false
    private var wasOutOfBounds = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.chase_layout)

        //Camera UI
        previewSV = findViewById(R.id.sv_preview)

        //request camera permissions
        requestPermission()

        // Button instantiations
        mBtnCnt = findViewById(R.id.btn_cnt)
        takeDataButton = findViewById(R.id.take_data)
        var clearDataButton: Button = findViewById(R.id.clear_data)
        var startButton: Button = findViewById(R.id.chase_geese)

        // set on-click listeners (see OnClick for more details)
        mBtnCnt?.setOnClickListener(this)
        takeDataButton?.setOnClickListener(this)
        clearDataButton?.setOnClickListener(this)
        startButton?.setOnClickListener(this)

        //GPS initialization

        //Create location request and set callback speed and location accuracy
        locationRequest = LocationRequest.create()
        locationRequest?.interval = 1000 * Constants.FAST_UPDATE_INTERVAL
        locationRequest?.fastestInterval = 1000 * Constants.FAST_UPDATE_INTERVAL
        locationRequest?.priority = Priority.PRIORITY_HIGH_ACCURACY

        //override location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                p0 ?: return
                for (location in p0.locations){

                    //if the user is trying to take GPS data for the geofence, save the newest latitude/longitude in the global lists
                    if (takeData) {
                        latCoordinateList.add(location.latitude.toFloat())
                        longCoordinateList.add(location.longitude.toFloat())
                        takeData = false
                        Toast.makeText(applicationContext, "Data Taken", Toast.LENGTH_SHORT).show()
                    }
                    else {
                        Toast.makeText(applicationContext, "Callback", Toast.LENGTH_SHORT).show()
                    }

                    if(myFence != null) { //if a geofence is in effect, check if the robot is inside the geofence based on the most recent data
                        val inFence: Boolean? = myFence?.insideFence(location)

                        //if the robot is outside the geofence, stop all driving
                        if(inFence == false){
                            myDriver.stopBot()
                            wasOutOfBounds = true
                            Toast.makeText(applicationContext, "Out of Bounds", Toast.LENGTH_SHORT).show()
                        }
                        //if the robot returned to bounds, re-enable vision tracking
                        else if (wasOutOfBounds){
                            myDriver.startBot()
                            Toast.makeText(applicationContext, "In Bounds", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        //start receiving accurate GPS data at regular intervals
        startLocationCallbacks()
    }

    override fun onStart() {
        super.onStart()

        //create camera source obj
        cameraSource = CameraSource(this, object: CameraSource.CameraSourceListener {
            override fun processImage(image: Bitmap) { //when you receive the camera frame
                if(runObjectDetection)
                {
                    //if obj detection in enabled, run vision tracking
                    runObjectDetection(image)
                }
                else {
                    //display raw camera feeed
                    psv.setPreviewSurfaceView(image)
                }
            }
            override fun onFPSListener(fps: Int) {}
        })

        //lauch the task that processes the video
        mCoroutineScope.launch {
            cameraSource?.initCamera()
        }

        //Creating the connection to the STM32F4 board

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

    // Controls what happens when the buttons get clicked
    override fun onClick(p0: View?) {
        when (p0?.id) {
            R.id.btn_cnt -> {
                mPodUsbSerialService?.usbStartConnection()
            }
            R.id.take_data -> {
                takeData()
            }
            R.id.clear_data -> {
                clearData()
            }
            R.id.chase_geese -> {
                startGeofence()
                runObjectDetection = true
            }
        }
    }

    // create broadcast receiver
    private val mBroadcastReceiver = object: BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(p0: Context?, p1: Intent?) {}
    }

    /**wrapper method for communicating with the board to drive the car.
    Takes the forward and rotation speeds, with a range of [-1.0,1.0]*/
    private fun driveCar(forward: Float, rot: Float) {
        val cp = CommanderPacket(0f, forward, rot, 14000.toShort().toUShort())
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

    /**
     * high level method responsible for running the obj detection and autonomously
     * driving the car based on vision tracking results
     */
    private fun runObjectDetection(bitmap: Bitmap) {

        // Get pre-processed img in right form
        val processedImg = objectDetectionHelper.preProcessInputImage(bitmap)
        var image = processedImg?.let { InputImage.fromBitmap(it.bitmap, 0) }

        //set up local model with custom bird classification model
        val localModel = LocalModel.Builder()
            .setAssetFilePath("lite-model_aiy_vision_classifier_birds_V1_3.tflite")
            .build()

        // Initialize the detector object
        val options = CustomObjectDetectorOptions.Builder(localModel)
            .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .setClassificationConfidenceThreshold(0.6f)
            .build()
        val detector = ObjectDetection.getClient(options)

        //Feed prepped image to the detector
        if (image != null) {

            detector.process(image).addOnSuccessListener { results ->

                val detectedObjects: MutableList<BoxWithText> = mutableListOf()

                //create a list of BoxWithText objects that hold the obj detector/classifier results
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

                // use the detected objects list to determine forward and rotation driving speeds
                val pair = myDriver.drive(detectedObjects)

                val (forward, rot) = pair
                Log.d(TAG, pair.toString())
                if (forward != 0f && forward != null)
                {
                    driveCar(forward, 0f)
                }
                if (rot != 0f && rot !=null)
                {
                    driveCar(0f,rot)
                }

                //rotate the result image so it looks right when you display it on the screen
                val rotationMatrix = Matrix()
                rotationMatrix.postRotate(270F)
                val rotatedImage = Bitmap.createBitmap(visualizedResult,0,0,visualizedResult.width, visualizedResult.height, rotationMatrix, true)

                //display rotated image
                psv.setPreviewSurfaceView(rotatedImage)
            }
        }
    }

    //GPS Methods
    /**
     * Calls startLocationCallbacks if GPS permissions were granted. Call back method for requesting
     * permissions
     */
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

    /**
     * Starts the location callbacks used for the geofence. If Location permission were granted,
     * it sets up the fusedLocation provider and launches the task that is responsible for the location callbacks.
     * If permissions were not granted, it requests them
     */
    private fun startLocationCallbacks() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            //start location callbacks
            fusedLocationProviderClient?.requestLocationUpdates(locationRequest,
                locationCallback,
                Looper.getMainLooper())
        }
        else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val permsArray = arrayOf<String>("${Manifest.permission.ACCESS_FINE_LOCATION}", "${Manifest.permission.ACCESS_COARSE_LOCATION}")
                requestPermissions(permsArray, Constants.PERMISSION_LOCATION)
            }
        }
    }

    /**
     * Sets the takeData param so that the location callback method knows to record the next received datapoint
     */
    private fun takeData() {
        takeData = true
    }

    /**
     * clears the arrays responsible for storing the latitude and longitude data for geofence creation
     */
    private fun clearData() {
        latCoordinateList = mutableListOf()
        longCoordinateList = mutableListOf()
    }

    /**
     * creates a geofence object using the data collected in the latitude and longitude lists
     */
    private fun startGeofence() {
        //take the max and min lat and long listed, and create a geofence object with that info
        val max_lat: Float = latCoordinateList.maxOrNull() ?: 0f
        val min_lat: Float = latCoordinateList.minOrNull() ?: 0f
        val max_long: Float = longCoordinateList.maxOrNull() ?: 0f
        val min_long: Float = longCoordinateList.minOrNull() ?: 0f
        if(max_lat != 0f && min_lat != 0f && max_long != 0f && min_long != 0f) {
            myFence = Geofence(max_lat, min_lat, max_long, min_long)
        }

    }

    /**
     * class that is responsible for storing the geofence data
     */
    class Geofence( val max_lat: Float, val min_lat: Float, val max_long: Float, val min_long: Float) {
        /**
         * tells you if you are inside the stored geofence
         */
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
 * @see <https://codelabs.developers.google.com/mlkit-android-odt#6>
 */
data class BoxWithText(val box: Rect, val text: String)
