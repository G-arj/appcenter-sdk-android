/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.distribute;

import static android.content.Context.NOTIFICATION_SERVICE;
import static com.microsoft.appcenter.distribute.DistributeConstants.DOWNLOAD_STATE_NOTIFIED;
import static com.microsoft.appcenter.distribute.DistributeConstants.LOG_TAG;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import com.microsoft.appcenter.utils.AppCenterLog;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;

@SuppressLint("InlinedApi")
@SuppressWarnings("CanBeFinal")
@PrepareForTest({
        Settings.class,
        AlertDialog.class,
        AlertDialog.Builder.class,
        ReleaseInstallerListener.class,
        AppCenterLog.class,
        DistributeUtils.class,
        Distribute.class,
        AppCenterLog.class,
        Build.class
})
public class DistributeWarnAlertSystemWindowsTest extends AbstractDistributeTest {

    @Mock
    private AlertDialog mAlertWindowsDialog;

    @Mock
    private Activity mFirstActivity;

    @Mock
    private ReleaseInstallerListener mReleaseInstallerListener;

    @Before
    public void setUpDialog() throws Exception {

        /* Mock static classes. */
        mockStatic(Settings.class);
        mockStatic(AppCenterLog.class);

        /* Reset mock release listener methods. */
        doCallRealMethod().when(mReleaseDownloaderListener).onComplete(anyLong(), anyLong());
        when(mReleaseInstallerListener.showInstallProgressDialog(any(Activity.class))).thenReturn(mock(Dialog.class));

        /* Mock release installer listener. */
        whenNew(ReleaseInstallerListener.class).withAnyArguments().thenReturn(mReleaseInstallerListener);

        /* Mock release details. */
        mockStatic(DistributeUtils.class);
        when(DistributeUtils.loadCachedReleaseDetails()).thenReturn(mReleaseDetails);

        /* Start distribute from background. */
        ArgumentCaptor<ReleaseDownloadListener> releaseDownloadListener = ArgumentCaptor.forClass(ReleaseDownloadListener.class);
        Distribute.getInstance().startFromBackground(mContext);

        /* Start distribute module. */
        start();

        /* Resume distribute. */
        when(mFirstActivity.getApplicationContext()).thenReturn(mContext);
        Distribute.getInstance().onActivityResumed(mFirstActivity);

        /* Mock system alert windows dialog. */
        Mockito.when(mDialogBuilder.create()).thenReturn(mAlertWindowsDialog);
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                Mockito.when(mAlertWindowsDialog.isShowing()).thenReturn(true);
                return null;
            }
        }).when(mAlertWindowsDialog).show();
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                Mockito.when(mAlertWindowsDialog.isShowing()).thenReturn(false);
                return null;
            }
        }).when(mAlertWindowsDialog).hide();

        /* Verify that dialog wasn't shown. */
        verify(mAlertWindowsDialog, never()).show();
    }

    @Test
    public void showAndEnableAlertWindowsDialogQ() {

        /* Mock permission state for Android Q. */
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(false);
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(false);

        /* Notify about complete download. */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Verify that downloadId was set. */
        verify(mReleaseInstallerListener).setDownloadId(anyLong());

        /* Verify that dialog was shown after complete download. */
        verify(mAlertWindowsDialog).show();

        /* Emulate click on positive button. */
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setPositiveButton(eq(R.string.appcenter_distribute_unknown_sources_dialog_settings), clickListener.capture());
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_POSITIVE);
        verify(mFirstActivity).startActivity(any(Intent.class));

        /* Emulate behavior that settings was enabled via dialog. */
        Distribute.getInstance().onActivityPaused(mActivity);
        Distribute.getInstance().onApplicationEnterBackground();

        /* Verify that dialog was closed after call onActivityPaused. */
        verify(mReleaseInstallerListener).hideInstallProgressDialog();

        /* Mock settings result and resume Distribute. */
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(true);
        Distribute.getInstance().onApplicationEnterForeground();
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify that after enabling permissions the install process was started. */
        verify(mReleaseInstallerListener).startInstall();

        /* Emulate that install a new release was started. */
        Distribute.getInstance().notifyInstallProgress(true);
        verify(mReleaseInstallerListener, times(2)).hideInstallProgressDialog();

        /* Pause and resume Distribute again. */
        Distribute.getInstance().onActivityPaused(mActivity);
        Distribute.getInstance().onApplicationEnterBackground();
        Distribute.getInstance().onApplicationEnterForeground();
        Distribute.getInstance().onActivityResumed(mActivity);
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(true);

        /* Verify that after resume do nothing. */
        verifyStatic();
        AppCenterLog.info(eq(LOG_TAG), eq("Installing in progress..."));
    }

    @Test
    public void showAndEnableAlertWindowsDialogLowQWithEnabledSettings() {
        showAndEnableAlertWindowsDialogLowQ(true);
    }

    @Test
    public void showAndEnableAlertWindowsDialogLowQWithDisabledSettings() {
        showAndEnableAlertWindowsDialogLowQ(false);
    }

    private void showAndEnableAlertWindowsDialogLowQ(boolean isEnabled) {

        /* Mock permission state for Android M. */
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(true);
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(isEnabled);

        /* Notify about complete download. */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Verify that downloadId was set. */
        verify(mReleaseInstallerListener).setDownloadId(anyLong());

        /* Verify that dialog was not shown. */
        verify(mAlertWindowsDialog, never()).show();

        /* Emulate behavior that settings was enabled. */
        Distribute.getInstance().onActivityPaused(mActivity);
        Distribute.getInstance().onApplicationEnterBackground();

        /* Verify that dialog was closed. */
        verify(mReleaseInstallerListener).hideInstallProgressDialog();

        /* Do nothing with settings and resume Distribute module. */
        Distribute.getInstance().onApplicationEnterForeground();
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify that after enabling permissions the install process was started. */
        verify(mReleaseInstallerListener).startInstall();

        /* Emulate that install a new release was started. */
        Distribute.getInstance().notifyInstallProgress(true);
        verify(mReleaseInstallerListener, times(2)).hideInstallProgressDialog();
    }

    @Test
    public void showAndDisableAlertWindowsDialogQ() {

        /* Mock permission state for Android Q. */
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(false);
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(false);

        /* Notify about complete download. */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Verify that downloadId was set. */
        verify(mReleaseInstallerListener).setDownloadId(anyLong());

        /* Verify that dialog was shown after complete download. */
        verify(mAlertWindowsDialog).show();

        /* Emulate click on positive button. */
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setNegativeButton(eq(android.R.string.cancel), clickListener.capture());
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_NEGATIVE);

        /* Verify that after enabling permissions the install process was started. */
        verify(mReleaseInstallerListener).startInstall();
    }

    @Test
    public void showAndCancelAlertWindowsDialog() {

        /* Mock permission state for Android Q. */
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(false);
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(false);

        /* Notify about complete download. */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Verify that downloadId was set. */
        verify(mReleaseInstallerListener).setDownloadId(anyLong());

        /* Verify that dialog was shown after complete download. */
        verify(mAlertWindowsDialog).show();

        /* Emulate click on positive button. */
        ArgumentCaptor<DialogInterface.OnCancelListener> cancelListener = ArgumentCaptor.forClass(DialogInterface.OnCancelListener.class);
        verify(mDialogBuilder).setOnCancelListener(cancelListener.capture());
        cancelListener.getValue().onCancel(mAlertWindowsDialog);

        /* Verify that after enabling permissions the install process was started. */
        verify(mReleaseInstallerListener).startInstall();
    }

    @Test
    public void returningToDialogFromBackgroundWhenInstallerIsNull() {

        /* Mock permission state for Android Q. */
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(false);
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(false);

        /* Notify about complete download. */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Emulate behavior that settings was enabled via dialog. */
        Distribute.getInstance().onActivityPaused(mActivity);
        Distribute.getInstance().onApplicationEnterBackground();

        /* Make release details NULL to make installer listener NULL too. */
        Mockito.when(DistributeUtils.loadCachedReleaseDetails()).thenReturn(null);

        /* Start distribute with app secret NULL to make sure updateReleaseDetails is called on startFromBackground. */
        Distribute.getInstance().onStarting(mAppCenterHandler);
        Distribute.getInstance().onStarted(mContext, mChannel, null, null, true);
        Distribute.getInstance().startFromBackground(mContext);

        /* Enter foreground */
        Distribute.getInstance().onApplicationEnterForeground();
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify that installation process is trying to resume but installer is null because release details was not loaded. */
        verifyStatic();
        AppCenterLog.debug(eq(LOG_TAG), eq("Installing couldn't start due to the release installer wasn't initialized."));
    }

    @Test
    public void showSettingsDialogWhenActivityIsNullOnAndroidQ() throws Exception {

        /* Mock permission state for Android Q. */
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(false);
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(false);

        /* Set activity null in Distribute. */
        Distribute.getInstance().onActivityPaused(mActivity);

        /* Moke download state notified. */
        Mockito.when(DistributeUtils.getStoredDownloadState()).thenReturn(DOWNLOAD_STATE_NOTIFIED);

        /* Mock notification manager to avoid NRE. */
        NotificationManager manager = Mockito.mock(NotificationManager.class);
        whenNew(Notification.Builder.class).withAnyArguments()
                .thenReturn(Mockito.mock(Notification.Builder.class, RETURNS_MOCKS));
        Mockito.when(mContext.getSystemService(NOTIFICATION_SERVICE)).thenReturn(manager);

        /* Notify about complete download. */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Verify that dialog was not shown. */
        verify(mAlertWindowsDialog, never()).show();

        /* Verify system alert window is not trying to display if activity is null. */
        verifyStatic();
        AppCenterLog.warn(eq(LOG_TAG), eq("The application is in background mode, the system alerts windows won't be displayed."));
    }

    @Test
    public void needRefreshDialogWhenStartInstallationOnAndroidQ() throws Exception {

        /* Mock permission state for Android Q. */
        when(InstallerUtils.isSystemAlertWindowsEnabled(any(Context.class))).thenReturn(false);
        when(Settings.canDrawOverlays(any(Context.class))).thenReturn(false);

        /* Moke download state notified */
        Mockito.when(DistributeUtils.getStoredDownloadState()).thenReturn(DOWNLOAD_STATE_NOTIFIED);

        /* Mock notification manager to avoid NRE. */
        NotificationManager manager = Mockito.mock(NotificationManager.class);
        whenNew(Notification.Builder.class).withAnyArguments()
                .thenReturn(Mockito.mock(Notification.Builder.class, RETURNS_MOCKS));
        Mockito.when(mContext.getSystemService(NOTIFICATION_SERVICE)).thenReturn(manager);

        /* Notify about complete download. */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Start distribute with app secret NULL to make sure updateReleaseDetails is called on startFromBackground. */
        Distribute.getInstance().onStarting(mAppCenterHandler);
        Distribute.getInstance().onStarted(mContext, mChannel, null, null, true);
        Distribute.getInstance().startFromBackground(mContext);

        /* Release details should be mandatory to avoid completion installation workflow. */
        Mockito.when(mReleaseDetails.isMandatoryUpdate()).thenReturn(true);

        /* Start installation */
        mReleaseDownloaderListener.onComplete(1L, 1L);

        /* Verify that dialog was shown once only. */
        verify(mAlertWindowsDialog).show();

        /* Verify system alert window is not trying to display if it is already shown. */
        verifyStatic(never());
        AppCenterLog.warn(eq(LOG_TAG), eq("Show new system alerts windows dialog."));
    }
}

