package com.cardr.obdiqsdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.repairclub.repaircludsdk.coreobjects.FirmwareProgress
import com.repairclub.repaircludsdk.manager.RepairClubManager
import com.repairclub.repaircludsdk.models.ConnectionEntry
import com.repairclub.repaircludsdk.models.ConnectionStage
import com.repairclub.repaircludsdk.models.ConnectionState
import com.repairclub.repaircludsdk.models.DeviceItem
import com.repairclub.repaircludsdk.models.FirmwareReleaseType
import com.repairclub.repaircludsdk.models.ScanEntry
import com.repairclub.repaircludsdk.models.ScanProgressUpdate
import com.repairclub.repaircludsdk.models.VehicleEntry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

@RequiresApi(Build.VERSION_CODES.P)
class ConnectionManager(
    private val context: Context,
) {
    private val foundDevices = CopyOnWriteArrayList<DeviceItem>()
    private var timerStarted = false
    private var connectedStatus = false
    private var disconnectionHandler: (() -> Unit)? = null
    var disconnectEmissionHandler: (() -> Unit)? = null
    var repairClubManager: RepairClubManager? = null

    private var connectionHandler: ((connectionEntry: ConnectionEntry, connectionStage: ConnectionStage, connectionState: ConnectionState?) -> Unit)? =
        null
    private var connectionEntry: ConnectionEntry? = null
    private var latestConnectionStage: ConnectionStage? = null
    val connectionStates = mutableMapOf<ConnectionStage, ConnectionState>()

    var userScans = ArrayList<ScanEntry>()
    var vehicleEntry: VehicleEntry? = null
    val userVehicles: MutableLiveData<List<VehicleEntry>> by lazy {
        MutableLiveData<List<VehicleEntry>>()
    }
    var scanProgressUpdate: ((update: ScanProgressUpdate?) -> Unit)? = null
    var statusCodes = StringBuilder()

    var isAlreadyConnected = false
    var appVersion: String = ""
    var appName: String = ""
    var vinNumber = ""
    var totalCodes = "0"
    var yearstr = ""
    var make = ""
    var model = ""
    var carName = ""
    var fuelType = ""
    var isRedinessComplete = false
    var isMilOn: Boolean = false
    var connectionStatus = false
    var passFail = ""
    var completionPercentageTextValue = ""
    private val _progress = MutableLiveData<ScanProgressUpdate?>()
    val progressUpdate: LiveData<ScanProgressUpdate?> get() = _progress

    private val _connectionState = MutableLiveData<ConnectionState?>()
    val connectonState: LiveData<ConnectionState?> get() = _connectionState

    private val _emmissionList = MutableLiveData<ArrayList<EmissionRediness>?>()
    val emmissionList: LiveData<ArrayList<EmissionRediness>?> get() = _emmissionList

    var currentFirmwareVersion = ""

    private val gson: Gson by lazy {
        Gson()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Configure iDFV
        val uuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
        } else {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }


        // Retrieve the app version from the package manager
        appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        // Retrieve the app name
        appName = try {
            val applicationInfo = context.applicationInfo
            val stringId = applicationInfo.labelRes
            if (stringId == 0) applicationInfo.nonLocalizedLabel.toString() else context.getString(
                stringId
            )
        } catch (e: Exception) {
            "Unknown App"
        }

        subscribeToDisconnections()

        // Start connecting
        // connectToRepairClubDevices()
    }

    public fun initialize(context: Context):String{
        repairClubManager = RepairClubManager.getInstance()
        repairClubManager?.initialize(context)
        repairClubManager?.configureSDK(BuildConfig.SDK_KEY,"OBDIQ ULTRA SDK",BuildConfig.SDK_VERSION,"")
        connectToRepairClubDevices()
        return repairClubManager?.repairClubSDKVersion ?: ""
    }

    fun registerDisconnectionHandler(handler: () -> Unit) {
        disconnectionHandler = handler
    }

    fun registerConnectionHandler(handler: (connectionEntry: ConnectionEntry?, connectionStage: ConnectionStage, connectionState: ConnectionState?) -> Unit) {
        connectionHandler = handler
        println("Connection:: registerConnectionHandler")
    }


    fun getCurrentConnectionSnapshot(): Pair<ConnectionEntry?, Boolean> {
        // Return a pair containing the connection entry and connection status.
        return Pair(connectionEntry, connectedStatus)
    }


    fun handleConnectionAndGetSnapshot(
        disconnectionHandler: () -> Unit,
        connectionHandler: (connectionEntry: ConnectionEntry?, connectionStage: ConnectionStage, connectionState: ConnectionState?) -> Unit
    ): Pair<ConnectionEntry?, Boolean> {
        // Register the disconnection handler
        registerDisconnectionHandler(disconnectionHandler)

        // Register the connection handler
        registerConnectionHandler(connectionHandler)

        // Return a pair containing the connection entry and connection status
        return Pair(connectionEntry, connectedStatus)
    }

    private fun subscribeToDisconnections() {
        repairClubManager?.subscribeToDisconnections {
            connectedStatus = false
            connectionStates.clear()
            latestConnectionStage = null
            connectionEntry = null
            isMilOn = false
            isAlreadyConnected = false
            disconnectionHandler?.invoke()
            println("Connection:: registerConnectionHandler - reset connection")

        }
        println("Connection:: subscribeToDisconnections")
    }

   public fun connectToRepairClubDevices() {
//        noVinFunction?.invoke()
        repairClubManager?.returnDevices { devices ->
            devices.forEach { device ->
                if (!foundDevices.contains(device)) {
                    foundDevices.add(device)
                    println("Connection:: Device found - ${device.name} ${device.rssi}")
                    if (!timerStarted) {
                        timerStarted = true
                        startSelectDeviceTimer()
                    }
                }
            }
        }
    }

  public  fun stopTroubleCodeScan() {
        repairClubManager?.stopTroubleCodeScan()
    }

    private fun startSelectDeviceTimer() {
        println("startSelectDeviceTimer ")
        Handler(Looper.getMainLooper()).postDelayed({
            selectAndConnectToClosestDevice()
        }, 1000)
    }

    private fun selectAndConnectToClosestDevice() {
        if (foundDevices.isEmpty()) {
            println("Connection:: No devices found")
            return
        }

        val closestDevice = foundDevices.minByOrNull { it.rssi } // Chooses lowest RSSI
        println("closestDevice "+closestDevice)
        closestDevice?.blePeripheral?.let { blePeripheral ->
            repairClubManager?.connectTo(closestDevice) { connectionEntry, connectionStage, connectionState ->
                repairClubManager?.stopScanning()
                timerStarted = false
                handleConnectionUpdates(connectionEntry, connectionStage, connectionState)

            }
        }
    }

    private fun handleConnectionUpdates(
        connectionEntry: ConnectionEntry,
        connectionStage: ConnectionStage,
        connectionState: ConnectionState?
    ) {
        if (connectionState != null) {
            connectionStates[connectionStage] = connectionState
        }
        this.connectionEntry = connectionEntry
        connectionHandler?.invoke(connectionEntry, connectionStage, connectionState)

        when (connectionStage) {
            ConnectionStage.DEVICE_HANDSHAKE -> {
                println("Connection:: DEVICE_HANDSHAKE - $connectionState")
                this.connectedStatus = connectionState == ConnectionState.COMPLETED
            }

            ConnectionStage.MAIN_BUS_FOUND -> {
                println("Connection:: mainBusFound - $connectionState")

            }

            ConnectionStage.VIN_RECEIVED -> {
                println("Connection:: vinReceived - $connectionState")

                if (connectionState == ConnectionState.COMPLETED) {
                    vinNumber = connectionEntry.vin ?: ""

                    timer.cancel()
                    // Stop timer and stop to open No vin screen
                } else if (connectionState is ConnectionState.FAILED) {
                    // start 40 sec timer after complete
                    timer.start()
                }
            }

            ConnectionStage.VEHICLE_DECODED -> {
                println("Connection:: vehicleDecoded - $connectionState ${connectionEntry.vehicleEntry}")

                connectionEntry.vehicleEntry?.let { vehicleEntry ->
                    this.vehicleEntry = vehicleEntry

                    carName = vehicleEntry.shortDescription
                    yearstr = vehicleEntry.yearString
                    make = vehicleEntry.make
                    model = vehicleEntry.model
                    isAlreadyConnected = true
                }

                getDeviceFirmwareVersion()

            }

            ConnectionStage.CONFIG_DOWNLOADED -> {
                println("Connection:: configDownloaded - $connectionState")
            }

            ConnectionStage.BUS_SYNCED_TO_CONFIG -> {
                println("Connection:: busSyncedToConfig - $connectionState")
                if (connectionState == ConnectionState.COMPLETED) {
                    timer.cancel()
                    CoroutineScope(Dispatchers.Main).launch {
                        startScan()
                    }
                } else {
                    //MARK  Show Alert BLE fail
                    //viewModel.showMessageDialog("lease remove and reinsert the adapter in the vehicle OBD port; ensuring the device is fully inserted and the engine is running.")
                }

            }

            ConnectionStage.MIL_CHECKING -> {
                println("Connection:: milChecking - $connectionState")
                if (!isMilOn) {
                    if (connectionState == ConnectionState.COMPLETED && connectionEntry.milOn != null) {
                        isMilOn = connectionEntry.milOn ?: false

                    }
                }
            }

            else -> {
                // Handle other stages
            }
        }
    }


    fun getVehicalInformation(vin:String){
        repairClubManager?.requestVinDetailDecode(vin) {
            when {

                it.isSuccess -> {
//                    val vinDetailResult =
//                        it.getOrNull()?.toMap()?.filterValues { it.isNotEmpty() }
//                    fuelType = vinDetailResult?.get("FuelTypePrimary") ?: ""
                }
                it.isFailure ->{

                }
            }
        }
    }

    var noVinFunction = openNoVin()

    private val timer = object : CountDownTimer(40000, 1000) {
        override fun onTick(millisUntilFinished: Long) {
        }

        override fun onFinish() {
            // do something
            noVinFunction?.let { it() }

        }
    }

    private fun openNoVin(): (() -> Unit)? = null

    fun startScan() {
        if (connectionStates[ConnectionStage.CONFIG_DOWNLOADED] is ConnectionState.FAILED) {

            startGenericScan()
        }
        if (connectionStates[ConnectionStage.BUS_SYNCED_TO_CONFIG] == ConnectionState.COMPLETED) {

            startAdvancedScan()
        } else if (connectionStates[ConnectionStage.BUS_SYNCED_TO_CONFIG] is ConnectionState.FAILED) {
            startGenericScan()
        }
    }

    fun startAdvancedScan() {

        repairClubManager?.startTroubleCodeScan(true) {
            GlobalScope.launch(Dispatchers.Main) {
                setProgressValue(it)
            }
        }
    }

    fun startGenericScan() {
        repairClubManager?.startTroubleCodeScan(false) {
            GlobalScope.launch(Dispatchers.Main) {
                setProgressValue(it)
            }
        }
    }

    fun setProgressValue(value: ScanProgressUpdate?) {
        // _progress.value = value
        scanProgressUpdate?.invoke(value)
    }

    fun setConnectionStateValue(value: ConnectionState?) {
        _connectionState.value = value
    }

    fun disconnectOBD() {
        Log.d("disconnectOBD", "disconnectOBD: From Disconnect")
        repairClubManager?.stopTroubleCodeScan()
        repairClubManager?.disconnectFromDevice()
    }


    //MARK  Firmware update code below

    fun updateFirmWare(reqVersion: String, reqReleaseLevel: FirmwareReleaseType?) {
//       val levelType = reqReleaseLevel
//           ?: if (BuildConfig.DEBUG) {
//               FirmwareReleaseType.BETA
//           } else {
//               FirmwareReleaseType.PRODUCTION
//           }
        repairClubManager?.startDeviceFirmwareUpdate(
            reqVersion,
            reqReleaseLevel
        )
    }

    fun getDeviceFirmwareVersion(): Result<String?>? {
        currentFirmwareVersion =
            repairClubManager?.getDeviceFirmwareVersion()?.getOrNull()
                ?: ""
        return repairClubManager?.getDeviceFirmwareVersion()
    }


    fun stopDeviceFirmwareUpdate() {
        repairClubManager?.stopDeviceFirmwareUpdate()
    }


    fun subscribeToFirmwareVersionChanges(completionCallback: (String, String) -> Unit) {
        repairClubManager?.subscribeToFirmwareVersionChanges { s, s2 ->
            completionCallback.invoke(s, s2)
        }
    }

    fun subscribeToFirmwareProgress(
        completionCallback: (FirmwareProgress) -> Unit,
        progressUpdate: (Double) -> Unit
    ) {
        repairClubManager?.subscribeToFirmwareProgress {
            progressUpdate.invoke(completionPercentageText(it))
            completionCallback.invoke(it)
        }
    }

    private fun completionPercentageText(progress: FirmwareProgress): Double {
        val currentBlockNumber = progress.currentBlockNumber.toDouble()
        val currentBlockChunkTotal = progress.currentBlockChunkTotal.toDouble()
        val currentBlockCurrentChunkNumber = progress.currentBlockCurrentChunkNumber.toDouble()
        val blockTotal = progress.blockTotal.toDouble()
        /*     var completionPercentageTextValue = 0.0
             // Avoid division by zero
             if (blockTotal > 0 || currentBlockChunkTotal > 0) else {
                 completionPercentageTextValue = 0.0
             }*/

        // Calculate total chunks so far
        val totalChunksSoFar =
            (currentBlockNumber * currentBlockChunkTotal) + currentBlockCurrentChunkNumber
        // Calculate total chunks overall
        val totalChunksOverall = blockTotal * currentBlockChunkTotal
        // Ensure totalChunksOverall is greater than 0 to avoid division by zero
        return if (totalChunksOverall > 0) {
            val completionPercentage = (totalChunksSoFar / totalChunksOverall) * 100
            completionPercentage
        } else {
            0.00
        }
    }


    //Emission Rediness
    val emissionList = ArrayList<EmissionRediness>()
    fun getEmissionRediness(callback: (String) -> Unit) {
        isRedinessComplete = false
        emissionList.clear()
        repairClubManager?.subscribeToMonitors {
            if (it.isSuccess) {
                if (emissionList.isEmpty() || emissionList.size < 5) {
                    emissionList.clear()


                    it.getOrNull()?.forEach { monitor ->
                        Log.d("getEmissionRediness", "getEmissionRediness: $monitor")
                        val avail = monitor.readinessStatus?.first() ?: false
                        if (avail) {
                            emissionList.add(
                                EmissionRediness(
                                    name = monitor.valueName,
                                    description = monitor.description,
                                    available = monitor.readinessStatus?.first() ?: false,
                                    complete = monitor.readinessStatus?.last() ?: false
                                )
                            )
                        }
                    }
                } else {

                    repairClubManager?.endRequestMonitorsTimer()
                    disconnectEmissionHandler?.invoke()
                    isRedinessComplete = true
                }

//            if(emissionList.size > 5){
//                CoroutineScope(Dispatchers.Main).launch {
//                    Toast.makeText(
//                        context,
//                        "From endRequestMonitorsTimer ${emissionList.size}",
//                        Toast.LENGTH_SHORT
//                    ).show()
//                }
//                repairClubManager?.endRequestMonitorsTimer()
//                disconnectEmissionHandler?.invoke()
//                isRedinessComplete = true
//
//            }
                CoroutineScope(Dispatchers.Main).launch {
                    emissionList.removeAll { it.name.contains("MIL") }
                    val checkPassFail = checkPassFailEmission()
                    emissionList.map { it.finalstatus = checkPassFail }
                    _emmissionList.value = emissionList

                }

            } else if (it.isFailure) {


            }
        }
        //TODO  add countdown timer
        CoroutineScope(Dispatchers.Main).launch {
            if (!isRedinessComplete) {
                kotlinx.coroutines.delay(60000)
                repairClubManager?.endRequestMonitorsTimer()
                disconnectEmissionHandler?.invoke()
                isRedinessComplete = true
            }
        }
        repairClubManager?.requestMonitors()

        //////
        /*- warm_up_cycles_since_codes_cleared -> Mode 01 Pid 30
- distance_since_codes_cleared -> Mode 01 Pid 31
- time_since_trouble_codes_cleared -> Mode 01 Pid 4E
- time_run_with_MIL_on -> Mode 01 PID 4D*/



        CoroutineScope(Dispatchers.Main).launch {
            warm_up_cycles_since_codes_cleared()
            distance_since_codes_cleared()
            time_since_trouble_codes_cleared()
            time_run_with_MIL_on()
        }


    }

    fun checkPassFailEmission(): String {
        val nonComplete = emissionList.filter { !it.complete }
        if (emissionList.isEmpty() || emissionList.size <= 5){
            passFail = ""
            return ""
        }
        if(fuelType == "Gasoline"){
            val name = nonComplete.filter{it.name == "Evaporative System"}
            passFail = if(nonComplete.size == 1 && !name.isEmpty()){
                "PASS"
            }else if(nonComplete.size == 1 && name.isEmpty()){
                "FAIL"
            }else if(nonComplete.size > 1){
                "FAIL"
            }else{
                "PASS"
            }
        }else{
            val name = nonComplete.filter{it.name.contains("EGR/VVT System") || it.name.contains("NMHC Catalyst")}
            passFail = if(nonComplete.size >= 1 && name.isEmpty()){
                "FAIL"
            }else if(nonComplete.size == 2 && name.size == 2){
                "PASS"
            }else if(nonComplete.size == 1 && name.size == 1){
                "PASS"
            }else if(nonComplete.size > 2){
                "FAIL"
            }else{
                "PASS"
            }
        }

        return passFail
    }


    var warmUpCyclesSinceCodesCleared = 0.0
    var warmUpCyclesSinceCodesClearedstr = "-"
    fun warm_up_cycles_since_codes_cleared() {
        repairClubManager?.requestDataPoint("0130") {
            if (it.isNotEmpty()) {

                val scientificNotation = getScientificNotation(inputString = it)

                // Safely parse to Double or set to 0.0 if empty
                warmUpCyclesSinceCodesCleared = scientificNotation.toDoubleOrNull() ?: 0.0
                warmUpCyclesSinceCodesClearedstr = "-"
                warmUpCyclesSinceCodesClearedstr = warmUpCyclesSinceCodesCleared.toInt().toString()
                if(warmUpCyclesSinceCodesCleared == 0.0){
                    warmUpCyclesSinceCodesClearedstr = "-"
                }
            }
        }
    }


    var distanceSinceCodesCleared = 0
    var distanceSinceCodesClearedstr = "-"
    //    fun distance_since_codes_cleared() {
//        repairClubManager?.requestDataPoint("0131") {
//            // if (!statusCodes.contains("distance_since_codes_cleared")) {
//            if(it.isNotEmpty()) {
//                distanceSinceCodesClearedstr = "-"
//                Firebase.crashlytics.log("distance_since_codes_cleared:  ${it}")
//                distanceSinceCodesCleared = ((getScientificNotation(it).toDoubleOrNull()
//                    ?: (0.0 / 1.609))).toInt()
//                distanceSinceCodesClearedstr = distanceSinceCodesCleared.toString()
//                if(distanceSinceCodesCleared == 0){
//                    distanceSinceCodesClearedstr = "-"
//                }}
//           // statusCodes.append("distance_since_codes_cleared -> $it\n")
//            //callback(statusCodes.toString())
//            //}
//
//        }
//
//    }
    fun distance_since_codes_cleared() {
        repairClubManager?.requestDataPoint("0131") { code ->
            if(code.isNullOrEmpty()){
                return@requestDataPoint
            }
            val notation = getScientificNotation(code)
            val distanceSinceCodesCleareddouble = (notation.toDoubleOrNull() ?: 0.0) / 1.609
            distanceSinceCodesCleared = distanceSinceCodesCleareddouble.toInt()
            distanceSinceCodesClearedstr = distanceSinceCodesCleared.toString()
            if(distanceSinceCodesCleared == 0){
                distanceSinceCodesClearedstr = "-"
            }
        }
    }




    var timeSinceTroubleCodesCleared = 0
    var timeSinceTroubleCodesClearedstr = "-"
    fun time_since_trouble_codes_cleared() {
        repairClubManager?.requestDataPoint("014E") {
            // if (!statusCodes.contains("time_since_trouble_codes_cleared")) {
            if(it.isNotEmpty()) {
                timeSinceTroubleCodesClearedstr = "-"
                val notation = getScientificNotation(it)
                val timeSinceTroubleCodesCleareddoble = (notation.toDoubleOrNull() ?: 0.0) / 60
                timeSinceTroubleCodesCleared = timeSinceTroubleCodesCleareddoble.toInt()
                timeSinceTroubleCodesClearedstr = timeSinceTroubleCodesCleared.toString()
                if(timeSinceTroubleCodesCleared == 0){
                    timeSinceTroubleCodesClearedstr = "-"
                }}

        }


    }

    fun clearCode(){
        repairClubManager?.clearGenericCodes {

        }
    }

    fun  clearCodesReset(){
        timeRunWithMILOn = 0
        timeRunWithMILOnstr = "-"
        timeSinceTroubleCodesCleared = 0
        timeSinceTroubleCodesClearedstr = "-"
        distanceSinceCodesCleared = 0
        distanceSinceCodesClearedstr = "-"
        warmUpCyclesSinceCodesCleared = 0.0
        warmUpCyclesSinceCodesClearedstr = "-"
    }
    var timeRunWithMILOn = 0
    var timeRunWithMILOnstr = "-"
    fun time_run_with_MIL_on() {

        repairClubManager?.requestDataPoint("014D") {
            if(it.isNotEmpty()) {
                timeRunWithMILOnstr = "-"
                val notation = getScientificNotation(it)
                val timeSinceTroubleCodesCleareddoble = (notation.toDoubleOrNull() ?: 0.0) / 60
                timeRunWithMILOn = timeSinceTroubleCodesCleareddoble.toInt()
                timeRunWithMILOnstr = timeRunWithMILOn.toString()
                if(timeRunWithMILOn == 0){
                    timeRunWithMILOnstr = "-"
                }}
        }

    }

    fun getScientificNotation(inputString: String): String {
        var notation = ""

        // Check if inputString is empty before conversion
        if (inputString.isNotBlank()) {
            // Attempt to convert the input string to a Double
            try {
                val sciNotationValue = inputString.toDoubleOrNull()

                if (sciNotationValue != null) {
                    println("Scientific Notation Value: $sciNotationValue")
                    notation = sciNotationValue.roundToInt().toString()
                }
            }catch (ex:Exception){

            }

        }
        return notation
    }



    fun dummyCodeReset(){
        distanceSinceCodesCleared = 222222
        timeSinceTroubleCodesCleared = 222222
        warmUpCyclesSinceCodesCleared = 2222.0
        timeRunWithMILOn = 222222
    }

    fun isManualResetSuspected(): Int {


        // Readiness monitor check (most important)
        return if(warmUpCyclesSinceCodesClearedstr == "-" || distanceSinceCodesClearedstr == "-"){
            -1
        }else if (distanceSinceCodesCleared >= 100 && warmUpCyclesSinceCodesCleared > 25){
            1
        }else{
            0
        }


        // Uncommented logic for further suspicion checks (if needed)

        /*
        // Suspicion level checks
        var suspicionLevel = 0

        // Distance and warm-up cycle fail check
        if (fail >= 5 && distanceSinceCodesClearedCalc > 50) {
            suspicionLevel += 1  // Suspected
        }

        // Warm-up cycle vs. distance check
        val expectedWarmupCycles = (distanceSinceCodesClearedCalc / 50) * 10
        if (warmUpCyclesSinceCodesCleared < expectedWarmupCycles * 0.8) { // Allow for some tolerance
            suspicionLevel += 1  // Suspected
        }

        // Freeze frame check (if supported)
        if (freezeFrameDataExists() && !freezeFrameDataMatchesCurrentDTCs()) {
            suspicionLevel += 1  // Suspected
        }

        // Time since trouble codes cleared check
        if (timeSinceTroubleCodesCleared < 1440) { // Less than 24 hours (in minutes)
            suspicionLevel += 1
        }

        // Time run with MIL on check
        if (timeRunWithMILOn > 0) {
            suspicionLevel += 1  // MIL has been on since the last code clear
        }

        // MIL check and DTC availability
        if (boolIsMilcheckOn && isCurrentDTCAvail) {
            suspicionLevel += 1
        }

        return suspicionLevel > 2  // Require 2 or more suspicious factors
        */
    }


}
public  data class  EmissionRediness (
    var name:String = "",
    var available:Boolean = false,
    var complete:Boolean = false,
    var description:String = "",
    var finalstatus:String = "",

    )