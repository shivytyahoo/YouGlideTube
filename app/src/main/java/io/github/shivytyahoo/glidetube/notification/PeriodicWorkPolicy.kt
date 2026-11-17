package io.github.shivytyahoo.glidetube.notification

import androidx.work.ExistingPeriodicWorkPolicy

internal fun periodicWorkPolicy(reschedule: Boolean): ExistingPeriodicWorkPolicy =
    if (reschedule) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
