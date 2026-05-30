package com.local.tasknotescompanion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.local.tasknotescompanion.data.AppDatabase
import com.local.tasknotescompanion.data.TaskMappingStore
import com.local.tasknotescompanion.notifications.ReminderScheduler
import com.local.tasknotescompanion.notifications.NotificationPreferencesStore
import com.local.tasknotescompanion.repository.VaultRepository
import com.local.tasknotescompanion.worker.DailySummaryWorker
import com.local.tasknotescompanion.worker.NotificationResyncWorker
import com.local.tasknotescompanion.worker.VaultScanWorker
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class TaskNotesApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var mappingStore: TaskMappingStore
        private set
    lateinit var repository: VaultRepository
        private set
    lateinit var reminderScheduler: ReminderScheduler
        private set
    lateinit var notificationPreferencesStore: NotificationPreferencesStore
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, AppDatabase::class.java, "tasknotes.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        mappingStore = TaskMappingStore(this)
        repository = VaultRepository(database.taskDao(), mappingStore)
        notificationPreferencesStore = NotificationPreferencesStore(this)
        reminderScheduler = ReminderScheduler(this, database.scheduledNotificationDao(), database.taskDao(), mappingStore, notificationPreferencesStore)
        createNotificationChannel()
        scheduleBackgroundScan()
        scheduleDailySummary()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ReminderScheduler.CHANNEL_ID,
            "Task reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Time-sensitive task reminder alarms"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        }
        val richChannel = NotificationChannel(
            ReminderScheduler.RICH_CHANNEL_ID,
            "Full-screen reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Full-screen rich task reminder alarms"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        }
        val serviceChannel = NotificationChannel(
            ReminderScheduler.SERVICE_CHANNEL_ID,
            "Reminder playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background service used while rich reminder audio is active"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            setSound(null, null)
            enableVibration(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(listOf(channel, richChannel, serviceChannel))
    }

    private fun scheduleBackgroundScan() {
        val request = PeriodicWorkRequestBuilder<VaultScanWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "vault_scan",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )

        val resync = PeriodicWorkRequestBuilder<NotificationResyncWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "notification_resync",
            ExistingPeriodicWorkPolicy.UPDATE,
            resync,
        )
    }

    private fun scheduleDailySummary() {
        val prefs = notificationPreferencesStore.load()
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(prefs.dailySummaryTime)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_summary",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_notifications (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        taskPath TEXT NOT NULL,
                        taskTitle TEXT NOT NULL,
                        reminderId TEXT NOT NULL,
                        triggerAtMillis INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        description TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN dueTime TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN scheduledTime TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN repeatEveryMillis INTEGER")
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN repeatUntilCompleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN alertStyle TEXT")
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN alertNote TEXT")
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN alertImage TEXT")
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN alertAudio TEXT")
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN alertAudioLoop INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN alertAudioUntil TEXT")
                db.execSQL("ALTER TABLE scheduled_notifications ADD COLUMN alertAllowOverlay INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
