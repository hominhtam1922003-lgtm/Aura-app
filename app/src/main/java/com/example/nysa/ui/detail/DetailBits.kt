package com.example.nysa.ui.detail

/** Episode/chapter number formatting shared with the player sheets: 3 stays "3", 5.5 stays "5.5". */
fun fmtNum(n: Float): String = if (n % 1f == 0f) n.toInt().toString() else n.toString()
