package com.finora.app.platform.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.finora.app.FinoraApp
import com.finora.app.MainActivity
import com.finora.app.R
import com.finora.app.data.db.ReminderEntity
import com.finora.app.data.repo.Seed
import com.finora.app.data.repo.event
import com.finora.app.data.repo.terms
import com.finora.app.domain.loan.LoanCalculator
import com.finora.app.domain.model.LoanStatus
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** When a reminder next fires. Pure, so it is tested without WorkManager. */
object ReminderTiming {
    fun next(r: ReminderEntity, now: LocalDateTime): LocalDateTime {
        val time = java.time.LocalTime.of(r.timeOfDayMinutes / 60, r.timeOfDayMinutes % 60)
        if (r.intervalDays <= 1) {
            val today = now.toLocalDate().atTime(time)
            return if (today.isAfter(now)) today else today.plusDays(1)
        }
        // Occurrences are anchor + k·interval (k ≥ 1): the anchor is the day the cycle started.
        val anchor = r.anchorDate?.let(LocalDate::ofEpochDay) ?: now.toLocalDate()
        val elapsed = ChronoUnit.DAYS.between(anchor, now.toLocalDate()).coerceAtLeast(0)
        var k = (elapsed / r.intervalDays).coerceAtLeast(1)
        while (!anchor.plusDays(k * r.intervalDays).atTime(time).isAfter(now)) k++
        return anchor.plusDays(k * r.intervalDays).atTime(time)
    }
}

/**
 * Local reminders on WorkManager: each fires once, then schedules its next occurrence. No
 * cloud scheduler. Android may defer delivery under Doze / battery optimisation, which the
 * settings screen says plainly — the times are targets, not guarantees.
 */
class ReminderScheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    fun schedule(r: ReminderEntity, zone: ZoneId = ZoneId.systemDefault()) {
        val name = "reminder_${r.key}"
        if (!r.enabled) {
            wm.cancelUniqueWork(name); return
        }
        val now = LocalDateTime.now(zone)
        val delay = ChronoUnit.MILLIS.between(now, ReminderTiming.next(r, now)).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY to r.key))
            .addTag("reminder")
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    fun scheduleAll(reminders: List<ReminderEntity>) = reminders.forEach { schedule(it) }

    companion object {
        const val CHANNEL_REVIEW = "review"
        const val CHANNEL_PORTFOLIO = "portfolio"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_REVIEW, "Daily review", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Transactions waiting for a merchant, category or purpose, and loans falling due."
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PORTFOLIO, "Portfolio update", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reminder to enter the latest stock prices and fund NAVs."
            })
        }

        fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY) ?: return Result.success()
        val c = (applicationContext as FinoraApp).container
        val reminder = c.db.reminders().byKey(key) ?: return Result.success()
        if (reminder.enabled) notify(key, c)
        // Chain the next occurrence (also re-reads any time the user changed meanwhile).
        c.reminderScheduler.schedule(reminder)
        return Result.success()
    }

    private suspend fun notify(key: String, c: com.finora.app.AppContainer) {
        if (!ReminderScheduler.canNotify(applicationContext)) return
        val (channel, title, text, route) = if (key == Seed.PORTFOLIO) {
            val investments = c.db.investments().all().filter { !it.archived }
            if (investments.isEmpty()) return
            Quad(ReminderScheduler.CHANNEL_PORTFOLIO, "Time for your portfolio update", "Enter the latest prices and NAVs for ${investments.size} holdings.", "portfolio_update")
        } else {
            val pending = c.db.transactions().needsReviewCount()
            val today = LocalDate.now()
            val loans = c.db.loans().all().filter { it.status == LoanStatus.ACTIVE && it.dueDate != null }
            val payments = c.db.loans().observeAllPayments().first()
            val due = loans.count { l ->
                val dueDate = LocalDate.ofEpochDay(l.dueDate!!)
                val outstanding = LoanCalculator.state(l.terms(), payments.filter { it.loanId == l.id }.map { it.event() }, today).totalOutstandingPaise
                outstanding > 0 && !dueDate.isAfter(today.plusDays(l.remindDaysBefore.toLong()))
            }
            if (pending == 0 && due == 0) return
            val parts = listOfNotNull(
                pending.takeIf { it > 0 }?.let { "$it transaction${if (it == 1) "" else "s"} to review" },
                due.takeIf { it > 0 }?.let { "$it loan${if (it == 1) "" else "s"} due or overdue" },
            )
            Quad(ReminderScheduler.CHANNEL_REVIEW, "Daily review", parts.joinToString(" · "), "review")
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        val pi = PendingIntent.getActivity(applicationContext, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(applicationContext).notify(key.hashCode(), n)
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    companion object { const val KEY = "key" }
}
