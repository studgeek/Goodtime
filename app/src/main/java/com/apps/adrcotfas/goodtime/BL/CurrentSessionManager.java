/*
 * Copyright 2016-2019 Adrian Cotfas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.apps.adrcotfas.goodtime.BL;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.util.Log;

import com.apps.adrcotfas.goodtime.Settings.PreferenceHelper;
import com.apps.adrcotfas.goodtime.Util.Constants;

import java.util.concurrent.TimeUnit;

import org.greenrobot.eventbus.EventBus;

import static com.apps.adrcotfas.goodtime.Util.Constants.ACTION.FINISHED;
import static com.apps.adrcotfas.goodtime.Util.Constants.ONE_MINUTE_LEFT;
import static com.apps.adrcotfas.goodtime.Util.Constants.SESSION_TYPE;

/**
 * This class manages and modifies the mutable members of {@link CurrentSession}
 * The duration is updated using an {@link AppCountDownTimer}. Events coming from other layers will
 * trigger an update of the {@link CurrentSession}'s {@link TimerState} and {@link SessionType}.
 */
public class CurrentSessionManager extends ContextWrapper{

    private static final String TAG = CurrentSessionManager.class.getSimpleName();
    private static final long sixtySecondsInMillis = TimeUnit.SECONDS.toMillis(60);

    private AppCountDownTimer mTimer;
    private final CurrentSession mCurrentSession;
    private long mRemaining; // [ms]
    private final AlarmReceiver mAlarmReceiver;

    private long mSessionDuration;

    public CurrentSessionManager(Context context, CurrentSession currentSession) {
        super(context);
        mCurrentSession = currentSession;
        mAlarmReceiver = new AlarmReceiver();
    }

    public void startTimer(SessionType sessionType) {
        Log.v(TAG, "startTimer: " + sessionType.toString());

        mSessionDuration =  TimeUnit.MINUTES.toMillis(PreferenceHelper.getSessionDuration(sessionType));

        mCurrentSession.setTimerState(TimerState.ACTIVE);
        mCurrentSession.setSessionType(sessionType);
        mCurrentSession.setDuration(mSessionDuration);

        scheduleAlarm(sessionType, mSessionDuration,
                PreferenceHelper.oneMinuteBeforeNotificationEnabled()
                        && mSessionDuration > TimeUnit.MINUTES.toMillis(1));
        mTimer = new AppCountDownTimer(mSessionDuration);
        mTimer.start();
    }

    public void toggleTimer() {
        switch(mCurrentSession.getTimerState().getValue()) {
            case PAUSED:
                Log.v(TAG, "toggleTimer PAUSED");
                scheduleAlarm(mCurrentSession.getSessionType().getValue(), mRemaining,
                        PreferenceHelper.oneMinuteBeforeNotificationEnabled()
                                && mRemaining > TimeUnit.MINUTES.toMillis(1));
                mTimer.start();
                mCurrentSession.setTimerState(TimerState.ACTIVE);
                break;
            case ACTIVE:
                Log.v(TAG, "toggleTimer UNPAUSED");
                cancelAlarm();
                mTimer.cancel();
                mTimer = new AppCountDownTimer(mRemaining);
                mCurrentSession.setTimerState(TimerState.PAUSED);
                break;
            default:
                Log.wtf(TAG, "The timer is in an invalid state.");
                break;
        }
    }

    public void stopTimer() {
        cancelAlarm();
        if (mTimer != null) {
            mTimer.cancel();
        }
        mCurrentSession.setTimerState(TimerState.INACTIVE);
        mCurrentSession.setSessionType(SessionType.INVALID);
    }

    /**
     * This is used to get the minutes that should be stored to the statistics
     * To be called when the session is finished without user interaction
     * @return the minutes elapsed
     */
    public int getElapsedMinutesAtFinished() {
        int sessionMinutes = (int) TimeUnit.MILLISECONDS.toMinutes(mSessionDuration);
        int extraMinutes = PreferenceHelper.getAdd60SecondsCounter();
        return sessionMinutes + extraMinutes;
    }

    /**
     * This is used to get the minutes that should be stored to the statistics
     * To be called when the user manually stops an ongoing session (or skips)
     * @return the minutes elapsed
     */
    public int getElapsedMinutesAtStop() {
        int sessionMinutes = (int) TimeUnit.MILLISECONDS.toMinutes(mSessionDuration);
        int extraMinutes = PreferenceHelper.getAdd60SecondsCounter();
        int remainingMinutes = (int) TimeUnit.MILLISECONDS.toMinutes(mRemaining + 30000);
        return sessionMinutes - remainingMinutes + extraMinutes;
    }

    private void scheduleAlarm(SessionType sessionType, long duration, boolean remindOneMinuteLeft) {
        this.registerReceiver(mAlarmReceiver, new IntentFilter(FINISHED));
        final long triggerAtMillis = duration + SystemClock.elapsedRealtime();

        Log.v(TAG, "scheduleAlarm " + sessionType.toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getAlarmManager().setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis, getAlarmPendingIntent(sessionType));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getAlarmManager().setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis, getAlarmPendingIntent(sessionType));
        } else {
            getAlarmManager().set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis, getAlarmPendingIntent(sessionType));
        }

        if (remindOneMinuteLeft && sessionType == SessionType.WORK) {
            Log.v(TAG, "scheduled one minute left");
            final long triggerAtMillisOneMinuteLeft = duration - TimeUnit.MINUTES.toMillis(1) + SystemClock.elapsedRealtime();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getAlarmManager().setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillisOneMinuteLeft, getOneMinuteLeftAlarmPendingIntent());
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                getAlarmManager().setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillisOneMinuteLeft, getOneMinuteLeftAlarmPendingIntent());
            } else {
                getAlarmManager().set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillisOneMinuteLeft, getOneMinuteLeftAlarmPendingIntent());
            }
        }
    }

    private void cancelAlarm() {
        final PendingIntent intent = getAlarmPendingIntent(mCurrentSession.getSessionType().getValue());
        if (intent != null) {
            getAlarmManager().cancel(intent);
        }
        final PendingIntent intentOneMinuteLeft = getOneMinuteLeftAlarmPendingIntent();
        if (intentOneMinuteLeft != null) {
            getAlarmManager().cancel(intentOneMinuteLeft);
        }
        unregisterAlarmReceiver();
    }

    private void unregisterAlarmReceiver() {
        Log.v(TAG, "unregisterAlarmReceiver");
        try {
            this.unregisterReceiver(mAlarmReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "AlarmReceiver is already unregistered.");
        }
    }

    private AlarmManager getAlarmManager() {
        return (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
    }

    private PendingIntent getAlarmPendingIntent(SessionType sessionType) {
        Intent intent = new Intent(FINISHED);
        intent.putExtra(SESSION_TYPE, sessionType.toString());
        return PendingIntent.getBroadcast(getApplicationContext(), 0,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getOneMinuteLeftAlarmPendingIntent() {
        Intent intent = new Intent(FINISHED);
        intent.putExtra(ONE_MINUTE_LEFT, true);
        return PendingIntent.getBroadcast(getApplicationContext(), 1,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public CurrentSession getCurrentSession() {
        return mCurrentSession;
    }

    public void add60Seconds() {
        Log.v(TAG, "add60Seconds");

        long newRemaining = Math.min(mRemaining + sixtySecondsInMillis,
            TimeUnit.MINUTES.toMillis(240));
        this.updateRemaining(newRemaining);
    }

    public void remove60Seconds() {
        Log.v(TAG, "remove60Seconds");

        long newRemaining = Math.max(mRemaining - sixtySecondsInMillis, sixtySecondsInMillis);
        this.updateRemaining(newRemaining);
    }

    public void updateRemaining(long newRemaining) {
        cancelAlarm();
        mTimer.cancel();

        mRemaining = newRemaining;

        mTimer = new AppCountDownTimer(mRemaining);

        if (mCurrentSession.getTimerState().getValue() != TimerState.PAUSED) {
            scheduleAlarm(mCurrentSession.getSessionType().getValue(), mRemaining,
                PreferenceHelper.oneMinuteBeforeNotificationEnabled()
                    && mRemaining > TimeUnit.MINUTES.toMillis(1));
            mTimer.start();
            mCurrentSession.setTimerState(TimerState.ACTIVE);
        } else {
            mCurrentSession.setDuration(mRemaining);
        }
    }

    private class AppCountDownTimer extends CountDownTimer {

        private final String TAG = AppCountDownTimer.class.getSimpleName();
        /**
         * @param millisInFuture    The number of millis in the future from the call
         *                          to {@link #start()} until the countdown is done and {@link #onFinish()}
         *                          is called.
         */
        private AppCountDownTimer(long millisInFuture) {
            super(millisInFuture, 1000);
        }

        /**
         * This is useful only when the screen is turned on. It seems that onTick is not called for every tick if the
         * phone is locked and the app runs in the background.
         * I found this the hard way when using the session duration(which is set here) in saving to statistics.
         */
        @Override
        public void onTick(long millisUntilFinished) {
            Log.v(TAG, "is Ticking: " + millisUntilFinished + " millis remaining.");
            mCurrentSession.setDuration(millisUntilFinished);
            mRemaining = millisUntilFinished;
            EventBus.getDefault().post(new Constants.UpdateTimerProgressEvent());
        }

        @Override
        public void onFinish() {
            Log.v(TAG, "is finished.");
            mRemaining = 0;
        }
    }
}
