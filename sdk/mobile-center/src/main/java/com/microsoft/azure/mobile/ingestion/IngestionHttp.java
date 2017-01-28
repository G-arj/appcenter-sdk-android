package com.microsoft.azure.mobile.ingestion;

import android.content.Context;
import android.support.annotation.NonNull;

import com.microsoft.azure.mobile.http.DefaultHttpClient;
import com.microsoft.azure.mobile.http.HttpClient;
import com.microsoft.azure.mobile.http.HttpClientNetworkStateHandler;
import com.microsoft.azure.mobile.http.HttpClientRetryer;
import com.microsoft.azure.mobile.http.ServiceCall;
import com.microsoft.azure.mobile.http.ServiceCallback;
import com.microsoft.azure.mobile.ingestion.models.Log;
import com.microsoft.azure.mobile.ingestion.models.LogContainer;
import com.microsoft.azure.mobile.ingestion.models.json.LogSerializer;
import com.microsoft.azure.mobile.utils.NetworkStateHelper;

import org.json.JSONException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.azure.mobile.http.DefaultHttpClient.APP_SECRET;
import static com.microsoft.azure.mobile.http.DefaultHttpClient.METHOD_POST;

public class IngestionHttp implements Ingestion {

    /**
     * Default base URL.
     */
    private static final String DEFAULT_BASE_URL = "https://in.mobile.azure.com";

    /**
     * API Path.
     */
    private static final String API_PATH = "/logs?api_version=1.0.0-preview20160914";

    /**
     * Installation identifier HTTP Header.
     */
    private static final String INSTALL_ID = "Install-ID";

    /**
     * Log serializer.
     */
    private final LogSerializer mLogSerializer;

    /**
     * HTTP client.
     */
    private final HttpClient mHttpClient;

    /**
     * API base URL (scheme + authority).
     */
    private String mBaseUrl;

    /**
     * Init.
     *
     * @param context       any context.
     * @param logSerializer log serializer.
     */
    public IngestionHttp(@NonNull Context context, @NonNull LogSerializer logSerializer) {
        mLogSerializer = logSerializer;
        HttpClientRetryer retryer = new HttpClientRetryer(new DefaultHttpClient());
        NetworkStateHelper networkStateHelper = NetworkStateHelper.getSharedInstance(context);
        mHttpClient = new HttpClientNetworkStateHandler(retryer, networkStateHelper);
        mBaseUrl = DEFAULT_BASE_URL;
    }

    /**
     * Set the base url.
     *
     * @param baseUrl the base url.
     */
    @Override
    @SuppressWarnings("SameParameterValue")
    public void setServerUrl(@NonNull String baseUrl) {
        mBaseUrl = baseUrl;
    }

    @Override
    public ServiceCall sendAsync(String appSecret, UUID installId, LogContainer logContainer, final ServiceCallback serviceCallback) throws IllegalArgumentException {
        Map<String, String> headers = new HashMap<>();
        headers.put(INSTALL_ID, installId.toString());
        headers.put(APP_SECRET, appSecret);
        HttpClient.CallTemplate callTemplate = new IngestionCallTemplate(mLogSerializer, logContainer);
        return mHttpClient.callAsync(mBaseUrl + API_PATH, METHOD_POST, headers, callTemplate, serviceCallback);
    }

    @Override
    public void close() throws IOException {
        mHttpClient.close();
    }

    /**
     * Inner class is used to be able to mock System.currentTimeMillis, does not work if using anonymous inner class...
     */
    private static class IngestionCallTemplate implements HttpClient.CallTemplate {

        private final LogSerializer mLogSerializer;

        private final LogContainer mLogContainer;

        IngestionCallTemplate(LogSerializer logSerializer, LogContainer logContainer) {
            mLogSerializer = logSerializer;
            mLogContainer = logContainer;
        }

        @Override
        public String buildRequestBody() throws JSONException {

            /* Timestamps need to be as accurate as possible so we convert absolute time to relative now. Save times. */
            List<Log> logs = mLogContainer.getLogs();
            int size = logs.size();
            long[] absoluteTimes = new long[size];
            for (int i = 0; i < size; i++) {
                Log log = logs.get(i);
                long toffset = log.getToffset();
                absoluteTimes[i] = toffset;
                log.setToffset(System.currentTimeMillis() - toffset);
            }

            /* Serialize payload. */
            String payload;
            try {
                payload = mLogSerializer.serializeContainer(mLogContainer);
            } finally {

                /* Restore original times, could be retried later. */
                for (int i = 0; i < size; i++)
                    logs.get(i).setToffset(absoluteTimes[i]);
            }
            return payload;
        }
    }
}
