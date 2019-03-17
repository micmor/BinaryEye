package de.markusfisch.android.binaryeye.activity

import com.google.zxing.Result

import de.markusfisch.android.cameraview.widget.CameraView

import de.markusfisch.android.binaryeye.app.db
import de.markusfisch.android.binaryeye.app.initSystemBars
import de.markusfisch.android.binaryeye.app.prefs
import de.markusfisch.android.binaryeye.rs.Preprocessor
import de.markusfisch.android.binaryeye.zxing.Zxing
import de.markusfisch.android.binaryeye.R

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.os.Vibrator
import android.provider.MediaStore
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

import java.io.IOException

class CameraActivity : AppCompatActivity() {
	private val zxing = Zxing()
	private val decodingRunnable = Runnable {
		while (!Thread.currentThread().isInterrupted()) {
			val result = decodeFrame()
			if (result != null) {
				cameraView.post { found(result) }
				break
			}
		}
	}

	private lateinit var vibrator: Vibrator
	private lateinit var cameraView: CameraView
	private lateinit var zoomBar: SeekBar

	private var decodingThread: Thread? = null
	private var preprocessor: Preprocessor? = null
	private var frameData: ByteArray? = null
	private var frameWidth: Int = 0
	private var frameHeight: Int = 0
	private var frameOrientation: Int = 0
	private var invert = false
	private var flash = false
	private var returnResult = false
	private var frontFacing = false

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<String>,
		grantResults: IntArray
	) {
		when (requestCode) {
			REQUEST_CAMERA -> if (grantResults.size > 0 &&
				grantResults[0] != PackageManager.PERMISSION_GRANTED) {
				Toast.makeText(
					this,
					R.string.no_camera_no_fun,
					Toast.LENGTH_SHORT
				).show()
				finish()
			}
		}
	}

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(R.layout.activity_camera)

		initSystemBars(this)
		setSupportActionBar(findViewById(R.id.toolbar) as Toolbar)

		vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

		cameraView = findViewById(R.id.camera_view) as CameraView
		zoomBar = findViewById(R.id.zoom) as SeekBar

		initCameraView()
		initZoomBar()
		restoreZoom()
		initFlashFab(findViewById(R.id.flash))

		if (intent?.action == Intent.ACTION_SEND) {
			if ("text/plain" == intent.type) {
				handleSendText(intent)
			} else if (intent.type?.startsWith("image/") == true) {
				handleSendImage(intent)
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		preprocessor?.destroy()
		saveZoom()
	}

	override fun onResume() {
		super.onResume()
		System.gc()
		returnResult = "com.google.zxing.client.android.SCAN".equals(
			intent.action
		)
		if (hasCameraPermission()) {
			openCamera()
		}
	}

	private fun openCamera() {
		cameraView.openAsync(
			CameraView.findCameraId(
				if (frontFacing) {
					Camera.CameraInfo.CAMERA_FACING_FRONT
				} else {
					Camera.CameraInfo.CAMERA_FACING_BACK
				}
			)
		)
	}

	override fun onPause() {
		super.onPause()
		closeCamera()
	}

	private fun closeCamera() {
		cancelDecoding()
		cameraView.close()
	}

	override fun onRestoreInstanceState(savedState: Bundle) {
		super.onRestoreInstanceState(savedState)
		zoomBar.max = savedState.getInt(ZOOM_MAX)
		zoomBar.progress = savedState.getInt(ZOOM_LEVEL)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putInt(ZOOM_MAX, zoomBar.max)
		outState.putInt(ZOOM_LEVEL, zoomBar.progress)
		super.onSaveInstanceState(outState)
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.activity_camera, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.create -> {
				startActivity(MainActivity.getEncodeIntent(this))
				true
			}
			R.id.history -> {
				startActivity(MainActivity.getHistoryIntent(this))
				true
			}
			R.id.switch_camera -> {
				switchCamera()
				true
			}
			R.id.info -> {
				openReadme()
				true
			}
			else -> super.onOptionsItemSelected(item)
		}
	}

	private fun switchCamera() {
		closeCamera()
		frontFacing = frontFacing xor true
		openCamera()
	}

	private fun openReadme() {
		startActivity(
			Intent(
				Intent.ACTION_VIEW, Uri.parse(
					"https://github.com/markusfisch/BinaryEye/blob/master/README.md"
				)
			)
		)
	}

	private fun handleSendText(intent: Intent) {
		var text = intent.getStringExtra(Intent.EXTRA_TEXT)
		if (text?.isEmpty() == true) {
			startActivity(MainActivity.getEncodeIntent(this, text, true))
			finish()
		}
	}

	private fun handleSendImage(intent: Intent) {
		var uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
		if (uri == null) {
			return
		}
		val bitmap = try {
			MediaStore.Images.Media.getBitmap(contentResolver, uri)
		} catch (e: IOException) {
			null
		}
		if (bitmap == null) {
			Toast.makeText(
				this,
				R.string.error_no_content,
				Toast.LENGTH_SHORT
			).show()
			return
		}
		val result = zxing.decode(downsizeIfBigger(bitmap, 1024))
		if (result != null) {
			showResult(result)
			finish()
			return
		}
		Toast.makeText(
			this,
			R.string.no_barcode_found,
			Toast.LENGTH_SHORT
		).show()
		finish()
	}

	private fun hasCameraPermission(): Boolean {
		val permission = android.Manifest.permission.CAMERA

		if (ContextCompat.checkSelfPermission(this, permission) !=
			PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(
				this, arrayOf(permission),
				REQUEST_CAMERA
			)
			return false
		}

		return true
	}

	private fun initCameraView() {
		cameraView.setUseOrientationListener(true)
		cameraView.setTapToFocus()
		cameraView.setOnCameraListener(object : CameraView.OnCameraListener {
			override fun onConfigureParameters(
				parameters: Camera.Parameters
			) {
				if (parameters.isZoomSupported()) {
					val max = parameters.getMaxZoom()
					if (zoomBar.max != max) {
						zoomBar.max = max
						zoomBar.progress = max / 10
						saveZoom()
					}
					parameters.setZoom(zoomBar.progress)
				} else {
					zoomBar.visibility = View.GONE
				}
				val sceneModes = parameters.supportedSceneModes
				sceneModes?.let {
					for (mode in sceneModes) {
						if (mode.equals(Camera.Parameters.SCENE_MODE_BARCODE)) {
							parameters.sceneMode = mode
							break
						}
					}
				}
				CameraView.setAutoFocus(parameters)
			}

			override fun onCameraError() {
				Toast.makeText(
					this@CameraActivity,
					R.string.camera_error,
					Toast.LENGTH_SHORT
				).show()
				finish()
			}

			override fun onCameraReady(camera: Camera) {
				frameWidth = cameraView.frameWidth
				frameHeight = cameraView.frameHeight
				frameOrientation = cameraView.frameOrientation
				camera.setPreviewCallback { data, _ -> frameData = data }
			}

			override fun onPreviewStarted(camera: Camera) {
				startDecoding()
			}

			override fun onCameraStopping(camera: Camera) {
				cancelDecoding()
				camera.setPreviewCallback(null)
			}
		})
	}

	private fun initZoomBar() {
		zoomBar.setOnSeekBarChangeListener(object :
			SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(
				seekBar: SeekBar,
				progress: Int,
				fromUser: Boolean
			) {
				setZoom(progress)
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) {}

			override fun onStopTrackingTouch(seekBar: SeekBar) {}
		})
	}

	private fun setZoom(zoom: Int) {
		val camera: Camera? = cameraView.getCamera()
		camera?.let {
			try {
				val params = camera.getParameters()
				params.setZoom(zoom)
				camera.setParameters(params)
			} catch (e: RuntimeException) {
				// ignore; there's nothing we can do
			}
		}
	}

	private fun saveZoom() {
		val editor = prefs.preferences.edit()
		editor.putInt(ZOOM_MAX, zoomBar.max)
		editor.putInt(ZOOM_LEVEL, zoomBar.progress)
		editor.apply()
	}

	private fun restoreZoom() {
		zoomBar.max = prefs.preferences.getInt(ZOOM_MAX, zoomBar.max)
		zoomBar.progress = prefs.preferences.getInt(
			ZOOM_LEVEL,
			zoomBar.progress
		)
	}

	private fun initFlashFab(fab: View) {
		if (!packageManager.hasSystemFeature(
				PackageManager.FEATURE_CAMERA_FLASH
			)) {
			fab.visibility = View.GONE
		} else {
			fab.setOnClickListener { _ ->
				toggleTorchMode()
			}
		}
	}

	private fun toggleTorchMode() {
		val camera = cameraView.getCamera()
		val parameters = camera?.getParameters()
		parameters?.setFlashMode(
			if (flash) {
				Camera.Parameters.FLASH_MODE_OFF
			} else {
				Camera.Parameters.FLASH_MODE_TORCH
			}
		)
		flash = flash xor true
		parameters?.let { camera.setParameters(parameters) }
	}

	private fun startDecoding() {
		frameData = null
		decodingThread = Thread(decodingRunnable)
		decodingThread?.start()
	}

	private fun cancelDecoding() {
		if (decodingThread != null) {
			decodingThread?.interrupt()
			try {
				decodingThread?.join()
			} catch (e: InterruptedException) {
				// parent thread was interrupted
			}
			decodingThread = null
		}
	}

	private fun decodeFrame(): Result? {
		var fd = frameData
		fd ?: return null
		if (preprocessor == null) {
			preprocessor = Preprocessor(
				this,
				frameWidth,
				frameHeight,
				frameOrientation
			)
		}
		var pp = preprocessor
		pp ?: return null
		pp.process(fd)
		invert = invert xor true
		return zxing.decode(
			fd,
			pp.outWidth,
			pp.outHeight,
			invert
		)
	}

	private fun found(result: Result) {
		cancelDecoding()
		vibrator.vibrate(100)
		showResult(result)
	}

	private fun showResult(result: Result) {
		if (prefs.useHistory) {
			GlobalScope.launch {
				db.insertScan(
					System.currentTimeMillis(),
					result.text,
					result.barcodeFormat.toString()
				)
			}
		}

		if (returnResult) {
			val resultIntent = Intent()
			resultIntent.putExtra("SCAN_RESULT", result.text)
			setResult(RESULT_OK, resultIntent)
			finish()
			return
		}

		startActivity(
			MainActivity.getDecodeIntent(
				this,
				result.text,
				result.barcodeFormat
			)
		)
	}

	companion object {
		private const val REQUEST_CAMERA = 1
		private const val ZOOM_MAX = "zoom_max"
		private const val ZOOM_LEVEL = "zoom_level"
	}
}

fun downsizeIfBigger(bitmap: Bitmap, max: Int): Bitmap {
	return if (Math.max(bitmap.width, bitmap.height) > max) {
		var width: Int = max
		var height: Int = max
		if (bitmap.width > bitmap.height) {
			height = Math.round(
				width.toFloat() / bitmap.width.toFloat() *
					bitmap.height.toFloat()
			)
		} else {
			width = Math.round(
				height.toFloat() / bitmap.height.toFloat() *
					bitmap.width.toFloat()
			)
		}
		Bitmap.createScaledBitmap(bitmap, width, height, true)
	} else {
		bitmap
	}
}
