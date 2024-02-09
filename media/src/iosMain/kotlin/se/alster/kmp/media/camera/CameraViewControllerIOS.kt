package se.alster.kmp.media.camera

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.AVFoundation.fileDataRepresentation
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.CoreGraphics.CGRect
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotification
import platform.Foundation.URLByAppendingPathComponent
import platform.Foundation.temporaryDirectory
import platform.UIKit.UIColor
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import se.alster.kmp.media.camera.exception.CameraNotFoundException
import se.alster.kmp.media.camera.util.captureDeviceInputByPosition
import se.alster.kmp.media.toImageBitmap

/*
 * CameraViewControllerIOS is a UIViewController that manages the camera view and the camera session.
 * It is used to take photos and scan QR codes.
 * It throws a CameraNotFoundException if the front or back camera is not found.
 */
internal class CameraViewControllerIOS(
    private val videoGravity: AVLayerVideoGravity,
    private val captureController: CaptureController?,
    private val onScanComplete: ((String) -> Unit)?,
) : UIViewController(nibName = null, bundle = null) {
    private val captureControllerIOS = captureController as Foo

    @OptIn(ExperimentalForeignApi::class)
    fun onResize(rect: CValue<CGRect>) {
        captureControllerIOS.previewLayer.setFrame(rect)
    }

    fun onDispose() {
        captureControllerIOS.captureSession.stopRunning()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = UIColor.blackColor
        captureControllerIOS.previewLayer.frame = view.layer.bounds
        captureControllerIOS.previewLayer.videoGravity = videoGravity
        view.layer.addSublayer(captureControllerIOS.previewLayer)

        captureControllerIOS.captureSession.startRunning()

        switchCamera(CameraFacing.Back)

        val metadataOutput = AVCaptureMetadataOutput()

        if (metadataOutput.availableMetadataObjectTypes.contains(AVMetadataObjectTypeQRCode)
            && captureControllerIOS.captureSession.canAddOutput(metadataOutput)
            && onScanComplete != null
        ) {
            captureControllerIOS.captureSession.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(
                object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
                    override fun captureOutput(
                        output: platform.AVFoundation.AVCaptureOutput,
                        didOutputMetadataObjects: List<*>,
                        fromConnection: AVCaptureConnection
                    ) {
                        captureControllerIOS.captureSession.stopRunning()
                        val data = didOutputMetadataObjects.first()
                        val readableObject = data as? AVMetadataMachineReadableCodeObject
                        val stringValue = readableObject?.stringValue!!
                        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)

                        onScanComplete.invoke(stringValue)
                        dismissViewControllerAnimated(true, null)
                    }
                }, queue = dispatch_get_main_queue()
            )
            metadataOutput.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        }

    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        if (!captureControllerIOS.captureSession.isRunning()) {
            captureControllerIOS.captureSession.startRunning()
        }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        if (captureControllerIOS.captureSession.isRunning()) {
            captureControllerIOS.captureSession.stopRunning()
        }
    }

    override fun prefersStatusBarHidden(): Boolean {
        return false
    }

    fun onCameraFacingChanged(cameraFacing: CameraFacing) {
        switchCamera(cameraFacing)
    }

    @Suppress("UNUSED_PARAMETER")
    @ObjCAction
    fun orientationDidChange(arg: NSNotification) {
        val cameraConnection = captureControllerIOS.previewLayer.connection
        if (cameraConnection != null) {
            captureControllerIOS.actualOrientation = when (UIDevice.currentDevice.orientation) {
                UIDeviceOrientation.UIDeviceOrientationPortrait ->
                    AVCaptureVideoOrientationPortrait

                UIDeviceOrientation.UIDeviceOrientationLandscapeLeft ->
                    AVCaptureVideoOrientationLandscapeRight

                UIDeviceOrientation.UIDeviceOrientationLandscapeRight ->
                    AVCaptureVideoOrientationLandscapeLeft

                UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown ->
                    AVCaptureVideoOrientationPortrait

                else -> cameraConnection.videoOrientation
            }
            cameraConnection.videoOrientation = captureControllerIOS.actualOrientation
        }
    }

    private fun switchCamera(cameraFacing: CameraFacing) {
        removeAllCameras()
        when (cameraFacing) {
            CameraFacing.Front -> {
                captureControllerIOS.captureSession.addInput(captureControllerIOS.frontCamera)
            }

            CameraFacing.Back -> {
                captureControllerIOS.captureSession.addInput(captureControllerIOS.backCamera)
            }
        }
    }

    private fun removeAllCameras() {
        captureControllerIOS.captureSession.inputs.forEach {
            it as AVCaptureDeviceInput
            captureControllerIOS.captureSession.removeInput(it)
        }
    }
}

class Foo : CaptureController {
    internal val captureSession: AVCaptureSession = AVCaptureSession()
    internal val previewLayer: AVCaptureVideoPreviewLayer =
        AVCaptureVideoPreviewLayer(session = captureSession)

    internal var actualOrientation: AVCaptureVideoOrientation =
        AVCaptureVideoOrientationLandscapeRight

    internal val capturePhotoOutput = AVCapturePhotoOutput()
    internal val captureVideoFileOutput = AVCaptureMovieFileOutput()
    internal val cameraCaptureFileOutputRecordingDelegateIOS =
        CameraCaptureFileOutputRecordingDelegateIOS()

    internal val frontCamera: AVCaptureDeviceInput = captureDeviceInputByPosition(CameraFacing.Front)
        ?: throw CameraNotFoundException("Front camera not found")
    internal val backCamera: AVCaptureDeviceInput = captureDeviceInputByPosition(CameraFacing.Back)
        ?: throw CameraNotFoundException("Back camera not found")

    init {
        if (captureSession.canAddOutput(capturePhotoOutput)) {
            captureSession.addOutput(capturePhotoOutput)
        }
        if (captureSession.canAddOutput(captureVideoFileOutput)) {
            captureSession.addOutput(captureVideoFileOutput)
        }
        captureVideoFileOutput.connectionWithMediaType(AVMediaTypeVideo)?.videoOrientation =
            actualOrientation
        capturePhotoOutput.connectionWithMediaType(AVMediaTypeVideo)?.videoOrientation =
            actualOrientation
    }

    override fun takePicture(callback: (photo: CaptureResult) -> Unit) {
        capturePhotoOutput.capturePhotoWithSettings(
            AVCapturePhotoSettings.photoSettingsWithFormat(
                format = mapOf(AVVideoCodecKey to AVVideoCodecTypeJPEG)
            ), delegate = object : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
                override fun captureOutput(
                    output: AVCapturePhotoOutput,
                    didFinishProcessingPhoto: AVCapturePhoto,
                    error: NSError?
                ) {
                    didFinishProcessingPhoto.fileDataRepresentation()?.let {
                        return callback(CaptureResult.Success(UIImage(it).toImageBitmap()))
                    }
                    if (error != null) {
                        return callback(CaptureResult.Failure)
                    }
                }
            }
        )
    }

    override fun startRecording() {
        val path = NSFileManager.defaultManager.temporaryDirectory
            .URLByAppendingPathComponent("video.mp4")!!
        println("Recording started")
        captureVideoFileOutput.startRecordingToOutputFileURL(
            outputFileURL = path,
            recordingDelegate = cameraCaptureFileOutputRecordingDelegateIOS
        )
    }

    override fun stopRecording() {
        println("Recording stopped")
        captureVideoFileOutput.stopRecording()
    }
}
