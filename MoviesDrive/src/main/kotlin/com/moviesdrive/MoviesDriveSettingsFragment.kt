package com.moviesdrive

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MoviesDriveSettingsFragment(private val plugin: MoviesDrivePlugin) : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121216"))
                val radius = dp(context, 20).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header Title
        val titleView = TextView(context).apply {
            text = "🎬 MoviesDrive Catalogue Settings"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF5C35"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 4))
            }
        }
        mainContainer.addView(titleView)

        val subTitleView = TextView(context).apply {
            text = "Customize and toggle categories shown in the CloudStream Home catalogue rows."
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 16))
            }
        }
        mainContainer.addView(subTitleView)

        val formContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
            addView(formContainer)
        }
        mainContainer.addView(scrollView)

        // Add Custom Category Box
        val addCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C24"))
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#333344"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 16))
            }
        }

        val addCardHeader = TextView(context).apply {
            text = "➕ Add Custom Category to Catalogue"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding(0, 0, 0, dp(context, 8))
        }
        addCard.addView(addCardHeader)

        val nameInput = EditText(context).apply {
            hint = "Category Name (e.g. Family Entertainment)"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262632"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#444455"))
            }
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 8))
            }
        }
        addCard.addView(nameInput)

        val slugInput = EditText(context).apply {
            hint = "Category Slug (e.g. family or 2160p-4k)"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262632"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#444455"))
            }
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 10))
            }
        }
        addCard.addView(slugInput)

        val categoryListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun refreshCategoryList() {
            categoryListContainer.removeAllViews()
            val categories = MoviesDrivePreferences.getCategories(context)

            for (cat in categories) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#181820"))
                        cornerRadius = dp(context, 8).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(context, 6))
                    }
                }

                val cb = CheckBox(context).apply {
                    isChecked = cat.isEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        MoviesDrivePreferences.toggleCategory(context, cat.slug, isChecked)
                    }
                }
                row.addView(cb)

                val labelCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleTxt = TextView(context).apply {
                    text = if (cat.isCustom) "${cat.name} ✦" else cat.name
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                labelCol.addView(titleTxt)

                val slugTxt = TextView(context).apply {
                    text = "slug: /category/${cat.slug}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#888888"))
                }
                labelCol.addView(slugTxt)

                row.addView(labelCol)

                if (cat.isCustom) {
                    val deleteBtn = Button(context).apply {
                        text = "✕"
                        textSize = 14f
                        setTextColor(Color.parseColor("#FF5555"))
                        setBackgroundColor(Color.TRANSPARENT)
                        layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
                        setOnClickListener {
                            MoviesDrivePreferences.removeCategory(context, cat.slug)
                            Toast.makeText(context, "Removed ${cat.name}", Toast.LENGTH_SHORT).show()
                            refreshCategoryList()
                        }
                    }
                    row.addView(deleteBtn)
                }

                categoryListContainer.addView(row)
            }
        }

        val addBtn = Button(context).apply {
            text = "Add Category to Catalogue"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF5C35"))
                cornerRadius = dp(context, 8).toFloat()
            }
            setOnClickListener {
                val name = nameInput.text?.toString() ?: ""
                val slug = slugInput.text?.toString() ?: ""
                if (name.isBlank() || slug.isBlank()) {
                    Toast.makeText(context, "Please enter both Name and Slug", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val success = MoviesDrivePreferences.addCustomCategory(context, name, slug)
                if (success) {
                    nameInput.text.clear()
                    slugInput.text.clear()
                    Toast.makeText(context, "Added '$name' to catalogue!", Toast.LENGTH_SHORT).show()
                    refreshCategoryList()
                } else {
                    Toast.makeText(context, "Category slug already exists", Toast.LENGTH_SHORT).show()
                }
            }
        }
        addCard.addView(addBtn)
        formContainer.addView(addCard)

        // Action Toolbar (Reset to Defaults)
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 10))
            }
        }

        val resetBtn = Button(context).apply {
            text = "↺ Reset to Defaults"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                MoviesDrivePreferences.resetToDefaults(context)
                Toast.makeText(context, "Reset catalogue to defaults", Toast.LENGTH_SHORT).show()
                refreshCategoryList()
            }
        }
        toolbar.addView(resetBtn)
        formContainer.addView(toolbar)

        // Add category list
        formContainer.addView(categoryListContainer)
        refreshCategoryList()

        return mainContainer
    }
}
