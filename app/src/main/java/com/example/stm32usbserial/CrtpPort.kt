package com.example.stm32usbserial

/**
 * Enum class that handles the port numbers
 * Source: https://github.com/Leonana69/STM32UsbSerial/blob/master/app/src/main/java/com/example/stm32usbserial/CrtpPort.kt
 * @author Guojun Chen
 */
enum class CrtpPort(port: Int) {
    CONSOLE(0),
    PARAMETERS(2),
    COMMANDER(3),
    MEMORY(4),
    LOGGING(5),
    COMMANDER_GENERIC(7),
    COMMANDER_HL(8),
    DEBUGDRIVER(14),
    LINKCTRL(15),
    ALL(255),
    UNKNOWN(-1); //FIXME

    private var mPortNumber: Int = port
    /*! get the port number */
    fun getNumber() = mPortNumber

    companion object {
        fun getPortByNumber(nbr: Int): CrtpPort? {
            for (p in CrtpPort.values()) {
                if (p.mPortNumber == nbr)
                    return p
            }
            return null
        }
    }
}