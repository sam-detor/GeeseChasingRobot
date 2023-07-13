package com.example.stm32usbserial;

public class Constants {

    //GPS Constants
    public static Long FAST_UPDATE_INTERVAL = 5L;
    public static int PERMISSION_LOCATION = 99;

    //PID constants (These are very dependent on the environment)
    public static int GOOSE_LEN_INCHES = 27;
    public static int SMALL_BOX_THRESHOLD = 2100; //Constant for outside testing, inside testing: 1700
    public static double SIZE_SETPOINT = 100000.0;//60000.0;// //135594.0// (made it larger for chasing)
    public static double CENTER_SETPOINT = 0.0;
    public static Float CENTER_IN_PIXELS = 340.0F;

    public static DriveProfile grassProfile = new DriveProfile(0.001,0.000000001,0.08,0.5f,0.75f);
    public static DriveProfile carpetProfile = new DriveProfile(0.0001,0.000000001,0.0075,0.2f,0.1f);


    //Default rotation constants
    public static long ROT_THRESHOLD = 1000000000;
    public static int IDLE_THRESHOLD = 20000;

    //Interpolation constants
    public static int MAX_BUFFER_FRAMES = 7;

    //Debugging constants
    public static String TAG = "MLKit-ODT";

    //size of image (as given by bitmap.getHeight/width)
    public static int IMAGE_HEIGHT = 640; //IS X, functions as width in the code
    public static int IMAGE_WIDTH = 480; //IS Y, functions as height in the code
}
