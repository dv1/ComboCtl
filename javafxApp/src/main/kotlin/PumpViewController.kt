package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.Pump
import info.nightscout.comboctl.base.PumpIO
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Pane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class PumpViewController {
    private var pump: Pump? = null
    private var mainScope: CoroutineScope? = null
    private var displayFrameView: ImageView? = null

    private var displayFrameImage = WritableImage(DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT)
    private val displayFramePixels = ByteArray(DISPLAY_FRAME_WIDTH * DISPLAY_FRAME_HEIGHT * 3)

    fun setup(pump: Pump, mainScope: CoroutineScope, displayFrameView: ImageView) {
        this.pump = pump
        this.mainScope = mainScope
        this.displayFrameView = displayFrameView

        // Bind the parent pane's width and height property to the imageview
        // to make sure the imageview always is resized to fill the parent pane.
        val parentPane = displayFrameView.parent as Pane
        displayFrameView.fitWidthProperty().bind(parentPane.widthProperty())
        displayFrameView.fitHeightProperty().bind(parentPane.heightProperty())

        displayFrameView.image = displayFrameImage

        // Fill the image view with a checkerboard pattern initially.
        for (y in 0 until DISPLAY_FRAME_HEIGHT) {
            for (x in 0 until DISPLAY_FRAME_WIDTH) {
                val pixel = (if (((x + y) and 1) != 0) 0x00 else 0xFF).toByte()
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 0] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 1] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 2] = pixel
            }
        }
        val pixelWriter = displayFrameImage.pixelWriter
        pixelWriter.setPixels(
            0, 0, DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT,
            PixelFormat.getByteRgbInstance(),
            displayFramePixels,
            0,
            DISPLAY_FRAME_WIDTH * 3
        )

        pump.displayFrameFlow
            .onEach { displayFrame -> setDisplayFrame(displayFrame) }
            .launchIn(mainScope)
    }

    private fun setDisplayFrame(displayFrame: DisplayFrame) {
        println("New display frame")

        for (y in 0 until DISPLAY_FRAME_HEIGHT) {
            for (x in 0 until DISPLAY_FRAME_WIDTH) {
                val pixel = (if (displayFrame.getPixelAt(x, y)) 0x00 else 0xFF).toByte()
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 0] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 1] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 2] = pixel
            }
        }

        val pixelWriter = displayFrameImage.pixelWriter
        pixelWriter.setPixels(
            0, 0, DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT,
            PixelFormat.getByteRgbInstance(),
            displayFramePixels,
            0,
            DISPLAY_FRAME_WIDTH * 3
        )
    }

    fun pressCheckButton() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.sendShortRTButtonPress(PumpIO.Button.CHECK)
        }
    }

    fun pressMenuButton() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.sendShortRTButtonPress(PumpIO.Button.MENU)
        }
    }

    fun pressUpButton() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.sendShortRTButtonPress(PumpIO.Button.UP)
        }
    }

    fun pressDownButton() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.sendShortRTButtonPress(PumpIO.Button.DOWN)
        }
    }

    fun connectPump() {
        require(pump != null)
        require(mainScope != null)

        pump!!.connect(mainScope!!)
    }

    fun disconnectPump() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.disconnect()
        }
    }

    fun unpairPump() {
    }
}
