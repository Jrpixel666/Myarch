package com.apkforge

import android.app.Application

/**
 * Application entry point. Nothing to initialise eagerly today; the engine and
 * repository are created lazily by the ViewModel, but having a named
 * [Application] keeps room for a crash handler or logging setup later.
 */
class ForgeApp : Application()
