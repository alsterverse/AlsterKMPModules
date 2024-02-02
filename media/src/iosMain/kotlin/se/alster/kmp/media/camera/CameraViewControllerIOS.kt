package se.alster.kmp.media.camera

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeJPEG
import platform.AVFoundation.fileDataRepresentation
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.AudioToolbox.kSystemSoundID_Vibrate
import platform.CoreGraphics.CGRect
import platform.Foundation.NSError
import platform.UIKit.UIColor
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIImage
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import se.alster.kmp.media.camera.extensions.mapAVCaptureVideoOrientation
import se.alster.kmp.media.camera.util.captureDeviceInputByPosition
import se.alster.kmp.media.toImageBitmap

internal class CameraViewControllerIOS(
    private val videoGravity: AVLayerVideoGravity,
    private val onTakePhoto: ((onTakePhoto: ((photo: CaptureResult) -> Unit) -> Unit) -> Unit)?,
    private val onScanComplete: ((String) -> Unit)?,
) : UIViewController(nibName = null, bundle = null) {

    private val captureSession: AVCaptureSession = AVCaptureSession()
    private val previewLayer: AVCaptureVideoPreviewLayer =
        AVCaptureVideoPreviewLayer(session = captureSession)

    private val frontCamera: AVCaptureInput? = captureDeviceInputByPosition(CameraFacing.Front)
    private val backCamera: AVCaptureInput? = captureDeviceInputByPosition(CameraFacing.Back)

    fun onOrientationChanged(orientation: UIDeviceOrientation) {
        if (previewLayer.connection?.videoOrientation != null) {
            previewLayer.connection?.videoOrientation =
                orientation.mapAVCaptureVideoOrientation(AVCaptureVideoOrientationLandscapeRight)
            println("Orientation changed to $orientation")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun onResize(rect: CValue<CGRect>) {
        previewLayer.setFrame(rect)
    }

    fun onDispose() {
        captureSession.stopRunning()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = UIColor.blackColor

        switchCamera(CameraFacing.Back)

        val metadataOutput = AVCaptureMetadataOutput()

        if (metadataOutput.availableMetadataObjectTypes.contains(AVMetadataObjectTypeQRCode)
            && captureSession.canAddOutput(metadataOutput)
            && onScanComplete != null
        ) {
            captureSession.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(
                object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
                    override fun captureOutput(
                        output: platform.AVFoundation.AVCaptureOutput,
                        didOutputMetadataObjects: List<*>,
                        fromConnection: AVCaptureConnection
                    ) {
                        captureSession.stopRunning()
                        val data = didOutputMetadataObjects.first()
                        val readableObject = data as? AVMetadataMachineReadableCodeObject
                        val stringValue = readableObject?.stringValue!!
                        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)

                        onScanComplete.invoke(stringValue)
                        dismissViewControllerAnimated(true, null)
                    }
                }, queue = dispatch_get_main_queue())
            metadataOutput.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        } else {
            return
        }

        onTakePhoto?.invoke { callback ->
            val capturePhotoOutput = AVCapturePhotoOutput()
            capturePhotoOutput.capturePhotoWithSettings(
                AVCapturePhotoSettings.photoSettingsWithFormat(
                    format = mapOf(AVVideoCodecKey to AVVideoCodecTypeJPEG)
                ),
                object : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
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
                },
            )
            captureSession.addOutput(capturePhotoOutput)
        }


        previewLayer.frame = view.layer.bounds
        previewLayer.videoGravity = videoGravity
        view.layer.addSublayer(previewLayer)

        captureSession.startRunning()
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        if (!captureSession.isRunning()) {
            captureSession.startRunning()
        }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        if (captureSession.isRunning()) {
            captureSession.stopRunning()
        }
    }

    override fun prefersStatusBarHidden(): Boolean {
        return false
    }

    fun onCameraFacingChanged(cameraFacing: CameraFacing) {
        switchCamera(cameraFacing)
    }

    private fun switchCamera(cameraFacing: CameraFacing) {
        when (cameraFacing) {
            CameraFacing.Front -> {
                if (frontCamera != null && captureSession.inputs.contains(frontCamera)) {
                    captureSession.removeInput(frontCamera)
                }
                if (backCamera != null) {
                    captureSession.addInput(backCamera)
                }
            }

            CameraFacing.Back -> {
                if (backCamera != null && captureSession.inputs.contains(backCamera)) {
                    captureSession.removeInput(backCamera)
                }
                if (frontCamera != null) {
                    captureSession.addInput(frontCamera)
                }
            }
        }
    }
}
