package com.kafkasl.phonewhisper

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** External test-APK editor: the real service must cross the Android accessibility boundary. */
class InjectionTargetActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 80, 32, 32)
        }
        root.addView(TextView(this).apply { text = "Test d’insertion DictAI ${intent.getStringExtra("token").orEmpty()}"; textSize = 24f })
        root.addView(object : EditText(this) {
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE &&
                    intent.getBooleanExtra("withoutPaste", false)) return false
                return super.performAccessibilityAction(action, arguments)
            }
        }.apply {
            hint = "Écrivez votre message"
            setText(intent.getStringExtra("initial").orEmpty())
            setSelection(text.length)
            requestFocus()
        }, LinearLayout.LayoutParams(-1, 300))
        setContentView(root)
    }
}
