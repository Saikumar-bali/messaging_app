package com.messageapp

import android.app.Activity
import android.os.Bundle

class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // For our purpose, we don't need UI for this.
        finish()
    }
}
