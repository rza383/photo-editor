package org.hyperskill.photoeditor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.blue
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hyperskill.photoeditor.databinding.ActivityMainBinding
import kotlin.math.pow

private const val MEDIA_REQUEST_CODE = 0
class MainActivity : AppCompatActivity() {

    private lateinit var currentImage: ImageView
    private lateinit var galleryButton: Button
    private lateinit var saveButton: Button
    private lateinit var brightnessSlider: Slider
    private lateinit var contrastSlider: Slider
    private lateinit var saturationSlider: Slider
    private lateinit var gammaSlider: Slider
    private lateinit var binding: ActivityMainBinding
    private lateinit var defaultBitmap: Bitmap
    private var lastJob: Job? = null

    private val activityResultLauncher =
        registerForActivityResult(StartActivityForResult()) {result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val photoUri = result.data?.data ?: return@registerForActivityResult
                currentImage.setImageURI(photoUri)
                loadDefaultBitMap()
            }
        }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindViews()
        val listOfSliders = listOf(brightnessSlider, contrastSlider, saturationSlider, gammaSlider)
        //do not change this line
        currentImage.setImageBitmap(createBitmap()).also { loadDefaultBitMap() }

        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)

        galleryButton.setOnClickListener {
            activityResultLauncher.launch(intent)
        }

        saveButton.setOnClickListener {
            requestPermissionToSaveMedia (::saveMedia)
        }
        listOfSliders.forEach { slider: Slider ->
            slider.addOnChangeListener{_,_,_ ->
                applyFilters()
            }

        }
    }

    private fun bindViews() {
        binding.apply {
            currentImage = ivPhoto
            galleryButton = btnGallery
            brightnessSlider = slBrightness
            contrastSlider = slContrast
            saveButton = btnSave
            saturationSlider = slSaturation
            gammaSlider = slGamma
        }

    }

    // do not change this function
    private fun createBitmap(): Bitmap {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height)
        // get pixel array from source

        var R: Int
        var G: Int
        var B: Int
        var index: Int

        for (y in 0 until height) {
            for (x in 0 until width) {
                // get current index in 2D-matrix
                index = y * width + x
                // get color
                R = x % 100 + 40
                G = y % 100 + 80
                B = (x+y) % 100 + 120

                pixels[index] = Color.rgb(R,G,B)

            }
        }
        // output bitmap
        val bitmapOut = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmapOut.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmapOut
    }
    private fun loadDefaultBitMap(){
        defaultBitmap = currentImage.drawable.toBitmap()
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun applyFilters(){
        lastJob?.cancel()
        lastJob = CoroutineScope(Dispatchers.Default).launch {
            val bitmap = defaultBitmap.copy(
                Bitmap.Config.ARGB_8888,
                true)
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            defaultBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val brightenedPixelsDeferred = async { adjustBrightness(pixels) }
            val brightenedPixels = brightenedPixelsDeferred.await()
            bitmap.update(brightenedPixels)
            val contrastedPixelsDeferred = async { adjustContrast(width, height, brightenedPixels) }
            val contrastedPixels = contrastedPixelsDeferred.await()
            bitmap.update(contrastedPixels)
            val saturatedPixelsDeferred = async { adjustSaturation(contrastedPixels) }
            val saturatedPixels = saturatedPixelsDeferred.await()
            bitmap.update(saturatedPixels)
            val gammaPixelsDeferred = async { adjustGamma(contrastedPixels) }
            val gammaPixels = gammaPixelsDeferred.await()
            bitmap.update(gammaPixels)
            ensureActive()
            withContext(Dispatchers.Main){ currentImage.setImageBitmap(bitmap)}
        }
    }
    private fun Bitmap.update(pixels: IntArray) = setPixels(pixels, 0, width,0,0,width, height)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun adjustBrightness(pixels: IntArray): IntArray {
        val valueBrightness = brightnessSlider.value.toInt()
        for(i in pixels.indices){
            var (red, green, blue) = listOf(
                    pixels[i].red + valueBrightness,
                    pixels[i].green + valueBrightness,
                    pixels[i].blue + valueBrightness
                )
                red = red.checkRange()
                green = green.checkRange()
                blue = blue.checkRange()
            pixels[i] = Color.rgb(red, green, blue)
        }
        return pixels
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun adjustContrast(width: Int,
                               height: Int,
                               pixels: IntArray): IntArray {
        val valueContrast = contrastSlider.value.toInt()
        val avgBrightness = calculateAvgBrightness(width, height, pixels)
        val alpha: Double = (255.0 + valueContrast) / (255 - valueContrast)
        for(i in pixels.indices){
            var (red, green, blue) = listOf(
                pixels[i].red.calculateContrast(alpha, avgBrightness),
                pixels[i].green.calculateContrast(alpha, avgBrightness),
                pixels[i].blue.calculateContrast(alpha, avgBrightness)
            )
            red = red.checkRange()
            green = green.checkRange()
            blue = blue.checkRange()
            pixels[i] = Color.rgb(red, green, blue)
        }
        return pixels
    }
    private fun adjustSaturation(pixels: IntArray): IntArray{
        val valueSaturation = saturationSlider.value.toInt()
        val alpha = (255.0 + valueSaturation) / (255.0 - valueSaturation)
        for(i in pixels.indices){
            val rgbAvg = (pixels[i].red + pixels[i].green + pixels[i].blue) / 3
            var (red, green, blue) = listOf(
                pixels[i].red.calculateSaturation(alpha, rgbAvg),
                pixels[i].green.calculateSaturation(alpha, rgbAvg),
                pixels[i].blue.calculateSaturation(alpha, rgbAvg)
            )
            red = red.checkRange()
            green = green.checkRange()
            blue = blue.checkRange()
            pixels[i] = Color.rgb(red, green, blue)
        }
        return pixels
    }

    private fun adjustGamma(pixels: IntArray): IntArray{
        val valueGamma = gammaSlider.value.toDouble()
        for(i in pixels.indices){
            var (red, green, blue) = listOf(
                pixels[i].red.calculateGamma(valueGamma),
                pixels[i].green.calculateGamma(valueGamma),
                pixels[i].blue.calculateGamma(valueGamma)
            )
            red = red.checkRange()
            green = green.checkRange()
            blue = blue.checkRange()
            pixels[i] = Color.rgb(red, green, blue)
        }
        return pixels
    }

    private fun <T: Number> T.checkRange(): T{
        return if ( this.toInt() > 255)
            255 as T
        else if( this.toInt() < 0 )
            0 as T
        else this
    }
    private fun requestPermissionToSaveMedia(saveMedia: () -> Unit) {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED ->  saveMedia()

            ActivityCompat
                .shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) -> {
                AlertDialog.Builder(this)
                    .setTitle("Permission required")
                    .setMessage("This app needs permission to access this feature.")
                    .setPositiveButton("Grant") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            MEDIA_REQUEST_CODE,
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    MEDIA_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when(requestCode){
            MEDIA_REQUEST_CODE -> if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                 saveMedia()
            } else {
                // no permission, block access to this feature
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        }
    }
    private  fun saveMedia(){
        val bitmap = currentImage.drawable.toBitmap()
        val values = ContentValues()
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        values.put(MediaStore.Images.Media.WIDTH, bitmap.width)
        values.put(MediaStore.Images.Media.HEIGHT, bitmap.height)
        val uri = this@MainActivity.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return
        contentResolver.openOutputStream(uri).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
    }

    private fun calculateAvgBrightness(width: Int,
                                       height: Int,
                                       pixels: IntArray): Int{
        return (pixels.fold(0L){ acc, pixel ->
            acc + pixel.red + pixel.green + pixel.blue
        } / (width * height * 3)).toInt()
    }

    private fun Int.calculateContrast(alpha: Double, avgBrightness: Int): Int{
        return (alpha * (this - avgBrightness) + avgBrightness).toInt()
    }

    private fun Int.calculateSaturation(alpha: Double, avgRgb: Int): Int{
        return (alpha * (this - avgRgb) + avgRgb).toInt()
    }
    private fun Int.calculateGamma(gamma: Double): Int{
        return (255 * (this / 255.0).pow(gamma)).toInt()
    }
}