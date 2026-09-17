package com.ledger.offline

import android.app.Application
import com.ledger.offline.core.ServiceLocator

class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
