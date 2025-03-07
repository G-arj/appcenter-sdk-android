/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.distribute;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import com.microsoft.appcenter.distribute.download.ReleaseDownloader;
import com.microsoft.appcenter.distribute.download.ReleaseDownloaderFactory;
import com.microsoft.appcenter.distribute.ingestion.models.DistributionStartSessionLog;
import com.microsoft.appcenter.http.HttpClient;
import com.microsoft.appcenter.http.HttpResponse;
import com.microsoft.appcenter.http.ServiceCall;
import com.microsoft.appcenter.http.ServiceCallback;
import com.microsoft.appcenter.test.TestUtils;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.AppNameHelper;
import com.microsoft.appcenter.utils.AsyncTaskUtils;
import com.microsoft.appcenter.utils.context.SessionContext;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.microsoft.appcenter.Flags.DEFAULTS;
import static com.microsoft.appcenter.distribute.DistributeConstants.DOWNLOAD_STATE_AVAILABLE;
import static com.microsoft.appcenter.distribute.DistributeConstants.DOWNLOAD_STATE_COMPLETED;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_DISTRIBUTION_GROUP_ID;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_DOWNLOADED_RELEASE_HASH;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_DOWNLOADED_RELEASE_ID;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_DOWNLOAD_STATE;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_POSTPONE_TIME;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_RELEASE_DETAILS;
import static com.microsoft.appcenter.distribute.DistributeConstants.PREFERENCE_KEY_UPDATE_TOKEN;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@PrepareForTest({DistributeUtils.class, SessionContext.class})
public class DistributeBeforeDownloadTest extends AbstractDistributeTest {

    private void mockSessionContext() {
        mockStatic(SessionContext.class);
        SessionContext sessionContext = mock(SessionContext.class);
        when(SessionContext.getInstance()).thenReturn(sessionContext);
        SessionContext.SessionInfo sessionInfo = mock(SessionContext.SessionInfo.class);
        when(sessionContext.getSessionAt(anyLong())).thenReturn(sessionInfo);
        when(sessionInfo.getSessionId()).thenReturn(UUID.randomUUID());
    }

    @Test
    public void moreRecentWithIncompatibleMinApiLevel() throws Exception {
        mockSessionContext();
        TestUtils.setInternalState(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.LOLLIPOP);
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        Map<String, String> headers = new HashMap<>();
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        when(releaseDetails.getMinApiLevel()).thenReturn(Build.VERSION_CODES.M);
        String distributionGroupId = UUID.randomUUID().toString();
        when(releaseDetails.getDistributionGroupId()).thenReturn(distributionGroupId);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify on incompatible version we complete workflow. */
        verifyStatic(never());
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOADED_RELEASE_HASH);
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
        verify(mDialogBuilder, never()).create();
        verify(mDialog, never()).show();

        /* Verify we still track the distribution group statistics. */
        verifyStatic();
        SharedPreferencesManager.putString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID, distributionGroupId);
        verify(mDistributeInfoTracker).updateDistributionGroupId(distributionGroupId);
        verify(mChannel).enqueue(any(DistributionStartSessionLog.class), eq(Distribute.getInstance().getGroupName()), eq(DEFAULTS));

        /* After that if we resume app nothing happens. */
        Distribute.getInstance().onActivityPaused(mock(Activity.class));
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));
    }

    @Test
    public void olderVersionCode() throws Exception {
        mockSessionContext();
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        HashMap<String, String> headers = new HashMap<>();
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(5);
        when(releaseDetails.getMinApiLevel()).thenReturn(Build.VERSION_CODES.M);
        String distributionGroupId = UUID.randomUUID().toString();
        when(releaseDetails.getDistributionGroupId()).thenReturn(distributionGroupId);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);
        TestUtils.setInternalState(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.N_MR1);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify on failure we complete workflow. */
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
        verify(mDialogBuilder, never()).create();
        verify(mDialog, never()).show();

        /* Verify we still track the distribution group statistics. */
        verifyStatic();
        SharedPreferencesManager.putString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID, distributionGroupId);
        verify(mDistributeInfoTracker).updateDistributionGroupId(distributionGroupId);
        verify(mChannel).enqueue(any(DistributionStartSessionLog.class), eq(Distribute.getInstance().getGroupName()), eq(DEFAULTS));

        /* After that if we resume app nothing happens. */
        Distribute.getInstance().onActivityPaused(mock(Activity.class));
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify release hash was not even considered. */
        //noinspection ResultOfMethodCallIgnored
        verify(releaseDetails, never()).getReleaseHash();
    }

    @Test
    public void sameVersionCodeSameHash() throws Exception {
        mockSessionContext();
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        Map<String, String> headers = new HashMap<>();
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(6);
        when(releaseDetails.getReleaseHash()).thenReturn(TEST_HASH);
        String distributionGroupId = UUID.randomUUID().toString();
        when(releaseDetails.getDistributionGroupId()).thenReturn(distributionGroupId);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify on failure we complete workflow. */
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
        verify(mDialogBuilder, never()).create();
        verify(mDialog, never()).show();

        /* Verify we still track the distribution group statistics. */
        verifyStatic();
        SharedPreferencesManager.putString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID, distributionGroupId);
        verify(mDistributeInfoTracker).updateDistributionGroupId(distributionGroupId);
        verify(mChannel).enqueue(any(DistributionStartSessionLog.class), eq(Distribute.getInstance().getGroupName()), eq(DEFAULTS));

        /* After that if we resume app nothing happens. */
        Distribute.getInstance().onActivityPaused(mock(Activity.class));
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));
    }

    @Test
    public void moreRecentVersionCode() throws Exception {
        mockSessionContext();
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        HashMap<String, String> headers = new HashMap<>();
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        when(releaseDetails.getShortVersion()).thenReturn("7.0");
        String distributionGroupId = UUID.randomUUID().toString();
        when(releaseDetails.getDistributionGroupId()).thenReturn(distributionGroupId);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);
        when(InstallerUtils.isUnknownSourcesEnabled(any(Context.class))).thenReturn(true);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify dialog. */
        verify(mDialogBuilder).setTitle(R.string.appcenter_distribute_update_dialog_title);
        verify(mDialogBuilder).setMessage("unit-test-app7.07");
        verify(mDialogBuilder).create();
        verify(mDialog).show();

        /* Verify we track the distribution group statistics. */
        verifyStatic();
        SharedPreferencesManager.putString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID, distributionGroupId);
        verify(mDistributeInfoTracker).updateDistributionGroupId(distributionGroupId);
        verify(mChannel).enqueue(any(DistributionStartSessionLog.class), eq(Distribute.getInstance().getGroupName()), eq(DEFAULTS));

        /* After that if we resume app we refresh dialog. */
        Distribute.getInstance().onActivityPaused(mock(Activity.class));
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* No more http call. */
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* But dialog refreshed. */
        InOrder order = inOrder(mDialog);
        order.verify(mDialog).hide();
        order.verify(mDialog).show();
        order.verifyNoMoreInteractions();
        verify(mDialog, times(2)).show();
        verify(mDialogBuilder, times(2)).create();

        /* Disable does not hide the dialog. */
        Distribute.setEnabled(false);

        /* We already called hide once, make sure its not called a second time. */
        verify(mDialog).hide();

        /* Also no toast if we don't click on actionable button. */
        verify(mToast, never()).show();
    }

    @Test
    public void sameVersionDifferentHashWithHardcodedAppName() throws Exception {

        /* Mock we already have redirection parameters. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        mockSessionContext();
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        Map<String, String> headers = new HashMap<>();
        headers.put(DistributeConstants.HEADER_API_TOKEN, "some token");
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(6);
        when(releaseDetails.getShortVersion()).thenReturn("1.2.3");
        when(releaseDetails.getReleaseHash()).thenReturn("9f52199c986d9210842824df695900e1656180946212bd5e8978501a5b732e60");
        String distributionGroupId = UUID.randomUUID().toString();
        when(releaseDetails.getDistributionGroupId()).thenReturn(distributionGroupId);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Mock app name to be not localizable. */
        mockStatic(AppNameHelper.class);
        when(AppNameHelper.getAppName(mContext)).thenReturn("hardcoded-app-name");

        /* Trigger call. */
        Distribute.setUpdateTrack(UpdateTrack.PRIVATE);
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify dialog. */
        verify(mDialogBuilder).setTitle(R.string.appcenter_distribute_update_dialog_title);
        verify(mDialogBuilder).setMessage("hardcoded-app-name1.2.36");
        verify(mDialogBuilder).create();
        verify(mDialog).show();

        /* Verify we didn't track the distribution group statistics since it was already done at redirection. */
        verifyStatic(never());
        SharedPreferencesManager.putString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID, distributionGroupId);
        verify(mDistributeInfoTracker, never()).updateDistributionGroupId(distributionGroupId);
        verify(mChannel, never()).enqueue(any(DistributionStartSessionLog.class), eq(Distribute.getInstance().getGroupName()), eq(DEFAULTS));
    }

    @Test
    public void postponeDialog() throws Exception {

        /* Mock we already have redirection parameters. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        mockSessionContext();
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        String distributionGroupId = UUID.randomUUID().toString();
        when(releaseDetails.getDistributionGroupId()).thenReturn(distributionGroupId);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* Verify dialog. */
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setNegativeButton(eq(R.string.appcenter_distribute_update_dialog_postpone), clickListener.capture());
        verify(mDialog).show();

        /* Postpone it. */
        long now = 20122112L;
        mockStatic(System.class);
        when(System.currentTimeMillis()).thenReturn(now);
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                when(SharedPreferencesManager.getLong(invocation.getArguments()[0].toString(), 0)).thenReturn((Long) invocation.getArguments()[1]);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putLong(eq(PREFERENCE_KEY_POSTPONE_TIME), anyLong());
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_NEGATIVE);
        when(mDialog.isShowing()).thenReturn(false);

        /* Verify. */
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_RELEASE_DETAILS);
        verifyStatic();
        SharedPreferencesManager.putLong(eq(PREFERENCE_KEY_POSTPONE_TIME), eq(now));

        /* Verify we didn't track distribution group stats since we already had redirection parameters. */
        verifyStatic(never());
        SharedPreferencesManager.putString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID, distributionGroupId);
        verify(mDistributeInfoTracker, never()).updateDistributionGroupId(distributionGroupId);
        verify(mChannel, never()).enqueue(any(DistributionStartSessionLog.class), eq(Distribute.getInstance().getGroupName()), eq(DEFAULTS));

        /* Verify no more calls, e.g. happened only once. */
        Distribute.getInstance().onActivityPaused(mock(Activity.class));
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialog).show();
        verify(mHttpClient).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Restart should check release and should not show dialog again until 1 day has elapsed. */
        now += DistributeConstants.POSTPONE_TIME_THRESHOLD - 1;
        when(System.currentTimeMillis()).thenReturn(now);
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialog).show();

        /* Now its time to show again. */
        now += 1;
        when(System.currentTimeMillis()).thenReturn(now);
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialog, times(2)).show();

        /* Postpone again. */
        clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder, times(2)).setNegativeButton(eq(R.string.appcenter_distribute_update_dialog_postpone), clickListener.capture());
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_NEGATIVE);

        /* Check postpone again. */
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialog, times(2)).show();

        /* If mandatory release, we ignore postpone and still show dialog. */
        when(releaseDetails.isMandatoryUpdate()).thenReturn(true);
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialog, times(3)).show();

        /* Set back in time to make SDK clean state and force update. */
        verifyStatic(never());
        SharedPreferencesManager.remove(PREFERENCE_KEY_POSTPONE_TIME);
        when(releaseDetails.isMandatoryUpdate()).thenReturn(false);
        now = 1;
        when(System.currentTimeMillis()).thenReturn(now);
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialog, times(4)).show();
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_POSTPONE_TIME);
    }

    @Test
    public void disableBeforePostponeDialog() throws Exception {

        /* Setup mock. */
        mockSessionContext();
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        String distributionGroupId = UUID.randomUUID().toString();
        when(releaseDetails.getDistributionGroupId()).thenReturn(distributionGroupId);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify dialog. */
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setNegativeButton(eq(R.string.appcenter_distribute_update_dialog_postpone), clickListener.capture());
        verify(mDialog).show();

        /* Verify we track the distribution group statistics. */
        verifyStatic();
        SharedPreferencesManager.putString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID, distributionGroupId);
        verify(mDistributeInfoTracker).updateDistributionGroupId(distributionGroupId);
        verify(mChannel).enqueue(any(DistributionStartSessionLog.class), eq(Distribute.getInstance().getGroupName()), eq(DEFAULTS));

        /* Disable. */
        Distribute.setEnabled(false);
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);

        /* Postpone it. */
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_NEGATIVE);
        when(mDialog.isShowing()).thenReturn(false);

        /* Since we were disabled, no action but toast to explain what happened. */
        verify(mToast).show();

        /* Verify no more calls, e.g. happened only once. */
        Distribute.getInstance().onActivityPaused(mActivity);
        Distribute.getInstance().onActivityResumed(mActivity);
        verify(mDialog).show();
        verify(mHttpClient).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
        verifyStatic(never());
        SharedPreferencesManager.putLong(eq(PREFERENCE_KEY_POSTPONE_TIME), anyLong());
    }

    @Test
    @PrepareForTest(AsyncTaskUtils.class)
    public void disableBeforeDownload() throws Exception {

        /* Mock we already have redirection parameters. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);
        mockStatic(AsyncTaskUtils.class);
        when(InstallerUtils.isUnknownSourcesEnabled(any(Context.class))).thenReturn(true);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mActivity);

        /* Verify dialog. */
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), clickListener.capture());
        verify(mDialog).show();

        /* Disable. */
        Distribute.setEnabled(false);
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);

        /* Click on download. */
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_POSITIVE);
        when(mDialog.isShowing()).thenReturn(false);

        /* Since we were disabled, no action but toast to explain what happened. */
        verify(mToast).show();

        /* Verify no more calls, e.g. happened only once. */
        Distribute.getInstance().onActivityPaused(mActivity);
        Distribute.getInstance().onActivityResumed(mActivity);
        verify(mDialog).show();
        verify(mHttpClient).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);

        /* Verify no download scheduled. */
        verify(mReleaseDownloader, never()).resume();
    }

    @Test
    @PrepareForTest({AsyncTaskUtils.class, ProgressDialog.class})
    public void pauseBeforeDownload() throws Exception {

        /* Mock ProgressDialog. */
        whenNew(ProgressDialog.class).withAnyArguments().thenReturn(mock(ProgressDialog.class));
        whenNew(ProgressDialog.class).withArguments(isNull()).thenThrow(new NullPointerException());

        /* Mock we already have redirection parameters. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        when(releaseDetails.isMandatoryUpdate()).thenReturn(true);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);
        mockStatic(AsyncTaskUtils.class);
        when(InstallerUtils.isUnknownSourcesEnabled(any(Context.class))).thenReturn(true);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* Verify dialog. */
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), clickListener.capture());
        verify(mDialog).show();

        /* Pause. */
        Distribute.getInstance().onActivityPaused(null);

        /* Click on download. */
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_POSITIVE);
        when(mDialog.isShowing()).thenReturn(false);

        /* Verify that download is scheduled. */
        verify(mReleaseDownloader).resume();
    }

    @Test
    public void mandatoryUpdateDialogAndCacheTests() throws Exception {

        /* Mock some storage calls. */
        mockSomeStorage();

        /* Mock we already have redirection parameters. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        final AtomicReference<ServiceCallback> serviceCallbackRef = new AtomicReference<>();
        final ServiceCall serviceCall = mock(ServiceCall.class);
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                Object serviceCallback = invocation.getArguments()[4];
                if (serviceCallback instanceof ServiceCallback) {
                    serviceCallbackRef.set((ServiceCallback) serviceCallback);
                }
                return serviceCall;
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        when(releaseDetails.isMandatoryUpdate()).thenReturn(true);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        assertNotNull(serviceCallbackRef.get());
        serviceCallbackRef.get().onCallSucceeded(new HttpResponse(200, "mock"));
        serviceCallbackRef.set(null);

        /* Verify release notes persisted. */
        verifyStatic();
        SharedPreferencesManager.putString(PREFERENCE_KEY_RELEASE_DETAILS, "mock");
        verifyStatic();
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_AVAILABLE);

        /* Verify dialog. */
        verify(mDialogBuilder, never()).setNegativeButton(anyInt(), any(DialogInterface.OnClickListener.class));
        verify(mDialogBuilder).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), any(DialogInterface.OnClickListener.class));

        /* Verify dialog restored offline even if process restarts. */
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialogBuilder, times(2)).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), any(DialogInterface.OnClickListener.class));
        verify(mDialogBuilder, never()).setNegativeButton(anyInt(), any(DialogInterface.OnClickListener.class));
        verify(mHttpClient, times(2)).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));
        assertNotNull(serviceCallbackRef.get());

        /* Simulate network back and get same release again, should do nothing particular. */
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(true);
        serviceCallbackRef.get().onCallSucceeded(new HttpResponse(200, "mock"));

        /* Check we didn't change state, e.g. happened only once. */
        verifyStatic();
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_AVAILABLE);

        /* Restart and this time we will detect a more recent optional release. */
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* Verify call is made and that we restored again mandatory update dialog in the mean time. */
        verify(mHttpClient, times(3)).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));
        verify(mDialogBuilder, times(3)).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), any(DialogInterface.OnClickListener.class));
        verify(mDialogBuilder, never()).setNegativeButton(anyInt(), any(DialogInterface.OnClickListener.class));

        /* Then detect new release in background. */
        releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(5);
        when(releaseDetails.getVersion()).thenReturn(8);
        when(releaseDetails.isMandatoryUpdate()).thenReturn(false);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);
        serviceCallbackRef.get().onCallSucceeded(new HttpResponse(200, "mock"));

        /* Check state updated again when we detect it. */
        verifyStatic(times(2));
        SharedPreferencesManager.putInt(PREFERENCE_KEY_DOWNLOAD_STATE, DOWNLOAD_STATE_AVAILABLE);

        /* Restart SDK, even offline, should show optional dialog. */
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialogBuilder, times(4)).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), any(DialogInterface.OnClickListener.class));
        verify(mDialogBuilder).setNegativeButton(anyInt(), any(DialogInterface.OnClickListener.class));

        /* And still check again for further update. */
        verify(mHttpClient, times(4)).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Unblock call with network up. */
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(true);
        serviceCallbackRef.get().onCallSucceeded(new HttpResponse(200, "mock"));

        /* If we restart SDK online, its an optional update so dialog will not be restored until new call made. */
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* Verify dialog behavior happened only once. */
        verify(mDialogBuilder).setNegativeButton(anyInt(), any(DialogInterface.OnClickListener.class));

        /* Dialog shown only after new call made in that scenario. */
        serviceCallbackRef.get().onCallSucceeded(new HttpResponse(200, "mock"));
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder, times(5)).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), clickListener.capture());
        verify(mDialogBuilder, times(2)).setNegativeButton(anyInt(), any(DialogInterface.OnClickListener.class));

        /* If we finally click on download, no call cancel since already successful. */
        when(InstallerUtils.isUnknownSourcesEnabled(mContext)).thenReturn(true);
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_POSITIVE);
        verify(serviceCall, never()).cancel();
    }

    @Test
    public void cancelGetReleaseCallIfDownloadingCachedDialogAfterRestart() throws Exception {

        /* Mock some storage calls. */
        mockSomeStorage();

        /* Mock we already have redirection parameters. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        final AtomicReference<ServiceCallback> serviceCallbackRef = new AtomicReference<>();
        final ServiceCall serviceCall = mock(ServiceCall.class);
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                Object serviceCallback = invocation.getArguments()[4];
                if (serviceCallback instanceof ServiceCallback) {
                    serviceCallbackRef.set((ServiceCallback) serviceCallback);
                }
                return serviceCall;
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        when(releaseDetails.isMandatoryUpdate()).thenReturn(false);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* Verify dialog. */
        serviceCallbackRef.get().onCallSucceeded(new HttpResponse(200, "mock"));
        verify(mDialogBuilder).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), any(DialogInterface.OnClickListener.class));

        /* Restart offline. */
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* Verify dialog restored and call scheduled. */
        verify(mHttpClient, times(2)).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder, times(2)).setPositiveButton(eq(R.string.appcenter_distribute_update_dialog_download), clickListener.capture());

        /* We are offline and call is scheduled, clicking download must cancel pending call. */
        when(InstallerUtils.isUnknownSourcesEnabled(mContext)).thenReturn(true);
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_POSITIVE);
        verify(serviceCall).cancel();
    }

    @Test
    public void releaseNotes() throws Exception {

        /* Mock we already have redirection parameters. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });

        /* No release notes. */
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(7);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        /* Verify dialog. */
        verify(mDialogBuilder, never()).setNeutralButton(eq(R.string.appcenter_distribute_update_dialog_view_release_notes), any(DialogInterface.OnClickListener.class));
        verify(mDialog).show();
        reset(mDialog);

        /* Release notes but somehow no URL. */
        when(releaseDetails.getReleaseNotes()).thenReturn("Fix a bug");
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialogBuilder, never()).setNeutralButton(eq(R.string.appcenter_distribute_update_dialog_view_release_notes), any(DialogInterface.OnClickListener.class));
        verify(mDialog).show();
        reset(mDialog);

        /* Release notes URL this time. */
        final Uri uri = mock(Uri.class);
        Intent intent = mock(Intent.class);
        whenNew(Intent.class).withArguments(Intent.ACTION_VIEW, uri).thenReturn(intent);
        when(releaseDetails.getReleaseNotesUrl()).thenReturn(uri);

        /* Empty release notes and URL. */
        when(releaseDetails.getReleaseNotes()).thenReturn("");
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mDialogBuilder, never()).setNeutralButton(eq(R.string.appcenter_distribute_update_dialog_view_release_notes), any(DialogInterface.OnClickListener.class));
        verify(mDialog).show();
        reset(mDialog);

        /* Release notes and URL. */
        when(releaseDetails.getReleaseNotes()).thenReturn("Fix a bug");
        restartProcessAndSdk();
        Distribute.getInstance().onActivityResumed(mActivity);
        ArgumentCaptor<DialogInterface.OnClickListener> clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder).setNeutralButton(eq(R.string.appcenter_distribute_update_dialog_view_release_notes), clickListener.capture());
        verify(mDialog).show();
        reset(mDialog);

        /* Click and check navigation. */
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_NEUTRAL);
        verify(mActivity).startActivity(intent);

        /* We thus leave app. */
        Distribute.getInstance().onActivityPaused(mActivity);
        when(mDialog.isShowing()).thenReturn(false);

        /* Going back should restore dialog. */
        Distribute.getInstance().onActivityResumed(mActivity);
        clickListener = ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
        verify(mDialogBuilder, times(2)).setNeutralButton(eq(R.string.appcenter_distribute_update_dialog_view_release_notes), clickListener.capture());
        verify(mDialog).show();

        /* Do the same test and simulate failed navigation. */
        mockStatic(AppCenterLog.class);
        ActivityNotFoundException exception = new ActivityNotFoundException();
        doThrow(exception).when(mActivity).startActivity(intent);
        clickListener.getValue().onClick(mDialog, DialogInterface.BUTTON_NEUTRAL);
        verify(mActivity, times(2)).startActivity(intent);
        verifyStatic();
        AppCenterLog.error(anyString(), anyString(), eq(exception));
    }

    @Test
    public void shouldRemoveReleaseHashStorageIfReportedSuccessfully() throws Exception {

        /* Mock release hash storage. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DOWNLOADED_RELEASE_HASH)).thenReturn("fake-hash");
        mockStatic(DistributeUtils.class);
        when(DistributeUtils.computeReleaseHash(any(PackageInfo.class))).thenReturn("fake-hash");

        /* Mock we already have token and no group. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(6);
        when(releaseDetails.getReleaseHash()).thenReturn(TEST_HASH);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOADED_RELEASE_HASH);
        verifyStatic();
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOADED_RELEASE_ID);
    }

    @Test
    public void shouldNotRemoveReleaseHashStorageIfHashesDontMatch() throws Exception {

        /* Mock release hash storage. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DOWNLOADED_RELEASE_HASH)).thenReturn("fake-hash");
        mockStatic(DistributeUtils.class);
        when(DistributeUtils.computeReleaseHash(any(PackageInfo.class))).thenReturn("fake-old-hash");

        /* Mock we already have token and no group. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        ReleaseDetails releaseDetails = mock(ReleaseDetails.class);
        when(releaseDetails.getId()).thenReturn(4);
        when(releaseDetails.getVersion()).thenReturn(6);
        when(releaseDetails.getReleaseHash()).thenReturn(TEST_HASH);
        when(ReleaseDetails.parse(anyString())).thenReturn(releaseDetails);

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));

        verifyStatic(never());
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOADED_RELEASE_HASH);
        verifyStatic(never());
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOADED_RELEASE_ID);
    }

    /**
     * Mock some storage calls.
     */
    private void mockSomeStorage() {
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                PowerMockito.when(SharedPreferencesManager.getInt(invocation.getArguments()[0].toString(), DOWNLOAD_STATE_COMPLETED)).thenReturn((Integer) invocation.getArguments()[1]);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putInt(eq(PREFERENCE_KEY_DOWNLOAD_STATE), anyInt());
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                PowerMockito.when(SharedPreferencesManager.getInt(invocation.getArguments()[0].toString(), DOWNLOAD_STATE_COMPLETED)).thenReturn(DOWNLOAD_STATE_COMPLETED);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.remove(PREFERENCE_KEY_DOWNLOAD_STATE);
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                PowerMockito.when(SharedPreferencesManager.getString(invocation.getArguments()[0].toString())).thenReturn(invocation.getArguments()[1].toString());
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putString(eq(PREFERENCE_KEY_RELEASE_DETAILS), anyString());
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                PowerMockito.when(SharedPreferencesManager.getString(invocation.getArguments()[0].toString())).thenReturn(null);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.remove(PREFERENCE_KEY_RELEASE_DETAILS);
    }


    @Test
    public void updateASecondTimeClearsPreviousReleaseCache() throws Exception {

        /* Mock first update completed. */
        mockStatic(DistributeUtils.class);
        when(DistributeUtils.getStoredDownloadState()).thenReturn(DOWNLOAD_STATE_COMPLETED);
        when(mReleaseDetails.getVersion()).thenReturn(6);
        when(mReleaseDetails.getId()).thenReturn(1);
        when(DistributeUtils.loadCachedReleaseDetails()).thenReturn(mReleaseDetails);
        ReleaseDownloader cachedReleaseDownloader = mock(ReleaseDownloader.class);
        when(ReleaseDownloaderFactory.create(any(Context.class), same(mReleaseDetails), any(ReleaseDownloadListener.class))).thenReturn(cachedReleaseDownloader);
        when(cachedReleaseDownloader.getReleaseDetails()).thenReturn(mReleaseDetails);

        /* Mock next release. */
        final ReleaseDetails nextReleaseDetails = mock(ReleaseDetails.class);
        when(nextReleaseDetails.getId()).thenReturn(2);
        when(nextReleaseDetails.getVersion()).thenReturn(7);
        when(ReleaseDetails.parse(anyString())).thenReturn(nextReleaseDetails);
        ReleaseDownloader nextReleaseDownloader = mock(ReleaseDownloader.class);
        when(ReleaseDownloaderFactory.create(any(Context.class), same(nextReleaseDetails), any(ReleaseDownloadListener.class))).thenReturn(nextReleaseDownloader);
        when(nextReleaseDownloader.getReleaseDetails()).thenReturn(nextReleaseDetails);

        /* Simulate cache update. */
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                when(DistributeUtils.loadCachedReleaseDetails()).thenReturn(nextReleaseDetails);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putString(eq(PREFERENCE_KEY_RELEASE_DETAILS), anyString());

        /* Mock we receive a second update. */
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });

        /* Trigger call. */
        start();
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify prompt is shown. */
        verify(mDialog).show();

        /* Verify previous download canceled. */
        verify(cachedReleaseDownloader).cancel();
    }

    @Test
    public void disableThenEnableBeforeUpdatingSecondTime() throws Exception {

        /* Mock first update completed but we disable in this test so mock we don't have release details. */
        mockStatic(DistributeUtils.class);
        when(DistributeUtils.getStoredDownloadState()).thenReturn(DOWNLOAD_STATE_COMPLETED);

        /* Mock next release. */
        final ReleaseDetails nextReleaseDetails = mock(ReleaseDetails.class);
        when(nextReleaseDetails.getId()).thenReturn(2);
        when(nextReleaseDetails.getVersion()).thenReturn(7);
        when(ReleaseDetails.parse(anyString())).thenReturn(nextReleaseDetails);
        ReleaseDownloader nextReleaseDownloader = mock(ReleaseDownloader.class);
        when(ReleaseDownloaderFactory.create(any(Context.class), same(nextReleaseDetails), any(ReleaseDownloadListener.class))).thenReturn(nextReleaseDownloader);
        when(nextReleaseDownloader.getReleaseDetails()).thenReturn(nextReleaseDetails);

        /* Simulate cache update. */
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                when(DistributeUtils.loadCachedReleaseDetails()).thenReturn(nextReleaseDetails);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putString(eq(PREFERENCE_KEY_RELEASE_DETAILS), anyString());

        /* Mock we receive a second update. */
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_DISTRIBUTION_GROUP_ID)).thenReturn("some group");
        when(SharedPreferencesManager.getString(PREFERENCE_KEY_UPDATE_TOKEN)).thenReturn("some token");
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).thenAnswer(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(new HttpResponse(200, "mock"));
                return mock(ServiceCall.class);
            }
        });
        HashMap<String, String> headers = new HashMap<>();
        headers.put(DistributeConstants.HEADER_API_TOKEN, "some token");

        /* Start SDK. */
        Distribute.setUpdateTrack(UpdateTrack.PRIVATE);
        start();

        /* Disable SDK. */
        ReleaseDownloader cleanupReleaseDownloader = mock(ReleaseDownloader.class);
        when(ReleaseDownloaderFactory.create(any(Context.class), isNull(ReleaseDetails.class), any(ReleaseDownloadListener.class))).thenReturn(cleanupReleaseDownloader);
        Distribute.setEnabled(false).get();
        Distribute.setEnabled(true).get();

        /* Verify previous download canceled. */
        verify(cleanupReleaseDownloader).cancel();

        /* Resume workflow. */
        Distribute.getInstance().onActivityResumed(mock(Activity.class));
        verify(mHttpClient).callAsync(anyString(), anyString(), eq(headers), any(HttpClient.CallTemplate.class), any(ServiceCallback.class));

        /* Verify prompt is shown. */
        verify(mDialog).show();
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.setInternalState(Build.VERSION.class, "SDK_INT", 0);
    }
}
