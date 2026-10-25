package com.tabeer.travelhub

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class Portal(val name: String, val category: String, val url: String)

class MainActivity : Activity() {
    private val portals = listOf(
        Portal("Travel Advisor", "Agent flights & groups", "https://traveladvisor.com.pk/agent-login.php"),
        Portal("Travel Point Aviation", "Group ticketing", "https://travelpointaviation.com/login")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.rgb(244, 247, 251))
        }
        page.addView(TextView(this).apply {
            text = "Travel Hub"
            textSize = 30f
            setTextColor(Color.rgb(20, 43, 70))
            typeface = Typeface.DEFAULT_BOLD
        })
        page.addView(TextView(this).apply {
            text = "Your travel portals, in one secure place"
            textSize = 14f
            setTextColor(Color.rgb(95, 111, 130))
            setPadding(0, dp(4), 0, dp(20))
        })
        portals.forEach { portal ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = rounded(Color.WHITE, dp(18))
                elevation = dp(2).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, PortalActivity::class.java).apply {
                        putExtra("portal_name", portal.name)
                        putExtra("portal_url", portal.url)
                    })
                }
            }
            card.addView(TextView(this).apply {
                text = portal.name
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(25, 55, 88))
            })
            card.addView(TextView(this).apply {
                text = portal.category
                textSize = 14f
                setTextColor(Color.rgb(95, 111, 130))
                setPadding(0, dp(7), 0, dp(12))
            })
            card.addView(TextView(this).apply {
                text = "OPEN PORTAL  →"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(24, 111, 190))
            })
            page.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) })
        }
        page.addView(TextView(this).apply {
            text = "Security note: passwords are not stored in this starter build. Add credentials only after the encrypted vault is implemented and tested."
            textSize = 12f
            setTextColor(Color.rgb(110, 90, 40))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(255, 247, 220), dp(12))
        })
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }
}
