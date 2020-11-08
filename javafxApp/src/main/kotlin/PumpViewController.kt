package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.HighLevelIO
import info.nightscout.comboctl.base.Pump
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Pane
import kotlinx.coroutines.CoroutineScope
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

        displayFrameView.setImage(displayFrameImage)

        // Fill the image view with a checkerboard pattern initially.
        for (y in 0 until DISPLAY_FRAME_HEIGHT) {
            for (x in 0 until DISPLAY_FRAME_WIDTH) {
                val pixel = (if (((x + y) and 1) != 0) 0x00 else 0xFF).toByte()
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 0] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 1] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 2] = pixel
            }
        }
        val pixelWriter = displayFrameImage.getPixelWriter()
        pixelWriter.setPixels(
            0, 0, DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT,
            PixelFormat.getByteRgbInstance(),
            displayFramePixels,
            0,
            DISPLAY_FRAME_WIDTH * 3
        )
    }

    fun setDisplayFrame(displayFrame: DisplayFrame) {
        println("New display frame")

        for (y in 0 until DISPLAY_FRAME_HEIGHT) {
            for (x in 0 until DISPLAY_FRAME_WIDTH) {
                val pixel = (if (displayFrame.getPixelAt(x, y)) 0x00 else 0xFF).toByte()
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 0] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 1] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 2] = pixel
            }
        }

        val pixelWriter = displayFrameImage.getPixelWriter()
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
            pump!!.sendSingleRTButtonPress(HighLevelIO.Button.CHECK)
        }
    }

    fun pressMenuButton() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.sendSingleRTButtonPress(HighLevelIO.Button.MENU)
        }
    }

    fun pressUpButton() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.sendSingleRTButtonPress(HighLevelIO.Button.UP)
        }
    }

    fun pressDownButton() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.sendSingleRTButtonPress(HighLevelIO.Button.DOWN)
        }
    }

    fun connectPump() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            pump!!.connect(mainScope!!)
        }
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
