package com.sturdyfishstick.watchomoviesindian

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class WatchOMoviesSettingsDialog(
    private val onSettingsSaved: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        const val PREF_NAME = "watchomovies_prefs"
        const val KEY_CUSTOM_DOMAIN = "custom_domain"
        const val KEY_SHOW_STUDIOS = "show_studios"
        const val KEY_SHOW_INDIAN_OTT = "show_indian_ott"
        const val KEY_SHOW_MOVIES = "show_movies"
        const val KEY_CF_AGGRESSIVE = "cf_aggressive"
        const val DEFAULT_DOMAIN = "https://watchomovies.social"

        fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    // Theme Colors (Modern Dark OTT Theme)
    private val bgColor = Color.parseColor("#121216")
    private val cardColor = Color.parseColor("#1C1C24")
    private val accentColor = Color.parseColor("#E50914")
    private val textPrimary = Color.parseColor("#FFFFFF")
    private val textSecondary = Color.parseColor("#A0A0B0")
    private val strokeColor = Color.parseColor("#2D2D3A")

    private fun Int.dp(context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun roundedBg(
        fillColor: Int,
        radiusDp: Int,
        context: Context,
        borderColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = radiusDp.dp(context).toFloat()
            if (borderColor != null) {
                setStroke(1.dp(context), borderColor)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val prefs = getPrefs(ctx)

        val scrollView = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(bgColor)
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(ctx), 16.dp(ctx), 20.dp(ctx), 28.dp(ctx))
        }

        // Top Drag Handle
        val handle = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(42.dp(ctx), 4.dp(ctx)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 18.dp(ctx)
            }
            background = roundedBg(Color.parseColor("#3A3A4A"), 4, ctx)
        }
        root.addView(handle)

        // Header Section
        val titleTv = TextView(ctx).apply {
            text = "WatchOMovies Settings"
            setTextColor(textPrimary)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }
        val subTitleTv = TextView(ctx).apply {
            text = "Customize domain, categories & network preferences"
            setTextColor(textSecondary)
            textSize = 13f
            setPadding(0, 4.dp(ctx), 0, 18.dp(ctx))
        }
        root.addView(titleTv)
        root.addView(subTitleTv)

        // --- CARD 1: DOMAIN SETTINGS ---
        val domainCard = createCard(ctx, "Server & Domain")
        val domainInput = EditText(ctx).apply {
            setText(prefs.getString(KEY_CUSTOM_DOMAIN, DEFAULT_DOMAIN))
            hint = DEFAULT_DOMAIN
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            textSize = 14f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(14.dp(ctx), 12.dp(ctx), 14.dp(ctx), 12.dp(ctx))
            background = roundedBg(bgColor, 10, ctx, strokeColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp(ctx)
            }
        }
        domainCard.addView(domainInput)

        val resetDomainBtn = TextView(ctx).apply {
            text = "Reset to default domain"
            setTextColor(accentColor)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10.dp(ctx), 0, 2.dp(ctx))
            setOnClickListener {
                domainInput.setText(DEFAULT_DOMAIN)
                Toast.makeText(ctx, "Default domain restored", Toast.LENGTH_SHORT).show()
            }
        }
        domainCard.addView(resetDomainBtn)
        root.addView(domainCard)

        // --- CARD 2: CATEGORY FILTERS ---
        val categoryCard = createCard(ctx, "Home Page Categories")

        val swMovies = addToggleRow(
            ctx, categoryCard,
            "Latest & Movies",
            "Show main movies & latest updates",
            prefs.getBoolean(KEY_SHOW_MOVIES, true)
        )
        val swIndianOtt = addToggleRow(
            ctx, categoryCard,
            "Indian OTT / Web Series",
            "Ullu, Fliz, PrimePlay, MoodX, Kooku, etc.",
            prefs.getBoolean(KEY_SHOW_INDIAN_OTT, true)
        )
        val swStudios = addToggleRow(
            ctx, categoryCard,
            "International Studios (18+)",
            "Brazzers, Bangbros, Vixen, Naughty America, etc.",
            prefs.getBoolean(KEY_SHOW_STUDIOS, true)
        )
        root.addView(categoryCard)

        // --- CARD 3: NETWORK & CLOUDFLARE ---
        val networkCard = createCard(ctx, "Network & Bypass")
        val swCfAggressive = addToggleRow(
            ctx, networkCard,
            "Auto-Retry Cloudflare",
            "Clear cookies & retry automatically on 403/503",
            prefs.getBoolean(KEY_CF_AGGRESSIVE, true)
        )
        root.addView(networkCard)

        // --- ACTION BUTTONS ---
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp(ctx)
            }
        }

        val cancelBtn = Button(ctx).apply {
            text = "Cancel"
            setTextColor(textSecondary)
            textSize = 14f
            isAllCaps = false
            background = roundedBg(cardColor, 12, ctx, strokeColor)
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(ctx), 1f).apply {
                marginEnd = 8.dp(ctx)
            }
            setOnClickListener { dismiss() }
        }

        val saveBtn = Button(ctx).apply {
            text = "Save & Apply"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = roundedBg(accentColor, 12, ctx)
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(ctx), 1.4f).apply {
                marginStart = 8.dp(ctx)
            }
            setOnClickListener {
                val cleanedDomain = domainInput.text.toString().trim().removeSuffix("/")
                    .ifBlank { DEFAULT_DOMAIN }

                prefs.edit().apply {
                    putString(KEY_CUSTOM_DOMAIN, cleanedDomain)
                    putBoolean(KEY_SHOW_MOVIES, swMovies.isChecked)
                    putBoolean(KEY_SHOW_INDIAN_OTT, swIndianOtt.isChecked)
                    putBoolean(KEY_SHOW_STUDIOS, swStudios.isChecked)
                    putBoolean(KEY_CF_AGGRESSIVE, swCfAggressive.isChecked)
                    apply()
                }

                Toast.makeText(ctx, "Settings saved! Restart app to apply changes.", Toast.LENGTH_SHORT).show()
                onSettingsSaved?.invoke()
                dismiss()
            }
        }

        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        root.addView(btnRow)

        scrollView.addView(root)
        return scrollView
    }

    private fun createCard(ctx: Context, sectionTitle: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardColor, 14, ctx, strokeColor)
            setPadding(16.dp(ctx), 14.dp(ctx), 16.dp(ctx), 14.dp(ctx))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 14.dp(ctx)
            }

            val header = TextView(ctx).apply {
                text = sectionTitle.uppercase()
                setTextColor(accentColor)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.08f
                setPadding(0, 0, 0, 8.dp(ctx))
            }
            addView(header)
        }
    }

    private fun addToggleRow(
        ctx: Context,
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initialState: Boolean
    ): Switch {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp(ctx), 0, 8.dp(ctx))
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12.dp(ctx)
            }
        }

        val titleTv = TextView(ctx).apply {
            text = title
            setTextColor(textPrimary)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        val subTv = TextView(ctx).apply {
            text = subtitle
            setTextColor(textSecondary)
            textSize = 12f
        }
        textCol.addView(titleTv)
        textCol.addView(subTv)

        val switchView = Switch(ctx).apply {
            isChecked = initialState
        }

        row.addView(textCol)
        row.addView(switchView)
        parent.addView(row)
        return switchView
    }
}
