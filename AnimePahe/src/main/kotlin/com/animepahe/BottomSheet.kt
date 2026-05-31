package com.animepahe

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment


class BottomFragment(@Suppress("unused") private val plugin: AnimePaheProviderPlugin) :
    BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val dp = ctx.resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        root.addView(
            TextView(ctx).apply {
                text = "AnimePahe Server"
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 8.dp())
            },
        )
        root.addView(
            TextView(ctx).apply {
                text = "Pick a mirror domain. App restart required for the change to apply."
                textSize = 13f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, 0, 0, 16.dp())
            },
        )

        val serverGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        val current = AnimePaheProviderPlugin.currentAnimepaheServer
        ServerList.entries.forEach { server ->
            val rb = RadioButton(ctx).apply {
                text = server.link.first
                isEnabled = server.link.second
                setTextColor(Color.WHITE)
                id = View.generateViewId()
                val pad = 10.dp()
                setPadding(paddingLeft + pad, pad, pad, pad)
                setOnClickListener {
                    AnimePaheProviderPlugin.currentAnimepaheServer = server.link.first
                }
            }
            serverGroup.addView(rb)
            if (current == server.link.first) serverGroup.check(rb.id)
        }
        root.addView(serverGroup)

        val saveBtn = Button(ctx).apply {
            text = "Restart App"
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Restart App?")
                    .setMessage("Restart now to apply the new server selection?")
                    .setPositiveButton("Yes") { _, _ -> restartApp(ctx) }
                    .setNegativeButton("Later") { dialog, _ ->
                        dialog.dismiss()
                        Toast.makeText(ctx, "Saved. Restart later to apply.", Toast.LENGTH_SHORT)
                            .show()
                        dismiss()
                    }
                    .show()
            }
        }
        root.addView(
            saveBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 24.dp() },
        )

        return root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }

    private fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val component = intent?.component ?: return
        context.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }
}
