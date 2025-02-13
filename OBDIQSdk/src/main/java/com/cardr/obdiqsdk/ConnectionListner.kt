package com.cardr.obdiqsdk

import com.repairclub.repaircludsdk.models.DeviceItem

public interface ConnectionListner {
    public  fun onDeviceFetch(foundedDevices:List<DeviceItem>?)
    public  fun onScanResult(foundedDevices:List<DeviceItem>?)

}