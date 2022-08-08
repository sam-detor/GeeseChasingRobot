package com.example.stm32usbserial;

public class Constants {

    //GPS Constants
    public static Long FAST_UPDATE_INTERVAL = 5l;
    public static int PERMISSION_LOCATION = 99;

    //PID constants
    public static int GOOSE_LEN_INCHES = 27;
    public static int SMALL_BOX_THRESHOLD = 17000;
    public static double SIZE_SETPOINT = 60000.0;//100000.0 //135594.0//
    public static double CENTER_SETPOINT = 0.0;//320.0
    public static Float CENTER_IN_PIXELS = 340.0F;

    public static DriveProfile grassProfile = new DriveProfile(0.001,0.000000001,0.08,0.2f,0.75f);
    public static DriveProfile carpetProfile = new DriveProfile(0.0001,0.000000001,0.0075,0.2f,0.1f);


    //Default rotation constants
    public static long ROT_THRESHOLD = 1000000000;
    public static int IDLE_THRESHOLD = 200;

    //Interpolation constants
    public static int MAX_BUFFER_FRAMES = 7;
}
