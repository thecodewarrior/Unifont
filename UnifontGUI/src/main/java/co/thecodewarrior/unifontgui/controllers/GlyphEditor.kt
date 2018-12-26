package co.thecodewarrior.unifontgui.controllers

import co.thecodewarrior.unifontgui.ChangeListener
import co.thecodewarrior.unifontgui.Changes
import co.thecodewarrior.unifontgui.Constants
import co.thecodewarrior.unifontgui.sizeHeightTo
import co.thecodewarrior.unifontgui.utils.*
import co.thecodewarrior.unifontlib.EditorGuide
import co.thecodewarrior.unifontlib.Glyph
import co.thecodewarrior.unifontlib.Unifont
import co.thecodewarrior.unifontlib.utils.Pos
import javafx.embed.swing.SwingFXUtils
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.input.MouseEvent
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javafx.scene.control.Slider
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import javafx.stage.Stage
import java.awt.BasicStroke
import java.awt.Font
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * shortcuts:
 * alt-left/right = advance -/+
 * shift-alt-left/right = left bearing -/+
 * ctrl-left/right = prev/next glyph
 */
class GlyphEditor: ChangeListener {
    lateinit var stage: Stage
    lateinit var project: Unifont
    lateinit var glyph: Glyph
    private val horizontalGuides = mutableListOf<EditorGuide>()
    private val verticalGuides = mutableListOf<EditorGuide>()

    @FXML
    lateinit var canvas: Canvas
    @FXML
    lateinit var metrics: VBox

    private val gc: GraphicsContext
        get() = canvas.graphicsContext2D
    private var fullImage: BufferedImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    private var g: Graphics2D = fullImage.createGraphics()

    var mouseDragType: Boolean? = null
    var lastPos: Pos? = null
    var lastDragType: Boolean = false

    var pixelSize: Int = 16
    var glyphSize: Int = 0
    lateinit var glyphPos: Pos
    lateinit var referencePos: Pos
    var referenceFont: Font = Constants.notoSans

    lateinit var zoomMetric: Slider
    lateinit var rightBearingMetric: Slider
    lateinit var leftBearingMetric: Slider
    lateinit var missingMetric: CheckBox
    lateinit var kernableMetric: CheckBox

    @FXML
    fun initialize() {

    }

    fun setup(stage: Stage, project: Unifont, glyph: Glyph) {
        this.stage = stage
        this.project = project
        this.pixelSize = 256/project.settings.size
        this.horizontalGuides.addAll(project.settings.horizontalGuides)
        this.verticalGuides.addAll(project.settings.verticalGuides)

        stage.setOnHiding { shutdown() }
        stage.isResizable = false

        stage.scene.addShortcuts(
                Shortcut("Alt+Left") {
                    this.glyph.advance = max(0, this.glyph.advance-1)
                    Changes.submit(this.glyph)
                },
                Shortcut("Alt+Right") {
                    this.glyph.advance = min(project.settings.size, this.glyph.advance+1)
                    Changes.submit(this.glyph)
                },
                Shortcut("Shift+Alt+Left") {
                    if(glyph.leftBearing < project.settings.size) {
                        this.glyph.advance = min(project.settings.size, this.glyph.advance + 1)
                        this.glyph.leftBearing++
                    }
                    Changes.submit(this.glyph)
                },
                Shortcut("Shift+Alt+Right") {
                    if(glyph.leftBearing > -project.settings.size) {
                        this.glyph.advance = max(0, this.glyph.advance - 1)
                        this.glyph.leftBearing--
                    }
                    Changes.submit(this.glyph)
                },
                Shortcut("Ctrl+Left") {
                    this.previousGlyph()
                },
                Shortcut("Ctrl+Right") {
                    this.nextGlyph()
                },
                Shortcut("Alt+K") {
                    this.glyph.noAutoKern = !this.glyph.noAutoKern
                    Changes.submit(this.glyph)
                }
        )

        zoomMetric = createSlider("Zoom", 3, 32, pixelSize) {
            zoom(it, keepCanvasSize = true)
            redrawCanvas()
        }.also { slider ->
            slider.valueChangingProperty().addListener { _, oldValue, newValue ->
                if(oldValue && !newValue) {
                    zoom(pixelSize)
                    redrawCanvas()
                }
            }
        }

        missingMetric = createCheckbox("Missing", glyph.missing) {
            this.glyph.missing = it
            Changes.submit(this.glyph)
        }

        kernableMetric = createCheckbox("Don't auto-kern", glyph.noAutoKern) {
            this.glyph.noAutoKern = it
            Changes.submit(this.glyph)
        }

        rightBearingMetric = createSlider("Right bearing", -project.settings.size/2, project.settings.size/2, glyph.rightBearing) {
            this.glyph.rightBearing = it
            Changes.submit(this.glyph)
        }
        leftBearingMetric = createSlider("Left bearing", -project.settings.size/2, project.settings.size/2, glyph.leftBearing) {
            this.glyph.leftBearing = it
            Changes.submit(this.glyph)
        }

        zoom(pixelSize)
        loadGlyph(glyph)

        redrawCanvas()
    }

    @FXML
    fun nextGlyph() {
        val newGlyph = project[glyph.codepoint+1] ?: return
        loadGlyph(newGlyph)
        redrawCanvas()
    }

    @FXML
    fun previousGlyph() {
        val newGlyph = project[glyph.codepoint-1] ?: return
        loadGlyph(newGlyph)
        redrawCanvas()
    }

    fun loadGlyph(glyph: Glyph) {
        try {
            this.unlistenTo(this.glyph)
        } catch(e: UninitializedPropertyAccessException) {
            // nop
        }
        this.glyph = glyph
        this.listenTo(glyph)
        this.stage.title = "Edit U+%04X (${glyph.character})".format(glyph.codepoint)

        rightBearingMetric.value = glyph.rightBearing.toDouble()
        leftBearingMetric.value = glyph.leftBearing.toDouble()
        missingMetric.isSelected = glyph.missing
        val bounds = glyph.bounds
        if(bounds.width == 0 && bounds.height == 0) {
            if(rightBearingMetric.value == 0.0)
                rightBearingMetric.value = project.settings.defaultBearing.toDouble()
            if(rightBearingMetric.value == 0.0)
                rightBearingMetric.value = project.settings.defaultBearing.toDouble()
        }
        kernableMetric.isSelected = glyph.noAutoKern
        zoom(pixelSize)
    }

    fun createSlider(name: String, min: Int, max: Int, initialValue: Int, change: (Int) -> Unit): Slider {
        val label = Label(name)
        label.textAlignment = TextAlignment.CENTER
        label.prefWidth = 200.0
        val slider = Slider(min.toDouble(), max.toDouble(), initialValue.toDouble())
        slider.isShowTickMarks = true
        slider.isSnapToTicks = true
        slider.majorTickUnit = 1.0
        slider.minorTickCount = 0
        var lastValue = initialValue
        slider.valueProperty().addListener { _, _, value ->
            val intValue = value.toDouble().roundToInt()
            slider.value = intValue.toDouble()
            if(intValue != lastValue) {
                lastValue = intValue
                change(intValue)
            }
        }

        metrics.children.add(label)
        metrics.children.add(slider)
        return slider
    }

    fun createCheckbox(name: String, initialValue: Boolean, change: (Boolean) -> Unit): CheckBox {
        val checkbox = CheckBox(name)
        checkbox.isSelected = initialValue
        var lastValue = initialValue
        checkbox.selectedProperty().addListener { _, _, value ->
            if(value != lastValue) {
                lastValue = value
                change(value)
            }
        }

        metrics.children.add(checkbox)
        return checkbox
    }

    fun zoom(size: Int, keepCanvasSize: Boolean = false) {
        pixelSize = size
        glyphSize = project.settings.size*pixelSize
        glyphPos = Pos(2 * glyphSize, glyphSize / 2)
        referencePos = Pos(glyphSize / 2, glyphSize / 2)

        this.referenceFont = referenceFont.sizeHeightTo("X", pixelSize*project.settings.capHeight.toFloat())
        if(!keepCanvasSize) {
            canvas.width = glyphSize * 3.5
            canvas.height = glyphSize * 2.0
        }

        stage.sizeToScene()
    }

    fun pixelCoords(canvasPos: Pos): Pos {
        return (canvasPos - glyphPos) / pixelSize
    }

    fun shutdown() {
        unlistenTo(this.glyph)
    }

    override fun changeOccured(target: Any) {
        glyph.missing = false
        glyph.rightBearing = rightBearingMetric.value.toInt()
        glyph.leftBearing = leftBearingMetric.value.toInt()
        missingMetric.isSelected = glyph.missing
        kernableMetric.isSelected = glyph.noAutoKern
        glyph.markDirty()
        redrawCanvas()
    }

    fun redrawCanvas() {
        if(fullImage.width != canvas.width.toInt() || fullImage.height != canvas.height.toInt()) {
            fullImage = BufferedImage(canvas.width.toInt(), canvas.height.toInt(), BufferedImage.TYPE_INT_ARGB)
            g = fullImage.createGraphics()
        }
        val scale = glyphSize / project.settings.size.toDouble()

        g.loadIdentity()
        g.background = Color(255, 255, 255, 0)
        g.clearRect(0, 0, fullImage.width, fullImage.height)
        g.strokeWidth = 1f

        drawGlyphEdges()
        g.loadIdentity()

        drawReferenceGuides()
        g.loadIdentity()

        drawGlyphGuides()
        g.loadIdentity()

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.translate(glyphPos.x, glyphPos.y)
        g.scale(scale, scale)
        g.drawImage(glyph.image, 0, 0, null)
        g.scale(1.0/scale, 1.0/scale)
        g.loadIdentity()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_DEFAULT)


        val baselineY = (project.settings.size - project.settings.baseline)*pixelSize
        val metrics = referenceFont.createGlyphVector(g.fontRenderContext, glyph.character).getGlyphMetrics(0)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = referenceFont
        g.drawString(glyph.character, referencePos.x - metrics.lsb.toInt(), referencePos.y + baselineY)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT)

        gc.clearRect(0.0, 0.0, canvas.width, canvas.height)
        gc.drawImage(SwingFXUtils.toFXImage(fullImage, null), 0.0, 0.0)
    }

    fun drawGlyphEdges() {
        g.color = Color.green
        g.drawLine(0, glyphPos.y, fullImage.width, glyphPos.y)
        g.drawLine(0, glyphPos.y + glyphSize, fullImage.width, glyphPos.y + glyphSize)

        g.drawLine(glyphPos.x, 0, glyphPos.x, fullImage.height)
        g.drawLine(glyphPos.x + glyphSize, 0, glyphPos.x + glyphSize, fullImage.height)
        g.drawLine(referencePos.x, 0, referencePos.x, fullImage.height)
        g.drawLine(referencePos.x + glyphSize, 0, referencePos.x + glyphSize, fullImage.height)

        g.color = Color.lightGray
        (1 until project.settings.size).forEach {
            g.drawLine(glyphPos.x, glyphPos.y+it*pixelSize, glyphPos.x+glyphSize, glyphPos.y+it*pixelSize)
            g.drawLine(referencePos.x, referencePos.y+it*pixelSize, referencePos.x+glyphSize, referencePos.y+it*pixelSize)

            g.drawLine(glyphPos.x+it*pixelSize, glyphPos.y, glyphPos.x+it*pixelSize, glyphPos.y+glyphSize)
            g.drawLine(referencePos.x+it*pixelSize, referencePos.y, referencePos.x+it*pixelSize, referencePos.y+glyphSize)
        }
    }

    fun drawReferenceGuides() {
        val baselineY = (project.settings.size - project.settings.baseline)*pixelSize

        g.translate(referencePos.x, referencePos.y + baselineY)

        g.color = Color.BLUE
        g.drawLine(0, 0, glyphSize, 0)
        val capHeight = referenceFont.createGlyphVector(g.fontRenderContext,"X").visualBounds.minY.toInt()
        g.drawLine(0, capHeight, glyphSize, capHeight)
        val xHeight = referenceFont.createGlyphVector(g.fontRenderContext,"x").visualBounds.minY.toInt()
        g.drawLine(0, xHeight, glyphSize, xHeight)
        val descender = referenceFont.createGlyphVector(g.fontRenderContext,"y").visualBounds.maxY.toInt()
        g.drawLine(0, descender, glyphSize, descender)


        val metrics = referenceFont.createGlyphVector(g.fontRenderContext, glyph.character).getGlyphMetrics(0)
        val lsb = metrics.lsb.toInt()
        val rsb = metrics.rsb.toInt()

        g.translate(-lsb, -baselineY)

        val advance = metrics.advance.toInt()
        g.drawLine(0, 0, 0, glyphSize)
        g.drawLine(advance, 0, advance, glyphSize)

        g.stroke = BasicStroke(1f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 10f, floatArrayOf(pixelSize/2f, pixelSize/2f), pixelSize/4f)
        if(lsb != 0) {
            g.drawLine(lsb, 0, lsb, glyphSize)
        }
        if(rsb != 0) {
            g.drawLine(advance - rsb, 0, advance - rsb, glyphSize)
        }
        g.stroke = BasicStroke(1f)
    }

    fun drawGlyphGuides() {
        val baselineY = (project.settings.size - project.settings.baseline)*pixelSize
        g.translate(glyphPos.x, glyphPos.y + baselineY)

        g.color = Color.BLUE
        g.drawLine(0, 0, glyphSize, 0)
        val capHeight = project.settings.capHeight*pixelSize
        g.drawLine(0, -capHeight, glyphSize, -capHeight)
        val xHeight = project.settings.xHeight*pixelSize
        g.drawLine(0, -xHeight, glyphSize, -xHeight)
        val descender = project.settings.descender*pixelSize
        g.drawLine(0, descender, glyphSize, descender)

        g.color = Color.GREEN
        g.strokeWidth = 2f
        g.drawLine(-glyph.leftBearing*pixelSize, 0, (glyph.advance - glyph.leftBearing)*pixelSize, 0)
        g.strokeWidth = 1f

        g.translate(0, -baselineY)

        horizontalGuides.forEach {
            g.color = it.color
            g.drawLine(0, it.position*pixelSize, glyphSize, it.position*pixelSize)
        }
        verticalGuides.forEach {
            g.color = it.color
            g.drawLine(it.position*pixelSize, 0, it.position*pixelSize, glyphSize)
        }
    }

    @FXML
    fun canvasMouseDragged(e: MouseEvent) {
        val pos = pixelCoords(Pos(e.x.toInt(), e.y.toInt()))
        this.lastPos = pos
        val mouseDragType = mouseDragType ?: return
        if(mouseDragType != glyph[pos.x, pos.y]) {
            glyph[pos.x, pos.y] = mouseDragType
            Changes.submit(glyph)
        }
    }

    @FXML
    fun canvasMouseMoved(e: MouseEvent) {

    }

    @FXML
    fun canvasMousePressed(e: MouseEvent) {
        val pos = pixelCoords(Pos(e.x.toInt(), e.y.toInt()))
        val lastPos = lastPos
        if(lastPos != null && e.isShiftDown) {
            lastPos.lineTo(pos).forEach { pixel ->
                glyph[pixel.x, pixel.y] = lastDragType
            }
            mouseDragType = lastDragType
        } else {
            mouseDragType = !glyph[pos.x, pos.y]
            glyph[pos.x, pos.y] = mouseDragType!!
        }
        this.lastDragType = mouseDragType!!
        this.lastPos = pos
        Changes.submit(glyph)
    }

    @FXML
    fun canvasMouseReleased(e: MouseEvent) {
        mouseDragType = null
    }
}
