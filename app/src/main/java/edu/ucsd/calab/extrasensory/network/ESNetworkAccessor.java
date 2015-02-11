package edu.ucsd.calab.extrasensory.network;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.ucsd.calab.extrasensory.ESApplication;
import edu.ucsd.calab.extrasensory.R;
import edu.ucsd.calab.extrasensory.data.ESActivity;
import edu.ucsd.calab.extrasensory.data.ESDatabaseAccessor;
import edu.ucsd.calab.extrasensory.data.ESTimestamp;

/**
 * This class handles the networking with the server.
 * The two main features is uploading a sensor-measurements bundle to the server
 * and sending user label-feedback to the server.
 *
 * This class is designed as a singleton.
 * We need a single network agent to know the current state
 * (e.g. waiting for server response, so not available to upload new examples).
 *
 * Created by Yonatan on 1/17/2015.
 */
public class ESNetworkAccessor {

    private static final String LOG_TAG = "[ESNetworkAccessor]";
    private static final long WAIT_TIME_AFTER_UPLOAD_IN_MILLIS = 15000;

    private static final String KEY_ZIP_FILENAME = "zip_filename";

    private ArrayList<String> _networkQueue;
    private long _busyUntilTimeInMillis = 0;

    /**
     * Singleton implementation:
     */
    private static ESNetworkAccessor _theSingleNetworkAccessor;
    private ESNetworkAccessor() {
        _networkQueue = new ArrayList<String>(8);
    }

    /**
     * Get the network accessor
     * @return The network accessor
     */
    public static ESNetworkAccessor getESNetworkAccessor() {
        if (_theSingleNetworkAccessor == null) {
            _theSingleNetworkAccessor = new ESNetworkAccessor();
        }

        return _theSingleNetworkAccessor;
    }

    /**
     * Add a data file (zip) to the queue of examples to upload to the server.
     *
     * @param zipFileName The path of the zip file to upload
     */
    public void addToNetworkQueue(String zipFileName) {
        Log.v(LOG_TAG,"Adding to network queue: " + zipFileName);
        _networkQueue.add(zipFileName);
        uploadWhatYouHave();
    }

    private void deleteZipFileAndRemoveFromNetworkQueue(String zipFileName) {
        _networkQueue.remove(zipFileName);
        File file = new File(ESApplication.getZipDir(),zipFileName);
        file.delete();
        Log.i(LOG_TAG,"Deleted and removed from network queue file: " + zipFileName);
    }

    public void uploadWhatYouHave() {
        Log.v(LOG_TAG,"uploadWhatYouHave() was called.");
        if (_networkQueue.size() <= 0) {
            Log.v(LOG_TAG, "Nothing to upload (queue is empty).");
            return;
        }
        // Check if there is WiFi connectivity:
        if (!isThereWiFiConnectivity()) {
            Log.i(LOG_TAG,"There is no WiFi right now. Not uploading.");
            return;
        }

        // Check if busy:
        long nowInMillis = new Date().getTime();
        if (nowInMillis < _busyUntilTimeInMillis) {
            Log.i(LOG_TAG,"Network is busy");
            return;
        }
        // Set the "busy until" sign:
        _busyUntilTimeInMillis = nowInMillis + WAIT_TIME_AFTER_UPLOAD_IN_MILLIS;

        // Get the next zip to handle:
        String nextZip = _networkQueue.remove(0);

        // Keep it at the end of the queue (until getting response):
        _networkQueue.add(nextZip);

        Log.v(LOG_TAG,"Popped zip from queue: " + nextZip);
        Log.v(LOG_TAG,"Now queue has: " + _networkQueue);

        // Send the next zip:
        ESApiHandler.ESApiParams params = new ESApiHandler.ESApiParams(
                ESApiHandler.API_TYPE.API_TYPE_UPLOAD_ZIP,nextZip,null,this);
        Log.d(LOG_TAG,"Created api params: " + params);
        ESApiHandler api = new ESApiHandler();
        api.execute(params);
    }

    private boolean isThereWiFiConnectivity() {
        if (ESApplication.debugMode()) {
            return true;
        }
        ConnectivityManager connectivityManager =
                (ConnectivityManager)ESApplication.getTheAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return networkInfo.isConnected();
    }

    public void sendFeedback(ESActivity activity) {
        //TODO send the feedback api
    }


    private void handleUploadedZip(ESTimestamp timestamp,String zipFilename,String predictedMainActivity) {
        // Since zip uploaded successfully, can remove it from network queue and delete the file:
        deleteZipFileAndRemoveFromNetworkQueue(zipFilename);
        // Update the ESActivity record:
        ESDatabaseAccessor dba = ESDatabaseAccessor.getESDatabaseAccessor();
        ESActivity activity = dba.getESActivity(timestamp);
        dba.setESActivityServerPrediction(activity,predictedMainActivity);
        Log.i(LOG_TAG,"After getting server prediction, activity is now: " + activity);

        // If there is already user labels, send feedback to server:
        if (activity.hasUserProvidedLabels()) {
            sendFeedback(activity);
        }

        // Mark network is available:
        markNetworkIsNotBusy();
    }

    private void markNetworkIsNotBusy() {
        this._busyUntilTimeInMillis = 0;
        uploadWhatYouHave();
    }


    private static class ESApiHandler extends AsyncTask<ESApiHandler.ESApiParams,Void,String> {


        @Override
        protected String doInBackground(ESApiParams... parameters) {
            ESApiParams params = parameters[0];
            doApiRequest(params);
            return null;
        }

        public enum API_TYPE {
            API_TYPE_UPLOAD_ZIP,
            API_TYPE_FEEDBACK
        }

        public static class ESApiParams {
            public API_TYPE _apiType;
            public String _zipFilenameForUpload;
            public ESActivity _activityForFeedback;
            public ESNetworkAccessor _requester;

            public ESApiParams(API_TYPE apiType,String zipFilenameForUpload,ESActivity activity,ESNetworkAccessor requester) {
                _apiType = apiType;
                _zipFilenameForUpload = zipFilenameForUpload;
                _activityForFeedback = activity;
                _requester = requester;
            }

            @Override
            public String toString() {
                return "<apiType: " + _apiType + ", zipFilename: " + _zipFilenameForUpload + ", activity: " + _activityForFeedback + ">";
            }
        }

        private static final int READ_TIMEOUT_MILLIS = 10000;
        private static final int CONNECT_TIMEOUT_MILLIS = 15000;

        private static final String LINE_END = "\r\n";
        private static final String TWO_HYPHENS = "--";
        private static final String BOUNDARY = "0xKhTmLbOuNdArY";

        private static final String RESPONSE_FIELD_TIMESTAMP = "timestamp";
        private static final String RESPONSE_FIELD_SUCCESS = "success";
        private static final String RESPONSE_FIELD_MESSAGE = "msg";
        private static final String RESPONSE_FIELD_ZIP_FILE = "filename";
        private static final String RESPONSE_FIELD_PREDICTED_MAIN_ACTIVITY = "predicted_activity";


        private void doApiRequest(ESApiParams params) {
            Log.v(LOG_TAG,"API params: " + params);
            if (params == null) {
                Log.e(LOG_TAG,"Got null params.");
                return;
            }

            switch (params._apiType) {
                case API_TYPE_UPLOAD_ZIP:
                    apiUploadZip(params);
                    break;
                case API_TYPE_FEEDBACK:
                    break;
                default:
                    Log.e(LOG_TAG,"Unsupported api type: " + params._apiType);
            }

        }

        private void apiUploadZip(ESApiParams params) {
            try {
                Resources resources = ESApplication.getTheAppContext().getResources();

                String zipFilename = params._zipFilenameForUpload;
                int bytesRead, bytesAvailable, bufferSize;
                byte[] buffer;
                int maxBufferSize = 1 * 1024 * 1024;
                File zipFile = new File(ESApplication.getZipDir(),zipFilename);

                URL url = new URL(resources.getString(R.string.server_api_prefix) + resources.getString(R.string.api_upload_zip));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                conn.setReadTimeout(READ_TIMEOUT_MILLIS);
                conn.setDoOutput(true);
                conn.setDoInput(true); // Allow Inputs
                conn.setUseCaches(false); // Don't use a Cached Copy
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Connection", "Keep-Alive");
                conn.setRequestProperty("ENCTYPE", "multipart/form-data");
                conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + BOUNDARY);
                conn.setRequestProperty("uploaded_file", zipFilename);

                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());

                dos.writeBytes(TWO_HYPHENS + BOUNDARY + LINE_END);
                dos.writeBytes("Content-Disposition: form-data; name=\"file\";filename=\""
                        + zipFilename + "\"" + LINE_END);

                dos.writeBytes(LINE_END);

                // create a buffer of  maximum size
                FileInputStream fileInputStream = new FileInputStream(zipFile);
                bytesAvailable = fileInputStream.available();

                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                buffer = new byte[bufferSize];

                // read file and write it into form...
                bytesRead = fileInputStream.read(buffer, 0, bufferSize);

                while (bytesRead > 0) {

                    dos.write(buffer, 0, bufferSize);
                    bytesAvailable = fileInputStream.available();
                    bufferSize = Math.min(bytesAvailable, maxBufferSize);
                    bytesRead = fileInputStream.read(buffer, 0, bufferSize);

                }

                // send multipart form data necesssary after file data...
                dos.writeBytes(LINE_END);
                dos.writeBytes(TWO_HYPHENS + BOUNDARY + TWO_HYPHENS + LINE_END);

                // Send the request:
                conn.connect();

                // Responses from the server (code and message)
                int responseCode = conn.getResponseCode();
                String serverResponseMessage = conn.getResponseMessage();
                Log.i(LOG_TAG, "HTTP Response is : "
                        + serverResponseMessage + ": " + responseCode);

                InputStream inputStream = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder stringBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    stringBuilder.append(line);
                }
                String responseStr = stringBuilder.toString();
                Log.i(LOG_TAG,"RMW server responded: " + responseStr);

                // Analyze the response:
                JSONObject response = new JSONObject(responseStr);
                ESTimestamp timestamp = new ESTimestamp(response.getInt(RESPONSE_FIELD_TIMESTAMP));
                if (!response.getBoolean(RESPONSE_FIELD_SUCCESS)) {
                    Log.e(LOG_TAG,"Server said upload failed.");
                    Log.e(LOG_TAG,"Server message: " + response.getString(RESPONSE_FIELD_MESSAGE));
                    return;
                }
                String responseZipFilename = response.getString(RESPONSE_FIELD_ZIP_FILE);
                String predictedMainActivity = response.getString(RESPONSE_FIELD_PREDICTED_MAIN_ACTIVITY);

                conn.disconnect();
                params._requester.handleUploadedZip(timestamp, responseZipFilename, predictedMainActivity);

            } catch (MalformedURLException e) {
                Log.e(LOG_TAG,"Failed with creating URI for uploading zip");
                e.printStackTrace();
            } catch (IOException e) {
                Log.e(LOG_TAG,"Failed with uploading zip");
                e.printStackTrace();
            } catch (JSONException e) {
                Log.e(LOG_TAG,"Error parsing the server response");
                e.printStackTrace();
            }

        }
    }
}
