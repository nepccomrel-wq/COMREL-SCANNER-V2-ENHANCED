package com.comrel.scanner

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var homeView: View
    private lateinit var cameraView: View
    private lateinit var reviewView: View
    private lateinit var libraryView: View
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var cameraDoneButton: Button
    private lateinit var addMoreButton: Button
    private lateinit var savePdfButton: Button
    private lateinit var discardButton: Button
    private lateinit var pageCounter: TextView
    private lateinit var reviewCount: TextView
    private lateinit var thumbContainer: LinearLayout
    private lateinit var libraryContainer: LinearLayout
    private lateinit var modeOriginalButton: Button
    private lateinit var modeBwButton: Button
    private lateinit var modeGrayButton: Button
    private lateinit var scanHint: TextView
    private lateinit var modeIndicator: TextView
    private lateinit var documentEdgeOverlay: DocumentEdgeOverlay
    private lateinit var captureResultOverlay: ImageView

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var openCvReady = false
    private var processing = false
    private var processingMode = ScanMode.ORIGINAL
    private var imageAnalysis: ImageAnalysis? = null
    private var lastAnalysisTime = 0L

    private val pages = mutableListOf<File>()
    private val libraryDir by lazy { File(filesDir, "documents").apply { mkdirs() } }

    private enum class ScanMode { ORIGINAL, BW, GRAYSCALE }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showCamera() else Toast.makeText(
                this, "Camera permission is required to scan documents.", Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        cameraExecutor = Executors.newSingleThreadExecutor()
        openCvReady = OpenCVLoader.initLocal()

        findViewById<Button>(R.id.startScanButton).setOnClickListener { startScanRequest() }
        findViewById<Button>(R.id.libraryButton).setOnClickListener { showLibrary() }
        findViewById<Button>(R.id.libraryBackButton).setOnClickListener { showHome() }
        captureButton.setOnClickListener { capturePage() }
        cameraDoneButton.setOnClickListener {
            if (pages.isEmpty()) toast("Scan at least one page first.") else showReview()
        }
        addMoreButton.setOnClickListener { showCamera() }
        savePdfButton.setOnClickListener { askPdfNameAndSave() }
        discardButton.setOnClickListener { discardBatch() }

        modeOriginalButton.setOnClickListener { setProcessingMode(ScanMode.ORIGINAL) }
        modeBwButton.setOnClickListener { setProcessingMode(ScanMode.BW) }
        modeGrayButton.setOnClickListener { setProcessingMode(ScanMode.GRAYSCALE) }

        setProcessingMode(ScanMode.ORIGINAL)
        updatePageCounters()
        renderLibrary()
    }

    private fun bindViews() {
        homeView = findViewById(R.id.homeView)
        cameraView = findViewById(R.id.cameraView)
        reviewView = findViewById(R.id.reviewView)
        libraryView = findViewById(R.id.libraryView)
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        cameraDoneButton = findViewById(R.id.cameraDoneButton)
        addMoreButton = findViewById(R.id.addMoreButton)
        savePdfButton = findViewById(R.id.savePdfButton)
        discardButton = findViewById(R.id.discardButton)
        pageCounter = findViewById(R.id.pageCounter)
        reviewCount = findViewById(R.id.reviewCount)
        thumbContainer = findViewById(R.id.thumbContainer)
        libraryContainer = findViewById(R.id.libraryContainer)
        modeOriginalButton = findViewById(R.id.modeOriginalButton)
        modeBwButton = findViewById(R.id.modeBwButton)
        modeGrayButton = findViewById(R.id.modeGrayButton)
        scanHint = findViewById(R.id.scanHint)
        modeIndicator = findViewById(R.id.modeIndicator)
        documentEdgeOverlay = findViewById(R.id.documentEdgeOverlay)
        captureResultOverlay = findViewById(R.id.captureResultOverlay)
    }

    private fun startScanRequest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            pages.clear()
            showCamera()
        } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showCamera() {
        homeView.visibility = View.GONE
        libraryView.visibility = View.GONE
        reviewView.visibility = View.GONE
        cameraView.visibility = View.VISIBLE
        captureResultOverlay.visibility = View.GONE
        captureResultOverlay.alpha = 1f
        documentEdgeOverlay.clearCorners()
        scanHint.text = "Move the camera until the document edges are detected"
        updateModeIndicator()
        startCamera()
        updatePageCounters()
    }

    private fun showReview() {
        stopCameraAnalysis()
        cameraView.visibility = View.GONE
        homeView.visibility = View.GONE
        libraryView.visibility = View.GONE
        reviewView.visibility = View.VISIBLE
        renderThumbnails()
        updatePageCounters()
    }

    private fun showLibrary() {
        stopCameraAnalysis()
        cameraView.visibility = View.GONE
        reviewView.visibility = View.GONE
        homeView.visibility = View.GONE
        libraryView.visibility = View.VISIBLE
        renderLibrary()
    }

    private fun showHome() {
        stopCameraAnalysis()
        cameraView.visibility = View.GONE
        reviewView.visibility = View.GONE
        libraryView.visibility = View.GONE
        homeView.visibility = View.VISIBLE
    }

    private fun stopCameraAnalysis() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        documentEdgeOverlay.clearCorners()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            val preview = Preview.Builder().setTargetRotation(targetRotation).build().also { it.surfaceProvider = previewView.surfaceProvider }
            imageCapture = ImageCapture.Builder().setTargetRotation(targetRotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(98)
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(targetRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { image -> analyzeLiveEdges(image) }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalysis)
            } catch (e: Exception) {
                toast("Unable to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeLiveEdges(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < 120L) {
            image.close()
            return
        }
        lastAnalysisTime = now

        try {
            var bitmap = imageProxyToBitmap(image)
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }

            val points = detectDocumentCorners(bitmap)
            val width = bitmap.width
            val height = bitmap.height
            bitmap.recycle()

            runOnUiThread {
                if (cameraView.visibility != View.VISIBLE || processing) return@runOnUiThread
                if (points.size == 4) {
                    documentEdgeOverlay.setCorners(mapImagePointsToPreview(points, width, height))
                    scanHint.text = "Document detected • Ready to scan"
                } else {
                    documentEdgeOverlay.clearCorners()
                    scanHint.text = "Move the camera until the document edges are detected"
                }
            }
        } catch (_: Exception) {
            // Live detection is only a visual aid; capture processing remains independent.
        } finally {
            image.close()
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun detectDocumentCorners(bitmap: Bitmap): Array<Point> {
        if (!openCvReady) return emptyArray()
        val maxSide = 900
        val scale = min(1.0, maxSide.toDouble() / max(bitmap.width, bitmap.height).toDouble()).toFloat()
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true) else bitmap
        val src = Mat()
        val gray = Mat()
        val edges = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val hierarchy = Mat()
        try {
            Utils.bitmapToMat(small, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 45.0, 140.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val imageArea = src.width().toDouble() * src.height().toDouble()
            var best: Array<Point>? = null
            var bestArea = 0.0

            for (contour in contours) {
                val area = abs(Imgproc.contourArea(contour))
                if (area < imageArea * 0.18 || area <= bestArea) {
                    contour.release()
                    continue
                }
                val curve = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(curve, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, 0.025 * peri, true)
                if (approx.total() == 4L) {
                    val poly = approx.toArray()
                    val convexMat = MatOfPoint(*poly)
                    if (Imgproc.isContourConvex(convexMat)) {
                        best = orderPoints(poly)
                        bestArea = area
                    }
                    convexMat.release()
                }
                curve.release()
                approx.release()
                contour.release()
            }

            if (best == null) return emptyArray()
            return best.map { Point(it.x / scale, it.y / scale) }.toTypedArray()
        } finally {
            if (small !== bitmap) small.recycle()
            src.release(); gray.release(); edges.release(); kernel.release(); hierarchy.release()
        }
    }

    private fun capturePage() {
        val capture = imageCapture ?: return
        if (processing) return
        processing = true
        captureButton.isEnabled = false
        scanHint.text = "Processing document…"

        val raw = File(cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(raw).build()

        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                processing = false
                captureButton.isEnabled = true
                scanHint.text = "Move the camera until the document edges are detected"
                toast("Capture failed: ${exception.message}")
            }

            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                cameraExecutor.execute {
                    try {
                        val corrected = processCapturedImage(raw)
                        raw.delete()
                        runOnUiThread {
                            pages.add(corrected)
                            processing = false
                            captureButton.isEnabled = true
                            documentEdgeOverlay.clearCorners()
                            showCaptureProcessingAnimation(corrected)
                            scanHint.text = "${modeLabel()} applied • Page ${pages.size} captured"
                            updatePageCounters()
                            toast("Page ${pages.size} captured")
                        }
                    } catch (e: Exception) {
                        raw.delete()
                        runOnUiThread {
                            processing = false
                            captureButton.isEnabled = true
                            scanHint.text = "Move the camera until the document edges are detected"
                            toast("Scan processing failed: ${e.message}")
                        }
                    }
                }
            }
        })
    }

    private fun modeLabel(): String = when (processingMode) {
        ScanMode.ORIGINAL -> "Original mode"
        ScanMode.GRAYSCALE -> "Gray mode"
        ScanMode.BW -> "B&W mode"
    }

    private fun showCaptureProcessingAnimation(file: File) {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        captureResultOverlay.setImageBitmap(bmp)
        captureResultOverlay.visibility = View.VISIBLE
        captureResultOverlay.alpha = 0f
        captureResultOverlay.animate()
            .alpha(1f)
            .setDuration(180)
            .withEndAction {
                captureResultOverlay.postDelayed({
                    captureResultOverlay.animate().alpha(0f).setDuration(280).withEndAction {
                        captureResultOverlay.visibility = View.GONE
                        captureResultOverlay.setImageDrawable(null)
                    }.start()
                }, 260)
            }.start()
    }

    private fun processCapturedImage(raw: File): File {
        var bitmap = decodeCorrectlyOriented(raw)
        val detected = if (openCvReady) detectAndWarp(bitmap) else bitmap
        if (detected !== bitmap) bitmap.recycle()
        bitmap = detected

        val processed = when (processingMode) {
            ScanMode.ORIGINAL -> bitmap
            ScanMode.GRAYSCALE -> toGrayscale(bitmap)
            ScanMode.BW -> toBlackWhite(bitmap)
        }
        if (processed !== bitmap) bitmap.recycle()

        val output = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { out ->
            processed.compress(Bitmap.CompressFormat.JPEG, 94, out)
        }
        processed.recycle()
        return output
    }

    private fun decodeCorrectlyOriented(file: File): Bitmap {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Could not decode captured image")
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }

    private fun detectAndWarp(bitmap: Bitmap): Bitmap {
        val maxSide = 1800
        val scale = min(1.0, maxSide.toDouble() / max(bitmap.width, bitmap.height).toDouble()).toFloat()
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val src = Mat()
        Utils.bitmapToMat(small, src)
        if (small !== bitmap) small.recycle()

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val imageArea = src.width() * src.height()
        var best: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = abs(Imgproc.contourArea(contour))
            if (area < imageArea * 0.20 || area <= bestArea) continue
            val curve = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(curve, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)
            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray().map { Point(it.x, it.y) }.toTypedArray()))) {
                best = approx
                bestArea = area
            }
            curve.release()
        }

        if (best == null) {
            src.release(); gray.release(); edges.release(); kernel.release(); contours.forEach { it.release() }
            return bitmap
        }

        val pts = orderPoints(best.toArray())
        val tl = pts[0]; val tr = pts[1]; val br = pts[2]; val bl = pts[3]
        val widthTop = distance(tl, tr); val widthBottom = distance(bl, br)
        val heightLeft = distance(tl, bl); val heightRight = distance(tr, br)
        val outW = max(800.0, min(2200.0, max(widthTop, widthBottom))).toInt()
        val outH = max(1000.0, min(3200.0, max(heightLeft, heightRight) * outW / max(widthTop, widthBottom))).toInt()

        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0)
        )
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, result)

        src.release(); gray.release(); edges.release(); kernel.release(); transform.release(); warped.release(); srcPts.release(); dstPts.release(); best.release()
        contours.forEach { it.release() }
        return result
    }

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val sorted = points.sortedWith(compareBy<Point> { it.x + it.y })
        val tl = sorted.first()
        val br = sorted.last()
        val remaining = sorted.drop(1).dropLast(1)
        val tr = remaining.minBy { it.y - it.x }
        val bl = remaining.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun toGrayscale(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun toBlackWhite(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114).toInt()
            val v = when {
                lum < 90 -> 0
                lum > 190 -> 255
                else -> if (lum < 140) 40 else 220
            }
            pixels[i] = Color.rgb(v, v, v)
        }
        out.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return out
    }

    private fun setProcessingMode(mode: ScanMode) {
        processingMode = mode
        modeOriginalButton.isSelected = mode == ScanMode.ORIGINAL
        modeBwButton.isSelected = mode == ScanMode.BW
        modeGrayButton.isSelected = mode == ScanMode.GRAYSCALE
        updateModeIndicator()
    }

    private fun updateModeIndicator() {
        val label = when (processingMode) {
            ScanMode.ORIGINAL -> "ORIGINAL MODE"
            ScanMode.GRAYSCALE -> "GRAY MODE"
            ScanMode.BW -> "B&W MODE"
        }
        modeIndicator.text = "✓ $label"
        modeOriginalButton.text = if (processingMode == ScanMode.ORIGINAL) "✓ ORIGINAL" else "ORIGINAL"
        modeBwButton.text = if (processingMode == ScanMode.BW) "✓ B&W" else "B&W"
        modeGrayButton.text = if (processingMode == ScanMode.GRAYSCALE) "✓ GRAY" else "GRAY"
    }

    private fun updatePageCounters() {
        val text = if (pages.size == 1) "1 page" else "${pages.size} pages"
        pageCounter.text = text
        reviewCount.text = text
    }

    private fun renderThumbnails() {
        thumbContainer.removeAllViews()
        pages.forEachIndexed { index, file ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(150), dp(210))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.WHITE)
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                contentDescription = "Page ${index + 1}"
            }
            val delete = Button(this).apply {
                text = "REMOVE"
                setOnClickListener { deletePage(index) }
            }
            box.addView(image); box.addView(delete); thumbContainer.addView(box)
        }
    }

    private fun deletePage(index: Int) {
        if (index !in pages.indices) return
        pages[index].delete(); pages.removeAt(index)
        if (pages.isEmpty()) showHome() else renderThumbnails()
        updatePageCounters()
    }

    private fun askPdfNameAndSave() {
        if (pages.isEmpty()) { toast("No scanned pages."); return }
        val input = EditText(this).apply {
            hint = "Document name"
            setSingleLine(true)
            setText("COMREL_SCAN_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}")
            selectAll()
        }
        val pad = dp(20)
        val container = FrameLayout(this).apply { setPadding(pad, 0, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Save PDF")
            .setMessage("Choose a name for this document")
            .setView(container)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ -> savePdf(sanitizeFileName(input.text.toString())) }
            .show()
    }

    private fun sanitizeFileName(value: String): String {
        var name = value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (name.isBlank()) name = "COMREL_SCAN_${System.currentTimeMillis()}"
        if (name.lowercase(Locale.US).endsWith(".pdf")) name = name.dropLast(4)
        return name.take(100) + ".pdf"
    }

    private fun savePdf(fileName: String) {
        savePdfButton.isEnabled = false
        cameraExecutor.execute {
            try {
                val outFile = File(libraryDir, fileName)
                if (outFile.exists()) throw IOException("A document with that name already exists.")
                createPdf(outFile)
                runOnUiThread {
                    savePdfButton.isEnabled = true
                    toast("Saved to COMREL SCANNER")
                    discardBatch()
                    renderLibrary()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    savePdfButton.isEnabled = true
                    toast("PDF save failed: ${e.message}")
                }
            }
        }
    }

    private fun createPdf(outFile: File) {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: throw IOException("Could not read page ${index + 1}")
                val pageBitmap = prepareForPdf(bitmap)
                val pageW = 1654
                val pageH = 2339
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                val page = pdf.startPage(pageInfo)
                page.canvas.drawColor(Color.WHITE)
                val scale = min(pageW.toFloat() / pageBitmap.width, pageH.toFloat() / pageBitmap.height)
                val w = pageBitmap.width * scale
                val h = pageBitmap.height * scale
                page.canvas.drawBitmap(pageBitmap, (pageW - w) / 2f, (pageH - h) / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                if (pageBitmap !== bitmap) pageBitmap.recycle()
                bitmap.recycle()
            }
            FileOutputStream(outFile).use { pdf.writeTo(it) }
        } finally { pdf.close() }
    }

    private fun prepareForPdf(bitmap: Bitmap): Bitmap {
        val maxPixels = 1654 * 2339
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixels <= maxPixels) return bitmap
        val scale = sqrt(maxPixels.toDouble() / pixels)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private fun renderLibrary() {
        libraryContainer.removeAllViews()
        val docs = libraryDir.listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (docs.isEmpty()) {
            val empty = TextView(this).apply { text = "No saved documents yet.\n\nTap SCAN DOCUMENT to create your first PDF."; textSize = 16f; setTextColor(Color.DKGRAY); setPadding(dp(8), dp(32), dp(8), dp(32)) }
            libraryContainer.addView(empty)
            return
        }
        docs.forEach { file ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(8), dp(10)); setBackgroundColor(Color.WHITE) }
            val icon = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)); setImageResource(R.drawable.ic_document) }
            val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(12), 0, dp(8), 0) }
            val name = TextView(this).apply { text = file.name; textSize = 16f; setTextColor(Color.rgb(30,30,30)); setTypeface(null, android.graphics.Typeface.BOLD) }
            val meta = TextView(this).apply { text = formatFileMeta(file); textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(3), 0, 0) }
            textBox.addView(name); textBox.addView(meta)
            val menu = Button(this).apply { text = "⋮"; textSize = 22f; setOnClickListener { showDocumentMenu(file) } }
            row.addView(icon); row.addView(textBox); row.addView(menu)
            row.setOnClickListener { openPdf(file) }
            val divider = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)); setBackgroundColor(Color.LTGRAY) }
            libraryContainer.addView(row); libraryContainer.addView(divider)
        }
    }

    private fun formatFileMeta(file: File): String = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(Date(file.lastModified()))

    private fun showDocumentMenu(file: File) {
        val options = arrayOf("Open", "Rename", "Share", "Delete")
        AlertDialog.Builder(this).setTitle(file.name).setItems(options) { _, which ->
            when (which) {
                0 -> openPdf(file)
                1 -> renameDocument(file)
                2 -> sharePdf(file)
                3 -> deleteDocument(file)
            }
        }.show()
    }

    private fun renameDocument(file: File) {
        val input = EditText(this).apply { setSingleLine(true); setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename PDF").setView(input).setNegativeButton("CANCEL", null).setPositiveButton("RENAME") { _, _ ->
            val newName = sanitizeFileName(input.text.toString())
            val target = File(libraryDir, newName)
            if (target.exists()) toast("A document with that name already exists.")
            else if (file.renameTo(target)) renderLibrary() else toast("Could not rename document.")
        }.show()
    }

    private fun deleteDocument(file: File) {
        AlertDialog.Builder(this).setTitle("Delete document?").setMessage(file.name).setNegativeButton("CANCEL", null).setPositiveButton("DELETE") { _, _ ->
            if (file.delete()) renderLibrary() else toast("Could not delete document.")
        }.show()
    }

    private fun openPdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: Exception) { toast("No PDF viewer is installed.") }
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    private fun discardBatch() {
        pages.forEach { it.delete() }; pages.clear(); thumbContainer.removeAllViews(); updatePageCounters(); showHome()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        pages.forEach { it.delete() }
        stopCameraAnalysis()
        cameraExecutor.shutdown()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
