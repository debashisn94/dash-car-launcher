package com.debashis.carlauncher

import android.service.notification.NotificationListenerService

/**
 * Exists solely to unlock MediaSessionManager.
 *
 * Android will not hand out active media sessions to an arbitrary app. The only way in is
 * to declare a NotificationListenerService and have the user (or adb) grant notification
 * access, then pass this component to getActiveSessions(). We never read a single
 * notification: the service body is empty on purpose.
 */
class DashNotificationListener : NotificationListenerService()
