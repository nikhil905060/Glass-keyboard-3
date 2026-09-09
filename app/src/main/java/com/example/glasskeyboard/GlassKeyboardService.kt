package com.example.glasskeyboard

import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

class GlassKeyboardService : InputMethodService() {

    private val letterRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )

    private val symbolRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("*", "\"", "'", ":", ";", "!", "?")
    )

    private val dictionary = listOf(
        "the", "that", "there", "then", "they", "this", "think", "time", "today", "tomorrow",
        "hello", "how", "have", "here", "help", "happy", "home", "hope",
        "and", "are", "about", "after", "also", "always", "again",
        "you", "your", "yes", "yesterday",
        "we", "well", "what", "when", "where", "why", "who", "will", "with", "would", "was", "went",
        "is", "it", "if", "in", "into",
        "for", "from", "friend",
        "good", "going", "got", "get", "give",
        "can", "come", "could", "call",
        "love", "like", "look", "little", "let",
        "need", "now", "new", "no", "not", "never",
        "okay", "of", "on", "one", "our", "out", "over",
        "please", "people", "put",
        "really", "right",
        "see", "some", "so", "sorry", "send", "soon",
        "to", "too", "tell", "thanks", "thank",
        "want", "week", "work", "world",
        "know", "just", "make", "many", "more", "much", "most", "must"
    )

    private lateinit var suggestionBar: LinearLayout
    private lateinit var keysContainer: LinearLayout
    private lateinit var bottomExtraRow: LinearLayout
    private val letterButtons = mutableListOf<Button>()
    private var commandButton: ImageButton? = null
    private var shiftOn = false
    private var symbolsMode = false
    private var clipboardMode = false
    private var wordBuffer = StringBuilder()

    private val clipHistory = mutableListOf<String>()
    private lateinit var clipboardManager: ClipboardManager

    private var spaceDragStartX = 0f
    private var spaceIsDragging = false

    private var previewPopup: PopupWindow? = null
    private var previewText: TextView? = null

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank()) {
                    clipHistory.remove(text)
                    clipHistory.add(0, text)
                    if (clipHistory.size > 10) {
                        clipHistory.removeAt(clipHistory.size - 1)
                    }
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.input_view, null) as LinearLayout
        suggestionBar = root.findViewById(R.id.suggestion_bar)
        keysContainer = root.findViewById(R.id.keys_container)
        bottomExtraRow = root.findViewById(R.id.bottom_extra_row)

        val clipToggle = root.findViewById<View>(R.id.clip_toggle)
        clipToggle?.setOnClickListener {
            playHaptic(it)
            clipboardMode = !clipboardMode
            renderSuggestions()
        }

        val topGlobe = root.findViewById<View>(R.id.top_globe)
        topGlobe?.setOnClickListener {
            playHaptic(it)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        setupPreviewPopup()
        buildKeyboard()
        buildBottomExtraRow()
        renderSuggestions()
        return root
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    private fun setupPreviewPopup() {
        val tv = TextView(this)
        tv.textSize = 22f
        tv.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(14), dp(6), dp(14), dp(6))
        tv.background = ContextCompat.getDrawable(this, R.drawable.popup_bg)
        tv.elevation = dp(6).toFloat()
        previewText = tv

        val pw = PopupWindow(
            tv,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        pw.isTouchable = false
        pw.isClippingEnabled = false
        previewPopup = pw
    }

    private fun showKeyPreview(anchor: View, text: String) {
        val popup = previewPopup ?: return
        previewText?.text = text
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - dp(20)
        val y = loc[1] - dp(48)
        try {
            if (popup.isShowing) {
                popup.update(x, y, -1, -1)
            } else {
                popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            }
        } catch (e: Exception) {
        }
    }

    private fun hideKeyPreview() {
        val popup = previewPopup
        if (popup != null && popup.isShowing) {
            popup.dismiss()
        }
    }

    private fun buildKeyboard() {
        keysContainer.removeAllViews()
        letterButtons.clear()

        val rows = if (symbolsMode) symbolRows else letterRows
        val keySize = dp(34)

        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            rowLayout.gravity = Gravity.CENTER_HORIZONTAL
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.bottomMargin = dp(5)
            rowLayout.layoutParams = rowParams

            if (rowIndex == 2 && !symbolsMode) {
                val cmd = makeIconKey(R.drawable.ic_command, 36, keySize) {
                    shiftOn = !shiftOn
                    updateCase()
                }
                commandButton = cmd
                rowLayout.addView(cmd)
            }

            for (ch in row) {
                val key = makeCharKey(ch, !symbolsMode, keySize)
                rowLayout.addView(key)
                if (!symbolsMode) {
                    letterButtons.add(key)
                }
            }

            if (rowIndex == 2) {
                val back = makeIconKey(R.drawable.ic_backspace, 36, keySize) {
                    handleBackspace()
                }
                rowLayout.addView(back)
            }

            keysContainer.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this)
        bottomRow.orientation = LinearLayout.HORIZONTAL
        bottomRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val toggleLabel = if (symbolsMode) "ABC" else "123"
        val toggle = makeSpecialKey(toggleLabel, 44, keySize) {
            symbolsMode = !symbolsMode
            buildKeyboard()
        }
        bottomRow.addView(toggle)

        val space = makeSpaceKey(keySize)
        val spaceParams = space.layoutParams as LinearLayout.LayoutParams
        spaceParams.marginStart = dp(5)
        space.layoutParams = spaceParams
        bottomRow.addView(space)

        val enter = makeEnterKey(keySize)
        val enterParams = enter.layoutParams as LinearLayout.LayoutParams
        enterParams.marginStart = dp(5)
        enter.layoutParams = enterParams
        bottomRow.addView(enter)

        keysContainer.addView(bottomRow)
    }

    private fun buildBottomExtraRow() {
        bottomExtraRow.removeAllViews()

        val globe = ImageButton(this)
        globe.setImageResource(R.drawable.ic_globe)
        globe.background = null
        globe.isLongClickable = false
        globe.layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT)
        globe.setOnLongClickListener { true }
        globe.setOnClickListener {
            playHaptic(it)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        bottomExtraRow.addView(globe)

        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        bottomExtraRow.addView(spacer)

        val mic = ImageButton(this)
        mic.setImageResource(R.drawable.ic_mic)
        mic.background = null
        mic.isLongClickable = false
        mic.layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT)
        mic.setOnLongClickListener { true }
        mic.setOnClickListener {
            playHaptic(it)
        }
        bottomExtraRow.addView(mic)
    }

    private fun makeCharKey(ch: String, isLetter: Boolean, sizePx: Int): Button {
        val btn = Button(this)
        btn.text = if (isLetter && shiftOn) ch.uppercase() else ch
        btn.tag = ch
        btn.textSize = 17f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        val bg = GradientDrawable()
        bg.setColor(ContextCompat.getColor(this, R.color.key_bg))
        bg.cornerRadius = dp(11).toFloat()
        btn.background = bg
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        btn.isLongClickable = false
        btn.setOnLongClickListener { true }
        btn.setPadding(0, 0, 0, 0)
        val params = LinearLayout.LayoutParams(sizePx, sizePx)
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val shown = if (isLetter && shiftOn) ch.uppercase() else ch
                    showKeyPreview(v, shown)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideKeyPreview()
                }
            }
            false
        }
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            val out = if (isLetter && shiftOn) ch.uppercase() else ch
            commitAndTrack(out)
            if (isLetter && shiftOn) {
                shiftOn = false
                updateCase()
            }
        }
        return btn
    }

    private fun makeSpecialKey(label: String, widthDp: Int, heightPx: Int, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = label
        btn.textSize = 13f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        val bg = GradientDrawable()
        bg.setColor(ContextCompat.getColor(this, R.color.key_bg_special))
        bg.cornerRadius = dp(11).toFloat()
        btn.background = bg
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        btn.isLongClickable = false
        btn.setOnLongClickListener { true }
        val params = LinearLayout.LayoutParams(dp(widthDp), heightPx)
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            onClick()
        }
        return btn
    }

    private fun makeIconKey(iconRes: Int, widthDp: Int, heightPx: Int, onClick: () -> Unit): ImageButton {
        val btn = ImageButton(this)
        btn.setImageResource(iconRes)
        btn.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        btn.setPadding(dp(7), dp(7), dp(7), dp(7))
        val bg = GradientDrawable()
        bg.setColor(ContextCompat.getColor(this, R.color.key_bg_special))
        bg.cornerRadius = dp(11).toFloat()
        btn.background = bg
        btn.elevation = dp(1).toFloat()
        btn.isLongClickable = false
        btn.setOnLongClickListener { true }
        val params = LinearLayout.LayoutParams(dp(widthDp), heightPx)
        params.marginEnd = dp(5)
        btn.layoutParams = params
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            onClick()
        }
        return btn
    }

    private fun makeSpaceKey(heightPx: Int): Button {
        val btn = Button(this)
        btn.text = "space"
        btn.textSize = 13f
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        val bg = GradientDrawable()
        bg.setColor(ContextCompat.getColor(this, R.color.key_bg))
        bg.cornerRadius = dp(11).toFloat()
        btn.background = bg
        btn.elevation = dp(1).toFloat()
        btn.isAllCaps = false
        btn.isLongClickable = false
        btn.setOnLongClickListener { true }
        btn.layoutParams = LinearLayout.LayoutParams(0, heightPx, 1f)

        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            commitAndTrack(" ")
        }

        val dragStepPx = dp(8)
        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    spaceDragStartX = event.rawX
                    spaceIsDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - spaceDragStartX
                    if (abs(dx) > dragStepPx) {
                        spaceIsDragging = true
                        playHaptic(v)
                        val code = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                        sendDownUpKeyEvents(code)
                        spaceDragStartX = event.rawX
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!spaceIsDragging) {
                        v.performClick()
                    }
                    spaceIsDragging = false
                }
            }
            true
        }
        return btn
    }

    private fun makeEnterKey(heightPx: Int): Button {
        val btn = Button(this)
        btn.text = ""
        val bg = GradientDrawable()
        bg.setColor(ContextCompat.getColor(this, R.color.accent_blue))
        bg.cornerRadius = dp(11).toFloat()
        btn.background = bg
        btn.elevation = dp(1).toFloat()
        btn.isLongClickable = false
        btn.setOnLongClickListener { true }
        btn.layoutParams = LinearLayout.LayoutParams(dp(54), heightPx)
        btn.setOnClickListener {
            playGlassEffect(btn)
            playHaptic(btn)
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            wordBuffer.clear()
            renderSuggestions()
        }
        return btn
    }

    private fun playGlassEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP))
        }
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 90
        anim.start()
        view.postDelayed({
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setRenderEffect(null)
            }
        }, 130)
    }

    private fun playHaptic(view: View) {
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun commitAndTrack(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (text == " " || text == "\n") {
            wordBuffer.clear()
        } else {
            wordBuffer.append(text)
        }
        renderSuggestions()
    }

    private fun handleBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        if (wordBuffer.isNotEmpty()) {
            wordBuffer.deleteCharAt(wordBuffer.length - 1)
        }
        renderSuggestions()
    }

    private fun updateCase() {
        for (btn in letterButtons) {
            val ch = btn.tag as String
            btn.text = if (shiftOn) ch.uppercase() else ch
        }
        val cmd = commandButton
        if (cmd != null) {
            val bg = GradientDrawable()
            val colorRes = if (shiftOn) R.color.key_bg_special_active else R.color.key_bg_special
            bg.setColor(ContextCompat.getColor(this, colorRes))
            bg.cornerRadius = dp(11).toFloat()
            cmd.background = bg
        }
    }

    private fun getSuggestions(): List<String> {
        val w = wordBuffer.toString().lowercase()
        if (w.isEmpty()) {
            return listOf("I", "the", "you")
        }
        val matches = dictionary.filter { it.startsWith(w) && it != w }
        val combined = (listOf(w) + matches).distinct()
        return combined.take(3)
    }

    private fun capitalizeLike(source: String, word: String): String {
        if (source.isNotEmpty() && source[0].isUpperCase()) {
            return word.replaceFirstChar { it.uppercase() }
        }
        return word
    }

    private fun chip(text: String, onTap: () -> Unit): Button {
        val btn = Button(this)
        btn.text = text
        btn.textSize = 13f
        btn.isAllCaps = false
        btn.setTextColor(ContextCompat.getColor(this, R.color.key_text))
        btn.setBackgroundColor(0)
        btn.maxLines = 1
        btn.isLongClickable = false
        btn.setOnLongClickListener { true }
        btn.ellipsize = android.text.TextUtils.TruncateAt.END
        btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        btn.setOnClickListener {
            playHaptic(it)
            onTap()
        }
        return btn
    }

    private fun divider(): View {
        val d = View(this)
        val p = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        p.topMargin = dp(6)
        p.bottomMargin = dp(6)
        d.layoutParams = p
        d.setBackgroundColor(ContextCompat.getColor(this, R.color.divider))
        return d
    }

    private fun renderSuggestions() {
        suggestionBar.removeAllViews()

        if (clipboardMode) {
            if (clipHistory.isEmpty()) {
                suggestionBar.addView(chip("Clipboard empty") {})
                return
            }
            val items = clipHistory.take(4)
            for (index in items.indices) {
                val text = items[index]
                val preview = if (text.length > 18) text.take(18) + "…" else text
                suggestionBar.addView(chip(preview) {
                    currentInputConnection?.commitText(text, 1)
                    clipboardMode = false
                    renderSuggestions()
                })
                if (index < items.size - 1) {
                    suggestionBar.addView(divider())
                }
            }
            return
        }

        val current = wordBuffer.toString()
        val sugs = getSuggestions()
        for (index in sugs.indices) {
            val w = sugs[index]
            val display = capitalizeLike(current, w)
            suggestionBar.addView(chip(display) {
                currentInputConnection?.deleteSurroundingText(current.length, 0)
                currentInputConnection?.commitText("$display ", 1)
                wordBuffer.clear()
                renderSuggestions()
            })
            if (index < sugs.size - 1) {
                suggestionBar.addView(divider())
            }
        }
    }
}
   
