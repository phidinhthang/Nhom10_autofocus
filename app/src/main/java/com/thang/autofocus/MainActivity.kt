package com.thang.autofocus

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.scale
import java.util.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    lateinit var capReq: CaptureRequest.Builder
    lateinit var handler: Handler
    lateinit var handlerThread: HandlerThread
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    lateinit var laplacianText: TextView
    lateinit var histEqualCheckbox: CheckBox
    lateinit var grayWorldCheckBox: CheckBox
    lateinit var lightBulkCheckbox: CheckBox
    lateinit var blueSkyCheckBox: CheckBox
    lateinit var previewReader: ImageReader
    lateinit var button: Button
    lateinit var cameraCaptureSession: CameraCaptureSession
    lateinit var cameraDevice: CameraDevice
    private var cameraId: String = ""
    private var screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private var screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private var focusStage = "not-focus"
    private var focalLength = 5.23F
    private var measureList = mutableListOf<Float>()
    private var distanceList = mutableListOf<Float>()
    private var lastTime = System.currentTimeMillis()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        get_permissions()

        previewReader = ImageReader.newInstance(screenHeight.div(4), screenWidth.div(4), ImageFormat.JPEG, 1)
        textureView = findViewById(R.id.textureView)
        button = findViewById(R.id.capture)
        histEqualCheckbox = findViewById(R.id.histEqualCheckbox)
        grayWorldCheckBox = findViewById(R.id.grayWorldCheckbox)
        lightBulkCheckbox = findViewById(R.id.lightBulkCheckbox)
        blueSkyCheckBox = findViewById(R.id.blueSkyCheckbox)
        laplacianText = findViewById(R.id.laplacian_text)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {}

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {}
        }

        button.setOnClickListener { changeFocus() }
        previewReader.setOnImageAvailableListener({
            Log.e("screen", "width " + screenWidth.toString() + " height " + screenHeight.toString())

            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            //val focalLengths: FloatArray? = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

             val image = it.acquireLatestImage()
            if (image != null) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val bytes = ByteArray(buffer.capacity())
                buffer.get(bytes)
                var preview = BitmapFactory.decodeByteArray(bytes, 0, buffer.capacity())

                image.close()

                if (preview != null) {
                    preview = preview.copy(preview.config, true)
                    val bitmapArr = IntArray(preview.width*preview.height)
                    val pixels = IntArray(preview.width*preview.height)
                    preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)

                    val imageHeight = preview.width
                    val imageWidth = preview.height
                    for (i in 0 until imageHeight) {
                        for (j in 0 until imageWidth) {
                            // i is index of height, j is index of width,
                            bitmapArr[i * imageWidth + j] = pixels[(imageWidth - j - 1) * imageHeight + i]
                        }
                    }

                    val laplacian_res = laplacian(bitmapArr, imageHeight, imageWidth)

                    if (histEqualCheckbox.isChecked) {
                        val redCount = MutableList(256) {0}
                        val greenCount = MutableList(256) {0}
                        val blueCount = MutableList(256) {0}
                        val redRemap = MutableList(256) {0}
                        val greenRemap = MutableList(256) {0}
                        val blueRemap = MutableList(256) {0}
                        var redAccProb = 0F
                        var greenAccProb = 0F
                        var blueAccProb = 0F

                        for (i in 0 until imageHeight) {
                            for (j in 0 until imageWidth) {
                                val pixel = bitmapArr[i * imageWidth + j]
                                redCount[pixel.and(0xFF)] += 1
                                greenCount[(pixel.shr(8)).and(0xFF)] += 1
                                blueCount[(pixel.shr(16)).and(0xFF)] += 1
                            }
                        }

                        for (i in 0..255) {
                            // red channel
                            redAccProb += redCount[i].toFloat() / (imageHeight*imageWidth).toFloat()
                            redRemap[i] = (redAccProb * 255).roundToInt()

                            // green channel
                            greenAccProb += greenCount[i].toFloat() / (imageHeight*imageWidth).toFloat()
                            greenRemap[i] = (greenAccProb * 255).roundToInt()

                            // blue channel
                            blueAccProb += blueCount[i].toFloat() / (imageHeight*imageWidth).toFloat()
                            blueRemap[i] = (blueAccProb * 255).roundToInt()
                        }

                        for (i in 0 until imageHeight) {
                            for (j in 0 until imageWidth) {
                                val oldPixel = bitmapArr[i * imageWidth + j]
                                val oldRed = oldPixel.and(0xFF)
                                val oldGreen = (oldPixel.shr(8)).and(0xFF)
                                val oldBlue = (oldPixel.shr(16)).and(0xFF)
                                val newRed = redRemap[oldRed]
                                val newGreen = greenRemap[oldGreen]
                                val newBlue = blueRemap[oldBlue]

                                bitmapArr[i * imageWidth + j] = ((0xFF).shl(24)).or(newBlue.shl(16)).or(newGreen.shl(8)).or(newRed)
                            }
                        }
                    } else if (grayWorldCheckBox.isChecked || blueSkyCheckBox.isChecked || lightBulkCheckbox.isChecked) {
                        var averageRed = 0F
                        var averageGreen = 0F
                        var averageBlue = 0F
                        var redWeight = 1F
                        var greenWeight = 1F
                        var blueWeight = 1F
                        if (blueSkyCheckBox.isChecked) {
                            redWeight = 1F
                            greenWeight = 0.8588F
                            blueWeight = 0.8F
                        } else if (lightBulkCheckbox.isChecked) {
                            redWeight = 0.5372F
                            greenWeight = 0.7686F
                            blueWeight = 1F
                        }

                        for (i in 0 until imageHeight) {
                            for (j in 0 until imageWidth) {
                                val pixel = bitmapArr[i * imageWidth + j]
                                averageRed += pixel.and(0xFF)
                                averageGreen += (pixel.shr(8)).and(0xFF)
                                averageBlue += (pixel.shr(16)).and(0xFF)
                            }
                        }

                        averageRed /= (imageWidth * imageHeight)
                        averageGreen /= (imageWidth * imageHeight)
                        averageBlue /= (imageWidth * imageHeight)
                        val gray = (averageRed + averageGreen + averageBlue) / 3

                        for (i in 0 until imageHeight) {
                            for (j in 0 until imageWidth) {
                                val oldPixel = bitmapArr[i * imageWidth + j]
                                val oldRed = oldPixel.and(0xFF)
                                val oldGreen = (oldPixel.shr(8)).and(0xFF)
                                val oldBlue = (oldPixel.shr(16)).and(0xFF)
                                var newRed = (gray * redWeight / averageRed * oldRed).toInt()
                                var newGreen = (gray * greenWeight / averageGreen * oldGreen).toInt()
                                var newBlue = (gray * blueWeight / averageBlue * oldBlue).toInt()

                                if (newRed < 0) newRed = 0
                                if (newRed > 255) newRed = 255
                                if (newGreen < 0) newGreen = 0
                                if (newGreen > 255) newGreen = 255
                                if (newBlue < 0) newBlue = 0
                                if (newBlue > 255) newBlue = 255

                                bitmapArr[i * imageWidth + j] = ((0xFF).shl(24)).or(newBlue.shl(16)).or(newGreen.shl(8)).or(newRed)
                            }
                        }
                    }

                    if (focusStage != "not-focus") {
                        if (focusStage == "focus-1") {
                            measureList.clear()
                            distanceList.clear()
                            capReq.set(CaptureRequest.LENS_FOCUS_DISTANCE, 10f) // 10cm
                            cameraCaptureSession.setRepeatingRequest(capReq.build(), null, null)
                            focusStage = "focus-2"
                            lastTime = System.currentTimeMillis()
                        } else if (focusStage == "focus-2" && ((System.currentTimeMillis() - lastTime) > 1200)) {
                            measureList.add(laplacian_res)
                            distanceList.add((100F * focalLength) / (100F - focalLength))
                            capReq.set(CaptureRequest.LENS_FOCUS_DISTANCE, 4f) // 25cm
                            cameraCaptureSession.setRepeatingRequest(capReq.build(), null, null)
                            focusStage = "focus-3"
                            lastTime = System.currentTimeMillis()
                        } else if (focusStage == "focus-3" && ((System.currentTimeMillis() - lastTime) > 1200)) {
                            measureList.add(laplacian_res)
                            distanceList.add((250 * focalLength) / (250 - focalLength))
                            capReq.set(CaptureRequest.LENS_FOCUS_DISTANCE, 2f) // 50cm
                            cameraCaptureSession.setRepeatingRequest(capReq.build(), null, null)
                            focusStage = "focus-4"
                            lastTime = System.currentTimeMillis()
                        } else if (focusStage == "focus-4" && ((System.currentTimeMillis() - lastTime) > 1200)) {
                            measureList.add(laplacian_res)
                            distanceList.add((500 * focalLength) / (500 - focalLength))
                            capReq.set(CaptureRequest.LENS_FOCUS_DISTANCE, 1f) // 1m
                            cameraCaptureSession.setRepeatingRequest(capReq.build(), null, null)
                            focusStage = "focus-5"
                            lastTime = System.currentTimeMillis()
                        } else if (focusStage == "focus-5" && ((System.currentTimeMillis() - lastTime) > 1200)) {
                            measureList.add(laplacian_res)
                            distanceList.add((1000 * focalLength) / (1000 - focalLength))

                            Log.e("measureList", measureList.toString() + " " + distanceList.toString())
                            val focusDiopArray = arrayListOf(10f, 4f, 2f, 1f)
                            val maxIndex = measureList.indexOf(Collections.max(measureList))
                            val bestDiop = focusDiopArray[maxIndex]

                            capReq.set(CaptureRequest.LENS_FOCUS_DISTANCE, bestDiop)
                            cameraCaptureSession.setRepeatingRequest(capReq.build(), null, null)

                            focusStage = "not-focus"
                        }
                    }

                    val canvas = textureView.lockCanvas()
                    if (canvas != null) {
                        val bitmapConf = Bitmap.Config.ARGB_8888
                        val bitmap = Bitmap.createBitmap(preview.height, preview.width, bitmapConf)
                        bitmap.setPixels(bitmapArr, 0, preview.height, 0, 0, preview.height, preview.width)

                        val paint = Paint()
                        canvas.drawBitmap(bitmap.scale(screenWidth, screenHeight), 0F, 0F, paint)
                        textureView.unlockCanvasAndPost(canvas)
                    }
                }
            }
        }, handler)
    }

    private fun changeFocus() {
        Log.e("button", "clicked")
        focusStage = "focus-1"
    }

    private fun offsetToRow(offset: Int, imageWidth: Int): Int {
        return offset.div(imageWidth)
    }

    private fun offsetToCol(offset: Int, imageWidth: Int): Int {
        return offset - imageWidth * offsetToRow(offset, imageWidth)
    }

    private fun coordsToOffset(y: Int, x: Int): Int {
        return y * 240 + x
    }

    private fun pixelAverage(data: Int): Float {
        val red = (data.and(0xFF))
        val green = (data.shr(8).and(0xFF))
        val blue = (data.shr(16).and(0xFF))

        return (red + green + blue).toFloat().div(3)
    }

    private fun laplacian(data: IntArray, imageHeight: Int, imageWidth: Int): Float {
        //var res = FloatArray(data.size) { i -> 0F }

        val kernel = arrayOf(0, 1, 0, 1, -4, 1, 0, 1, 0)
        var mean = 0F
        var meanOfSquare = 0F
        for (i in data.indices) {
            val row = offsetToRow(i, imageWidth)
            val col = offsetToCol(i, imageWidth)
            if (row > 0 && row < imageHeight - 1 && col > 0 && col < imageWidth - 1) {
                var value = 0F
                for (j in -1..1) {
                    for (k in -1..1) {
                        value += pixelAverage(data[coordsToOffset(row+j, col+k)]) * (kernel[(j+1)*3+k+1].toFloat())
                    }
                }
                mean += value
                meanOfSquare += value * value
            }
        }
        mean /= data.size.toFloat()
        meanOfSquare /= data.size.toFloat()

        return meanOfSquare - mean * mean
    }

    @SuppressLint("MissingPermission")
    fun open_camera() {
        cameraId = cameraManager.cameraIdList[0]
        cameraManager.openCamera(cameraId, object: CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0

                capReq = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

                val surface = previewReader.surface
                capReq.addTarget(surface)

                capReq.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                capReq.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)


                cameraDevice.createCaptureSession(listOf(surface), object: CameraCaptureSession.StateCallback() {
                    override fun onConfigured(p0: CameraCaptureSession) {
                        cameraCaptureSession = p0
                        cameraCaptureSession.setRepeatingRequest(capReq.build(), null, null)
                    }

                    override fun onConfigureFailed(p0: CameraCaptureSession) {}
                }, handler)
            }

            override fun onDisconnected(p0: CameraDevice) {}
            override fun onError(p0: CameraDevice, p1: Int) {}
        }, handler)
    }

    fun get_permissions() {
        val permissionLst = mutableListOf<String>()

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissionLst.add(android.Manifest.permission.CAMERA)
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissionLst.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissionLst.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permissionLst.size > 0) {
            requestPermissions(permissionLst.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        grantResults.forEach {
            if (it != PackageManager.PERMISSION_GRANTED) {
                get_permissions()
            }
        }
    }

    fun onHistEqualCheckboxClicked(view: View) {
        if (view is CheckBox) {
            val checked = view.isChecked

            if (checked) {
                histEqualCheckbox.isChecked = (view.id == R.id.histEqualCheckbox)
                grayWorldCheckBox.isChecked = (view.id == R.id.grayWorldCheckbox)
                lightBulkCheckbox.isChecked = (view.id == R.id.lightBulkCheckbox)
                blueSkyCheckBox.isChecked = (view.id == R.id.blueSkyCheckbox)
            }
        }
    }
}