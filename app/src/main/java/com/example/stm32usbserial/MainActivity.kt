package com.example.stm32usbserial

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity(), View.OnClickListener {
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
                val cp: CommanderPacket = CommanderPacket(0F, 1F, 0F, 14000u)
//                val cp: CommanderHoverPacket = CommanderHoverPacket(0.1F, 0F, 0F, 0.23F)
                mPodUsbSerialService?.usbSendData((cp as CrtpPacket).toByteArray())
//                Log.i("CLICK", (cp as CrtpPacket).toByteArray().joinToString(" "){ it.toString(radix = 16).padStart(2, '0')})
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
}
