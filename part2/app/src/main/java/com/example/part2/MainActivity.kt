package com.example.part2

import android.app.Application
import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter


@Entity
data class OrientationData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
    val timestamp: Long = System.currentTimeMillis(),
)

@Dao
interface OrientationDao {
    @Insert
    suspend fun insert(orientationData: OrientationData)
    @Query("SELECT * FROM OrientationData ORDER BY timestamp DESC LIMIT 100")
    suspend fun getAll(): List<OrientationData>
    @Query("SELECT * FROM OrientationData ORDER BY timestamp DESC")
    suspend fun getAllRecords(): List<OrientationData>
}

@Database(entities = [OrientationData::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun orientationDao(): OrientationDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "orientation-database"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrientationDataDisplay()
        }
    }
}

class OrientationViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val sensorManager: SensorManager = application.getSystemService(SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var lastAccelerometer = FloatArray(3)
    private var lastMagnetometer = FloatArray(3)
    private var isAccelerometerSet = false
    private var isMagnetometerSet = false
    var orientationAngles = mutableStateOf(floatArrayOf(0f, 0f, 0f))
    private val db = AppDatabase.getDatabase(application)

    private val _orientationData = MutableLiveData<List<OrientationData>>()
    val orientationData: LiveData<List<OrientationData>> = _orientationData

    private val updateJob = Job()
    private val updateScope = CoroutineScope(Dispatchers.Main + updateJob)
    private val _allOrientationData = MutableLiveData<List<OrientationData>>()
    val allOrientationData: LiveData<List<OrientationData>> = _allOrientationData

    fun fetchAllOrientationData() {
        viewModelScope.launch(Dispatchers.IO) {
            val data = db.orientationDao().getAllRecords()
            _allOrientationData.postValue(data)
        }
    }

    init {
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        fetchOrientationData()
        startPeriodicSave()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(it.values, 0, lastAccelerometer, 0, it.values.size)
                isAccelerometerSet = true
            } else if (it.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(it.values, 0, lastMagnetometer, 0, it.values.size)
                isMagnetometerSet = true
            }
            if (isAccelerometerSet && isMagnetometerSet) {
                updateOrientationAngles()
            }
        }
    }

    private fun updateOrientationAngles() {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer)
        val adjustedRotationMatrix = FloatArray(9)
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, adjustedRotationMatrix)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(adjustedRotationMatrix, orientation)
        orientationAngles.value = floatArrayOf(
            Math.toDegrees(orientation[0].toDouble()).toFloat(),
            Math.toDegrees(orientation[1].toDouble()).toFloat(),
            Math.toDegrees(orientation[2].toDouble()).toFloat()
        )
    }

    private fun startPeriodicSave() {
        updateScope.launch {
            while (isActive) {
                delay(1000)
                if (isAccelerometerSet && isMagnetometerSet) {
                    db.orientationDao().insert(
                        OrientationData(
                            roll = orientationAngles.value[2],
                            pitch = orientationAngles.value[1],
                            yaw = orientationAngles.value[0]
                        )
                    )
                    fetchOrientationData()
                }
            }
        }
    }

    private fun fetchOrientationData() {
        updateScope.launch(Dispatchers.IO) {
            _orientationData.postValue(db.orientationDao().getAll())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        super.onCleared()
        updateJob.cancel()
        sensorManager.unregisterListener(this)
    }
}

@Composable
fun OrientationDataDisplay(orientationViewModel: OrientationViewModel = viewModel()) {
    val context = LocalContext.current
    val orientationData by orientationViewModel.orientationData.observeAsState(initial = emptyList())
    val allOrientationData by orientationViewModel.allOrientationData.observeAsState()
    var showGraph by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Orientation App",
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Roll: ${orientationViewModel.orientationAngles.value[2]}°",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
                )
                Text(
                    "Pitch: ${orientationViewModel.orientationAngles.value[1]}°",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
                )
                Text(
                    "Yaw: ${orientationViewModel.orientationAngles.value[0]}°",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
                )
                Button(
                    onClick = { showGraph = !showGraph },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(if (showGraph) "Hide Graph" else "Show Graph")
                }
                // Button for saving the data to CSV
                Button(
                    onClick = { orientationViewModel.fetchAllOrientationData() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Load and Save All Data to CSV")
                }
                if (showGraph) {
                    SensorLineChart(orientationData)
                }
            }
        }
    }
    LaunchedEffect(allOrientationData) {
        allOrientationData?.let {
            saveDataToCSV(it, context)
        }
    }
}

fun saveDataToCSV(data: List<OrientationData>, context: Context) {
    val file = File(context.getExternalFilesDir(null), "OrientationData.csv")
    try {
        FileWriter(file).use { fw ->
            fw.append("ID,Roll,Pitch,Yaw,Timestamp\n")
            data.forEach {
                fw.append("${it.id},${it.roll},${it.pitch},${it.yaw},${it.timestamp}\n")
            }
        }
        Log.d("CSVWrite", "File written successfully at ${file.absolutePath}")
    } catch (e: Exception) {
        Log.e("CSVWriteError", "Error writing CSV file: ${e.localizedMessage}")
    }
}

// Graph display function
@Composable
fun SensorLineChart(orientationData: List<OrientationData>) {
    val entriesRoll = orientationData.mapIndexed { index, data -> Entry(index.toFloat(), data.roll) }
    val entriesPitch = orientationData.mapIndexed { index, data -> Entry(index.toFloat(), data.pitch) }
    val entriesYaw = orientationData.mapIndexed { index, data -> Entry(index.toFloat(), data.yaw) }

    AndroidView(
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                setPinchZoom(true)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        update = { chart ->
            val lineDataSetRoll = LineDataSet(entriesRoll, "Roll").apply {
                color = Color.RED
                valueTextColor = Color.WHITE
            }
            val lineDataSetPitch = LineDataSet(entriesPitch, "Pitch").apply {
                color = Color.GREEN
                valueTextColor = Color.WHITE
            }
            val lineDataSetYaw = LineDataSet(entriesYaw, "Yaw").apply {
                color = Color.BLUE
                valueTextColor = Color.WHITE
            }

            chart.data = LineData(lineDataSetRoll, lineDataSetPitch, lineDataSetYaw)
            chart.notifyDataSetChanged()
            chart.invalidate()
        }
    )
}


